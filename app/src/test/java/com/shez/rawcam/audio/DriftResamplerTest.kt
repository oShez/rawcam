package com.shez.rawcam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.abs

class DriftResamplerTest {

    /** Runs [input] through in [chunk]-frame pieces and returns everything emitted. */
    private fun run(
        r: DriftResampler,
        input: FloatArray,
        channels: Int,
        chunk: Int = 97, // deliberately not a divisor of anything, to shake out edges
    ): FloatArray {
        val out = ArrayList<Float>()
        val sink = { b: FloatArray, n: Int ->
            for (i in 0 until n * channels) out.add(b[i])
        }
        var f = 0
        val total = input.size / channels
        while (f < total) {
            val n = minOf(chunk, total - f)
            r.process(input.copyOfRange(f * channels, (f + n) * channels), n, sink)
            f += n
        }
        r.finish(sink)
        return out.toFloatArray()
    }

    private fun ramp(frames: Int) = FloatArray(frames) { it * 0.0001f }

    @Test
    fun `output is exactly the requested length`() {
        // This is the "clip length == audio length" guarantee: the exported WAV
        // has to be the video's duration to the sample, whatever the input was.
        val r = DriftResampler(channels = 1, outFramesPerInFrame = 1.0001, targetFrames = 5_000L)
        assertEquals(5_000, run(r, ramp(4_000), 1).size)
    }

    @Test
    fun `a constant signal survives resampling unchanged`() {
        // Catches an interpolator whose taps do not sum to unity: DC in, DC out,
        // at every fractional phase the resampler steps through.
        val out = run(DriftResampler(1, 1.0001, 3_000L), FloatArray(4_000) { 0.25f }, 1)
        for (v in out) assertEquals(0.25f, v, 1e-6f)
    }

    @Test
    fun `an unstretched pass leaves the samples alone`() {
        // Ratio 1 with matching lengths must be a passthrough, not a smeared copy.
        val input = ramp(1_000)
        val out = run(DriftResampler(1, 1.0, 1_000L), input, 1)
        assertEquals(1_000, out.size)
        for (i in input.indices) assertEquals(input[i], out[i], 1e-6f)
    }

    @Test
    fun `input that runs out is padded with silence rather than truncated`() {
        // Audio that ended early still has to fill the video's duration, or the
        // clip and its sound are different lengths again.
        val out = run(DriftResampler(1, 1.0, 5_000L), FloatArray(1_000) { 0.5f }, 1)
        assertEquals(5_000, out.size)
        assertTrue("tail should be silence", out.takeLast(3_000).all { it == 0f })
    }

    @Test
    fun `stereo channels stay independent`() {
        // Interleaving is the classic place to get this wrong: left leaking into
        // right shows up here and nowhere else.
        val frames = 2_000
        val input = FloatArray(frames * 2)
        for (f in 0 until frames) {
            input[f * 2] = 0.5f // left
            input[f * 2 + 1] = -0.5f // right, opposite sign
        }
        val out = run(DriftResampler(2, 1.0001, 1_500L), input, 2)
        assertEquals(1_500 * 2, out.size)
        for (f in 0 until 1_500) {
            assertEquals(0.5f, out[f * 2], 1e-6f)
            assertEquals(-0.5f, out[f * 2 + 1], 1e-6f)
        }
    }

    @Test
    fun `a stretched pass maps the input's end to the output's end`() {
        // The whole point of the ratio: a take recorded 100 ppm short is played
        // back over the video's full duration, so the last input sample lands at
        // the last output sample rather than short of it.
        val frames = 100_000
        val input = ramp(frames)
        val target = 100_010L // frames * 1.0001
        val out = run(DriftResampler(1, 1.0001, target), input, 1)
        assertEquals(target.toInt(), out.size)
        assertTrue(
            "expected the input's tail at the output's tail, got ${out.last()}",
            abs(out.last() - input.last()) < 0.0002f,
        )
    }

    @Test
    fun `a tone keeps its amplitude`() {
        // A cheap stand-in for "does this interpolator wreck the audio". 1 kHz at
        // 48 kHz is far enough below Nyquist that cubic interpolation should hold
        // its peak to well under a tenth of a dB.
        val n = 48_000
        val input = FloatArray(n) {
            kotlin.math.sin(2.0 * Math.PI * 1000.0 * it / 48_000.0).toFloat()
        }
        val out = run(DriftResampler(1, 1.0001, 48_000L), input, 1)
        val peak = out.drop(100).dropLast(100).maxOf { abs(it) }
        assertTrue("peak fell to $peak", peak > 0.999f && peak <= 1.0001f)
    }
}
