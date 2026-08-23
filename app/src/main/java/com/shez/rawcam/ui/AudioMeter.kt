package com.shez.rawcam.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.shez.rawcam.audio.MeterLevels
import kotlinx.coroutines.delay

/**
 * Peak level meter. Shown whenever audio is enabled and deliberately NOT gated on
 * the stats-sidebar setting -- levels are a recording-critical indicator, not a
 * stat.
 *
 * Scale is -60..0 dBFS. The clip lamp latches for [CLIP_LATCH_MS] so a single
 * over-sample cannot flash past unnoticed between frames.
 */
@Composable
fun AudioMeter(
    levels: MeterLevels,
    channels: Int,
    noAudio: Boolean,
    modifier: Modifier = Modifier,
) {
    if (noAudio) {
        Text(
            text = "NO AUDIO",
            color = Color(0xFFFF5252),
            textAlign = TextAlign.Center,
            modifier = modifier,
        )
        return
    }

    var clipLatched by remember { mutableStateOf(false) }
    var peakHoldL by remember { mutableFloatStateOf(MeterLevels.SILENCE_DBFS) }
    var peakHoldR by remember { mutableFloatStateOf(MeterLevels.SILENCE_DBFS) }

    LaunchedEffect(levels.clipped) {
        if (levels.clipped) {
            clipLatched = true
            delay(CLIP_LATCH_MS)
            clipLatched = false
        }
    }
    LaunchedEffect(levels) {
        if (levels.peakDbfsL > peakHoldL) peakHoldL = levels.peakDbfsL
        if (levels.peakDbfsR > peakHoldR) peakHoldR = levels.peakDbfsR
        delay(PEAK_HOLD_MS)
        peakHoldL = levels.peakDbfsL
        peakHoldR = levels.peakDbfsR
    }

    Column(modifier = modifier) {
        MeterBar(levels.peakDbfsL, peakHoldL, clipLatched)
        if (channels == 2) MeterBar(levels.peakDbfsR, peakHoldR, clipLatched)
    }
}

@Composable
private fun MeterBar(dbfs: Float, peakHold: Float, clipped: Boolean) {
    Canvas(
        modifier = Modifier
            .fillMaxWidth()
            .height(6.dp)
            .padding(vertical = 1.dp)
            .background(Color(0xFF1A1A1A)),
    ) {
        val w = size.width * norm(dbfs)
        val color = when {
            dbfs >= -3f -> Color(0xFFFF5252)
            dbfs >= -12f -> Color(0xFFFFC107)
            else -> Color(0xFF4CAF50)
        }
        if (w > 0f) drawRect(color = color, size = Size(w, size.height))
        val hold = size.width * norm(peakHold)
        if (hold > 0f) {
            drawRect(
                color = Color.White,
                topLeft = Offset(hold - PEAK_TICK_PX, 0f),
                size = Size(PEAK_TICK_PX, size.height),
            )
        }
        if (clipped) {
            drawRect(
                color = Color(0xFFFF1744),
                topLeft = Offset(size.width - CLIP_LAMP_PX, 0f),
                size = Size(CLIP_LAMP_PX, size.height),
            )
        }
    }
}

/** Maps -60..0 dBFS onto 0..1, clamped. */
private fun norm(dbfs: Float): Float = ((dbfs + 60f) / 60f).coerceIn(0f, 1f)

private const val CLIP_LATCH_MS = 2_000L
private const val PEAK_HOLD_MS = 1_500L
private const val PEAK_TICK_PX = 2f
private const val CLIP_LAMP_PX = 6f
