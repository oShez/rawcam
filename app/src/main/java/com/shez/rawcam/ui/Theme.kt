package com.shez.rawcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.shez.rawcam.R

/**
 * RawCam palette. Single dark theme; the app is a camera and commits to it.
 *
 * TWO accents, deliberately, because one was carrying two unrelated meanings. Red used
 * to mean both "this control is open" and "something is wrong", which is fine until
 * they appear together -- and on the capture screen they always do.
 *
 * - [Interactive] (green) is the user's own doing: the open parameter row, a selected
 *   pill, a locked control, a slider you are dragging. It never appears on its own.
 * - [Accent] (red) is the camera's doing: rolling, or wrong. A recording indicator is
 *   red on every camera ever built, and a fault has to be able to shout over a green
 *   interface without competing with it.
 * - [Success] is the same green family, deepened, for status readouts in lists. It is
 *   never used on anything tappable.
 *
 * No green ever touches the picture. A colour-grading suite is painted neutral grey
 * because chromatic adaptation drags your white balance toward whatever fills your
 * peripheral vision, and this app hands the user a manual white-balance control they
 * set by eye against the live preview. Saturated chrome beside that image would bias
 * the very judgement the app exists to support, so the greens stay in the letterbox
 * bars and the frame carries nothing but its corner ticks.
 *
 * The greens are a three-step ramp by volume, not three different meanings:
 *
 * - jade [InteractiveSurface] -- the bed under a row you have opened. Quietest.
 * - forest [InteractiveMid] -- a standing selection you are not touching right now.
 * - lifted forest [Interactive] -- the live value and edge you are reading, and the
 *   only one ever set as text.
 *
 * The whole ramp is one hue family. An earlier pass used parrot #32CD32 at the top; it
 * cleared contrast easily but glared on a dark instrument, so the top step came down to
 * the least-bright forest that still clears 4.5:1 as type. Brightness here is bounded
 * from below by legibility and from above by taste, and 5.18:1 is where both hold.
 */
object RawCamColors {
    val Background = Color(0xFF0A0B0D)
    val Surface = Color(0xFF17191D)
    val SurfaceVariant = Color(0xFF24272C)
    val OnSurface = Color(0xFFE9EAEC)
    val Muted = Color(0xFF8F959D)
    val Outline = Color(0xFF3A3E45)
    val Accent = Color(0xFFE5484D)

    /**
     * Jade. The only green in the app that is ever set as TEXT, and it is blue-shifted
     * rather than a pure green for two measured reasons.
     *
     * Legibility: WCAG 2 ratios are unreliable on dark grounds -- they are symmetric,
     * ignore stroke width, and a "passing" 4.5:1 can still be unreadable near black. On
     * APCA, which is built for this case, forest #2C9E2C scores Lc 38.8 on the rail
     * surface. APCA wants 60 for body text and 45 even for large or bold. The forest
     * that felt right was failing by a wide margin; the WCAG 5.18:1 that said otherwise
     * was measuring the wrong thing. Jade #35C99B scores Lc 60.5.
     *
     * Colour vision: red-green is the single worst pair to carry meaning, affecting
     * ~8% of men. Simulated, #E5484D lands on #8F8F44 for a deuteranope -- and forest
     * green lands almost exactly there too, separation 0.043 in linear light. Jade
     * keeps blue in it, so it survives the collapse: separation 0.353, eight times
     * further apart, because deuteranopia leaves the blue-yellow axis intact.
     *
     * Glare: this sits at essentially the same APCA contrast as the parrot it replaces
     * (60.5 vs 60.4), yet reads calmer, because pure green sits at the eye's peak
     * luminous sensitivity near 555nm. Shifting hue toward blue buys comfort without
     * paying any legibility for it -- which is why the answer to "too bright" was to
     * move the hue, not to darken it. Darkening was what broke legibility in the first
     * place.
     */
    val Interactive = Color(0xFF35C99B)

    /** True forest. The middle of the ramp: a standing selection -- the chosen pill,
     *  the current frame rate -- where jade is louder than a not-currently-touched
     *  control deserves. Fills are not text: APCA asks Lc 15 of them, not 60, so the
     *  calm dark green that could never carry a value is exactly right here. */
    val InteractiveMid = Color(0xFF228B22)

    /** Jade. Far too dark to set type on a near-black ground, which is exactly what
     *  makes it the right bed: a deep green fill under a parrot border, so an open row
     *  reads as lit rather than merely outlined. */
    val InteractiveSurface = Color(0xFF06402B)

