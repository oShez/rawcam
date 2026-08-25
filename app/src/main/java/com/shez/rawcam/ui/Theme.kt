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

/** RawCam palette — single dark theme; the app is a camera and commits to it. */
object RawCamColors {
    val Background = Color(0xFF0A0B0D)
    val Surface = Color(0xFF17191D)
    val SurfaceVariant = Color(0xFF24272C)
    val OnSurface = Color(0xFFE9EAEC)
    val Muted = Color(0xFF8F959D)
    val Outline = Color(0xFF3A3E45)
    val Accent = Color(0xFFE5484D)
    val Success = Color(0xFF6FBF73)
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
 * lowercase reads as a rendering accident rather than a decision.
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
        fontWeight = FontWeight.Medium, letterSpacing = 1.5.sp,
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
            primary = RawCamColors.Accent,
            onPrimary = Color.White,
            secondary = RawCamColors.Muted,
            error = RawCamColors.Accent,
        ),
        content = content,
    )
}
