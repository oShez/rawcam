package com.shez.rawcam.ui

import android.content.Intent
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.shez.rawcam.NativeBridge
import com.shez.rawcam.export.ExportPaths
import com.shez.rawcam.settings.MainsFreq
import com.shez.rawcam.settings.MeterRegion
import com.shez.rawcam.settings.MeterScope
import com.shez.rawcam.settings.OisMode
import com.shez.rawcam.settings.RECORD_BIT_DEPTHS
import com.shez.rawcam.settings.Settings
import com.shez.rawcam.settings.SettingsRepository
import com.shez.rawcam.settings.ShutterDisplay
import com.shez.rawcam.settings.StartupMeter
import kotlinx.coroutines.launch
import java.io.File

/**
 * Full settings list, sectioned to match [docs/superpowers/specs/2026-07-18-settings-page-design.md].
 * Every row reads from [SettingsRepository.settings] and writes back through
 * [SettingsRepository.update] on change -- there is no local draft state (other
 * than [TextFieldRow]'s in-progress text) to keep in sync with the persisted
 * value, so external changes (e.g. Reset) are reflected immediately via the flow.
 */
@Composable
fun SettingsScreen(
    onBack: () -> Unit = {},
    viewModel: RecordViewModel = viewModel(),
) {
    BackHandler(onBack = onBack)
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val settings by SettingsRepository.settings.collectAsState(initial = Settings())
    val recordUiState by viewModel.uiState.collectAsState()
    var showResetDialog by remember { mutableStateOf(false) }
    var showReport by remember { mutableStateOf(false) }
    var dumpStatus by remember { mutableStateOf<String?>(null) }
    // The system grant screen ExportPaths.requestAllFilesAccess() opens is a
    // separate Activity on the same task backstack -- this screen resumes
    // (ON_RESUME) when the user navigates back from it, which is the only
    // signal available that the permission may have changed.
    var allFilesAccess by remember { mutableStateOf(ExportPaths.hasAllFilesAccess(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                allFilesAccess = ExportPaths.hasAllFilesAccess(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun apply(transform: (Settings) -> Settings) {
        scope.launch { SettingsRepository.update(transform) }
    }

    if (showReport) {
        CompatibilityReportScreen(reportText = recordUiState.reportText, onBack = { showReport = false })
        return
    }

    if (showResetDialog) {
        AlertDialog(
            onDismissRequest = { showResetDialog = false },
            title = { Text("Reset all settings?") },
            text = { Text("This also clears remembered capture state.") },
            confirmButton = {
                TextButton(onClick = {
                    showResetDialog = false
                    scope.launch { SettingsRepository.clearAll() }
                }) { Text("Reset") }
            },
            dismissButton = {
                TextButton(onClick = { showResetDialog = false }) { Text("Cancel") }
            },
        )
    }

    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        ScreenHeader(title = "Settings", onBack = onBack)
        // Width-capped: full-bleed rows put a label and its value ~1870px apart on this
        // screen, so reading one row meant crossing the display. See ScreenBody.
        ScreenBody(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
            // Only what CANNOT be reached from the record screen lives here. ISO,
            // shutter, white balance, tint, lens, resolution, frame rate and the
            // whole audio group are all one tap away on the rail, so duplicating
            // them here was two places to change one value -- and the "default"
            // copies were dead besides, since capture state is always restored now.
            //
            // activeLens gates the per-lens bit-depth options below.
            val activeLens = recordUiState.lenses.getOrNull(recordUiState.lensIndex)

            // Format first: compression and depth decide what a clip IS, and they
            // interact (8-bit only saves space with compression on), so they sit
            // together at the top. Limits and housekeeping follow.
            SectionHeader("RECORDING", divider = false)
            ToggleRow(
                title = "Compress recordings",
                subtitle = "Lossless compression to shrink .rawv file size",
                checked = settings.compressRecordings,
                onChange = { v -> apply { it.copy(compressRecordings = v) } },
            )
            // 0 when no lens is known yet (enumeration still running): every explicit depth
            // is then disabled and only Native is selectable, which is the safe default.
            val activeLensNativeDepth = effectiveBitDepth(activeLens?.whiteLevel ?: 0, 0)
            EnumRow(
                title = "Record bit depth",
                // "shrinks files" is measured (21.4% at 12-bit); the old copy also
                // claimed it "eases sustained writes", which the 2026-09-08 device A/B
                // showed is BACKWARDS -- 12-bit drops >=8.2 pp MORE frames than 14-bit
                // under thermal load. See the Result section of
                // docs/superpowers/specs/2026-08-30-selectable-record-bit-depth-design.md.
                subtitle = "Lower depth shrinks files, but drops more frames once the " +
                    "phone is warm. 8-bit only saves space with compression on.",
                // Derived from RECORD_BIT_DEPTHS -- SettingsRepository's coercion whitelist --
                // rather than a second hardcoded list, so the two cannot drift: an entry
                // added to one and not the other would offer a value coerced() silently
                // resets to Native.
                options = RECORD_BIT_DEPTHS.map { it to if (it == 0) "Native" else it.toString() },
                selected = settings.recordBitDepth,
                onSelect = { v -> apply { it.copy(recordBitDepth = v) } },
                // Native is never disabled; an explicit depth above what this lens delivers is.
                isEnabled = { v -> v == 0 || v <= activeLensNativeDepth },
            )
            EnumRow(
                title = "Max clip length", subtitle = null,
                options = listOf(0 to "Off", 30 to "30s", 60 to "1m", 300 to "5m", 600 to "10m"),
                selected = settings.maxClipLengthSeconds,
                onSelect = { v -> apply { it.copy(maxClipLengthSeconds = v) } },
            )
            SliderRow(
                title = "Free-space reserve", stops = (5..120 step 5).toList(),
                selected = settings.freeSpaceReserveSeconds,
                labelFor = { "${it}s" },
                onSelect = { v -> apply { it.copy(freeSpaceReserveSeconds = v) } },
            )
            ToggleRow(
                title = "Thermal auto-stop",
                subtitle = "Stop recording when the device overheats (otherwise warn only)",
                checked = settings.thermalAutoStop,
                onChange = { v -> apply { it.copy(thermalAutoStop = v) } },
            )
            EnumRow(
                title = "Anti-flicker", subtitle = null,
                options = listOf(MainsFreq.OFF to "Off", MainsFreq.HZ50 to "50 Hz", MainsFreq.HZ60 to "60 Hz"),
                selected = settings.mainsFreq,
                onSelect = { v -> apply { it.copy(mainsFreq = v) } },
            )
            EnumRow(
                title = "Optical stabilization", subtitle = null,
                options = listOf(OisMode.AUTO to "Auto", OisMode.ON to "On", OisMode.OFF to "Off"),
                selected = settings.oisMode,
                onSelect = { v -> apply { it.copy(oisMode = v) } },
            )

            // Startup metering belongs with the tap-to-meter rows: all four decide
            // when and how the app meters, and it is NOT a duplicate of anything on
            // the rail -- it governs launch behaviour, which has no on-screen control.
            SectionHeader("METERING")
            EnumRow(
                title = "Meter on launch", subtitle = null,
                options = listOf(
                    StartupMeter.ALWAYS to "Always",
                    StartupMeter.IF_NO_SAVED to "If nothing saved",
                    StartupMeter.NEVER to "Never",
                ),
                selected = settings.startupMeter,
                onSelect = { v -> apply { it.copy(startupMeter = v) } },
            )
            EnumRow(
                title = "Tap adjusts", subtitle = null,
                options = listOf(
                    MeterScope.EVERYTHING to "Everything",
                    MeterScope.EXPOSURE_FOCUS to "Exposure + focus",
                    MeterScope.WB_ONLY to "White balance",
                ),
                selected = settings.meterScope,
                onSelect = { v -> apply { it.copy(meterScope = v) } },
            )
            EnumRow(
                title = "Meter region", subtitle = null,
                options = listOf(MeterRegion.SMALL to "S", MeterRegion.MEDIUM to "M", MeterRegion.LARGE to "L"),
                selected = settings.meterRegion,
                onSelect = { v -> apply { it.copy(meterRegion = v) } },
            )
            EnumRow(
                title = "Reticle hold", subtitle = null,
                options = listOf(300 to "0.3s", 600 to "0.6s", 1200 to "1.2s"),
                selected = settings.reticleHoldMs,
                onSelect = { v -> apply { it.copy(reticleHoldMs = v) } },
            )

            SectionHeader("VIEWFINDER")
            ToggleRow(
                title = "Grid", subtitle = "Rule-of-thirds overlay", checked = settings.gridEnabled,
                onChange = { v -> apply { it.copy(gridEnabled = v) } },
            )
            ToggleRow(
                title = "Level", subtitle = "Horizon indicator", checked = settings.levelEnabled,
                onChange = { v -> apply { it.copy(levelEnabled = v) } },
            )
            ToggleRow(
                title = "Highlight zebra", subtitle = "Stripe blown highlights (clipping to white)",
                checked = settings.zebraHighlightEnabled,
                onChange = { v -> apply { it.copy(zebraHighlightEnabled = v) } },
            )
            ToggleRow(
                title = "Shadow zebra", subtitle = "Stripe crushed shadows (clipping to black)",
                checked = settings.zebraShadowEnabled,
                onChange = { v -> apply { it.copy(zebraShadowEnabled = v) } },
            )
            EnumRow(
                title = "Shutter display", subtitle = null,
                options = listOf(ShutterDisplay.FRACTION to "1/48", ShutterDisplay.ANGLE to "180°"),
                selected = settings.shutterDisplay,
                onSelect = { v -> apply { it.copy(shutterDisplay = v) } },
            )

            // Clip name prefix leads here rather than sitting in RECORDING: it names
            // the FILES, which is what the rest of this section is about, and it is
            // not a recording limit like the rows it used to sit among.
            SectionHeader("CLIPS & EXPORT")
            TextFieldRow(
                title = "Clip name prefix", value = settings.clipPrefix,
                onCommit = { v -> apply { it.copy(clipPrefix = v) } },
            )
            ToggleRow(
                title = "Confirm before delete", subtitle = null, checked = settings.confirmDelete,
                onChange = { v -> apply { it.copy(confirmDelete = v) } },
            )
            ToggleRow(
                title = "Delete original after export",
                subtitle = "Removes the .rawv once DNGs are written",
                checked = settings.deleteAfterExport,
                onChange = { v -> apply { it.copy(deleteAfterExport = v) } },
            )
            ToggleRow(
                title = "Auto-export after recording", subtitle = null, checked = settings.autoExport,
                onChange = { v -> apply { it.copy(autoExport = v) } },
            )

            // Stats sidebar and BENCH moved here from VIEWFINDER: they are
            // diagnostics, not framing aids, and sat oddly beside grid and zebras.
            // DEVICE folded in too -- one place for everything a normal user never
            // needs to open.
            SectionHeader("ADVANCED")
            ToggleRow(
                title = "Stats sidebar", subtitle = null, checked = settings.showStatsSidebar,
                onChange = { v -> apply { it.copy(showStatsSidebar = v) } },
            )
            ToggleRow(
                title = "BENCH button", subtitle = null, checked = settings.showBench,
                onChange = { v -> apply { it.copy(showBench = v) } },
            )
            ToggleRow(
                title = "Diagnostic logging", subtitle = "Verbose meter/WB logs", checked = settings.debugLogging,
                onChange = { v -> apply { it.copy(debugLogging = v) } },
            )
            Row(
                Modifier.fillMaxWidth().padding(vertical = 10.dp)
                    .clickable { showResetDialog = true },
            ) {
                Text("Reset all settings", color = RawCamColors.Accent, fontSize = 15.sp)
            }

            // Split back out of ADVANCED. Folding DEVICE in traded a coherent
            // grouping for one fewer header and made ADVANCED a junk drawer: these
            // three OPEN something -- a report, a file dialog, a system permission
            // screen -- while everything above is a preference you set and leave.
            SectionHeader("DEVICE")
            ActionRow(
                title = "Compatibility report",
                subtitle = "What RawCam found on this phone, and why",
                onClick = { showReport = true },
            )
            ActionRow(
                title = "Dump characteristics (JSON)",
                subtitle = dumpStatus ?: "Writes a snapshot fixture and opens the share sheet",
                onClick = {
                    dumpStatus = "Dumping…"
                    viewModel.dumpCharacteristics { result ->
                        result.onSuccess { file ->
                            dumpStatus = "Saved ${file.name}"
                            shareFile(context, file, "application/json")
                        }.onFailure { e ->
                            dumpStatus = "Failed: ${e.message}"
                        }
                    }
                },
            )
            ActionRow(
                title = "Allow file access",
                subtitle = if (allFilesAccess) {
                    "Granted — exports show up over USB in Windows/Mac file browsers"
                } else {
                    "Lets exported clips show up over USB in Windows/Mac file browsers"
                },
                onClick = { if (!allFilesAccess) ExportPaths.requestAllFilesAccess(context) },
            )

            SectionHeader("ABOUT")
            Text(
                "RawCam " + com.shez.rawcam.BuildConfig.VERSION_NAME, color = RawCamColors.Muted, fontSize = 12.sp,
                fontFamily = RawCamMono, modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(
                "Core " + NativeBridge.nativeVersion(), color = RawCamColors.Muted, fontSize = 12.sp,
                fontFamily = RawCamMono, modifier = Modifier.padding(vertical = 2.dp),
            )
            Text(
                Build.MODEL, color = RawCamColors.Muted, fontSize = 12.sp,
                fontFamily = RawCamMono, modifier = Modifier.padding(vertical = 2.dp),
            )
            Spacer(Modifier.height(24.dp))
        }
    }
}

@Composable
private fun SectionHeader(title: String, divider: Boolean = true) {
    // A hairline above the label, not just whitespace. This list scrolls well past
    // a screen, and spacing alone left the boundary between two groups reading as
    // one more gap between rows. Outline is the same low-contrast token the
    // disabled row text uses, so it separates without competing with the content.
    //
    // `divider = false` for the FIRST header only: a rule there would hang directly
    // under the screen title with no group above it to close off.
    if (divider) {
        Spacer(Modifier.height(14.dp))
        Spacer(Modifier.fillMaxWidth().height(1.dp).background(RawCamColors.Outline))
    }
    Text(
        title, color = RawCamColors.Muted, style = RawCamType.Label,
        modifier = Modifier.padding(top = if (divider) 14.dp else 20.dp, bottom = 6.dp),
    )
}

@Composable
private fun ToggleRow(title: String, subtitle: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RawCamColors.OnSurface, fontSize = 15.sp)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = RawCamColors.Muted, fontSize = 12.sp)
            }
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}

/** Inline segmented selector -- FpsToggle's visual language, generalized to any [T]. */
@Composable
private fun <T> EnumRow(
    title: String, subtitle: String?, options: List<Pair<T, String>>, selected: T,
    onSelect: (T) -> Unit, isEnabled: (T) -> Boolean = { true },
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, color = RawCamColors.OnSurface, fontSize = 15.sp)
        subtitle?.let {
            Spacer(Modifier.height(2.dp))
            Text(it, color = RawCamColors.Muted, fontSize = 12.sp)
        }
        Spacer(Modifier.height(8.dp))
        Row(
            Modifier
                .horizontalScroll(rememberScrollState())
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, RawCamColors.Outline, RoundedCornerShape(8.dp)),
        ) {
            options.forEach { (value, label) ->
                val on = value == selected
                val enabled = isEnabled(value)
                Row(
                    Modifier
                        .then(if (enabled) Modifier.clickable { onSelect(value) } else Modifier)
                        // `on && enabled`, not just `on`. Depth is stored as
                        // REQUESTED, so a value can be both selected and undeliverable
                        // -- pick 12 on the main camera, switch to the 10-bit
                        // ultra-wide, and 12 is still the stored choice while being
                        // disabled. Painting the selected background there made a
                        // greyed, untappable option read as the active one. It now
                        // shows as plainly disabled; the take records Native, and the
                        // stored value is still there when a lens that can deliver it
                        // comes back.
                        .background(
                            if (on && enabled) RawCamColors.SurfaceVariant else Color.Transparent,
                        )
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(
                        label,
                        color = when {
                            !enabled -> RawCamColors.Outline
                            on -> RawCamColors.OnSurface
                            else -> RawCamColors.Muted
                        },
                        fontSize = 13.sp,
                    )
                }
            }
        }
    }
}

