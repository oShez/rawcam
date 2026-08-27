package com.shez.rawcam.ui

import android.graphics.BitmapFactory
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.shez.rawcam.preview.PreviewService
import com.shez.rawcam.preview.ProxyStore
import kotlinx.coroutines.delay
import java.io.File

/**
 * Flips through a clip's pre-rendered proxy frames. Nothing is developed here --
 * every frame on screen is a JPEG that PreviewService already wrote, which is
 * what keeps this responsive however slow RAW decoding turns out to be.
 *
 * Playback is deliberately choppy: proxies are every Nth frame, so 24fps
 * material plays at 24/N. Enough to see what happened in a take, which is the
 * whole point of the screen.
 */
@Composable
fun ClipViewerScreen(clip: File, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val dir = remember(clip) { ProxyStore.dirFor(context, clip.name) }

    var meta by remember(clip) { mutableStateOf(ProxyStore.readIndex(dir)) }
    var available by remember(clip) { mutableStateOf(0) }
    var index by remember(clip) { mutableStateOf(0) }
    var playing by remember(clip) { mutableStateOf(true) }

    // Poll what exists, so a viewer opened mid-generation fills in as frames land
    // instead of blocking on "ready".
    LaunchedEffect(dir) {
        while (true) {
            available = ProxyStore.completedCount(dir)
            meta = ProxyStore.readIndex(dir)
            val m = meta
            if (m != null && m.complete && available >= m.proxyCount) break
            delay(500)
        }
    }

    val stride = meta?.stride ?: ProxyStore.MIN_STRIDE
    // Frame interval is stride/fps seconds -- derived, not hardcoded, so a
    // capped stride on a long take still plays at the right speed. The 24 is the
    // capture rate; when ProxyIndex learns to carry fps, read it from there.
    val frameIntervalMs = remember(stride) { (1000L * stride) / 24 }

    LaunchedEffect(playing, available, frameIntervalMs) {
        while (playing && available > 0) {
            delay(frameIntervalMs)
            index = if (index + 1 < available) index + 1 else 0
        }
    }

    val bitmap = remember(index, available) {
        ProxyStore.frameFile(dir, index).takeIf { it.isFile }
            ?.let { BitmapFactory.decodeFile(it.absolutePath) }
    }

    Column(
        Modifier.fillMaxSize().systemBarsPadding().padding(horizontal = 20.dp, vertical = 10.dp)
    ) {
        ScreenHeader(title = "Preview", onBack = onBack) {
            Text(clip.name, color = RawCamColors.Muted, style = RawCamType.Label)
        }
        Box(
            Modifier.weight(1f).fillMaxWidth().clickable { playing = !playing },
            contentAlignment = Alignment.Center,
        ) {
            when {
                bitmap != null -> Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                PreviewService.progressFor(clip.name) >= 0 || meta?.complete == false ->
                    Text(
                        "Preparing preview -- $available / ${meta?.proxyCount ?: 0}",
                        color = RawCamColors.Muted, style = RawCamType.Meta,
                    )
                else -> Text(
                    "Preview unavailable", color = RawCamColors.Muted, style = RawCamType.Meta,
                )
            }
        }
        // Scrub: horizontal drag maps position along the bar to a proxy ordinal.
        // The filled portion is the play head, so the bar reads as a position and
        // not just as a place that happens to accept drags.
        Box(
            Modifier.fillMaxWidth().height(36.dp).clip(RoundedCornerShape(4.dp))
                .background(RawCamColors.Surface)
                .pointerInput(available) {
                    detectHorizontalDragGestures { change, _ ->
                        if (available > 0) {
                            playing = false
                            val f = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            index = ((available - 1) * f).toInt()
                        }
                    }
                },
            contentAlignment = Alignment.CenterStart,
        ) {
            val progress =
                if (available > 1) index.toFloat() / (available - 1).toFloat() else 0f
            Box(
                Modifier.fillMaxWidth(progress).fillMaxHeight()
                    .background(RawCamColors.InteractiveSurface)
            )
            Text(
                "f=${ProxyStore.sourceIndexOf(index, stride)} / ${meta?.sourceFrames ?: 0}" +
                    if (playing) "   PLAYING" else "   PAUSED",
                color = RawCamColors.OnSurface, style = RawCamType.Meta,
                modifier = Modifier.padding(horizontal = 12.dp),
            )
        }
    }
}
