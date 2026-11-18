@file:OptIn(ExperimentalTvMaterial3Api::class, androidx.compose.ui.ExperimentalComposeUiApi::class)

package dev.veil.launcher.ui

import android.opengl.GLSurfaceView
import android.os.Handler
import android.os.Looper
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.focusGroup
import androidx.activity.compose.BackHandler
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.tv.material3.Border
import androidx.tv.material3.ClickableSurfaceDefaults
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.Icon
import androidx.tv.material3.LocalContentColor
import androidx.tv.material3.Surface
import androidx.tv.material3.Text
import dev.veil.launcher.LauncherState
import dev.veil.launcher.data.AudioHub
import dev.veil.launcher.viz.VisualizerRenderer
import dev.veil.launcher.viz.VizSettings
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/** Visualizer settings: live preview on the left, sliders on the right (Left/Right on the remote to adjust). */
@Composable
fun VizSettingsDialog(state: LauncherState) {
    val context = LocalContext.current
    // Back returns to the Veil menu (the "Visualizer settings" button), not all the way home.
    val close = { VizSettings.save(context); state.vizSettingsOpen = false; state.menuReturn = "viz" }
    val first = remember { FocusRequester() }
    val nextButton = remember { FocusRequester() }
    var presetName by remember { mutableStateOf("") }

    // Ravine: a layer inside the main window, not a Dialog. On the Shield a live GLSurfaceView in a separate
    // dialog window never received input focus: key presses went nowhere and Android killed Ravine for not
    // responding (ANR "does not have a focused window"). Ambient's in-window visualizer never had that problem.
    BackHandler { Sfx.back(); close() }
    val shape = RoundedCornerShape(10.dp)
    Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.55f)), contentAlignment = Alignment.Center) {
        Row(
            // No accessibility tree (see MainActivity): the live preview would rebuild it every frame.
            Modifier.clearAndSetSemantics { }
                // Keep the remote inside this layer (Home is still composed underneath).
                .focusProperties { exit = { FocusRequester.Cancel } }.focusGroup()
                .width(780.dp).clip(shape).background(Veil.Sheet).border(1.dp, Veil.Line, shape)
                .padding(22.dp),
            horizontalArrangement = Arrangement.spacedBy(22.dp),
        ) {
            // Live preview
            Column(Modifier.width(400.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("Visualizer", fontFamily = Display, fontSize = 32.sp, lineHeight = 34.sp)
                Text(
                    "Play some music to see it react", fontFamily = Display,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.6f),
                )
                Spacer(Modifier.height(4.dp))
                Box(Modifier.fillMaxWidth().height(225.dp).clip(RoundedCornerShape(6.dp)).background(Color.Black)) {
                    VizPreview(onPreset = { presetName = it })
                    Text(
                        presetName, fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier.align(Alignment.BottomStart).padding(8.dp)
                            .clip(RoundedCornerShape(50)).background(Color.Black.copy(alpha = 0.45f))
                            .padding(horizontal = 10.dp, vertical = 4.dp),
                    )
                }
                Text(
                    "Left and right adjust a setting  ·  Back to close", fontFamily = Display,
                    fontStyle = androidx.compose.ui.text.font.FontStyle.Italic, fontSize = 10.sp, color = Veil.Text.copy(alpha = 0.45f),
                )
            }
            // Sliders
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Spacer(Modifier.height(30.dp))
                VSlider("Sensitivity", "How strongly it reacts to the beat", VizSettings.sensitivity, 0.25f, 3f, 0.25f,
                    { "${(it * 100).roundToInt()}%" }, Modifier.focusRequester(first)) { VizSettings.sensitivity = it }
                VSlider("Motion", "Overall speed", VizSettings.motion, 0.5f, 2f, 0.1f, { "${(it * 100).roundToInt()}%" }) { VizSettings.motion = it }
                VSlider("Trails", "How long the image lingers", VizSettings.trails, 0f, 1f, 0.1f, { "${(it * 100).roundToInt()}%" }) { VizSettings.trails = it }
                VSlider("Glow", "Soft bloom around bright parts", VizSettings.glow, 0f, 1f, 0.1f, { "${(it * 100).roundToInt()}%" }) { VizSettings.glow = it }
                VSlider("Brightness", "How bright the brightest parts can get", VizSettings.brightness, 0f, 1f, 0.1f, { "${(it * 100).roundToInt()}%" }) { VizSettings.brightness = it }
                VSlider("Theme tint", "How much your theme colour is blended in", VizSettings.tint, 0f, 1f, 0.1f, { "${(it * 100).roundToInt()}%" }) { VizSettings.tint = it }
                VSlider("Preset time", "How long each preset plays", VizSettings.presetSeconds, 10f, 120f, 5f, { "${it.roundToInt()} s" }) { VizSettings.presetSeconds = it }
                VToggle(
                    "Clean screen",
                    "Hide all text & icons; they show for 5 s when a song starts",
                    VizSettings.clean,
                ) { VizSettings.clean = !VizSettings.clean; VizSettings.save(context) }
                VToggle(
                    "Lyrics",
                    "Synced lyrics over the visuals, also with Clean screen",
                    VizSettings.lyrics,
                    // Down goes to "Next preset" (otherwise it lands on the nearer "Reset to defaults").
                    Modifier.focusProperties { down = nextButton },
                ) { VizSettings.lyrics = !VizSettings.lyrics; VizSettings.save(context) }
                // Buttons live under the sliders: Left/Right on a slider adjusts it, so Down is how you get here.
                Row(Modifier.padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DialogButton("Next preset", VeilIcons.Sparkle, Modifier.focusRequester(nextButton)) { VizSettings.skipRequests++ }
                    DialogButton("Reset to defaults", VeilIcons.Timer) { VizSettings.reset(context) }
                }
            }
        }
    }
    LaunchedEffect(Unit) { delay(120); runCatching { first.requestFocus() } }
}

