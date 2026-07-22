package com.shez.rawcam.camera

import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * The single most important test in this module. Spec A's floor is "never
 * crash"; this is what actually enforces it. Any input at all -- absurd values,
 * every field null, contradictory capabilities -- must yield Supported or
 * Unsupported, never an exception.
 */
class LensDiscoveryFuzzTest {

    private fun randomSnapshot(r: Random): CameraSnapshot {
        fun <T> maybe(v: T): T? = if (r.nextBoolean()) v else null
        val absurd = listOf(0, -1, Int.MAX_VALUE, Int.MIN_VALUE, 1023, 4095)
        return CameraSnapshot(
            cameraId = listOf("0", "2", "semt0", "", "999").random(r),
            facing = maybe(listOf(0, 1, 2, -7).random(r)),
            capabilities = List(r.nextInt(0, 5)) { r.nextInt(-2, 12) },
            physicalIds = List(r.nextInt(0, 3)) { r.nextInt(0, 40).toString() },
            rawSizes = List(r.nextInt(0, 4)) {
                SizeSpec(r.nextInt(-8, 9000), r.nextInt(-8, 9000), r.nextLong(-5, 1_000_000_000))
            },
            cfa = maybe(r.nextInt(-3, 9)),
            whiteLevel = maybe(absurd.random(r)),
            blackLevel = maybe(List(r.nextInt(0, 6)) { absurd.random(r) }),
            focalLengthsMm = maybe(List(r.nextInt(0, 3)) { r.nextFloat() * 200 - 100 }),
            physicalSizeMm = maybe(List(r.nextInt(0, 4)) { r.nextFloat() * 20 - 10 }),
            colorTransform1 = maybe(List(r.nextInt(0, 12)) { r.nextFloat() * 4 - 2 }),
            colorTransform2 = maybe(List(r.nextInt(0, 12)) { r.nextFloat() * 4 - 2 }),
            illuminant1 = maybe(r.nextInt(-5, 30)),
            illuminant2 = maybe(r.nextInt(-5, 30)),
            isoRange = maybe(List(r.nextInt(0, 4)) { absurd.random(r) }),
            exposureRangeNs = maybe(List(r.nextInt(0, 4)) { r.nextLong(-10, Long.MAX_VALUE / 2) }),
            activeArray = maybe(RectSpec(r.nextInt(-9, 9), r.nextInt(-9, 9), r.nextInt(-9, 9000), r.nextInt(-9, 9000))),
            minFocusDiopters = maybe(r.nextFloat() * 100 - 50),
            oisModes = maybe(List(r.nextInt(0, 4)) { r.nextInt(-2, 5) }),
            sensorOrientation = maybe(listOf(0, 90, 180, 270, 45, -90).random(r)),
            standalone = r.nextBoolean(),
        )
    }

    @Test
    fun `discover never throws for any input`() {
        val r = Random(20260722)
        repeat(5000) { iteration ->
            val cams = List(r.nextInt(0, 6)) { randomSnapshot(r) }
            val result = try {
                LensDiscovery.discover(cams)
            } catch (t: Throwable) {
                throw AssertionError("discover() threw on iteration $iteration: $cams", t)
            }
            assertTrue(result is DeviceProfile.Supported || result is DeviceProfile.Unsupported)
        }
    }

    @Test
    fun `a supported result always has a valid mainIndex`() {
        val r = Random(19700101)
        repeat(3000) {
            val result = LensDiscovery.discover(List(r.nextInt(0, 6)) { randomSnapshot(r) })
            if (result is DeviceProfile.Supported) {
                assertTrue(result.lenses.isNotEmpty())
                assertTrue(result.mainIndex in result.lenses.indices)
            }
        }
    }
}
