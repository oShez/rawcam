package com.shez.rawcam

import android.os.Bundle
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            var result by remember { mutableStateOf("") }
            var isRunning by remember { mutableStateOf(false) }
            val scope = rememberCoroutineScope()
            Column {
                Text("RawCam core: ${NativeBridge.nativeVersion()}")
                Button(enabled = !isRunning, onClick = {
                    if (isRunning) return@Button
                    isRunning = true
                    scope.launch {
                        try {
                            val mbps = withContext(Dispatchers.IO) {
                                val path = File(getExternalFilesDir(null), "bench.bin").absolutePath
                                // ~6GB, ≈10s of 12MP RAW16 @24fps
                                NativeBridge.nativeBenchmarkWrite(path, 25_000_000, 240)
                            }
                            result = "%.0f MB/s".format(mbps)
                            Log.d("RawCamBench", "sustained=$result raw=$mbps")
                        } finally {
                            isRunning = false
                        }
                    }
                }) {
                    Text(if (isRunning) "Running..." else "Benchmark")
                }
                Text(result)
            }
        }
    }
}
