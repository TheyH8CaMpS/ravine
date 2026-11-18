package dev.veil.launcher.ui

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Icon
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Wake-up intro (~1.4 s): the lock blooms in, "veil" is unveiled left to right with a light line sweeping
 * under it, then everything lifts away into Home. [running] = false shows just the plain background
 * (armed while the screen is off, so waking never flashes the old frame first).
 */
@Composable
fun IntroOverlay(running: Boolean, onDone: () -> Unit) {
    val bg = remember { Animatable(1f) }      // overlay opacity
    val lock = remember { Animatable(0f) }
    val reveal = remember { Animatable(0f) }  // wordmark unveil 0..1
    val line = remember { Animatable(0f) }
    val glow = remember { Animatable(0f) }
    val lift = remember { Animatable(0f) }    // exit: logo grows slightly and fades

    LaunchedEffect(running) {
        if (!running) return@LaunchedEffect
        Sfx.intro()
        launch { glow.animateTo(1f, tween(900, easing = LinearOutSlowInEasing)) }
        launch { lock.animateTo(1f, spring(dampingRatio = 0.55f, stiffness = Spring.StiffnessMediumLow)) }
        delay(160)
        launch { reveal.animateTo(1f, tween(620, easing = FastOutSlowInEasing)) }
        delay(240)
        launch { line.animateTo(1f, tween(650, easing = FastOutSlowInEasing)) }
        delay(560)
        launch { lift.animateTo(1f, tween(450, easing = FastOutSlowInEasing)) }
        bg.animateTo(0f, tween(450, easing = FastOutSlowInEasing))
        onDone()
    }

    val accent = Veil.Accent
    Box(
        Modifier.fillMaxSize().graphicsLayer { alpha = bg.value }.background(Veil.Bg).drawBehind {
            val g = glow.value
            if (g > 0f) drawRect(
                Brush.radialGradient(
                    0f to accent.copy(alpha = 0.16f * g), 0.6f to accent.copy(alpha = 0.04f * g), 1f to Color.Transparent,
                    center = Offset(size.width / 2, size.height / 2), radius = size.width * (0.25f + 0.2f * g),
                ),
            )
        },
        contentAlignment = Alignment.Center,
    ) {
        if (!running) return@Box
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.graphicsLayer {
                val s = 1f + 0.06f * lift.value
                scaleX = s; scaleY = s
            },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    VeilIcons.Lock, contentDescription = null, tint = accent,
                    modifier = Modifier.size(34.dp).graphicsLayer {
                        val v = lock.value
                        alpha = v.coerceIn(0f, 1f); scaleX = 0.6f + 0.4f * v; scaleY = 0.6f + 0.4f * v
                    },
                )
                Spacer(Modifier.width(14.dp))
                Text(
                    "ravine", fontSize = 56.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-2).sp, color = Veil.Text,
                    modifier = Modifier.drawWithContent {
                        // Unveil: clip to a soft-edged window that moves left to right.
                        val r = reveal.value
                        if (r <= 0f) return@drawWithContent
                        val edge = size.width * 0.25f
                        val x = -edge + (size.width + edge) * r
                        drawContext.canvas.saveLayer(androidx.compose.ui.geometry.Rect(Offset.Zero, size), androidx.compose.ui.graphics.Paint())
                        drawContent()
                        drawRect(
                            Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = x, endX = x + edge),
                            blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
                        )
                        drawContext.canvas.restore()
                    },
                )
            }
            Spacer(Modifier.height(14.dp))
            Box(
                Modifier.width(150.dp).height(2.dp).graphicsLayer {
                    alpha = (1f - lift.value)
                    scaleX = line.value
                }.clip(RoundedCornerShape(50)).background(
                    Brush.horizontalGradient(listOf(Color.Transparent, accent, Veil.Text, accent, Color.Transparent)),
                ),
            )
        }
    }
}
