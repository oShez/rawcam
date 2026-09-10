package com.shez.rawcam.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class WavTimebaseTest {

    @get:Rule val tmp = TemporaryFolder()

    private val bext = BextInfo(
        description = "RawCam test",
        originationDate = "2026-08-17",
        originationTime = "12:00:00",
        timeReferenceSamples = 48_000L,
    )

    /** Writes a source WAV of [frames] frames carrying a 1 kHz tone. */
    private fun source(name: String, frames: Int, channels: Int = 1): File {
        val f = tmp.newFile(name)
        WavWriter(f, 48_000, channels, bext).use { w ->
            val chunk = FloatArray(1024 * channels)
            var done = 0
            while (done < frames) {
                val n = minOf(1024, frames - done)
                for (i in 0 until n) {
                    val v = kotlin.math.sin(2.0 * Math.PI * 1000.0 * (done + i) / 48_000.0)
                    for (c in 0 until channels) chunk[i * channels + c] = (v * 0.5).toFloat()
                }
                w.append(chunk, n * channels)
                done += n
            }
        }
        return f
    }

    private fun dataFrames(f: File, channels: Int = 1): Long =
        (f.length() - WavWriter.HEADER_BYTES) / (channels * 3L)

    /** Decodes the 24-bit LE samples back to signed ints. */
    private fun samples(f: File): IntArray {
        val b = f.readBytes()
        val n = (b.size - WavWriter.HEADER_BYTES) / 3
        return IntArray(n) { i ->
            val o = WavWriter.HEADER_BYTES + i * 3
            (b[o + 2].toInt() shl 16) or
                ((b[o + 1].toInt() and 0xFF) shl 8) or (b[o].toInt() and 0xFF)
        }
    }

    @Test
    fun `the output is exactly the length the video asks for`() {
        // The headline requirement: sound and picture are the same duration, so
        // the pair drops into a timeline as a matched clip.
        val src = source("a.wav", 40_000)
        val dst = File(tmp.root, "a-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = 0, targetFrames = 50_000L))
        assertEquals(50_000L, dataFrames(dst))
    }

    @Test
    fun `a source longer than the video is cut to length`() {
        val src = source("b.wav", 60_000)
        val dst = File(tmp.root, "b-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = 0, targetFrames = 25_000L))
        assertEquals(25_000L, dataFrames(dst))
    }

    @Test
    fun `the bext chunk crosses over untouched`() {
        // The timecode lives in here. Losing it during the copy would undo the
        // whole auto-sync feature at the last step.
        val src = source("c.wav", 10_000)
        val dst = File(tmp.root, "c-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = 0, targetFrames = 10_000L))
        val a = src.readBytes()
        val b = dst.readBytes()
        for (i in 44 until 44 + WavWriter.BEXT_PAYLOAD_BYTES) {
            assertEquals("bext byte $i", a[i], b[i])
        }
    }

    @Test
    fun `no drift and a matching length is a faithful copy`() {
        val src = source("d.wav", 20_000)
        val dst = File(tmp.root, "d-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = 0, targetFrames = 20_000L))
        val a = samples(src)
        val b = samples(dst)
        assertEquals(a.size, b.size)
        // A round trip through float and back is allowed a LSB or two; anything
        // more means the passthrough is smearing samples.
        for (i in a.indices) {
            assertTrue("sample $i: ${a[i]} vs ${b[i]}", abs(a[i] - b[i]) <= 2)
        }
    }

    /** A ramp source: output frame N should carry input frame N/ratio, legibly. */
    private fun rampSource(name: String, frames: Int): File {
        val f = tmp.newFile(name)
        WavWriter(f, 48_000, 1, bext).use { w ->
            val chunk = FloatArray(1024)
            var done = 0
            while (done < frames) {
                val n = minOf(1024, frames - done)
                for (i in 0 until n) chunk[i] = rampValue(done + i, frames)
                w.append(chunk, n)
                done += n
            }
        }
        return f
    }

    private fun rampValue(i: Int, frames: Int) = (i.toFloat() / frames) * 0.9f

    private fun rampCode(i: Int, frames: Int) = (rampValue(i, frames) * 8_388_607.0f).toInt()

    /** Catmull-Rom reproduces a straight line exactly, so a ramp should land
     *  within a LSB or two; the tolerance is far tighter than the 3775-code
     *  gap an uncorrected or inverted ratio would produce. */
    private fun assertNear(expected: Int, actual: Int, what: String) {
        assertTrue("$what: expected ~$expected, got $actual", abs(expected - actual) <= 200)
    }

    @Test
    fun `positive drift stretches the source over the output`() {
        // 1000 ppm -- far past anything real, so the effect lands in whole frames:
        // 20000 input frames must cover exactly 20020 output frames.
        //
        // Asserting only that the tail "is audio" was worthless: mutation testing
        // showed both ignoring the drift entirely and inverting its sign survived
        // it, because the padding those leave behind is shorter than the window
        // being searched. A ramp pins the actual mapping instead.
        val frames = 20_000
        val src = rampSource("e.wav", frames)
        val dst = File(tmp.root, "e-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = 1000, targetFrames = 20_020L))
        val s = samples(dst)
        assertEquals(20_020, s.size)
        // The take's end lands on the output's end, not 20 frames short of it.
        assertNear(rampCode(frames - 1, frames), s.last(), "output tail")
        // And the middle is mapped too, not just the ends: output 10010 is input
        // 10000. Uncorrected it would be input 10010, a code 3775 higher.
        assertNear(rampCode(10_000, frames), s[10_010], "output midpoint")
    }

    @Test
    fun `negative drift compresses the source over the output`() {
        // The mirror case, so an inverted sign cannot hide in the one direction.
        // At -1000 ppm 20000 input frames cover 19980 output frames.
        val frames = 20_000
        val src = rampSource("h.wav", frames)
        val dst = File(tmp.root, "h-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = -1000, targetFrames = 19_980L))
        val s = samples(dst)
        assertEquals(19_980, s.size)
        assertNear(rampCode(frames - 1, frames), s.last(), "output tail")
        assertNear(rampCode(10_000, frames), s[9_990], "output midpoint")
    }

    @Test
    fun `a file that is not one of ours is refused rather than mangled`() {
        val junk = tmp.newFile("f.wav")
        junk.writeBytes(ByteArray(2000) { 0x7F })
        val dst = File(tmp.root, "f-out.wav")
        assertFalse(WavTimebase.conform(junk, dst, driftPpm = 0, targetFrames = 1_000L))
    }

    @Test
    fun `the destination header describes the audio actually written`() {
        val src = source("g.wav", 10_000, channels = 2)
        val dst = File(tmp.root, "g-out.wav")
        assertTrue(WavTimebase.conform(src, dst, driftPpm = 0, targetFrames = 12_000L))
        val b = dst.readBytes()
        fun u16(at: Int) = ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt()
        fun u32(at: Int) = ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong()
        assertEquals(2, u16(22)) // channels preserved
        assertEquals(48_000L, u32(24)) // rate preserved
        assertEquals(12_000L * 2 * 3, u32(650)) // data size matches the target
        assertEquals(dst.length() - 8, u32(4)) // RIFF size agrees with the file
    }
}
