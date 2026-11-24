package dev.veil.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import kotlinx.coroutines.delay
import java.time.LocalDateTime
import java.time.Month
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.random.Random

/** Which seasonal touch (if any) applies right now. */
enum class Season { NONE, SNOW, COUNTDOWN, FIREWORKS, EMBERS, CONFETTI }

object Seasons {
    fun at(t: LocalDateTime): Season {
        val d = t.dayOfMonth
        return when (t.month) {
            Month.DECEMBER -> if (d == 31 && t.hour == 23) Season.COUNTDOWN else Season.SNOW
            Month.JANUARY -> if (d == 1 && t.hour == 0 && t.minute < 15) Season.FIREWORKS else Season.NONE
            Month.OCTOBER -> if (d >= 24) Season.EMBERS else Season.NONE
            Month.MAY -> if (d == 17) Season.CONFETTI else Season.NONE
            else -> Season.NONE
        }
    }

    /** December: the wordmark gets a snowflake. */
    fun isWinter(t: LocalDateTime = LocalDateTime.now()) = t.month == Month.DECEMBER
}

/** The season right now, re-checked every 20 s (so the countdown and fireworks start on time). */
@Composable
fun currentSeason(): Season {
    val s by produceState(Seasons.at(LocalDateTime.now())) {
        while (true) { value = Seasons.at(LocalDateTime.now()); delay(20_000) }
    }
    return s
}

private class Particle(
    var x: Float, var y: Float, var vx: Float, var vy: Float,
    var size: Float, var phase: Float, var color: Color, var life: Float = 1f, var spin: Float = 0f,
)

/**
 * A light particle layer for Veil Ambient. Particles live in 0..1 screen space and are drawn with Canvas,
 * capped at ~90 so Ambient stays smooth on the Streamer.
 */
@Composable
fun SeasonalLayer(season: Season, modifier: Modifier = Modifier) {
    if (season == Season.NONE) return
    var frame by remember { mutableLongStateOf(0L) }
    LaunchedEffect(Unit) { while (true) withFrameNanos { frame = it } }
    val rnd = remember { Random(System.nanoTime()) }
    val particles = remember(season) { ArrayList<Particle>().also { seed(season, it, rnd) } }
    var last by remember { mutableLongStateOf(0L) }
    var burstTimer by remember { mutableLongStateOf(0L) }

    Box(modifier.fillMaxSize()) {
        Canvas(Modifier.fillMaxSize().graphicsLayer()) { // own layer: redrawn every frame
            val now = frame
            val dt = if (last == 0L) 0.016f else ((now - last) / 1e9f).coerceIn(0f, 0.05f)
            last = now
            val w = size.width; val h = size.height
            when (season) {
                Season.SNOW, Season.COUNTDOWN -> particles.forEach { p ->
                    p.phase += dt
                    p.y += p.vy * dt
                    p.x += (p.vx + sin(p.phase * 1.3f) * 0.012f) * dt
                    if (p.y > 1.05f) { p.y = -0.05f; p.x = rnd.nextFloat() }
                    drawCircle(Color.White.copy(alpha = p.color.alpha), radius = p.size, center = Offset(p.x * w, p.y * h))
                }
                Season.EMBERS -> particles.forEach { p ->
                    p.phase += dt
                    p.y -= p.vy * dt
                    p.x += sin(p.phase * 2f) * 0.01f * dt
                    if (p.y < -0.05f) { p.y = 1.05f; p.x = rnd.nextFloat() }
                    val flicker = 0.55f + 0.45f * sin(p.phase * 6f + p.size)
                    drawCircle(p.color.copy(alpha = p.color.alpha * flicker), radius = p.size, center = Offset(p.x * w, p.y * h))
                }
                Season.CONFETTI -> particles.forEach { p ->
                    p.phase += dt
                    p.y += p.vy * dt
                    p.x += sin(p.phase * 1.7f) * 0.02f * dt
                    p.spin += dt * 140f
                    if (p.y > 1.05f) { p.y = -0.05f; p.x = rnd.nextFloat() }
                    rotate(p.spin, pivot = Offset(p.x * w, p.y * h)) {
                        drawRect(p.color, topLeft = Offset(p.x * w - p.size, p.y * h - p.size * 0.5f), size = Size(p.size * 2f, p.size))
                    }
                }
                Season.FIREWORKS -> {
                    burstTimer += (dt * 1000).toLong()
                    if (burstTimer > 900 && particles.size < 160) { burstTimer = 0; burst(particles, rnd) }
                    val it = particles.iterator()
                    while (it.hasNext()) {
                        val p = it.next()
                        p.vy += 0.18f * dt               // gravity
                        p.x += p.vx * dt; p.y += p.vy * dt
                        p.life -= dt * 0.65f
                        if (p.life <= 0f) { it.remove(); continue }
                        drawCircle(p.color.copy(alpha = p.life.coerceIn(0f, 1f)), radius = p.size, center = Offset(p.x * w, p.y * h))
                    }
                }
                Season.NONE -> Unit
            }
        }
        // Top centre, so it never covers the music or artwork text.
        when (season) {
            Season.COUNTDOWN -> Countdown(Modifier.align(Alignment.TopCenter).padding(top = 64.dp))
            Season.FIREWORKS -> Text(
                "Happy New Year", fontSize = 36.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1).sp,
                modifier = Modifier.align(Alignment.TopCenter).padding(top = 70.dp),
            )
            else -> Unit
        }
    }
}

