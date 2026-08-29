package com.shez.rawcam.ui

import kotlin.math.roundToInt
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rails, action buttons and fps toggle live in the side gutters a 4:3
 * preview leaves. If a wider preview is allowed to claim that width, it slides
 * underneath them -- which is the bug this pins.
 *
 * These are deliberately written against the CONSEQUENCE (picture width) rather
 * than against the fraction itself, because the fraction is an implementation
 * detail and the width is what the layout actually depends on.
 */
class ViewfinderLayoutTest {

    // The device this was diagnosed on, in its locked landscape orientation.
    private val screenW = 2400
    private val screenH = 1080

    /** Picture size the composable ends up with: height capped by the fraction,
     *  width derived from it by aspectRatio(). Mirrors the modifier chain. */
    private fun pictureSize(previewAspect: Float): Pair<Int, Int> {
        val h = screenH * previewHeightFraction(previewAspect)
        return (h * previewAspect).roundToInt() to h.roundToInt()
    }

    @Test fun fourThreeIsCompletelyUnchanged() {
        // The fraction must be exactly 1.0 here: 4:3 is the reference layout and
        // any shrinkage would be a regression in the common case.
        assertEquals(1.0f, previewHeightFraction(4f / 3f), 1e-6f)
        val (w, h) = pictureSize(4f / 3f)
        assertEquals(1440, w)
        assertEquals(1080, h)
    }

    @Test fun sixteenNineKeepsTheFourThreeWidth() {
        val (w, h) = pictureSize(16f / 9f)
        // THE invariant: same width as 4:3, so the rails do not get covered.
        assertEquals(1440, w)
        // Height shrinks instead -- black bands above and below, by design.
        assertEquals(810, h)
    }

    @Test fun everyAspectAtLeastFourThreeYieldsTheSameWidth() {
        val (referenceW, _) = pictureSize(4f / 3f)
        // Sweep well past 16:9 -- anamorphic-ish and absurd aspects included, so
        // the guarantee is not accidentally specific to the two options the RES
        // picker happens to offer today.
        var a = 4f / 3f
        while (a <= 4f) {
            val (w, h) = pictureSize(a)
            assertEquals("width changed at aspect $a", referenceW, w)
            assertTrue("picture $w x $h overflows the screen at aspect $a",
                w <= screenW && h <= screenH)
            a += 0.01f
        }
    }

    @Test fun narrowerThanFourThreeStaysHeightBound() {
        // A 1:1 or portrait-ish sensor size already fits between the rails, so it
        // must NOT be shrunk -- coerceAtMost keeps it on the old path.
        for (aspect in listOf(1f, 5f / 4f, 4f / 3f)) {
            assertEquals("aspect $aspect should be height-bound",
                1.0f, previewHeightFraction(aspect), 1e-6f)
        }
        val (w, h) = pictureSize(1f)
        assertEquals(1080, w)
        assertEquals(1080, h)
    }

    /**
     * The bug the user actually saw. The side bar's width is derived from the
     * picture width, and below 104dp the rail gives up and falls back to a
     * horizontally scrolling chip strip laid over the frame. Capping the picture
     * is not enough on its own -- the bar arithmetic has to use the same factor,
     * or 16:9 measures a bar half its real width and degrades anyway.
     */
    @Test fun theRailStillFitsAtSixteenNine() {
        // Mirrors the composable: bar = (screen - picture) / 2, in px here.
        fun barWidth(previewAspect: Float): Float =
            (screenW - screenH * previewWidthFactor(previewAspect)) / 2f

        val fourThreeBar = barWidth(4f / 3f)
        val sixteenNineBar = barWidth(16f / 9f)
        assertEquals("16:9 must leave the same bar as 4:3", fourThreeBar, sixteenNineBar, 0.5f)
        // 480px on this device. The old formula gave 240 here, which is what
        // pushed railWidth under the 104dp threshold and triggered the fallback.
        assertEquals(480f, sixteenNineBar, 0.5f)
    }

    /** The two sites must not drift apart again: the factor the bar measures with
     *  has to be the width the viewfinder actually takes. */
    @Test fun widthFactorAgreesWithTheViewfinderSizing() {
        var a = 1f
        while (a <= 4f) {
            val fromSizing = previewHeightFraction(a) * a
            assertEquals("factor disagrees at aspect $a", fromSizing, previewWidthFactor(a), 1e-6f)
            a += 0.01f
        }
    }

    @Test fun theRailsStayClearOfThePicture() {
        // Measured bounds from uiautomator on the device: left rail x163..459,
        // right action buttons x2089..2341. The picture is centred, so it must
        // not reach into either.
        val leftRailRight = 459
        val rightRailLeft = 2089
        for (aspect in listOf(4f / 3f, 16f / 9f)) {
            val (w, _) = pictureSize(aspect)
            val left = (screenW - w) / 2
            val right = left + w
            assertTrue("picture starts at $left, over the left rail, at aspect $aspect",
                left >= leftRailRight)
            assertTrue("picture ends at $right, over the right rail, at aspect $aspect",
                right <= rightRailLeft)
        }
    }
}
