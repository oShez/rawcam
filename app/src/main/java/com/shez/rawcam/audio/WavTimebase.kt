package com.shez.rawcam.audio

import android.util.Log
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * Rewrites a recorded sidecar WAV onto its clip's timebase: drift-corrected, and
 * exactly the picture's duration.
 *
 * Runs at EXPORT rather than during capture, deliberately. The drift figure is
 * only trustworthy once the take is over (see [AvSync.driftPpm], which refuses
 * to answer for spans too short to out-measure their own noise), the frame count
 * is not final until the clip is closed, and the capture path has no spare
 * headroom for a resample. Export already copies this file, so the work sits
 * outside the realtime budget and the original in `clips/` is never touched.
 */
object WavTimebase {

    private const val TAG = "WavTimebase"
    private const val BYTES_PER_SAMPLE = 3
    private const val CHUNK_FRAMES = 8192
    private const val BEXT_PAYLOAD_OFFSET = 44

    /**
     * Reads [src] and writes [dst] with exactly [targetFrames] sample frames,
     * resampling by the measured [driftPpm] on the way.
     *
     * [driftPpm] is the header's `audioDriftPpm`. Positive means the mic clock
     * ran slow, so the audio is short and leads the picture; the output is
     * stretched by `1 + ppm/1e6` to put it back. Zero -- which is also what
     * [AvSync.driftPpm] reports when it could not measure honestly -- leaves the
     * rate alone and conforms only the length.
     *
     * Returns false without leaving a usable [dst] if [src] is not one of ours.
     * Callers must treat that as a failed copy, not as "no audio".
     */
    fun conform(src: File, dst: File, driftPpm: Int, targetFrames: Long): Boolean {
        if (targetFrames <= 0L) return false
        if (src.length() < WavWriter.HEADER_BYTES) return false
        val header = ByteArray(WavWriter.HEADER_BYTES)
        try {
            RandomAccessFile(src, "r").use { it.readFully(header) }
        } catch (e: Exception) {
            Log.e(TAG, "could not read WAV header from $src", e)
            return false
        }
        if (String(header, 0, 4, Charsets.US_ASCII) != "RIFF" ||
            String(header, 8, 4, Charsets.US_ASCII) != "WAVE" ||
            String(header, WavWriter.HEADER_BYTES - 8, 4, Charsets.US_ASCII) != "data"
        ) {
            Log.e(TAG, "not a RawCam WAV: $src")
            return false
        }
        val bb = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
        val channels = bb.getShort(22).toInt()
        val sampleRate = bb.getInt(24)
        val bits = bb.getShort(34).toInt()
        if (channels !in 1..2 || sampleRate <= 0 || bits != 24) {
            Log.e(TAG, "unsupported WAV format: ch=$channels rate=$sampleRate bits=$bits")
            return false
        }

        val frameBytes = channels * BYTES_PER_SAMPLE
        // Derived from the file's real size rather than the stored data size, so a
        // clip recovered from a killed process is conformed on what it actually
        // holds. repairIfTruncated has already agreed the two match by this point.
        val available = (src.length() - WavWriter.HEADER_BYTES) / frameBytes

        // Stretch by the MEASURED error. See DriftResampler for why the sign runs
        // this way, and why the ratio must not be derived from the two lengths.
        val ratio = 1.0 + driftPpm / 1_000_000.0
        val resampler = DriftResampler(channels, ratio, targetFrames)

        return try {
            WavWriter(dst, sampleRate, channels).use { out ->
                val sink = { buf: FloatArray, frames: Int -> out.append(buf, frames * channels) }
                BufferedInputStream(FileInputStream(src), 1 shl 16).use { input ->
                    var skipped = 0L
                    while (skipped < WavWriter.HEADER_BYTES) {
                        val s = input.skip(WavWriter.HEADER_BYTES - skipped)
                        if (s <= 0L) break
                        skipped += s
                    }
                    val raw = ByteArray(CHUNK_FRAMES * frameBytes)
                    val floats = FloatArray(CHUNK_FRAMES * channels)
                    var framesRead = 0L
                    while (framesRead < available) {
                        val want = minOf(CHUNK_FRAMES.toLong(), available - framesRead).toInt()
                        val n = fill(input, raw, want * frameBytes)
                        val got = n / frameBytes
                        if (got <= 0) break
                        decode(raw, floats, got * channels)
                        resampler.process(floats, got, sink)
                        framesRead += got
                    }
                }
                // Pads to length when the audio ran short, which is a real case: a
                // mic that disconnected mid-take still has to fill the clip.
                resampler.finish(sink)
            }
            // The bext payload describes when the take STARTED, which conforming
            // the length and rate does not change -- so it crosses byte-for-byte
            // rather than being rebuilt, preserving the timecode and any
            // provisional marker exactly as recorded.
            copyBext(header, dst)
            true
        } catch (e: Exception) {
            Log.e(TAG, "failed to conform $src", e)
            dst.delete()
            false
        }
    }

    /** Reads until [len] bytes are in [buf] or the stream ends. */
    private fun fill(input: BufferedInputStream, buf: ByteArray, len: Int): Int {
        var off = 0
        while (off < len) {
            val n = input.read(buf, off, len - off)
            if (n < 0) break
            off += n
        }
        return off
    }

    /** 24-bit LE to float, the inverse of WavWriter's asymmetric full scale. */
    private fun decode(raw: ByteArray, out: FloatArray, samples: Int) {
        var o = 0
        for (i in 0 until samples) {
            val v = (raw[o + 2].toInt() shl 16) or
                ((raw[o + 1].toInt() and 0xFF) shl 8) or
                (raw[o].toInt() and 0xFF)
            out[i] = if (v >= 0) v / 8_388_607.0f else v / 8_388_608.0f
            o += BYTES_PER_SAMPLE
        }
    }

    private fun copyBext(srcHeader: ByteArray, dst: File) {
        RandomAccessFile(dst, "rw").use { raf ->
            raf.seek(BEXT_PAYLOAD_OFFSET.toLong())
            raf.write(srcHeader, BEXT_PAYLOAD_OFFSET, WavWriter.BEXT_PAYLOAD_BYTES)
        }
    }
}