/** Reuses [TickedSlider] (RecordScreen.kt) for a numeric setting. */
@Composable
private fun SliderRow(
    title: String, stops: List<Int>, selected: Int, labelFor: (Int) -> String, onSelect: (Int) -> Unit,
) {
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(title, color = RawCamColors.OnSurface, fontSize = 15.sp)
            Text(
                labelFor(selected), color = RawCamColors.Muted,
                fontSize = 13.sp, fontFamily = RawCamMono,
            )
        }
        Spacer(Modifier.height(6.dp))
        TickedSlider(stops = stops, selected = selected, labelFor = labelFor, onSelect = onSelect)
    }
}

/**
 * Single-line text field; commits (sanitized via [SettingsRepository.sanitizePrefix])
 * on IME "done" or when focus leaves the field, not on every keystroke.
 */
@Composable
private fun TextFieldRow(title: String, value: String, onCommit: (String) -> Unit) {
    var text by remember(value) { mutableStateOf(value) }
    // Only commits on a focused->unfocused transition (guarded by `wasFocused`) --
    // onFocusChanged also fires once on initial composition (unfocused), which
    // would otherwise fire a spurious commit on every screen open.
    var wasFocused by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
        Text(title, color = RawCamColors.OnSurface, fontSize = 15.sp)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            singleLine = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = {
                text = SettingsRepository.sanitizePrefix(text)
                onCommit(text)
            }),
            modifier = Modifier.fillMaxWidth()
                .onFocusChanged { focus ->
                    if (wasFocused && !focus.isFocused) {
                        val sanitized = SettingsRepository.sanitizePrefix(text)
                        text = sanitized
                        onCommit(sanitized)
                    }
                    wasFocused = focus.isFocused
                },
        )
    }
}

