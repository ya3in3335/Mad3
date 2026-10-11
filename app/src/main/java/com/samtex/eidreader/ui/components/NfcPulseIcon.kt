package com.samtex.eidreader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.samtex.eidreader.domain.model.ScanPhase
import com.samtex.eidreader.ui.theme.SamtexColors

/** Large animated NFC symbol whose color and motion reflect the current scan phase. */
@Composable
fun NfcPulseIcon(phase: ScanPhase, modifier: Modifier = Modifier, size: Dp = 200.dp) {
    val active = phase in setOf(ScanPhase.WAITING, ScanPhase.CARD_DETECTED, ScanPhase.CONNECTING, ScanPhase.READING)
    val fast = phase in setOf(ScanPhase.CARD_DETECTED, ScanPhase.CONNECTING, ScanPhase.READING)
    val target = when (phase) {
        ScanPhase.COMPLETED, ScanPhase.WAITING, ScanPhase.CARD_DETECTED, ScanPhase.CONNECTING, ScanPhase.READING -> SamtexColors.Accent
        ScanPhase.ERROR, ScanPhase.CONNECTION_LOST -> SamtexColors.Error
        ScanPhase.NFC_DISABLED, ScanPhase.NFC_UNAVAILABLE -> SamtexColors.Muted
    }
    val color by animateColorAsState(target, tween(400), label = "nfcColor")
    val transition = rememberInfiniteTransition(label = "nfcPulse")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (fast) 900 else 2200, easing = LinearEasing), RepeatMode.Restart),
        label = "pulse",
    )
    val wave by transition.animateFloat(
        initialValue = 0.55f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(if (fast) 400 else 1100), RepeatMode.Reverse),
        label = "wave",
    )

    Canvas(modifier.size(size)) {
        val center = Offset(this.size.width / 2, this.size.height / 2)
        val maxR = this.size.minDimension / 2
        val coreR = maxR * 0.52f
        if (active) {
            for (i in 0..1) {
                val p = (pulse + i * 0.5f) % 1f
                val r = coreR + (maxR - coreR) * p
                drawCircle(color.copy(alpha = (1f - p) * 0.35f), radius = r, center = center, style = Stroke(width = 3.dp.toPx()))
            }
        }
        drawCircle(SamtexColors.SecondaryBackground, radius = coreR, center = center)

        // Card glyph
        val cardW = coreR * 0.95f
        val cardH = coreR * 0.62f
        val cardTopLeft = Offset(center.x - cardW / 2 - coreR * 0.12f, center.y - cardH / 2)
        drawRoundRect(
            color = SamtexColors.Primary,
            topLeft = cardTopLeft,
            size = Size(cardW, cardH),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()),
            style = Stroke(width = 3.dp.toPx()),
        )
        drawRoundRect(
            color = color,
            topLeft = Offset(cardTopLeft.x + cardW * 0.14f, cardTopLeft.y + cardH * 0.28f),
            size = Size(cardW * 0.24f, cardH * 0.32f),
            cornerRadius = androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()),
        )
        // Radio waves to the right of the card
        val waveCenter = Offset(cardTopLeft.x + cardW * 0.62f, center.y)
        for (i in 1..3) {
            val r = coreR * (0.16f + 0.13f * i)
            val alpha = if (active) (wave - (i - 1) * 0.15f).coerceIn(0.25f, 1f) else 1f
            drawArc(
                color = color.copy(alpha = alpha),
                startAngle = -40f,
                sweepAngle = 80f,
                useCenter = false,
                topLeft = Offset(waveCenter.x - r, waveCenter.y - r),
                size = Size(r * 2, r * 2),
                style = Stroke(width = 3.5.dp.toPx(), cap = StrokeCap.Round),
            )
        }
    }
}

/** Utility for consistent provenance/status colors. */
fun statusColor(ok: Boolean?, restricted: Boolean = false): Color = when {
    restricted -> SamtexColors.Warning
    ok == true -> SamtexColors.Accent
    ok == false -> SamtexColors.Error
    else -> SamtexColors.Muted
}
