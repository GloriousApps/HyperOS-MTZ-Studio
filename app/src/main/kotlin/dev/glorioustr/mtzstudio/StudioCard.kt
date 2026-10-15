package dev.glorioustr.mtzstudio

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.dp

@Composable
internal fun StudioCard(
    modifier: Modifier = Modifier,
    neon: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = MaterialTheme.shapes.large
    if (neon) {
        Box(
            modifier = modifier
                .shadow(3.dp, shape)
                .clip(shape)
                .background(Brush.linearGradient(listOf(Color(0xE6222550), Color(0xCC101B43), Color(0xE6111738))))
                .border(
                    1.dp,
                    Brush.linearGradient(listOf(Color(0xFF9B6DFF), Color(0xFF5D8DFF), Color(0xFF55E4FF))),
                    shape,
                ),
        ) {
            Column(Modifier.fillMaxWidth(), content = content)
        }
        return
    }
    if (LocalAppContentStyle.current != AppContentStyle.LIQUID_GLASS) {
        Card(modifier = modifier, shape = shape, content = content)
        return
    }

    val colors = MaterialTheme.colorScheme
    val lightSurface = colors.background.luminance() > 0.5f
    val glassBase = Brush.linearGradient(
        colors = if (lightSurface) {
            listOf(Color(0xFFE5DDF2), Color(0xFFE1E7F1), Color(0xFFEADCF0))
        } else {
            listOf(Color(0xFF38363E), Color(0xFF302C3D), Color(0xFF3A3347))
        },
    )
    val glassEdge = Brush.linearGradient(
        colors = listOf(
            Color(0xFF8E63FF).copy(alpha = 0.72f),
            colors.outline.copy(alpha = 0.64f),
            Color(0xFF48D9FF).copy(alpha = 0.56f),
        ),
    )

    // A Material Card with a transparent container still creates its own tonal surface layer.
    // On light Aero Glass this appeared as a white rectangle inset inside the glass edge.
    // Draw the glass as one clipped layer so the whole card has a continuous surface.
    Box(
        modifier = modifier
            .shadow(2.dp, shape)
            .clip(shape)
            .background(glassBase)
            .border(1.dp, glassEdge, shape),
    ) {
        Column(Modifier.fillMaxWidth(), content = content)
    }
}
