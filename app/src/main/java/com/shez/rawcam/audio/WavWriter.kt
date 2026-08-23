package com.shez.rawcam.audio

import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Contents of the BWF `bext` chunk. Written so a WAV separated from its `.rawv`
 * still carries its own sync provenance -- the sidecar design's one real
 * weakness. Strings are ASCII and are truncated (never overflowed) into their
 * fixed-width fields.
 */
data class BextInfo(
    val description: String,
    val originationDate: String,   // YYYY-MM-DD
    val originationTime: String,   // HH:MM:SS
    val timeReferenceSamples: Long,
)

/**
 * Streaming 24-bit PCM RIFF/WAVE writer.
 *
 * Every chunk sits at a fixed offset, so the two size fields and the whole
 * `bext` payload can be patched in place at close. That is what lets the caller
 * supply provenance only known once recording has ended, without buffering the
 * audio or rewriting the file.
 *
 * Head trimming is deliberately NOT this class's job: RIFF data begins at a
 * fixed offset, so removing leading samples afterwards would mean rewriting the
 * whole file. AudioRecorder applies the trim before the first [append] instead.
 *
 * Classic RIFF size fields are 32-bit, so total file size is capped just under
 * 4 GiB -- about 4.1 hours mono / 2 hours stereo at 48 kHz/24-bit. Beyond that
 * the size fields wrap; this is an inherent limitation of plain WAV (RF64
 * lifts it) and not something this writer works around.
 */
