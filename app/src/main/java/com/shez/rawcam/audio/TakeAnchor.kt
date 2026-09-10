package com.shez.rawcam.audio

import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The take-start anchor: one wall-clock instant, read once when recording
 * starts, that both halves of a take are stamped from -- SMPTE timecode in the
 * exported DNG sequence (DNG tag 51043) and TimeReference/OriginationDate/Time
 * in the sidecar WAV's BWF `bext` chunk. Sharing one number is what lets an NLE
 * line the two up on import instead of the operator dragging audio into place.
 *
 * That is a NOMINAL alignment. It does not correct the known sub-frame A/V
 * residual and must not be described as doing so.
 *
 * Pure and free of Android dependencies, like [AvSync], so it is testable on
 * the JVM. The day reduction here mirrors `nsSinceLocalMidnight()` in
 * `core/include/rawcam/timecode.h`; the two must agree, and both are tested
 * against the same cases so they cannot drift apart unnoticed.
 */
object TakeAnchor {

    private const val DAY_NS = 86_400_000_000_000L
    private val DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
    private val TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss")

    /**
     * Sample frames from local midnight to the take's start -- BWF's
     * TimeReference field.
     */
    fun timeReferenceSamples(startEpochNs: Long, tzOffsetSec: Int, sampleRate: Int): Long {
        val ns = nsSinceLocalMidnight(startEpochNs, tzOffsetSec)
        // Whole seconds and the sub-second remainder are converted separately,
        // the same shape packTimecode() uses on the DNG side. A take almost
        // never begins exactly on a second, and folding the remainder away
        // would throw the audio a third of a second out at worst.
        return ns / 1_000_000_000L * sampleRate +
            (ns % 1_000_000_000L) * sampleRate / 1_000_000_000L
    }

    /** The take's local calendar day, as BWF OriginationDate wants it. */
    fun originationDate(startEpochNs: Long, tzOffsetSec: Int): String =
        localDateTime(startEpochNs, tzOffsetSec).format(DATE)

    /** The take's local time of day, as BWF OriginationTime wants it. */
    fun originationTime(startEpochNs: Long, tzOffsetSec: Int): String =
        localDateTime(startEpochNs, tzOffsetSec).format(TIME)

    // The offset is applied explicitly rather than through the device's default
    // zone: the anchor records the offset that was in force when the take
    // started, so a clip exported after a DST change or a flight still reads
    // back the wall clock the operator actually shot against.
    private fun localDateTime(startEpochNs: Long, tzOffsetSec: Int): LocalDateTime =
        LocalDateTime.ofEpochSecond(
            Math.floorDiv(startEpochNs, 1_000_000_000L), 0, ZoneOffset.ofTotalSeconds(tzOffsetSec))

    /**
     * Nanoseconds from local midnight to [startEpochNs]. Floor-mod, not `%`: a
     * west-of-UTC offset pushes an early-morning UTC instant into the previous
     * local day, where a truncated remainder would be negative.
     */
    private fun nsSinceLocalMidnight(startEpochNs: Long, tzOffsetSec: Int): Long =
        Math.floorMod(startEpochNs + tzOffsetSec * 1_000_000_000L, DAY_NS)
}
