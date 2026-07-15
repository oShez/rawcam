package com.shez.rawcam.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.export.ExportService
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale

private data class ClipEntry(
    val file: File,
    val width: Int,
    val height: Int,
    val fps: Int,
    val frameCount: Int,
    val exportedFrameCount: Int,   // -1 if never exported / no output folder yet
)

private fun clipsDirOf(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "clips")

private fun exportsDirOf(context: android.content.Context) =
    File(context.getExternalFilesDir(null), "exports")

private fun baseName(f: File) = f.name.removeSuffix(".rawv")

private fun humanSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(Locale.US, mb / 1024.0) else "%.1f MB".format(Locale.US, mb)
}

private fun loadClips(context: android.content.Context): List<ClipEntry> {
    val clipsDir = clipsDirOf(context)
    val exportsDir = exportsDirOf(context)
    val files = clipsDir.listFiles { f -> f.isFile && f.name.endsWith(".rawv") } ?: emptyArray()
    return files.sortedByDescending { it.lastModified() }.map { f ->
        val info = NativeBridge.nativeClipInfo(f.absolutePath)
        val outDir = File(exportsDir, baseName(f))
        val exportedCount = if (outDir.isDirectory) {
            outDir.listFiles { of -> of.name.endsWith(".dng") }?.size ?: 0
        } else -1
        ClipEntry(
            file = f,
            width = info.getOrElse(0) { 0 },
            height = info.getOrElse(1) { 0 },
            fps = info.getOrElse(2) { 0 },
            frameCount = info.getOrElse(3) { 0 },
            exportedFrameCount = exportedCount,
        )
    }
}

/**
 * Lists recorded clips (name, resolution, frame count via [NativeBridge.nativeClipInfo],
 * which delegates to RawvReader -- crash-recovery scan included). Export starts
 * [ExportService] as a foreground service; progress/notification live there. Delete
 * asks for confirmation before removing the .rawv file. The list -- including each
 * clip's exported-folder DNG count -- refreshes on a 2s poll so an export finishing
 * in the background is reflected without user action.
 */
@Composable
fun ClipsScreen(onBack: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    var refreshTick by remember { mutableStateOf(0) }
    var clips by remember { mutableStateOf(loadClips(context)) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

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

    LaunchedEffect(refreshTick) { clips = loadClips(context) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(2000)
            refreshTick++
        }
    }

    pendingDelete?.let { toDelete ->
        AlertDialog(
            onDismissRequest = { pendingDelete = null },
            title = { Text("Delete clip?") },
            text = { Text("This permanently deletes ${toDelete.name}. This cannot be undone.") },
            confirmButton = {
                TextButton(onClick = {
                    toDelete.delete()
                    pendingDelete = null
                    refreshTick++
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { pendingDelete = null }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←") }
            Text("Clips", style = androidx.compose.material3.MaterialTheme.typography.headlineSmall)
        }
        androidx.compose.foundation.layout.Spacer(Modifier.padding(4.dp))
        if (clips.isEmpty()) {
            Text("No clips recorded yet.")
        } else {
            LazyColumn(Modifier.fillMaxSize()) {
                items(clips, key = { it.file.absolutePath }) { clip ->
                    ClipRow(
                        clip = clip,
                        onExport = {
                            val outDir = File(exportsDirOf(context), baseName(clip.file))
                            ExportService.start(
                                context,
                                clip.file.absolutePath,
                                outDir.absolutePath,
                                baseName(clip.file),
                            )
                        },
                        onCancel = { ExportService.cancel(context) },
                        onDelete = { pendingDelete = clip.file },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun ClipRow(
    clip: ClipEntry, onExport: () -> Unit, onCancel: () -> Unit, onDelete: () -> Unit,
) {
    val status = ExportService.status[baseName(clip.file)]
    val running = status == ExportService.ExportStatus.RUNNING
    Row(
        Modifier.fillMaxWidth().padding(vertical = 8.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(Modifier.weight(1f)) {
            Text(clip.file.name, style = androidx.compose.material3.MaterialTheme.typography.titleMedium)
            Text("${clip.width}x${clip.height}@${clip.fps}fps  ${clip.frameCount} frames  ${humanSize(clip.file.length())}")
            when {
                running -> Text("Exporting…")
                clip.exportedFrameCount >= 0 ->
                    Text("Exported: ${clip.exportedFrameCount}/${clip.frameCount} DNGs" +
                        when (status) {
                            ExportService.ExportStatus.FAILED -> " (failed)"
                            ExportService.ExportStatus.CANCELLED -> " (cancelled)"
                            else -> ""
                        })
                else -> Text("Not exported")
            }
        }
        Column(horizontalAlignment = androidx.compose.ui.Alignment.End) {
            if (running) {
                Button(onClick = onCancel) { Text("Cancel") }
            } else {
                Button(onClick = onExport) { Text("Export") }
            }
            OutlinedButton(onClick = onDelete, enabled = !running) { Text("Delete") }
        }
    }
}
