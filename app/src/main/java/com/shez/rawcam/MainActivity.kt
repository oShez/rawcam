package com.shez.rawcam

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.ViewModelProvider
import com.shez.rawcam.ui.ClipsScreen
import com.shez.rawcam.ui.RecordScreen
import com.shez.rawcam.ui.RecordViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: RecordViewModel by lazy {
        ViewModelProvider(this)[RecordViewModel::class.java]
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var tab by remember { mutableStateOf(0) }
            Column(Modifier.fillMaxSize()) {
                TabRow(selectedTabIndex = tab, modifier = Modifier.statusBarsPadding()) {
                    Tab(selected = tab == 0, onClick = { tab = 0 }, text = { Text("Record") })
                    Tab(selected = tab == 1, onClick = { tab = 1 }, text = { Text("Clips") })
                }
                Column(Modifier.weight(1f)) {
                    if (tab == 0) RecordScreen(viewModel) else ClipsScreen()
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
