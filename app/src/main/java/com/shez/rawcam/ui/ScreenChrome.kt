package com.shez.rawcam.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Shared chrome for the three non-capture screens (Clips, Exports, Settings).
 *
 * They had grown three copies of the same header -- a bare "←" TextButton beside a
 * Material `titleLarge` -- which put them in a different visual language from the
 * capture screen they are reached from: sans sentence-case against mono uppercase, and
 * a raw glyph where every other control in the app is a bordered pill. One header here
 * means they cannot drift apart again.
 */

/** Widest a column of prose or settings rows is allowed to get.
 *
 * Landscape on this device is 2400px across. Left unconstrained, a settings row put its
 * label at the far left and its value at the far right, ~1870px apart, so pairing the
 * two meant crossing the whole screen -- the exact problem the capture rail's one-line
 * rows exist to avoid. Capping the measure keeps label and value in one glance.
 *
 * 480dp is taken from the capture screen's expanded control panel, which is 400dp and
 * whose sliders are perfectly usable at that width -- so this is wide enough for the
 * widest control here (the 2000K-10000K white-balance slider) without spending width
 * that only pushes a value further from its label. */
private val CONTENT_MAX_WIDTH = 480.dp

@Composable
fun ScreenHeader(
    title: String,
    onBack: () -> Unit,
    trailing: @Composable () -> Unit = {},
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Surface(
            color = Color(0xB80A0B0D),
            shape = RoundedCornerShape(8.dp),
            border = BorderStroke(1.dp, RawCamColors.Outline),
        ) {
            Text(
                "BACK",
                color = RawCamColors.OnSurface,
                style = RawCamType.Label,
                modifier = Modifier
                    .clickable(onClick = onBack)
                    .padding(horizontal = 14.dp, vertical = 10.dp),
            )
        }
        Text(title.uppercase(), color = RawCamColors.OnSurface, style = RawCamType.Value)
        Spacer(Modifier.weight(1f))
        trailing()
    }
}

/**
 * Constrains a screen's body to [CONTENT_MAX_WIDTH] while leaving the surrounding
 * surface full-bleed, so the background still reaches the edges of a very wide screen
 * but the content stays scannable.
 */
@Composable
fun ScreenBody(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Box(modifier.fillMaxWidth()) {
        Column(Modifier.widthIn(max = CONTENT_MAX_WIDTH)) { content() }
    }
}

/**
 * An empty list, said in the app's own voice: what is here, then what to do about it.
 *
 * The previous single grey sentence floated alone in a 2400x1080 void with no
 * structure, which read as an unfinished screen rather than a deliberate empty state.
 */
@Composable
fun EmptyState(headline: String, detail: String) {
    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(headline.uppercase(), color = RawCamColors.Muted, style = RawCamType.Label)
            Text(
                detail,
                color = RawCamColors.Muted,
                fontSize = 14.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.widthIn(max = 320.dp),
            )
        }
    }
}
