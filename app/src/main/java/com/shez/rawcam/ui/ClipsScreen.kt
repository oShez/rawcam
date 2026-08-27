package com.shez.rawcam.ui

import android.Manifest
import android.content.Intent
import android.graphics.BitmapFactory
import android.content.pm.PackageManager
import android.os.Build
import android.os.StatFs
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import com.shez.rawcam.preview.PreviewService
import com.shez.rawcam.preview.ProxyStore
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.export.ExportService
import com.shez.rawcam.export.ExportPaths
import com.shez.rawcam.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private data class ClipEntry(
    val file: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameCount: Int,
    val exportedFrameCount: Int,   // -1 if never exported / no output folder yet
    val posterPath: String?,       // proxy 0 on disk, or null while it does not exist yet
)

private fun clipsDirOf(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "clips")

private fun baseName(f: File) = f.name.removeSuffix(".rawv")

/** Sidecar WAV recorded alongside a clip (Task 9), same basename, same directory.
 * May not exist -- audio recording is optional and best-effort. */
private fun wavOf(f: File) = File(f.parentFile, baseName(f) + ".wav")

/** Hands the clip's .rawv -- and its sidecar WAV, when present -- to the system
 * share sheet via a FileProvider (the clips/ directory is under the external-files
 * root already covered by file_paths.xml's snapshot-root entry). Mirrors
 * ExportsScreen's shareExport: ACTION_SEND_MULTIPLE once there is more than one
 * file to send, plain single-item ACTION_SEND when the clip has no audio. */
