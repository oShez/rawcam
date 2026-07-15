package com.shez.rawcam.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

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
