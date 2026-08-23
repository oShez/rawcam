package com.shez.rawcam.audio

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

class WavWriterTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun bytes(f: File) = f.readBytes()

    private fun u32(b: ByteArray, at: Int): Long =
        ByteBuffer.wrap(b, at, 4).order(ByteOrder.LITTLE_ENDIAN).int.toLong() and 0xFFFFFFFFL

    private fun u16(b: ByteArray, at: Int): Int =
        ByteBuffer.wrap(b, at, 2).order(ByteOrder.LITTLE_ENDIAN).short.toInt() and 0xFFFF

    private fun ascii(b: ByteArray, at: Int, len: Int) = String(b, at, len, Charsets.US_ASCII)

    private val bext = BextInfo(
        description = "RawCam test",
        originationDate = "2026-08-17",
        originationTime = "12:00:00",
        timeReferenceSamples = 48_000L,
    )

    @Test
    fun `header fields land at the documented offsets`() {
        val f = tmp.newFile("a.wav")
        WavWriter(f, 48_000, 2).use { it.append(FloatArray(4), 4) }
        val b = bytes(f)
        assertEquals("RIFF", ascii(b, 0, 4))
        assertEquals("WAVE", ascii(b, 8, 4))
        assertEquals("fmt ", ascii(b, 12, 4))
        assertEquals(16L, u32(b, 16))
        assertEquals(1, u16(b, 20))
        assertEquals(2, u16(b, 22))
        assertEquals(48_000L, u32(b, 24))
        assertEquals(48_000L * 2 * 3, u32(b, 28))
        assertEquals(6, u16(b, 32))
        assertEquals(24, u16(b, 34))
        assertEquals("bext", ascii(b, 36, 4))
        assertEquals(602L, u32(b, 40))
        assertEquals("data", ascii(b, 646, 4))
        assertEquals(654, WavWriter.HEADER_BYTES)
    }

    @Test
    fun `sizes are patched on close`() {
        val f = tmp.newFile("b.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(6), 6) }
        val b = bytes(f)
        assertEquals(18L, u32(b, 650))
        assertEquals((b.size - 8).toLong(), u32(b, 4))
        assertEquals(WavWriter.HEADER_BYTES + 18, b.size)
    }

    @Test
    fun `full scale positive and negative encode to 24-bit LE`() {
        val f = tmp.newFile("c.wav")
        WavWriter(f, 48_000, 1).use { it.append(floatArrayOf(1.0f, -1.0f, 0.0f), 3) }
        val pcm = bytes(f).copyOfRange(WavWriter.HEADER_BYTES, WavWriter.HEADER_BYTES + 9)
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0x7F,   // +8388607
                0x00, 0x00, 0x80.toByte(),            // -8388608
                0x00, 0x00, 0x00,
            ),
            pcm,
        )
    }

    @Test
    fun `out of range input is clamped not wrapped`() {
        val f = tmp.newFile("d.wav")
        WavWriter(f, 48_000, 1).use { it.append(floatArrayOf(4.0f, -4.0f), 2) }
        val pcm = bytes(f).copyOfRange(WavWriter.HEADER_BYTES, WavWriter.HEADER_BYTES + 6)
        assertArrayEquals(
            byteArrayOf(0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x80.toByte()),
            pcm,
        )
    }

    @Test
    fun `stereo samples stay interleaved in submission order`() {
        val f = tmp.newFile("e.wav")
        WavWriter(f, 48_000, 2).use { it.append(floatArrayOf(1.0f, 0.0f, 0.0f, 1.0f), 4) }
        val pcm = bytes(f).copyOfRange(WavWriter.HEADER_BYTES, WavWriter.HEADER_BYTES + 12)
        assertArrayEquals(
            byteArrayOf(
                0xFF.toByte(), 0xFF.toByte(), 0x7F, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0xFF.toByte(), 0xFF.toByte(), 0x7F,
            ),
            pcm,
        )
    }

    @Test
    fun `count shorter than the array writes only count samples`() {
        val f = tmp.newFile("f.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(100), 5) }
        assertEquals(15L, u32(bytes(f), 650))
    }

    @Test
    fun `bext payload is written at the documented offsets`() {
        val f = tmp.newFile("g.wav")
        WavWriter(f, 48_000, 1).use {
            it.append(FloatArray(2), 2)
            it.close(bext)
        }
        val b = bytes(f)
        assertEquals("RawCam test", ascii(b, 44, 11))
        assertEquals("2026-08-17", ascii(b, 44 + 320, 10))
        assertEquals("12:00:00", ascii(b, 44 + 330, 8))
        assertEquals(
            48_000L,
            ByteBuffer.wrap(b, 44 + 338, 8).order(ByteOrder.LITTLE_ENDIAN).long,
        )
        assertEquals(0, u16(b, 44 + 346))
        assertEquals(602, WavWriter.BEXT_PAYLOAD_BYTES)
    }

    @Test
    fun `an oversized description is truncated rather than overflowing`() {
        val f = tmp.newFile("h.wav")
        WavWriter(f, 48_000, 1).use {
            it.append(FloatArray(2), 2)
            it.close(bext.copy(description = "x".repeat(400)))
        }
        val b = bytes(f)
        // Description is 256 bytes; byte 256 starts Originator and must be untouched by it.
        assertEquals("x".repeat(256), ascii(b, 44, 256))
        assertEquals("RawCam", ascii(b, 44 + 256, 6))
    }

    @Test
    fun `repair recovers a truncated file's sizes`() {
        val f = tmp.newFile("i.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(10), 10) }
        // Simulate a process kill: both size fields left zero, as a never-closed file has.
        RandomAccessFile(f, "rw").use { raf ->
            raf.seek(4); raf.write(ByteArray(4))
            raf.seek(650); raf.write(ByteArray(4))
        }
        assertTrue(WavWriter.repairIfTruncated(f))
        val b = bytes(f)
        assertEquals(30L, u32(b, 650))
        assertEquals((b.size - 8).toLong(), u32(b, 4))
    }

    @Test
    fun `repair leaves an already valid file alone`() {
        val f = tmp.newFile("j.wav")
        WavWriter(f, 48_000, 1).use { it.append(FloatArray(10), 10) }
        val before = bytes(f)
        assertTrue(WavWriter.repairIfTruncated(f))
        assertArrayEquals(before, bytes(f))
    }

    @Test
    fun `repair rejects a file that is not one of ours`() {
        val f = tmp.newFile("k.wav")
        f.writeBytes(ByteArray(32))
        assertFalse(WavWriter.repairIfTruncated(f))
    }

    @Test
    fun `data reaches disk periodically without an explicit close`() {
        val f = tmp.newFile("l.wav")
        val w = WavWriter(f, 48_000, 1)
        // Four single-sample append() calls -- one per call is the flush unit
        // (see WavWriter's FLUSH_EVERY_N_APPENDS=4) -- so by the fourth call
        // everything written so far, including the header, must be visible on
        // disk even though close() was never called.
        repeat(4) { w.append(floatArrayOf(1.0f), 1) }
        assertEquals((WavWriter.HEADER_BYTES + 4 * 3).toLong(), f.length())
        w.close(null)
    }
}
