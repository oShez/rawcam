package com.shez.rawcam.camera

/** Renders a [DeviceProfile] as plain shareable text: what RawCam found on this
 * phone, which lenses it accepted, and why it rejected the rest. Deliberately
 * free of android.* imports so it is unit-testable. */
object CompatibilityReport {

    fun render(profile: DeviceProfile, model: String, sdkInt: Int): String = buildString {
        appendLine("RawCam compatibility report")
        appendLine("Device: $model (Android SDK $sdkInt)")
        appendLine()
        when (profile) {
            is DeviceProfile.Unsupported -> {
                appendLine("RESULT: NOT SUPPORTED (${profile.reason})")
                appendLine(profile.detail)
            }
            is DeviceProfile.Supported -> {
                appendLine("RESULT: SUPPORTED - ${profile.lenses.size} lens(es)")
                profile.lenses.forEachIndexed { i, l ->
                    appendLine()
                    appendLine("[${i + 1}] ${l.label}  id=${l.cameraId}${if (l.isMain) "  (main)" else ""}")
                    appendLine("    control: ${l.controlTier}")
                    appendLine("    sizes:   " + l.sizes.joinToString(", ") { "${it.width}x${it.height}@${it.maxFps}" })
                    appendLine("    ISO:     ${l.isoRange.first}-${l.isoRange.last}")
                    appendLine("    standalone: ${l.standalone}")
                    if (l.defaulted.isNotEmpty()) {
                        appendLine("    DEFAULTED: " + l.defaulted.joinToString(", ") { it.name })
                    }
                }
            }
        }
        appendLine()
        appendLine("Enumeration log:")
        profile.notes.forEach { appendLine("  id ${it.cameraId}: ${if (it.accepted) "OK" else "SKIP"} - ${it.message}") }
    }
}