    /** Status readouts in lists. A deeper, calmer parrot rather than the unrelated
     *  sage it used to be -- one green family across the app, not two. */
    val Success = Color(0xFF2AA82A)
}

/**
 * The app's one typeface: Azeret Mono (SIL OFL 1.1, license in `licenses/`), shipped as
 * three static instances cut from the upstream variable font at 400, 500 and 700.
 *
 * This replaces `FontFamily.Monospace`, which resolved to whatever mono face the device
 * happened to ship. That was not a neutral default, it was an absent decision, and it is
 * the main reason the UI read as a prototype rather than a product.
 *
 * Static instances rather than the variable file, for a specific reason. The upstream
 * `AzeretMono[wght].ttf` declares `wght` with min 100, max 900 and **default 100**. When
 * Compose's `FontVariation` path silently fails to apply, every weight collapses to that
 * default, so the entire UI renders as Thin hairlines and requesting Bold changes
 * nothing -- which is exactly what happened on device, and is invisible in a build log.
 * Three separate resources cannot fail that way: each weight is its own file, chosen by
 * ordinary font matching, with no experimental API in the path.
 *
 * A monospaced face is also a functional requirement, not only an aesthetic one: the
 * timecode and frame counter tick continuously while recording, and on proportional
 * digits every tick reflows the row. Uniform advance width IS tabular figures, so no
 * `fontFeatureSettings = "tnum"` is needed anywhere.
 */
val RawCamMono = FontFamily(
    Font(R.font.azeret_mono_regular, FontWeight.Normal),
    Font(R.font.azeret_mono_medium, FontWeight.Medium),
    Font(R.font.azeret_mono_bold, FontWeight.Bold),
)

/**
 * Four steps, deliberately far apart. The capture screen previously used seven sizes
 * between 10sp and 15sp plus a lone 24sp, which is close enough to uniform that size
 * carried no meaning: a lens name, a dropped-frame count and a menu label all weighed
 * the same. These four are for glancing at arm's length in daylight, so each step is
 * a clear jump rather than a nudge.
 *
 * [Label] is the only style that sets letter spacing: at 10sp, uppercase mono needs
 * the extra tracking to stay readable, while the larger steps do not. Because that
 * tracking assumes capitals, every caller must pass an UPPERCASE string -- spaced-out
 * lowercase reads as a rendering accident rather than a decision. The tracking is
 * 0.6sp rather than the 1.5sp it started at: a monospaced face already carries generous
 * side bearings, and at 1.5sp the widest rail label (SHUTTER) plus its value plus the
 * chevron overran the ~93dp of content a letterbox rail actually has, clipping it to
 * "SHUTTE". Guaranteeing the fit beats relying on truncation.
 *
 * Weights sit a step heavier than a screen UI would normally want. This is a camera
 * HUD read at arm's length, often as white text over a bright preview in daylight,
 * where Azeret's Regular is a hairline that disappears against the image. Legibility
 * outdoors beats typographic delicacy here.
 */
object RawCamType {
    /** Field names above a value. Muted, uppercase, never the thing you read first. */
    val Label = TextStyle(
        fontFamily = RawCamMono, fontSize = 10.sp,
        fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp,
    )

    /** Secondary readouts and list rows: menu labels, clip names, counters. */
    val Meta = TextStyle(
        fontFamily = RawCamMono, fontSize = 13.sp, fontWeight = FontWeight.Normal,
    )

    /** A capture parameter's current setting. The workhorse of the rail. */
    val Value = TextStyle(
        fontFamily = RawCamMono, fontSize = 17.sp, fontWeight = FontWeight.Medium,
    )

    /** Elapsed time. The single largest number on screen, because while rolling it is
     *  the only one that matters. */
    val Timecode = TextStyle(
        fontFamily = RawCamMono, fontSize = 26.sp,
        fontWeight = FontWeight.Bold, letterSpacing = (-0.5).sp,
    )
}

@Composable
fun RawCamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = RawCamColors.Background,
            surface = RawCamColors.Surface,
            surfaceVariant = RawCamColors.SurfaceVariant,
            onBackground = RawCamColors.OnSurface,
            onSurface = RawCamColors.OnSurface,
            onSurfaceVariant = RawCamColors.Muted,
            outline = RawCamColors.Outline,
            // Material's own controls -- the exposure sliders' track and thumb, dialog
            // actions -- read `primary`. They are all things the user operates, so they
            // follow Interactive; `error` below keeps red for the states that are not.
            primary = RawCamColors.Interactive,
            onPrimary = RawCamColors.Background,
            secondary = RawCamColors.Muted,
            error = RawCamColors.Accent,
        ),
        content = content,
    )
}
