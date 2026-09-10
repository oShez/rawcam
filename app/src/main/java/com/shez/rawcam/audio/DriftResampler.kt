package com.shez.rawcam.audio

import kotlin.math.floor

/**
 * Resamples interleaved audio onto the video's timebase and emits exactly
 * [targetFrames] frames.
 *
 * Two jobs, deliberately in one pass, because doing them separately would fight
 * each other:
 *
 *  - **Rate.** The microphone and the camera sensor are clocked by different
 *    crystals, so a take drifts. [AvSync.driftPpm] measures the error;
 *    [outFramesPerInFrame] is `1 + ppm/1e6`, and stepping the read position by
 *    its reciprocal removes the drift across the whole take rather than only at
 *    the head. A POSITIVE ppm means the mic clock ran slow, so fewer samples
 *    exist than real time warrants: played at the nominal rate the audio ends
 *    early -- it LEADS -- and the fix is to stretch it. Getting that sign
 *    backwards would double the drift instead of removing it.
 *
 *  - **Length.** The output is padded or stopped so the sound is the picture's
 *    duration to the sample. That is a requirement of its own: a DNG sequence
 *    and a sidecar of different lengths do not sit in a timeline as a matched
 *    pair, however well their timecode agrees.
 *
 * The rate correction is what makes the length fit honest. Forcing the length
 * WITHOUT it would look right at both ends and still drift in the middle; and
 * deriving the ratio from the two lengths instead of from the measured ppm would
 * fold the tail's own error into the rate -- measuring a thing with an
 * instrument no more precise than the thing.
 *
 * Streaming, so a half-hour take is never held in memory. Not thread-safe; one
 * instance belongs to one export.
 *
 * Interpolation is Catmull-Rom cubic. At these ratios the read phase sweeps the
 * whole fractional range, so the interpolator's error is its full error rather
 * than a small-ratio special case -- cubic sits well below the noise floor for
 * anything under about 10 kHz and degrades gently above it. A windowed sinc
 * would be cleaner and drops in behind this same interface if the highs ever
 * prove to matter.
 */
class DriftResampler(
    private val channels: Int,
    outFramesPerInFrame: Double,
    private val targetFrames: Long,
) {
    /** Input frames advanced per output frame. */
    private val step = 1.0 / outFramesPerInFrame

    /**
     * Unconsumed input, always beginning with ONE frame of history so the cubic
     * has a sample behind the read position. [frac] is measured from frame 1.
     */
    private var carry = FloatArray(0)
    private var frac = 0.0
    private var started = false
    private var emitted = 0L

    /** Frames emitted so far; equals [targetFrames] once [finish] has run. */
    val framesEmitted: Long get() = emitted

    /**
     * Consumes [inFrames] frames of [input] and hands what it produces to [sink]
     * as (buffer, frames). The buffer is reused between calls, so a sink that
     * keeps it must copy.
     */
    fun process(input: FloatArray, inFrames: Int, sink: (FloatArray, Int) -> Unit) {
        if (inFrames <= 0 || emitted >= targetFrames) return

        val buf: FloatArray
        if (!started) {
            // Seed the history with a copy of frame 0, so output frame 0 reads
            // exactly input frame 0 instead of interpolating against silence --
            // that is what keeps a ratio of 1 a true passthrough.
            buf = FloatArray((inFrames + 1) * channels)
            System.arraycopy(input, 0, buf, 0, channels)
            System.arraycopy(input, 0, buf, channels, inFrames * channels)
            started = true
        } else {
            buf = FloatArray(carry.size + inFrames * channels)
            System.arraycopy(carry, 0, buf, 0, carry.size)
            System.arraycopy(input, 0, buf, carry.size, inFrames * channels)
        }
        emit(buf, sink)
    }

    /**
     * Pads with silence up to [targetFrames]. Audio that ended early -- a
     * disconnected mic, a killed writer -- still has to fill the picture's
     * duration, and silence is the honest filler.
     *
     * Before padding it drains what is left of the real input. The cubic needs
     * two frames of lookahead, so [process] always stops that far short of the
     * data it holds; without this the take's last two frames would be replaced
     * by silence instead of played, and on a length-matched export the join
     * would land exactly at the end of the clip.
     */
    fun finish(sink: (FloatArray, Int) -> Unit) {
        if (started && emitted < targetFrames && carry.size >= channels) {
            val carryFrames = carry.size / channels
            val buf = FloatArray((carryFrames + LOOKAHEAD) * channels)
            System.arraycopy(carry, 0, buf, 0, carry.size)
            // Edge-extend: repeat the final frame so the interpolator can reach
            // it. Holding the last value is the standard choice and, at two
            // frames, is 40 microseconds of the take.
            val last = (carryFrames - 1) * channels
            for (k in 0 until LOOKAHEAD) {
                System.arraycopy(carry, last, buf, carry.size + k * channels, channels)
            }
            emit(buf, sink)
        }
        var remaining = targetFrames - emitted
        if (remaining <= 0L) return
        val chunk = FloatArray(minOf(remaining, PAD_CHUNK_FRAMES).toInt() * channels)
        while (remaining > 0L) {
            val n = minOf(remaining, PAD_CHUNK_FRAMES).toInt()
            sink(chunk, n)
            remaining -= n
            emitted += n
        }
    }

    /** Emits everything [buf] can serve, then keeps the unconsumed remainder. */
    private fun emit(buf: FloatArray, sink: (FloatArray, Int) -> Unit) {
        val bufFrames = buf.size / channels
        var pos = 1.0 + frac
        val capacity = maxOf(((bufFrames - 2 - pos) / step).toInt() + 1, 0)
        val out = FloatArray(capacity * channels)
        var w = 0
        var produced = 0
        while (emitted < targetFrames) {
            val i = floor(pos).toInt()
            if (i + 2 > bufFrames - 1) break
            val t = (pos - i).toFloat()
            val base = (i - 1) * channels
            for (c in 0 until channels) {
                out[w++] = catmullRom(
                    buf[base + c],
                    buf[base + channels + c],
                    buf[base + 2 * channels + c],
                    buf[base + 3 * channels + c],
                    t,
                )
            }
            produced++
            emitted++
            pos += step
        }
        if (produced > 0) sink(out, produced)

        // Keep from one frame behind the read position, restoring the invariant.
        val keepFrom = floor(pos).toInt() - 1
        carry = buf.copyOfRange(keepFrom * channels, bufFrames * channels)
        frac = pos - floor(pos)
    }

    private companion object {
        /** Frames the cubic needs ahead of the read position. */
        const val LOOKAHEAD = 2
        const val PAD_CHUNK_FRAMES = 4096L
    }

    private fun catmullRom(y0: Float, y1: Float, y2: Float, y3: Float, t: Float): Float {
        val a = 2f * y1
        val b = y2 - y0
        val c = 2f * y0 - 5f * y1 + 4f * y2 - y3
        val d = -y0 + 3f * y1 - 3f * y2 + y3
        return 0.5f * (a + t * (b + t * (c + t * d)))
    }
}
