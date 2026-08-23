package com.shez.rawcam.ui

import android.os.SystemClock
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
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.shez.rawcam.audio.MeterLevels
import kotlinx.coroutines.delay

/**
 * Peak level meter. Shown whenever audio is enabled and deliberately NOT gated on
 * the stats-sidebar setting -- levels are a recording-critical indicator, not a
 * stat.
 *
 * Scale is -60..0 dBFS. The clip lamp latches for [CLIP_LATCH_MS] so a single
 * over-sample cannot flash past unnoticed between frames. [degraded] renders a
 * restrained AUDIO DEGRADED label (spec SS8) when the take's live status bits
 * include anything in AudioStatus.SYNC_INVALIDATING -- distinct from [noAudio],
 * which means no audio at all rather than merely untrustworthy sync.
 *
 * This composable stays mounted across many takes (it's gated on the
 * recordAudio SETTING in RecordScreen, not on whether a take is actually
 * recording), so every piece of latch/hold state below is `remember`ed keyed
 * on [recording]: without that key, a clip latch or an in-progress peak hold
 * from a previous take could still be showing when a brand new take starts.
 */
@Composable
fun AudioMeter(
    levels: MeterLevels,
    channels: Int,
    noAudio: Boolean,
    degraded: Boolean,
    recording: Boolean,
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

    var clipUntilMs by remember(recording) { mutableLongStateOf(0L) }
    var peakUntilL by remember(recording) { mutableLongStateOf(0L) }
    var peakUntilR by remember(recording) { mutableLongStateOf(0L) }
    var peakValueL by remember(recording) { mutableFloatStateOf(MeterLevels.SILENCE_DBFS) }
    var peakValueR by remember(recording) { mutableFloatStateOf(MeterLevels.SILENCE_DBFS) }
    var nowMs by remember(recording) { mutableLongStateOf(SystemClock.elapsedRealtime()) }

    // Both latches are driven off monotonic deadlines rather than restarting on
    // a LaunchedEffect key change (the previous approach's bug): keying on
    // levels.clipped never changes during SUSTAINED clipping, so that effect
    // was never restarted and the lamp went dark exactly when overload was
    // continuous; keying on levels itself changes on every emission
    // (~43-85ms), cancelling the pending peak-hold delay before it ever
    // completed, so the reset lines were dead while audio flowed and the
    // "1.5s peak hold" became an infinite hold pinned to the loudest transient
    // since composition. Deadlines only ever move forward and are compared
    // against a ticking [nowMs] below, so both latches release on their own
    // schedule regardless of how new level emissions happen to key.
    LaunchedEffect(levels) {
        val now = SystemClock.elapsedRealtime()
        if (levels.clipped) clipUntilMs = now + CLIP_LATCH_MS
        if (levels.peakDbfsL > peakValueL) {
            peakValueL = levels.peakDbfsL
            peakUntilL = now + PEAK_HOLD_MS
        } else if (now >= peakUntilL) {
            peakValueL = levels.peakDbfsL
        }
        if (levels.peakDbfsR > peakValueR) {
            peakValueR = levels.peakDbfsR
            peakUntilR = now + PEAK_HOLD_MS
        } else if (now >= peakUntilR) {
            peakValueR = levels.peakDbfsR
        }
    }
    // Forces periodic recomposition so a deadline takes visual effect even
    // between distinct level emissions -- e.g. clipping stops and audio goes
    // to exact silence, whose repeated MeterLevels(-160, -160, false) readings
    // are structurally equal and so would not by themselves retrigger the
    // effect above.
    LaunchedEffect(recording) {
        while (true) {
            delay(TICK_MS)
            nowMs = SystemClock.elapsedRealtime()
        }
    }

    val clipLatched = nowMs < clipUntilMs
    val peakHoldL = if (nowMs < peakUntilL) peakValueL else levels.peakDbfsL
    val peakHoldR = if (nowMs < peakUntilR) peakValueR else levels.peakDbfsR

    Column(modifier = modifier) {
        if (degraded) {
            Text(
                text = "AUDIO DEGRADED",
                color = Color(0xFFFFA726),
                fontSize = 10.sp,
                modifier = Modifier.padding(bottom = 2.dp),
            )
        }
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
private const val TICK_MS = 100L
private const val PEAK_TICK_PX = 2f
private const val CLIP_LAMP_PX = 6f
