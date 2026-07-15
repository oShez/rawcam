package com.shez.rawcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
import com.shez.rawcam.ui.ClipsScreen
import com.shez.rawcam.ui.RawCamTheme
import com.shez.rawcam.ui.RecordScreen
import com.shez.rawcam.ui.RecordViewModel

private enum class Screen { Record, Clips }

class MainActivity : ComponentActivity() {

    private val viewModel: RecordViewModel by lazy {
        ViewModelProvider(this)[RecordViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RawCamTheme {
                var screen by remember { mutableStateOf(Screen.Record) }
                // Leaving the Record screen disposes its SurfaceView; mid-recording
                // that abandons the session's preview target and silently stalls the
                // RAW stream (the timer keeps counting, no frames land). Lock
                // navigation until the recording (or an in-flight start/stop) is done.
                val recState by viewModel.uiState.collectAsState()
                val locked = recState.recording || recState.busy
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    when (screen) {
                        Screen.Record -> RecordScreen(
                            viewModel = viewModel,
                            clipsEnabled = !locked,
                            onOpenClips = { if (!locked) screen = Screen.Clips },
                        )
                        Screen.Clips -> ClipsScreen(onBack = { screen = Screen.Record })
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
