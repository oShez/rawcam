package com.shez.rawcam

import android.Manifest
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.shez.rawcam.audio.AudioRecorder
import com.shez.rawcam.settings.SettingsRepository
import com.shez.rawcam.ui.ClipsScreen
import com.shez.rawcam.ui.ExportsScreen
import com.shez.rawcam.ui.RawCamTheme
import com.shez.rawcam.ui.RecordScreen
import com.shez.rawcam.ui.RecordViewModel
import com.shez.rawcam.ui.SettingsScreen
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map

private enum class Screen { Record, Clips, Exports, Settings }

class MainActivity : ComponentActivity() {

    private val viewModel: RecordViewModel by lazy {
        ViewModelProvider(this)[RecordViewModel::class.java]
    }

    // A denial surfaces at record time via AudioResult/AudioStatus.PERMISSION_DENIED,
    // never as a blocked recording -- video always wins over audio.
    private val audioPermissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { /* result
            surfaces at record time via AudioStatus.PERMISSION_DENIED */ }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        SettingsRepository.init(applicationContext)
        setContent {
            RawCamTheme {
                var screen by remember { mutableStateOf(Screen.Record) }
                // Leaving the Record screen disposes its SurfaceView; mid-recording
                // that abandons the session's preview target and silently stalls the
                // RAW stream (the timer keeps counting, no frames land). Lock
                // navigation until the recording (or an in-flight start/stop) is done.
                //
                // Mapped + distinctUntilChanged rather than collectAsState() on the
                // whole uiState: that state ticks every 0.5-1s while recording
                // (elapsedSeconds/written/dropped), which would otherwise recompose
                // this whole Composable -- reallocating onOpenClips and forcing
                // RecordScreen to recompose -- on every tick even though `locked`
                // itself changes rarely.
                val locked by remember(viewModel) {
                    viewModel.uiState.map { it.recording || it.busy }.distinctUntilChanged()
                }.collectAsState(initial = false)
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        Screen.Record -> RecordScreen(
                            viewModel = viewModel,
                            clipsEnabled = !locked,
                            onOpenClips = { if (!locked) screen = Screen.Clips },
                            exportsEnabled = !locked,
                            onOpenExports = { if (!locked) screen = Screen.Exports },
                            settingsEnabled = !locked,
                            onOpenSettings = { if (!locked) screen = Screen.Settings },
                            audioInputs = { AudioRecorder(this).listInputs() },
                        )
                        Screen.Clips -> ClipsScreen(onBack = { screen = Screen.Record })
                        Screen.Exports -> ExportsScreen(onBack = { screen = Screen.Record })
                        Screen.Settings -> SettingsScreen(
                            onBack = { screen = Screen.Record },
                            viewModel = viewModel,
                            onRequestAudioPermission = {
                                audioPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
                            },
                            audioInputs = { AudioRecorder(this).listInputs() },
                        )
                    }
                }
            }
        }
    }

    override fun onStop() {
        super.onStop()
        // If a recording is in flight when the activity backgrounds, finalize the
        // file and release the camera before the system can tear anything down.
        viewModel.handleActivityStop()
    }
}