private fun shareClip(context: android.content.Context, clip: File) {
    val authority = "${context.packageName}.fileprovider"
    val files = listOfNotNull(clip, wavOf(clip).takeIf { it.exists() })
    val uris = ArrayList(files.map { FileProvider.getUriForFile(context, authority, it) })
    if (uris.isEmpty()) return
    val intent = if (uris.size > 1) {
        Intent(Intent.ACTION_SEND_MULTIPLE).apply {
            type = "*/*"
            putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    } else {
        Intent(Intent.ACTION_SEND).apply {
            type = "*/*"
            putExtra(Intent.EXTRA_STREAM, uris[0])
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
    context.startActivity(Intent.createChooser(intent, "Send ${clip.name}"))
}

private fun humanSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(Locale.US, mb / 1024.0) else "%.1f MB".format(Locale.US, mb)
}

/** `clip_yyyyMMdd_HHmmss.rawv` -> "Jul 13 · 14:51"; anything else keeps its filename. */
private fun clipTitle(f: File): String {
    val m = Regex("clip_(\\d{8}_\\d{6})").find(f.name) ?: return f.name
    return try {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.groupValues[1])!!
        SimpleDateFormat("MMM d · HH:mm", Locale.US).format(d)
    } catch (e: Exception) {
        f.name
    }
}

private fun durationLabel(frames: Int, fps: Int): String =
    if (fps > 0) "%.1f s".format(Locale.US, frames.toFloat() / fps) else "— s"

private fun loadClips(context: android.content.Context): List<ClipEntry> {
    val clipsDir = clipsDirOf(context)
    // Checks every export root (public + private fallback), not just the one
    // exportsRootDir() currently resolves to -- a clip exported before the
    // MANAGE_EXTERNAL_STORAGE permission was granted (or after it was revoked)
    // stays where it was written, and must not read as "Not exported" just
    // because the resolved root later changed.
    val exportRoots = ExportPaths.allExportRoots(context)
    val files = clipsDir.listFiles { f -> f.isFile && f.name.endsWith(".rawv") } ?: emptyArray()
    return files.sortedByDescending { it.lastModified() }.map { f ->
        val info = NativeBridge.nativeClipInfo(f.absolutePath)
        val outDir = exportRoots.map { root -> File(root, baseName(f)) }.firstOrNull { it.isDirectory }
        val exportedCount = if (outDir != null) {
            outDir.listFiles { of -> of.name.endsWith(".dng") }?.size ?: 0
        } else -1
        val proxyDir = ProxyStore.dirFor(context, f.name)
        val poster = ProxyStore.frameFile(proxyDir, 0).takeIf { it.isFile }?.absolutePath
        ClipEntry(
            file = f,
            width = info.getOrElse(0) { 0 },
            height = info.getOrElse(1) { 0 },
            fps = info.getOrElse(2) { 0 },
            frameCount = info.getOrElse(3) { 0 },
            exportedFrameCount = exportedCount,
            posterPath = poster,
        )
    }.also { ProxyStore.pruneOrphans(context, files.map { f -> f.name }.toSet()) }
}

/**
 * Lists recorded clips (name, resolution, frame count via [NativeBridge.nativeClipInfo],
 * which delegates to RawvReader -- crash-recovery scan included). Export starts
 * [ExportService] as a foreground service; progress/notification live there. Delete
 * asks for confirmation before removing the .rawv file. The list -- including each
 * clip's exported-folder DNG count -- refreshes on a 2s poll (file I/O on
 * Dispatchers.IO) so an export finishing in the background is reflected without
 * user action.
 */
@Composable
fun ClipsScreen(onBack: () -> Unit = {}, onOpenViewer: (File) -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var clips by remember { mutableStateOf<List<ClipEntry>>(emptyList()) }
    var freeBytes by remember { mutableStateOf(0L) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    // Off-main delete (existing audit fix, preserved): the file removal itself runs
    // on Dispatchers.IO, then refreshTick++ triggers the poll loop above to reload
    // the list. Known pre-existing gap (backlog, not fixed here): if the composable
    // leaves composition between the delete and the refreshTick++, this scope's
    // launch is cancelled mid-flight and the list won't reflect the deletion until
    // the next natural refresh.
    fun performDelete(clip: File) {
        scope.launch {
            withContext(Dispatchers.IO) {
                val clipDeleted = clip.delete()
                // Pairing: a sidecar WAV means nothing without its .rawv, so it goes
                // with it -- but only once the .rawv delete actually succeeded. A
                // surviving .rawv must never be left with its WAV deleted out from
                // under it.
                if (clipDeleted) {
                    val wav = wavOf(clip)
                    if (wav.exists() && !wav.delete()) {
                        android.util.Log.w("ClipsScreen", "failed to delete sidecar $wav")
                    }
                }
            }
            refreshTick++
        }
    }

    // Export runs as a foreground service with a progress notification (required on
    // API 26+ for any foreground service); on API 33+ actually POSTING it needs the
    // runtime POST_NOTIFICATIONS permission. The service itself still runs and writes
    // DNGs without it -- this just makes the progress notification visible.
    val notificationPermLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {}
    LaunchedEffect(Unit) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) !=
                PackageManager.PERMISSION_GRANTED
        ) {
            notificationPermLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    LaunchedEffect(refreshTick) {
        val loaded = withContext(Dispatchers.IO) {
            val free = try {
                StatFs(context.getExternalFilesDir(null)!!.absolutePath).availableBytes
            } catch (e: Exception) {
                0L
            }
            free to loadClips(context)
        }
        freeBytes = loaded.first
        clips = loaded.second
    }
    // Lifecycle-gated: without this, the loop (and the StatFs + dir listing +
    // nativeClipInfo scan it triggers via refreshTick) would keep running every 2s
    // while the app is backgrounded. repeatOnLifecycle cancels the block below STARTED
    // and restarts it when the screen returns to the foreground.
    val lifecycleOwner = LocalLifecycleOwner.current
    LaunchedEffect(lifecycleOwner) {
        lifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            while (true) {
                delay(2000)
                refreshTick++
            }
        }
    }

    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete clip?") },
            text = { Text("This permanently deletes ${toDelete.name}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    pendingDelete = null
                    performDelete(toDelete)
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        ScreenHeader(title = "Clips", onBack = onBack) {
            Text(
                humanSize(freeBytes) + " FREE",
                color = RawCamColors.Muted, style = RawCamType.Label,
            )
        }
        if (clips.isEmpty()) {
            EmptyState("No clips yet", "Recordings you make will be listed here.")
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(clips, key = { it.file.absolutePath }) { clip ->
                    ClipCard(
                        clip = clip,
                        onOpen = { onOpenViewer(clip.file) },
                        onExport = {
                            scope.launch {
                                val deleteAfter = SettingsRepository.settings.first().deleteAfterExport
                                val outDir = File(ExportPaths.exportsRootDir(context), baseName(clip.file))
                                ExportService.start(
                                    context,
                                    clip.file.absolutePath,
                                    outDir.absolutePath,
                                    baseName(clip.file),
                                    deleteAfter,
                                )
                            }
                        },
                        onCancel = { ExportService.cancel(context, baseName(clip.file)) },
                        onShare = { shareClip(context, clip.file) },
                        onDelete = {
                            scope.launch {
                                if (SettingsRepository.settings.first().confirmDelete) {
                                    pendingDelete = clip.file
                                } else {
                                    performDelete(clip.file)
                                }
                            }
                        },
                    )
                }
            }
        }
    }
}