@Composable
private fun Countdown(modifier: Modifier) {
    val left by produceState(0L) {
        while (true) {
            val now = LocalDateTime.now()
            val midnight = now.toLocalDate().plusDays(1).atStartOfDay()
            value = java.time.Duration.between(now, midnight).seconds.coerceAtLeast(0)
            delay(250)
        }
    }
    Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) {
        Text("New year in", style = Type.Label, fontSize = 12.sp, color = Veil.Accent)
        Text(
            "%d:%02d".format(left / 60, left % 60),
            fontSize = 44.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-1.5).sp,
        )
    }
}

private fun seed(season: Season, out: ArrayList<Particle>, r: Random) {
    when (season) {
        Season.SNOW, Season.COUNTDOWN -> repeat(90) {
            out += Particle(r.nextFloat(), r.nextFloat(), (r.nextFloat() - 0.5f) * 0.01f, 0.025f + r.nextFloat() * 0.05f,
                1.5f + r.nextFloat() * 3.5f, r.nextFloat() * 6f, Color.White.copy(alpha = 0.35f + r.nextFloat() * 0.5f))
        }
        Season.EMBERS -> repeat(55) {
            out += Particle(r.nextFloat(), r.nextFloat(), 0f, 0.02f + r.nextFloat() * 0.05f,
                1.5f + r.nextFloat() * 2.5f, r.nextFloat() * 6f,
                listOf(Color(0xFFFF8A3D), Color(0xFFFFB347), Color(0xFFFF5E3A)).random(r).copy(alpha = 0.5f + r.nextFloat() * 0.4f))
        }
        Season.CONFETTI -> repeat(70) {
            // Red, white and blue for 17 May.
            out += Particle(r.nextFloat(), r.nextFloat(), 0f, 0.04f + r.nextFloat() * 0.06f,
                3f + r.nextFloat() * 3f, r.nextFloat() * 6f,
                listOf(Color(0xFFBA0C2F), Color(0xFFF4F5F7), Color(0xFF00205B)).random(r), spin = r.nextFloat() * 360f)
        }
        Season.FIREWORKS -> repeat(2) { burst(out, r) }
        Season.NONE -> Unit
    }
}

private fun burst(out: ArrayList<Particle>, r: Random) {
    val cx = 0.2f + r.nextFloat() * 0.6f; val cy = 0.15f + r.nextFloat() * 0.35f
    val color = listOf(Color(0xFFFFD45E), Color(0xFFFF6B8A), Color(0xFF7FD8FF), Color(0xFFD9FF6B), Color(0xFFC9B8FF)).random(r)
    repeat(48) {
        val a = (it / 48f) * 2f * PI.toFloat()
        val sp = 0.12f + r.nextFloat() * 0.1f
        out += Particle(cx, cy, cos(a) * sp * 0.6f, sin(a) * sp, 1.6f + r.nextFloat() * 1.6f, 0f, color, life = 1f)
    }
}