@Composable
private fun VizPreview(onPreset: (String) -> Unit) {
    val context = LocalContext.current
    val main = remember { Handler(Looper.getMainLooper()) }
    val audio = remember { AudioHub.acquire(context) }
    val accent = Veil.Accent; val bg = Veil.Bg
    val renderer = remember { VisualizerRenderer(audio) { n -> main.post { onPreset(n) } } }
    renderer.accent = floatArrayOf(accent.red, accent.green, accent.blue)
    renderer.background = floatArrayOf(bg.red, bg.green, bg.blue)
    val view = remember {
        GLSurfaceView(context).apply {
            setEGLContextClientVersion(3)
            setZOrderMediaOverlay(true) // draw above the dialog's window background
            setRenderer(renderer)
        }
    }
    DisposableEffect(Unit) {
        view.onResume()
        onDispose { view.onPause(); AudioHub.release() }
    }
    AndroidView(factory = { view }, modifier = Modifier.fillMaxWidth().height(225.dp))
}

/** A TV slider: focus it, then Left/Right change the value. */
@Composable
private fun VSlider(
    label: String,
    hint: String,
    value: Float,
    min: Float,
    max: Float,
    step: Float,
    format: (Float) -> String,
    modifier: Modifier = Modifier,
    onChange: (Float) -> Unit,
) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = {},
        modifier = modifier.fillMaxWidth()
            .onFocusChanged { focused = it.isFocused }
            .focusSound(1.12f)
            .onPreviewKeyEvent { e ->
                if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                val next = when (e.key) {
                    Key.DirectionLeft -> (value - step).coerceIn(min, max)
                    Key.DirectionRight -> (value + step).coerceIn(min, max)
                    else -> return@onPreviewKeyEvent false
                }
                // The tick rises in pitch with the value.
                if (next != value) Sfx.move(0.85f + 0.6f * ((next - min) / (max - min)))
                onChange(next)
                true
            },
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f), contentColor = Veil.Text,
            focusedContainerColor = Color.White.copy(alpha = 0.12f), focusedContentColor = Veil.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, Veil.Accent), shape = RoundedCornerShape(6.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Column(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                if (focused) Text("‹  ", fontSize = 11.sp, color = Veil.Accent)
                Text(format(value), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = if (focused) Veil.Accent else Veil.Text)
                if (focused) Text("  ›", fontSize = 11.sp, color = Veil.Accent)
            }
            val frac = ((value - min) / (max - min)).coerceIn(0f, 1f)
            Box(Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.15f))) {
                Box(Modifier.fillMaxWidth(frac).height(4.dp).clip(RoundedCornerShape(50)).background(Veil.Accent))
            }
            if (focused) Text(hint, fontSize = 8.5.sp, color = Veil.Text.copy(alpha = 0.6f))
        }
    }
}

/** On/off row: OK toggles. */
@Composable
private fun VToggle(label: String, hint: String, on: Boolean, modifier: Modifier = Modifier, onToggle: () -> Unit) {
    var focused by remember { mutableStateOf(false) }
    Surface(
        onClick = { if (on) Sfx.off() else Sfx.on(); onToggle() },
        modifier = modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(6.dp)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.05f), contentColor = Veil.Text,
            focusedContainerColor = Color.White.copy(alpha = 0.12f), focusedContentColor = Veil.Text,
        ),
        border = ClickableSurfaceDefaults.border(
            focusedBorder = Border(BorderStroke(1.5.dp, Veil.Accent), shape = RoundedCornerShape(6.dp)),
        ),
        scale = ClickableSurfaceDefaults.scale(focusedScale = 1.02f),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                if (focused) Text(hint, fontSize = 8.5.sp, color = Veil.Text.copy(alpha = 0.6f))
            }
            // Switch
            Box(
                Modifier.width(34.dp).height(18.dp).clip(RoundedCornerShape(50))
                    .background(if (on) Veil.Accent else Color.White.copy(alpha = 0.2f))
                    .padding(2.dp),
                contentAlignment = if (on) Alignment.CenterEnd else Alignment.CenterStart,
            ) {
                Box(Modifier.size(14.dp).clip(CircleShape).background(if (on) Veil.Ink else Veil.Text))
            }
        }
    }
}

@Composable
private fun DialogButton(
    label: String,
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    modifier: Modifier = Modifier,
    onClick: () -> Unit,
) {
    Surface(
        onClick = { Sfx.select(); onClick() },
        modifier = modifier.focusSound(1.12f),
        shape = ClickableSurfaceDefaults.shape(RoundedCornerShape(50)),
        colors = ClickableSurfaceDefaults.colors(
            containerColor = Color.White.copy(alpha = 0.08f), contentColor = Veil.Text,
            focusedContainerColor = Veil.Text, focusedContentColor = Veil.Ink,
        ),
    ) {
        Row(Modifier.padding(horizontal = 12.dp, vertical = 7.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, contentDescription = null, modifier = Modifier.size(12.dp), tint = LocalContentColor.current)
            Spacer(Modifier.width(6.dp))
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold)
        }
    }
}