class WavWriter(
    private val file: File,
    private val sampleRate: Int,
    private val channels: Int,
) : Closeable {

    private val out = BufferedOutputStream(FileOutputStream(file), BUFFER_BYTES)
    private var dataBytes = 0L
    private var closed = false
    private val scratch = ByteArray(SCRATCH_SAMPLES * 3)
    private var appendsSinceFlush = 0

    init {
        out.write(buildHeader())
    }

    /**
     * Appends the first [count] entries of [samples] as interleaved 24-bit LE.
     * Input is nominal -1.0..+1.0 and is clamped, so post-gain overshoot
     * saturates the way an ADC would rather than wrapping to the opposite rail.
     */
    fun append(samples: FloatArray, count: Int) {
        var i = 0
        while (i < count) {
            val n = minOf(SCRATCH_SAMPLES, count - i)
            var b = 0
            for (k in 0 until n) {
                val v = samples[i + k]
                val clamped = if (v > 1.0f) 1.0f else if (v < -1.0f) -1.0f else v
                // Asymmetric full scale: +1.0 -> 0x7FFFFF, -1.0 -> -0x800000.
                // toInt() truncates toward zero rather than rounding to nearest;
                // deliberate -- inaudible at 24-bit and matches common practice.
                // Do not change to rounding, it would break the byte-exact tests.
                val s = if (clamped >= 0f) (clamped * 8_388_607.0f).toInt()
                        else (clamped * 8_388_608.0f).toInt()
                scratch[b++] = (s and 0xFF).toByte()
                scratch[b++] = ((s shr 8) and 0xFF).toByte()
                scratch[b++] = ((s shr 16) and 0xFF).toByte()
            }
            out.write(scratch, 0, b)
            dataBytes += b
            i += n
        }
        // Flushes every FLUSH_EVERY_N_APPENDS calls (see its own comment): a
        // process kill can otherwise lose up to the whole 64 KB OS-buffered
        // window (~220ms at this class's 288 KB/s stereo rate) -- and for a
        // clip too short to ever fill that buffer, even the header (written at
        // construction, sitting in the same buffer) would never reach disk,
        // leaving a 0-byte file that repairIfTruncated rejects and that blocks
        // deleting the whole exported pair. Cheap: flush() pushes bytes into
        // the OS page cache, not a physical-disk fsync.
        appendsSinceFlush++
        if (appendsSinceFlush >= FLUSH_EVERY_N_APPENDS) {
            out.flush()
            appendsSinceFlush = 0
        }
    }

    override fun close() = close(null)

    /** Flushes, then patches the RIFF/data sizes and, when [bext] is non-null,
     * the `bext` payload. Idempotent. */
    fun close(bext: BextInfo?) {
        if (closed) return
        closed = true
        try {
            out.flush()
        } finally {
            out.close()
        }
        RandomAccessFile(file, "rw").use { raf ->
            if (bext != null) {
                raf.seek(BEXT_PAYLOAD_OFFSET.toLong())
                raf.write(encodeBext(bext))
            }
            raf.seek(4)
            raf.write(le32((file.length() - 8).toInt()))
            raf.seek(DATA_SIZE_OFFSET.toLong())
            raf.write(le32(dataBytes.toInt()))
        }
    }

    private fun buildHeader(): ByteArray {
        val h = ByteArray(HEADER_BYTES)
        val bb = ByteBuffer.wrap(h).order(ByteOrder.LITTLE_ENDIAN)
        bb.put("RIFF".toByteArray(Charsets.US_ASCII))
        bb.putInt(0)                       // patched at close
        bb.put("WAVE".toByteArray(Charsets.US_ASCII))
        bb.put("fmt ".toByteArray(Charsets.US_ASCII))
        bb.putInt(16)
        bb.putShort(1)                     // PCM
        bb.putShort(channels.toShort())
        bb.putInt(sampleRate)
        bb.putInt(sampleRate * channels * 3)
        bb.putShort((channels * 3).toShort())
        bb.putShort(24)
        bb.put("bext".toByteArray(Charsets.US_ASCII))
        bb.putInt(BEXT_PAYLOAD_BYTES)
        bb.position(BEXT_PAYLOAD_OFFSET + BEXT_PAYLOAD_BYTES)  // payload stays zeroed
        bb.put("data".toByteArray(Charsets.US_ASCII))
        bb.putInt(0)                       // patched at close
        return h
    }

    private fun encodeBext(info: BextInfo): ByteArray {
        val p = ByteArray(BEXT_PAYLOAD_BYTES)
        putAscii(p, 0, 256, info.description)
        putAscii(p, 256, 32, "RawCam")
        putAscii(p, 320, 10, info.originationDate)
        putAscii(p, 330, 8, info.originationTime)
        ByteBuffer.wrap(p, 338, 8).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(info.timeReferenceSamples)
        ByteBuffer.wrap(p, 346, 2).order(ByteOrder.LITTLE_ENDIAN).putShort(0)  // BWF v0
        return p
    }

    /** Copies at most [len] ASCII bytes; the rest of the field stays NUL. */
    private fun putAscii(dst: ByteArray, at: Int, len: Int, s: String) {
        val src = s.toByteArray(Charsets.US_ASCII)
        System.arraycopy(src, 0, dst, at, minOf(len, src.size))
    }

    companion object {
        const val BEXT_PAYLOAD_BYTES = 602
        private const val BEXT_PAYLOAD_OFFSET = 44
        private const val DATA_SIZE_OFFSET = 650
        const val HEADER_BYTES = 654
        private const val BUFFER_BYTES = 64 * 1024
        private const val SCRATCH_SAMPLES = 4096

        // N=4: each append() call is roughly one audio chunk (~43-85ms of audio
        // at AudioRecorder's typical mono/stereo cadence -- see its READ_SAMPLES),
        // so this bounds a process kill's trailing loss to ~170-340ms, the same
        // order as the previous implicit ~220ms-to-fill-the-buffer bound, but now
        // GUARANTEED regardless of how full the OS buffer happens to be, rather
        // than only once 64 KB has actually accumulated.
        private const val FLUSH_EVERY_N_APPENDS = 4

        /** Encodes [v] as 4 little-endian bytes. Shared by [close] and
         * [repairIfTruncated] -- the one size-field encoding used at every
         * patch site. */
        private fun le32(v: Int): ByteArray =
            ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(v).array()

        /**
         * Recovers the size fields of a WAV left behind by a killed process,
         * inferring the data size from the file length. Mirrors the
         * recover-by-scan approach `.rawv` already uses for `frameCount`.
         *
         * Returns true when the file is one of ours and now has correct sizes
         * (including when it was already correct), false when it is too short or
         * is not one of ours.
         */
        fun repairIfTruncated(file: File): Boolean {
            if (file.length() < HEADER_BYTES) return false
            return try {
                RandomAccessFile(file, "rw").use { raf ->
                    val tag = ByteArray(4)
                    raf.seek(0); raf.readFully(tag)
                    if (String(tag, Charsets.US_ASCII) != "RIFF") return false
                    raf.seek(HEADER_BYTES - 8L); raf.readFully(tag)
                    if (String(tag, Charsets.US_ASCII) != "data") return false

                    val actualData = (file.length() - HEADER_BYTES).toInt()
                    val actualRiff = (file.length() - 8).toInt()
                    raf.seek(DATA_SIZE_OFFSET.toLong())
                    val storedData = Integer.reverseBytes(raf.readInt())
                    raf.seek(4)
                    val storedRiff = Integer.reverseBytes(raf.readInt())
                    if (storedData == actualData && storedRiff == actualRiff) return true

                    raf.seek(4)
                    raf.write(le32(actualRiff))
                    raf.seek(DATA_SIZE_OFFSET.toLong())
                    raf.write(le32(actualData))
                    true
                }
            } catch (e: IOException) {
                false
            }
        }
    }
}