/** Navigational row -- like [ToggleRow] but fires [onClick] instead of toggling a
 * value, for actions that open a sub-screen or share sheet (Device section). */
@Composable
private fun ActionRow(title: String, subtitle: String?, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 10.dp).clickable(onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = RawCamColors.OnSurface, fontSize = 15.sp)
            subtitle?.let {
                Spacer(Modifier.height(2.dp))
                Text(it, color = RawCamColors.Muted, fontSize = 12.sp)
            }
        }
        Text("›", color = RawCamColors.Muted, fontSize = 18.sp)
    }
}

/**
 * Read-only scrollable view of [CompatibilityReport.render]'s output, reached from
 * the Device section's "Compatibility report" row. SHARE fires a plain-text
 * ACTION_SEND -- there is no file to attach here (contrast the JSON dump below,
 * which shares an actual file via FileProvider).
 */
@Composable
private fun CompatibilityReportScreen(reportText: String, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    Column(Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("←", fontSize = 18.sp) }
            Text("Compatibility report", style = MaterialTheme.typography.titleLarge)
        }
        Spacer(Modifier.height(8.dp))
        Text(
            reportText,
            color = RawCamColors.OnSurface,
            fontSize = 12.sp,
            fontFamily = RawCamMono,
            modifier = Modifier.weight(1f).verticalScroll(rememberScrollState()),
        )
        Spacer(Modifier.height(12.dp))
        Row(
            Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp))
                .border(1.dp, RawCamColors.Outline, RoundedCornerShape(8.dp))
                .clickable { shareText(context, reportText) }
                .padding(vertical = 12.dp),
            horizontalArrangement = Arrangement.Center,
        ) {
            Text("SHARE", color = RawCamColors.Accent, fontSize = 14.sp)
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Plain-text share (compatibility report). */
private fun shareText(context: android.content.Context, text: String) {
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_TEXT, text)
    }
    context.startActivity(Intent.createChooser(intent, "Share compatibility report"))
}

/** File share via the app's FileProvider (see file_paths.xml) -- same mechanism as
 * ExportsScreen's DNG sharing, single-file ACTION_SEND instead of _MULTIPLE. */
private fun shareFile(context: android.content.Context, file: File, mime: String) {
    val authority = "${context.packageName}.fileprovider"
    val uri = FileProvider.getUriForFile(context, authority, file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = mime
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Share ${file.name}"))
}
