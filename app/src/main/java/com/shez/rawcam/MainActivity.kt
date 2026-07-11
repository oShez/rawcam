package com.shez.rawcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.lifecycle.ViewModelProvider
import com.shez.rawcam.ui.RecordScreen
import com.shez.rawcam.ui.RecordViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: RecordViewModel by lazy {
        ViewModelProvider(this)[RecordViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            RecordScreen(viewModel)
        }
    }

    override fun onStop() {
        super.onStop()
        // If a recording is in flight when the activity backgrounds, finalize the
        // file and release the camera before the system can tear anything down.
        viewModel.handleActivityStop()
    }
}
