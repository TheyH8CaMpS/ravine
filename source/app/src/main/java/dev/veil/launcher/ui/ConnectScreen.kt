package dev.veil.launcher.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.tv.material3.Text
import dev.veil.launcher.LauncherState
import kotlinx.coroutines.delay

@Composable
fun ConnectScreen(state: LauncherState) {
    val dc = state.deviceCode
    // No top bar here: its pills would take focus away from the sign-in code. Back cancels.
    Column(Modifier.fillMaxSize().background(Veil.Bg).padding(start = 48.dp, end = 48.dp, top = 26.dp)) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text("Connect Trakt", style = Type.Label, fontSize = 12.sp, color = Veil.Accent)
                Spacer(Modifier.height(8.dp))
                Text("Show what you're watching", fontSize = 30.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = (-0.9).sp)
                Spacer(Modifier.height(22.dp))
                if (state.editingClientId) {
                    ClientIdForm(state)
                } else {
                    if (dc != null) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            // Scanning opens Trakt's activate page with the code already filled in.
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                QrCode("${dc.url.trimEnd('/')}/${dc.userCode}", Modifier.size(150.dp))
                                Spacer(Modifier.height(8.dp))
                                Text("Scan with your phone", fontSize = 10.sp, color = Veil.Text.copy(alpha = 0.6f))
                            }
                            Spacer(Modifier.width(44.dp))
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("Or on your phone or computer, go to", fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.7f))
                                Spacer(Modifier.height(4.dp))
                                Text(dc.url.removePrefix("https://"), fontSize = 17.sp, fontWeight = FontWeight.Bold)
                                Spacer(Modifier.height(16.dp))
                                Text("and enter this code", fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.7f))
                                Spacer(Modifier.height(10.dp))
                                Text(
                                    dc.userCode,
                                    color = Veil.Ink, fontSize = 34.sp, fontWeight = FontWeight.ExtraBold, letterSpacing = 6.sp,
                                    modifier = Modifier.clip(RoundedCornerShape(50)).background(Veil.Text)
                                        .padding(horizontal = 30.dp, vertical = 8.dp),
                                )
                            }
                        }
                        Spacer(Modifier.height(20.dp))
                    }
                    Text(state.connectStatus, fontSize = 11.sp, color = Veil.Text.copy(alpha = 0.7f))
                    Spacer(Modifier.height(18.dp))
                    // Not focused on purpose: nothing is highlighted until you press a direction key.
                    Pill(onClick = { state.editClientId() }, padding = PaddingValues(horizontal = 16.dp, vertical = 6.dp)) {
                        Text(
                            if (state.trakt.usingOwnId) "Change your Trakt app" else "Use your own Trakt app",
                            fontSize = 10.sp, fontWeight = FontWeight.SemiBold,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                }
                Text("Press Back to cancel", fontSize = 10.sp, color = Veil.Text.copy(alpha = 0.5f))
            }
        }
    }
}

/** Client ID of your own Trakt app. Saved on the TV, so it survives Veil updates. */
@Composable
private fun ClientIdForm(state: LauncherState) {
    var id by remember { mutableStateOf(if (state.trakt.usingOwnId) state.trakt.clientId else "") }
    var problem by remember { mutableStateOf<String?>(null) }
    val field = remember { FocusRequester() }
    val save = {
        val clean = id.filter { !it.isWhitespace() }
        if (clean.length < 20 || !clean.all { it.isLetterOrDigit() }) {
            problem = "That doesn't look like a client ID. It's a long code of letters and numbers."
        } else {
            problem = null
            state.saveClientId(clean)
        }
    }
    Text(
        "Paste or type the Client ID of your own Trakt app.",
        fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.7f), textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(4.dp))
    Text(
        "Make one at trakt.tv/oauth/applications, redirect URI urn:ietf:wg:oauth:2.0:oob",
        fontSize = 11.sp, color = Veil.Text.copy(alpha = 0.55f), textAlign = TextAlign.Center,
    )
    Spacer(Modifier.height(16.dp))
    if (state.connectStatus.isNotEmpty()) {
        Text(state.connectStatus, fontSize = 11.sp, color = Veil.Accent, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
    }
    BasicTextField(
        value = id,
        onValueChange = { id = it; problem = null },
        singleLine = true,
        textStyle = TextStyle(color = Veil.Text, fontSize = 12.sp, fontFamily = Manrope),
        cursorBrush = SolidColor(Veil.Accent),
        keyboardOptions = KeyboardOptions(autoCorrect = false, keyboardType = KeyboardType.Ascii, imeAction = ImeAction.Done),
        keyboardActions = KeyboardActions(onDone = { save() }),
        modifier = Modifier.width(460.dp).focusRequester(field)
            .clip(RoundedCornerShape(50)).background(Color.White.copy(alpha = 0.08f))
            .border(1.dp, Veil.Line, RoundedCornerShape(50))
            .padding(horizontal = 16.dp, vertical = 10.dp),
        decorationBox = { inner ->
            Box {
                if (id.isEmpty()) Text("Trakt Client ID", fontSize = 12.sp, color = Veil.Text.copy(alpha = 0.45f))
                inner()
            }
        },
    )
    Spacer(Modifier.height(10.dp))
    problem?.let {
        Text(it, fontSize = 10.sp, color = Veil.Accent, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
    }
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Pill(onClick = { save() }, padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
            Text("Save and connect", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
        }
        if (state.trakt.usingOwnId) {
            Pill(onClick = { state.saveClientId(null) }, padding = PaddingValues(horizontal = 18.dp, vertical = 6.dp)) {
                Text("Use Veil's Trakt app", fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
            }
        }
    }
    Spacer(Modifier.height(10.dp))
    LaunchedEffect(Unit) {
        delay(120)
        runCatching { field.requestFocus() }
    }
}

/**
 * QR code made on the TV (nothing is sent anywhere). Drawn once per code as a tiny bitmap, one pixel per
 * module, and scaled up without smoothing so it stays sharp. Dark on light with a quiet zone, which every
 * phone camera reads.
 */
@Composable
private fun QrCode(text: String, modifier: Modifier = Modifier) {
    val image = remember(text) {
        runCatching {
            val m = com.google.zxing.qrcode.QRCodeWriter().encode(
                text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0,
                mapOf(com.google.zxing.EncodeHintType.MARGIN to 2),
            )
            val dark = Veil.Ink.toArgb(); val light = Veil.Text.toArgb()
            val px = IntArray(m.width * m.height) { if (m.get(it % m.width, it / m.width)) dark else light }
            Bitmap.createBitmap(px, m.width, m.height, Bitmap.Config.ARGB_8888).asImageBitmap()
        }.getOrNull()
    } ?: return
    Image(
        image, contentDescription = null, filterQuality = FilterQuality.None,
        modifier = modifier.clip(RoundedCornerShape(12.dp)),
    )
}