@Composable
private fun ClipCard(
    clip: ClipEntry, onOpen: () -> Unit, onExport: () -> Unit, onCancel: () -> Unit,
    onShare: () -> Unit, onDelete: () -> Unit,
) {
    val status = ExportService.status[baseName(clip.file)]
    val running = status == ExportService.ExportStatus.RUNNING
    Card(
        colors = CardDefaults.cardColors(containerColor = RawCamColors.Surface),
        border = BorderStroke(1.dp, RawCamColors.Outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.clickable(onClick = onOpen).padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                Modifier.size(96.dp, 72.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(RawCamColors.SurfaceVariant),
                contentAlignment = Alignment.Center,
            ) {
                val poster = clip.posterPath
                val pending = PreviewService.progressFor(clip.file.name)
                when {
                    poster != null -> {
                        val bmp = remember(poster) { BitmapFactory.decodeFile(poster) }
                        if (bmp != null) {
                            Image(
                                bitmap = bmp.asImageBitmap(),
                                contentDescription = null,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                            )
                        }
                    }
                    pending >= 0 -> Text("...", color = RawCamColors.Muted, fontSize = 12.sp)
                    else -> Text("--", color = RawCamColors.Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(clipTitle(clip.file), style = MaterialTheme.typography.titleMedium)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "${clip.width}×${clip.height} · ${clip.fps} fps · ${clip.frameCount} frames · " +
                            "${durationLabel(clip.frameCount, clip.fps)} · ${humanSize(clip.file.length())}",
                        color = RawCamColors.Muted, fontSize = 12.sp, fontFamily = RawCamMono,
                    )
                    if (wavOf(clip.file).exists()) {
                        Spacer(Modifier.width(6.dp))
                        Text(
                            "A", color = RawCamColors.Success, fontSize = 12.sp,
                            fontFamily = RawCamMono,
                        )
                    }
                }
                Spacer(Modifier.height(6.dp))
                when {
                    running -> {
                        val done = clip.exportedFrameCount.coerceAtLeast(0)
                        Text(
                            "Exporting…  $done / ${clip.frameCount}",
                            fontSize = 12.sp, fontFamily = RawCamMono,
                        )
                        Spacer(Modifier.height(4.dp))
                        LinearProgressIndicator(
                            progress = {
                                if (clip.frameCount > 0) done.toFloat() / clip.frameCount else 0f
                            },
                            modifier = Modifier.fillMaxWidth(0.85f),
                        )
                    }
                    clip.exportedFrameCount >= 0 -> {
                        // Fewer DNGs on disk than source frames with no live status
                        // (e.g. the app restarted mid-export, wiping the in-memory
                        // status map) must not render as a green "Exported" -- flag
                        // the shortfall so the user knows to re-export.
                        val partial = clip.frameCount > 0 && clip.exportedFrameCount < clip.frameCount
                        val (suffix, color) = when {
                            status == ExportService.ExportStatus.FAILED -> " (failed)" to RawCamColors.Accent
                            status == ExportService.ExportStatus.CANCELLED -> " (cancelled)" to RawCamColors.Muted
                            status == ExportService.ExportStatus.AUDIO_COPY_FAILED ->
                                " (audio copy failed -- source kept, retry export)" to RawCamColors.Accent
                            partial -> " (incomplete · ${clip.exportedFrameCount}/${clip.frameCount})" to RawCamColors.Accent
                            else -> "" to RawCamColors.Success
                        }
                        Text(
                            "Exported · ${clip.exportedFrameCount} DNGs$suffix",
                            color = color, fontSize = 12.sp,
                        )
                    }
                    else -> Text("Not exported", color = RawCamColors.Muted, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (running) {
                    OutlinedButton(onClick = onCancel) { Text("Cancel") }
                } else {
                    Button(onClick = onExport) { Text("Export") }
                    TextButton(onClick = onShare) { Text("Share", color = RawCamColors.Muted) }
                    TextButton(onClick = onDelete) { Text("Delete", color = RawCamColors.Muted) }
                }
            }
        }
    }
}
