package com.shez.rawcam.ui

import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.shez.rawcam.settings.SettingsRepository
import com.shez.rawcam.export.ExportPaths
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale

private data class ExportEntry(val dir: File, val dngCount: Int, val totalBytes: Long)

private fun exportsDirOf(context: Context) = ExportPaths.exportsRootDir(context)

private fun loadExports(context: Context): List<ExportEntry> {
    val dirs = exportsDirOf(context).listFiles { f -> f.isDirectory } ?: emptyArray()
    return dirs.mapNotNull { dir ->
        val dngs = dir.listFiles { f -> f.name.endsWith(".dng") } ?: return@mapNotNull null
        if (dngs.isEmpty()) return@mapNotNull null
        ExportEntry(dir, dngs.size, dngs.sumOf { it.length() })
    }.sortedByDescending { it.dir.lastModified() }
}

/** `clip_yyyyMMdd_HHmmss` -> "Jul 13 · 14:51", mirroring ClipsScreen's clipTitle but
 * matched against a directory name (the export folder) instead of a .rawv file. */
private fun exportTitle(dir: File): String {
    val m = Regex("clip_(\\d{8}_\\d{6})").find(dir.name) ?: return dir.name
    return try {
        val d = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).parse(m.groupValues[1])!!
        SimpleDateFormat("MMM d · HH:mm", Locale.US).format(d)
    } catch (e: Exception) {
        dir.name
    }
}

private fun humanSize(bytes: Long): String {
    val mb = bytes / (1024.0 * 1024.0)
    return if (mb >= 1024) "%.2f GB".format(Locale.US, mb / 1024.0) else "%.1f MB".format(Locale.US, mb)
}

/** Hands the clip's whole DNG set to the system share sheet via a FileProvider (scoped
 * to exports/ only, see file_paths.xml) -- Quick Share/Nearby Share to a paired laptop,
 * a Drive folder, email, whatever share target the user already has set up. No custom
 * networking: the share sheet is the standard, already-hardened Android mechanism for
 * "get these files onto another device", so this app doesn't need to be a file server. */
private fun shareExport(context: Context, entry: ExportEntry) {
    val authority = "${context.packageName}.fileprovider"
    val dngs = entry.dir.listFiles { f -> f.name.endsWith(".dng") } ?: return
    val uris = ArrayList(dngs.map { FileProvider.getUriForFile(context, authority, it) })
    if (uris.isEmpty()) return
    val intent = Intent(Intent.ACTION_SEND_MULTIPLE).apply {
        type = "*/*"
        putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Send ${entry.dir.name}"))
}

/**
 * Browses exported DNG folders (written by [com.shez.rawcam.export.ExportService] under
 * getExternalFilesDir/exports/<clipName>/), shares a clip's full DNG set through the
 * system share sheet, and deletes an export folder outright (the source .rawv on
 * ClipsScreen is untouched -- this only removes the exported DNG copy). Delete reuses
 * the same [com.shez.rawcam.settings.Settings.confirmDelete] gate and confirmation-
 * dialog pattern as ClipsScreen's clip delete, for one consistent "are you sure" rule
 * across both screens. List refreshes on the same 2s lifecycle-gated poll pattern as
 * ClipsScreen, so an export finishing (or a delete) while this screen is open shows up
 * without user action.
 */
@Composable
fun ExportsScreen(onBack: () -> Unit = {}) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var refreshTick by remember { mutableStateOf(0) }
    var exports by remember { mutableStateOf<List<ExportEntry>>(emptyList()) }
    var pendingDelete by remember { mutableStateOf<File?>(null) }

    // Off-main delete, same pattern as ClipsScreen.performDelete: the directory
    // removal runs on Dispatchers.IO, then refreshTick++ reloads the list.
    fun performDelete(dir: File) {
        scope.launch {
            withContext(Dispatchers.IO) { dir.deleteRecursively() }
            refreshTick++
        }
    }

    LaunchedEffect(refreshTick) {
        exports = withContext(Dispatchers.IO) { loadExports(context) }
    }
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
            title = { Text("Delete export?") },
            text = { Text("This permanently deletes the exported DNGs in ${toDelete.name}. This cannot be undone.") },
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
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 18.sp) }
            Text("Exports", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        if (exports.isEmpty()) {
            Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text("No exports yet — export a clip from Clips.", color = RawCamColors.Muted)
            }
        } else {
            LazyColumn(Modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                items(exports, key = { it.dir.absolutePath }) { entry ->
                    ExportCard(
                        entry,
                        onShare = { shareExport(context, entry) },
                        onDelete = {
                            scope.launch {
                                if (SettingsRepository.settings.first().confirmDelete) {
                                    pendingDelete = entry.dir
                                } else {
                                    performDelete(entry.dir)
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
private fun ExportCard(entry: ExportEntry, onShare: () -> Unit, onDelete: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(containerColor = RawCamColors.Surface),
        border = BorderStroke(1.dp, RawCamColors.Outline.copy(alpha = 0.5f)),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(Modifier.weight(1f)) {
                Text(exportTitle(entry.dir), style = MaterialTheme.typography.titleMedium)
                Text(
                    "${entry.dngCount} DNGs · ${humanSize(entry.totalBytes)}",
                    color = RawCamColors.Muted, fontSize = 12.sp, fontFamily = FontFamily.Monospace,
                )
            }
            Spacer(Modifier.width(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = onShare) { Text("Send") }
                TextButton(onClick = onDelete) { Text("Delete", color = RawCamColors.Muted) }
            }
        }
    }
}
