package com.shez.rawcam.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.util.LruCache
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.shez.rawcam.preview.PreviewService
import com.shez.rawcam.preview.ProxyStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

/** Width of the play head. Also the amount the track reserves so it never clips. */
private val PLAYHEAD_WIDTH = 3.dp

/** Tall enough to be a real finger target -- 36dp was under the 48dp minimum. */
private val SCRUB_HEIGHT = 48.dp

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

    // Decoding a proxy costs a few milliseconds and allocates ~3MB. Doing it
    // inside composition -- which is what this screen used to do -- meant every
    // pointer event during a drag blocked the very frame it was supposed to
    // draw, so scrubbing stuttered in proportion to how fast you moved. Decode
    // off the main thread instead, cache what has been seen so dragging back
    // over old ground is instant, and let collectLatest drop a decode the finger
    // has already moved past.
    val cache = remember(clip) {
        val budget = (Runtime.getRuntime().maxMemory() / 8)
            .coerceAtMost(64L * 1024 * 1024)
            .coerceAtLeast(8L * 1024 * 1024)
            .toInt()
        object : LruCache<Int, Bitmap>(budget) {
            override fun sizeOf(key: Int, value: Bitmap) = value.byteCount
        }
    }
    // The last frame successfully decoded. Held across a pending decode on
    // purpose: showing the previous frame for a few milliseconds reads as a
    // smooth scrub, where blanking to the empty state reads as a flicker.
    var shown by remember(clip) { mutableStateOf<Bitmap?>(null) }
    var decodeAttempted by remember(clip) { mutableStateOf(false) }

    LaunchedEffect(dir) {
        snapshotFlow { index to available }.collectLatest { (want, _) ->
            val hit = cache.get(want)
            if (hit != null) {
                shown = hit
                decodeAttempted = true
                return@collectLatest
            }
            val file = ProxyStore.frameFile(dir, want)
            val decoded = withContext(Dispatchers.IO) {
                file.takeIf { it.isFile }?.let { BitmapFactory.decodeFile(it.absolutePath) }
            }
            decodeAttempted = true
            if (decoded != null) {
                cache.put(want, decoded)
                shown = decoded
            }
        }
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
            val frame = shown
            when {
                frame != null -> Image(
                    bitmap = frame.asImageBitmap(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Fit,
                )
                // Nothing decoded yet on a clip that has frames: stay blank for the
                // one beat it takes rather than flashing "unavailable" and back.
                !decodeAttempted -> Unit
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
        ScrubBar(
            index = index,
            available = available,
            sourceFrame = ProxyStore.sourceIndexOf(index, stride),
            sourceFrames = meta?.sourceFrames ?: 0,
            playing = playing,
            onSeek = { target ->
                playing = false
                index = target
            },
        )
    }
}

/**
 * Position along the take, and the control for changing it.
 *
 * Seeks from the first touch rather than through detectHorizontalDragGestures,
 * whose touch slop swallows the opening few pixels of every drag -- the finger
 * moves, nothing happens, then the play head jumps to catch up. Handling the
 * gesture directly also makes a plain tap seek, so the bar can be used without
 * dragging at all.
 */
@Composable
private fun ScrubBar(
    index: Int,
    available: Int,
    sourceFrame: Long,
    sourceFrames: Int,
    playing: Boolean,
    onSeek: (Int) -> Unit,
) {
    BoxWithConstraints(
        Modifier.fillMaxWidth().height(SCRUB_HEIGHT).clip(RoundedCornerShape(4.dp))
            .background(RawCamColors.Surface)
            .pointerInput(available) {
                if (available <= 0) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown(requireUnconsumed = false)
                    onSeek(ordinalAt(down.position.x, size.width, available))
                    down.consume()
                    while (true) {
                        val event = awaitPointerEvent()
                        val touch = event.changes.firstOrNull { it.id == down.id } ?: break
                        if (!touch.pressed) break
                        onSeek(ordinalAt(touch.position.x, size.width, available))
                        touch.consume()
                    }
                }
            },
        contentAlignment = Alignment.CenterStart,
    ) {
        val track: Dp = maxWidth
        val progress = if (available > 1) index.toFloat() / (available - 1).toFloat() else 0f

        // Elapsed portion. Reads as "how far in"; the play head reads as "exactly where".
        Box(
            Modifier.width(track * progress).fillMaxHeight()
                .background(RawCamColors.InteractiveSurface)
        )
        Text(
            "f=$sourceFrame / $sourceFrames" + if (playing) "   PLAYING" else "   PAUSED",
            color = RawCamColors.OnSurface, style = RawCamType.Meta,
            modifier = Modifier.padding(horizontal = 12.dp),
        )
        // Drawn last so the head stays crisp on top of the readout it crosses near
        // the start of the take. Centred on the position, clamped at both ends so
        // it sits fully on the track instead of half-clipping at 0 and 1.
        val headX = (track * progress - PLAYHEAD_WIDTH / 2)
            .coerceIn(0.dp, (track - PLAYHEAD_WIDTH).coerceAtLeast(0.dp))
        Box(
            Modifier.offset(x = headX).width(PLAYHEAD_WIDTH).fillMaxHeight()
                .clip(RoundedCornerShape(2.dp))
                .background(RawCamColors.Interactive)
        )
    }
}

/** Maps a touch x within a track [widthPx] wide to a proxy ordinal. */
internal fun ordinalAt(x: Float, widthPx: Int, available: Int): Int {
    if (widthPx <= 0 || available <= 0) return 0
    val fraction = (x / widthPx.toFloat()).coerceIn(0f, 1f)
    // Rounds rather than truncates: truncation biases every seek downwards and
    // makes the last ordinal reachable only at the exact right-hand pixel.
    return ((available - 1) * fraction).roundToInt().coerceIn(0, available - 1)
}
