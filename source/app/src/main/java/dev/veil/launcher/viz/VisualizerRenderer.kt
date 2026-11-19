package dev.veil.launcher.viz

import android.opengl.GLES30
import android.opengl.GLSurfaceView
import android.os.SystemClock
import android.util.Log
import dev.veil.launcher.data.AudioTap
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.exp
import kotlin.math.min
import kotlin.random.Random

/**
 * MilkDrop-style renderer, tuned for the Streamer's small GPU. Every frame:
 *  0. Field pass (tiny, ~210x120): the smooth fluid velocity and the noise fields for marbling/silk. These
 *     are smooth by nature, so computing them per screen pixel was wasted work (it held the fluid presets
 *     at ~18 fps).
 *  1. Feedback pass (~640x360, half-float): the previous frame is carried along the flow, faded, and new
 *     audio-reactive colour is painted in. Mipmaps are built from it for the bloom.
 *  2. Display pass (1280x720 surface, scaled up by the display hardware for free): glossy lighting, wide
 *     bloom from the mipmaps, echo/solarize effects, filmic tone curve, light vignette, dithering.
 *
 * Music → motion (smooth, but visible): kicks surge the flow, every beat drops a bloom of colour somewhere
 * on screen that the flow then carries off, mids shift the colour, highs make threads and highlights
 * shimmer, and the song's loud/quiet passages raise and lower the exposure.
 */
class VisualizerRenderer(
    private val audio: AudioTap,
    private val onPreset: (String) -> Unit,
) : GLSurfaceView.Renderer {

    /** Theme colours (0..1 RGB), set from the UI thread. */
    @Volatile var accent = floatArrayOf(0.85f, 1f, 0.42f)
    @Volatile var background = floatArrayOf(0.08f, 0.09f, 0.11f)

    private var fieldProg = 0
    private var feedbackProg = 0
    private var displayProg = 0
    private val tex = IntArray(2)
    private val fbo = IntArray(2)
    private val fieldTex = IntArray(1)
    private val fieldFbo = IntArray(1)
    private var fw = 0; private var fh = 0; private var gw = 0; private var gh = 0; private var sw = 0; private var sh = 0
    private var levels = 1
    private var src = 0
    private var broken = false
    // Uniform locations, one map per program (looked up once; the old "$program:$name" key built a new
    // string for every uniform on every frame).
    private val fieldLoc = HashMap<String, Int>()
    private val feedbackLoc = HashMap<String, Int>()
    private val displayLoc = HashMap<String, Int>()

    private val start = SystemClock.elapsedRealtime()
    private var lastFrame = 0L
    private var tm = 0f          // motion time (scaled by the Motion slider)
    private var ft = 0f          // flow time: runs faster when the music is more intense
    private var hue = 0f
    private var lastSilentCheck = 0L
    private var lastBeatSeen = 0L
    private var seenSkips = VizSettings.skipRequests
    private var fpsFrames = 0; private var fpsStart = 0L

    // Audio envelopes: quick attack, smooth release (seconds-based, frame-rate independent).
    private var bassEnv = 0f; private var midEnv = 0f; private var trebEnv = 0f
    private var snareEnv = 0f; private var hatEnv = 0f; private var toneEnv = 0f
    /** Which way the next snare twists the flow (alternates, so it never drifts one way). */
    private var snareDir = 1f; private var snareArmed = true
    private var loud = 0.5f      // the song's loud/quiet passages
    private var swell = 0f       // soft swell after each beat

    /** Beat "blooms": x, y (0..1), age (s), palette offset; plus strength. */
    private val imp = FloatArray(16) { if (it % 4 == 2) 9f else 0f }
    private val impS = FloatArray(4)
    private var impNext = 0

    private val keys = VizPreset.KEYS
    private var preset = VizPresets.gameDayPreset() ?: VizPresets.all.random()
    private var presetStart = 0f
    private val cur = VizPresets.vary(preset, Random).let { m -> FloatArray(keys.size) { m.getValue(keys[it]) } }
    private val target = cur.copyOf()
    /** Offset into the noise fields, re-rolled per preset: a different flow pattern every time. */
    private var seedX = Random.nextFloat() * 100f; private var seedY = Random.nextFloat() * 100f
    /** Recently shown presets, so they don't come back too soon. */
    private val recent = ArrayDeque<String>()

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        try {
            fieldProg = program(VERT, FIELD_FRAG)
            feedbackProg = program(VERT, FEEDBACK_FRAG)
            displayProg = program(VERT, DISPLAY_FRAG)
            fieldLoc.clear(); feedbackLoc.clear(); displayLoc.clear()
            broken = false
            onPreset(preset.name)
        } catch (e: Exception) {
            Log.e("VeilViz", "shader setup failed", e)
            broken = true
        }
    }

    override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
        sw = width; sh = height
        // Lite mode (slow TVs): a smaller feedback buffer, about half the pixels to process each frame.
        val maxW = if (dev.veil.launcher.BuildConfig.LITE_MODE) 448 else 640
        fw = (width * 2 / 3).coerceIn(320, maxW); fh = (fw * height / width).coerceAtLeast(120)
        gw = fw / 3; gh = fh / 3
        levels = 1 + (31 - Integer.numberOfLeadingZeros(maxOf(fw, fh)))
        if (tex[0] != 0) {
            GLES30.glDeleteTextures(2, tex, 0); GLES30.glDeleteFramebuffers(2, fbo, 0)
            GLES30.glDeleteTextures(1, fieldTex, 0); GLES30.glDeleteFramebuffers(1, fieldFbo, 0)
        }
        GLES30.glGenFramebuffers(2, fbo, 0)
        GLES30.glGenFramebuffers(1, fieldFbo, 0)
        for (i in 0..1) tex[i] = makeTarget(fbo[i], fw, fh, levels, GLES30.GL_MIRRORED_REPEAT)
        fieldTex[0] = makeTarget(fieldFbo[0], gw, gh, 1, GLES30.GL_CLAMP_TO_EDGE)
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
    }

    /**
     * Creates a render target texture attached to framebuffer [f]. Half-float when the GPU can render to it
     * (smooth fades, no banding in the trails), otherwise 8-bit. Returns the texture name.
     */
    private fun makeTarget(f: Int, w: Int, h: Int, lv: Int, wrap: Int): Int {
        for (fmt in intArrayOf(GLES30.GL_RGBA16F, GLES30.GL_RGBA8)) {
            val t = IntArray(1)
            GLES30.glGenTextures(1, t, 0)
            GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, t[0])
            GLES30.glTexStorage2D(GLES30.GL_TEXTURE_2D, lv, fmt, w, h)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MIN_FILTER, if (lv > 1) GLES30.GL_LINEAR_MIPMAP_LINEAR else GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_S, wrap)
            GLES30.glTexParameteri(GLES30.GL_TEXTURE_2D, GLES30.GL_TEXTURE_WRAP_T, wrap)
            GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, f)
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, t[0], 0)
            if (GLES30.glCheckFramebufferStatus(GLES30.GL_FRAMEBUFFER) == GLES30.GL_FRAMEBUFFER_COMPLETE) {
                GLES30.glClearColor(0f, 0f, 0f, 1f)
                GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
                if (fmt != GLES30.GL_RGBA16F) Log.w("VeilViz", "half-float targets unavailable; using 8-bit")
                return t[0]
            }
            GLES30.glFramebufferTexture2D(GLES30.GL_FRAMEBUFFER, GLES30.GL_COLOR_ATTACHMENT0, GLES30.GL_TEXTURE_2D, 0, 0)
            GLES30.glDeleteTextures(1, t, 0)
        }
        throw IllegalStateException("no usable render target format")
    }

    /** Start of the previous frame, for pacing. */
    private var paceAt = 0L

    override fun onDrawFrame(gl: GL10?) {
        // Pace at a steady 30 fps (every other screen refresh) instead of "as fast as possible". Uneven
        // 31-35 fps judders; a steady 30 looks smoother, and the small gap it leaves each frame lets the
        // GPU draw Veil's own animations on top (lyrics, track card) without them stuttering.
        val since = SystemClock.elapsedRealtime() - paceAt
        if (paceAt != 0L && since < FRAME_MS) runCatching { Thread.sleep(FRAME_MS - since) }
        paceAt = SystemClock.elapsedRealtime()
        val bg = background
        if (broken || fw == 0) {
            GLES30.glClearColor(bg[0], bg[1], bg[2], 1f)
            GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT)
            return
        }
        val nowMs = SystemClock.elapsedRealtime()
        val t = (nowMs - start) / 1000f
        val dt = if (lastFrame == 0L) 0.016f else min(0.1f, (nowMs - lastFrame) / 1000f)
        lastFrame = nowMs
        if (nowMs - lastSilentCheck > 3_000) { lastSilentCheck = nowMs; audio.restartIfSilent() }
        fpsFrames++
        if (nowMs - fpsStart > 10_000) {
            if (fpsStart != 0L) Log.d("VeilViz", "${preset.name}: ${fpsFrames * 1000 / (nowMs - fpsStart)} fps")
            fpsStart = nowMs; fpsFrames = 0
        }

        // ---- Settings ----
        val sens = VizSettings.sensitivity
        val motion = VizSettings.motion
        audio.beatThreshold = 1f + 0.55f / sens.coerceAtLeast(0.25f)
        tm += dt * motion

        // ---- Audio → envelopes ----
        fun env(cur: Float, tgt: Float, attack: Float, release: Float): Float {
            val tau = if (tgt > cur) attack else release
            return cur + (tgt - cur) * (1f - exp(-dt / tau))
        }
        bassEnv = env(bassEnv, ((audio.bassRel - 1f) * sens).coerceIn(0f, 2f), 0.035f, 0.28f)
        midEnv = env(midEnv, ((audio.midRel - 1f) * sens).coerceIn(0f, 2f), 0.06f, 0.4f)
        trebEnv = env(trebEnv, ((audio.trebRel - 1f) * sens).coerceIn(0f, 2f), 0.04f, 0.25f)
        loud = env(loud, audio.level, 0.25f, 1.2f)
        snareEnv = env(snareEnv, (audio.snare * sens).coerceIn(0f, 1.5f), 0.012f, 0.12f)
        hatEnv = env(hatEnv, (audio.hat * sens).coerceIn(0f, 1.5f), 0.01f, 0.09f)
        toneEnv = env(toneEnv, audio.tone, 0.5f, 1.0f)
        if (snareArmed && snareEnv > 0.45f) { snareArmed = false; snareDir = -snareDir }
        else if (snareEnv < 0.2f) snareArmed = true
        val beatAt = audio.lastBeatAt
        val beat = if (beatAt == 0L) 0f else exp(-(nowMs - beatAt) / 160f).coerceIn(0f, 1f)
        swell *= exp(-dt / 0.55f)
        if (beatAt != lastBeatSeen && beatAt != 0L && nowMs - beatAt < 200) {
            lastBeatSeen = beatAt
            swell = maxOf(swell, 0.55f + 0.45f * min(1f, bassEnv))
            // Drop a bloom of colour somewhere on screen.
            val i = impNext; impNext = (impNext + 1) % 4
            imp[i * 4] = 0.12f + 0.76f * Random.nextFloat()
            imp[i * 4 + 1] = 0.15f + 0.7f * Random.nextFloat()
            imp[i * 4 + 2] = 0f
            imp[i * 4 + 3] = Random.nextFloat() * 0.35f
            impS[i] = 0.5f + 0.7f * min(1f, bassEnv)
        }
        for (i in 0 until 4) imp[i * 4 + 2] += dt
        ft += dt * motion * (0.35f + 0.45f * loud + 0.9f * bassEnv + 0.5f * swell)
        hue += dt * (0.003f + 0.012f * midEnv) * motion

        // ---- Presets ----
        val skips = VizSettings.skipRequests
        val age = t - presetStart
        if (skips != seenSkips || age > VizSettings.presetSeconds ||
            (age > VizSettings.presetSeconds * 0.7f && swell > 0.9f && Random.nextFloat() < 0.05f)
        ) { seenSkips = skips; nextPreset(t) }
        val k = min(1f, dt * 0.8f)
        for (i in cur.indices) cur[i] += (target[i] - cur[i]) * k
        val v = ::param
        val aspect = fw.toFloat() / fh

        // ---- Pass 0: flow & noise fields (tiny) ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fieldFbo[0])
        GLES30.glViewport(0, 0, gw, gh)
        val fp = fieldProg
        GLES30.glUseProgram(fp)
        u1f(fp, "ft", ft); u1f(fp, "aspect", aspect); u2f(fp, "seed", seedX, seedY)
        u1f(fp, "warpFreq", v("warpFreq")); u1f(fp, "warpSpeed", v("warpSpeed"))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)

        // ---- Pass 1: feedback ----
        val dst = 1 - src
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, fbo[dst])
        GLES30.glViewport(0, 0, fw, fh)
        val p = feedbackProg
        GLES30.glUseProgram(p)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE1)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, fieldTex[0])
        u1i(p, "field", 1)
        GLES30.glActiveTexture(GLES30.GL_TEXTURE0)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[src])
        u1i(p, "prev", 0)
        u2f(p, "res", fw.toFloat(), fh.toFloat())
        u1f(p, "t", tm); u1f(p, "ft", ft)
        u1f(p, "bass", bassEnv); u1f(p, "mid", midEnv); u1f(p, "treb", trebEnv)
        u1f(p, "beat", beat); u1f(p, "loud", loud); u1f(p, "swell", swell)
        u1f(p, "snare", snareEnv * snareDir); u1f(p, "hat", hatEnv); u1f(p, "tone", toneEnv)
        u1i(p, "flow", preset.flow)
        u1f(p, "zoom", 1f + (v("zoom") - 1f) * motion)
        u1f(p, "rot", v("rot") * motion)
        val trails = VizSettings.trails
        u1f(p, "decay", 1f - (1f - v("decay")) * (1.8f - trails * 1.35f))
        for (key in FEEDBACK_KEYS) u1f(p, key, v(key))
        u1f(p, "themeMix", (v("themeMix") * VizSettings.tint * 2f).coerceIn(0f, 1f))
        u3f(p, "pa", v("pa0"), v("pa1"), v("pa2")); u3f(p, "pb", v("pb0"), v("pb1"), v("pb2"))
        u3f(p, "pc", v("pc0"), v("pc1"), v("pc2")); u3f(p, "pd", v("pd0"), v("pd1"), v("pd2"))
        u1f(p, "hue", hue)
        val a = accent; u3f(p, "accent", a[0], a[1], a[2])
        GLES30.glUniform1fv(uniform(p, "spec"), 32, audio.spectrum, 0)
        GLES30.glUniform1fv(uniform(p, "wave"), 64, audio.wave, 0)
        GLES30.glUniform4fv(uniform(p, "imp"), 4, imp, 0)
        GLES30.glUniform1fv(uniform(p, "impS"), 4, impS, 0)
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        // Mipmaps of the new frame = progressively blurred copies, used for the bloom.
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[dst])
        GLES30.glGenerateMipmap(GLES30.GL_TEXTURE_2D)

        // ---- Pass 2: display ----
        GLES30.glBindFramebuffer(GLES30.GL_FRAMEBUFFER, 0)
        GLES30.glViewport(0, 0, sw, sh)
        val d = displayProg
        GLES30.glUseProgram(d)
        GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, tex[dst])
        u1i(d, "fb", 0)
        u2f(d, "texel", 1f / fw, 1f / fh)
        u3f(d, "bg", bg[0], bg[1], bg[2])
        u1f(d, "glow", VizSettings.glow)
        u1f(d, "beat", beat); u1f(d, "time", t)
        u1f(d, "exposure", 0.8f + 0.35f * loud + 0.18f * swell)
        u1f(d, "shimmer", 0.5f * trebEnv + hatEnv)
        u1f(d, "snare", snareEnv)
        val b = VizSettings.brightness
        u1f(d, "ceiling", 0.5f + 2.5f * b * b)
        u1i(d, "echoOrient", preset.echoOrient)
        for (key in DISPLAY_KEYS) u1f(d, key, v(key))
        GLES30.glDrawArrays(GLES30.GL_TRIANGLES, 0, 3)
        src = dst
    }

    /**
     * Next look: about a third of the time a freshly generated preset, otherwise a hand-made one that hasn't
     * been shown recently. Either way its details are re-rolled, so it never looks exactly the same twice.
     */
    private fun nextPreset(t: Float) {
        val team = VizPresets.gameDayPreset()
        val n = if (team != null && Random.nextFloat() < 0.7f) {
            team // game day: mostly the team's colours, each time re-rolled in its details
        } else if (Random.nextFloat() < 0.35f) {
            VizPresets.generate(Random)
        } else {
            val pool = VizPresets.all.filter { it.name != preset.name }
            pool.filter { it.name !in recent }.ifEmpty { pool }.random()
        }
        recent.addLast(n.name)
        while (recent.size > 8) recent.removeFirst()
        preset = n
        presetStart = t
        val params = VizPresets.vary(n, Random)
        for (i in keys.indices) target[i] = params.getValue(keys[i])
        seedX = Random.nextFloat() * 100f; seedY = Random.nextFloat() * 100f
        onPreset(n.name)
    }

    // ---- GL helpers ----
    private fun uniform(p: Int, name: String): Int {
        val m = when (p) { fieldProg -> fieldLoc; feedbackProg -> feedbackLoc; else -> displayLoc }
        return m.getOrPut(name) { GLES30.glGetUniformLocation(p, name) }
    }

    /** Index of each blended parameter (was a linear search through ~45 names, ~50 times a frame). */
    // (Not `HashMap().apply { keys... }`: inside apply, `keys` is the empty map's own key set.)
    private val keyIndex: Map<String, Int> = keys.withIndex().associate { (i, k) -> k to i }
    private fun param(key: String): Float = cur[keyIndex.getValue(key)]
    private fun u1f(p: Int, n: String, v: Float) = GLES30.glUniform1f(uniform(p, n), v)
    private fun u1i(p: Int, n: String, v: Int) = GLES30.glUniform1i(uniform(p, n), v)
    private fun u2f(p: Int, n: String, x: Float, y: Float) = GLES30.glUniform2f(uniform(p, n), x, y)
    private fun u3f(p: Int, n: String, x: Float, y: Float, z: Float) = GLES30.glUniform3f(uniform(p, n), x, y, z)

    private fun shader(type: Int, src: String): Int {
        val s = GLES30.glCreateShader(type)
        GLES30.glShaderSource(s, src)
        GLES30.glCompileShader(s)
        val ok = IntArray(1)
        GLES30.glGetShaderiv(s, GLES30.GL_COMPILE_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("shader: " + GLES30.glGetShaderInfoLog(s))
        return s
    }

    private fun program(vs: String, fs: String): Int {
        val p = GLES30.glCreateProgram()
        GLES30.glAttachShader(p, shader(GLES30.GL_VERTEX_SHADER, vs))
        GLES30.glAttachShader(p, shader(GLES30.GL_FRAGMENT_SHADER, fs))
        GLES30.glLinkProgram(p)
        val ok = IntArray(1)
        GLES30.glGetProgramiv(p, GLES30.GL_LINK_STATUS, ok, 0)
        if (ok[0] == 0) throw IllegalStateException("link: " + GLES30.glGetProgramInfoLog(p))
        return p
    }

    private companion object {
        const val FRAME_MS = 33L

        /** Uploaded as-is to the feedback shader (zoom/rot/decay/themeMix are adjusted separately). */
        val FEEDBACK_KEYS = listOf(
            "swirl", "warpAmp", "warpFreq", "warpSpeed", "advect", "cx", "cy", "punch",
            "wLine", "wDye", "wMarble", "wPlasma", "wCaustic", "wSilk", "wSplat",
        )
        val DISPLAY_KEYS = listOf("echoAlpha", "echoZoom", "solarize", "invertFlash", "flash", "gloss")

        const val VERT = """#version 300 es
out vec2 uv;
void main() {
    vec2 p = vec2(float((gl_VertexID << 1) & 2), float(gl_VertexID & 2));
    uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}"""

        /** Shared noise helpers. */
        const val NOISE = """
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
float noise(vec2 p) {
    vec2 i = floor(p), f = fract(p); f = f * f * (3.0 - 2.0 * f);
    return mix(mix(hash(i), hash(i + vec2(1.0, 0.0)), f.x), mix(hash(i + vec2(0.0, 1.0)), hash(i + vec2(1.0, 1.0)), f.x), f.y);
}
float fbm(vec2 p) {
    float s = 0.0, a = 0.5;
    for (int i = 0; i < 3; i++) { s += a * noise(p); p = p * 2.03 + vec2(1.7, 9.2); a *= 0.5; }
    return s / 0.875;
}
vec2 curl(vec2 p) {
    float e = 0.15;
    return vec2(noise(p + vec2(0.0, e)) - noise(p - vec2(0.0, e)), noise(p - vec2(e, 0.0)) - noise(p + vec2(e, 0.0))) / (2.0 * e);
}
"""

        /** Pass 0: fluid velocity (rg, encoded 0..1) + marbling field (b) + silk field (a). */
        const val FIELD_FRAG = """#version 300 es
precision highp float;
in vec2 uv;
out vec4 o;
uniform float ft, aspect, warpFreq, warpSpeed;
uniform vec2 seed;
""" + NOISE + """
void main() {
    vec2 p = uv - 0.5; p.x *= aspect;
    vec2 sd = seed;
    // Domain-warped curl noise: no sources or sinks, so colour is stirred like paint, never sucked into
    // (or sprayed out of) a centre.
    vec2 fq = p * warpFreq + vec2(ft * warpSpeed * 0.06, -ft * warpSpeed * 0.045) + sd;
    vec2 w = vec2(fbm(fq + vec2(0.0, ft * 0.03)), fbm(fq + vec2(5.2, 1.3) - ft * 0.025));
    vec2 vel = curl(fq + 2.0 * w);
    float marble = fbm(p * 1.6 + vec2(ft * 0.05, -ft * 0.04) + sd.yx);
    float silk = fbm(p * 2.2 + vec2(ft * 0.03, ft * 0.08) - sd);
    o = vec4(clamp(vel * 0.125 + 0.5, 0.0, 1.0), marble, silk);
}"""

        const val FEEDBACK_FRAG = """#version 300 es
precision highp float;
in vec2 uv;
out vec4 o;
uniform sampler2D prev;
uniform sampler2D field;
uniform vec2 res;
uniform float t, ft, bass, mid, treb, beat, loud, swell;
uniform float snare, hat, tone; // snare is signed: the twist direction alternates
uniform int flow;
uniform float zoom, rot, swirl, warpAmp, warpFreq, warpSpeed, decay, advect, cx, cy, punch;
uniform float wLine, wDye, wMarble, wPlasma, wCaustic, wSilk, wSplat;
uniform vec3 pa, pb, pc, pd, accent;
uniform float themeMix, hue;
uniform float spec[32];
uniform float wave[64];
uniform vec4 imp[4];
uniform float impS[4];

const float TAU = 6.2831853;
vec3 pal(float x) {
    vec3 c = pa + pb * cos(TAU * (pc * x + pd + hue));
    return mix(c, accent * (0.55 + 0.65 * c), themeMix);
}
""" + NOISE + """
vec2 rot2(vec2 p, float a) { float c = cos(a), s = sin(a); return vec2(c * p.x - s * p.y, s * p.x + c * p.y); }
float waveAt(float x) { x = clamp(x, 0.0, 0.999) * 63.0; int i = int(x); return mix(wave[i], wave[min(i + 1, 63)], fract(x)); }
float bandAvg(int i) { return (spec[i * 8] + spec[i * 8 + 2] + spec[i * 8 + 4] + spec[i * 8 + 6]) * 0.25; }

void main() {
    float aspect = res.x / res.y;
    vec2 center = vec2(cx, cy);
    vec2 p = uv - center; p.x *= aspect;
    vec4 F = texture(field, uv);

    // ---- Motion: where does this pixel's colour come from in the previous frame? ----
    float r = length(p);
    float dir = zoom >= 1.0 ? 1.0 : -1.0;
    p /= zoom + dir * punch * (0.03 * bass + 0.035 * beat);
    // Snares/claps give the flow a quick twist, strongest in the middle, alternating direction.
    p = rot2(p, rot * (1.0 + 0.6 * mid) + swirl * (0.35 - r) + 0.045 * snare * max(0.0, 0.6 - r));
    if (flow == 1) {
        // Smoky curl-noise flow (the original Liquid Chrome motion).
        p += warpAmp * (0.7 + 0.5 * mid) * 4.0 * curl(p * warpFreq + vec2(t * warpSpeed * 0.3, -t * warpSpeed * 0.2));
    } else if (flow == 7) {
        // Fluid: velocity from the field pass. Kicks surge it, beats swell it.
        vec2 vel = (F.xy - 0.5) * 8.0;
        // Busy mids (melody, vocals) stir it up too.
        p += warpAmp * (0.4 + 0.35 * loud + 1.4 * bass + 0.9 * swell + 0.45 * mid) * vel;
    } else {
        float amp = warpAmp * (0.7 + 0.5 * mid);
        p += amp * vec2(sin(p.y * warpFreq + t * warpSpeed), cos(p.x * warpFreq * 1.13 - t * warpSpeed * 0.87));
    }
    p.y -= advect * (0.004 + 0.008 * bass);
    vec2 suv = vec2(p.x / aspect, p.y) + center;
    vec3 c = max(textureLod(prev, suv, 0.0).rgb * decay - 0.0015, 0.0);

    // ---- New colour, painted in ----
    vec2 q = uv - 0.5; q.x *= aspect;
    vec3 add = vec3(0.0);
    // Mids nudge the colour along the palette; brighter vocals/synths move it one way, darker the other.
    float tint = 0.06 * mid + 0.12 * tone;

    if (wLine > 0.001) {
        float y = waveAt(uv.x) * (0.22 + 0.2 * bass);
        add += wLine * pal(uv.x + t * 0.1) * exp(-abs(q.y - y) * 140.0);
    }
    if (wDye > 0.001) {
        // Four drops of dye wandering the whole screen, each fed by its own part of the music
        // (bass, low mids, mids, highs): a busier band = bigger, brighter drop.
        for (int i = 0; i < 4; i++) {
            float fi = float(i);
            vec2 dp = vec2(sin(ft * (0.13 + 0.031 * fi) + fi * 2.1 + 0.6 * sin(ft * 0.05 + fi)) * 0.45 * aspect,
                           sin(ft * (0.11 + 0.027 * fi) + fi * 4.7 + 0.7 * sin(ft * 0.043 + fi * 1.3)) * 0.42);
            float s = bandAvg(i);
            s = s * s * 1.6;
            float rad = 0.045 + 0.09 * s + 0.03 * swell;
            add += wDye * pal(fi * 0.12 + tint) * exp(-dot(q - dp, q - dp) / (rad * rad)) * (0.02 + 0.22 * s);
        }
    }
    if (wSplat > 0.001) {
        // Beat blooms: a burst of colour at a random spot on each beat, carried off by the flow.
        for (int i = 0; i < 4; i++) {
            float age = imp[i].z;
            if (age < 1.0) {
                vec2 ip = (imp[i].xy - 0.5) * vec2(aspect, 1.0);
                float rad = 0.035 + 0.13 * (1.0 - exp(-age * 5.0));
                float a = impS[i] * exp(-age / 0.18);
                add += wSplat * pal(imp[i].w + tint) * exp(-dot(q - ip, q - ip) / (rad * rad)) * a * 0.16;
            }
        }
    }
    if (wMarble > 0.001) {
        // Marbling veins; each vein is tuned to its own frequency band, so the music lights them up.
        float f = F.b;
        float vein = exp(-abs(fract(f * 4.0 + ft * 0.03) - 0.5) * 34.0);
        float x = clamp(fract(f * 2.0), 0.0, 0.999) * 31.0;
        float s = mix(spec[int(x)], spec[min(int(x) + 1, 31)], fract(x));
        add += wMarble * pal(f * 0.5 + tint) * vein * (0.008 + 0.13 * s * s + 0.05 * swell + 0.06 * abs(snare));
    }
    if (wPlasma > 0.001) {
        // A faint wash of colour everywhere, so the screen stays full; breathes with the song.
        add += wPlasma * pal(F.b * 0.8 + tint) * (0.004 + 0.02 * loud + 0.015 * swell);
    }
    if (wSilk > 0.001) {
        // Silk threads (one contour of a moving noise field) that shimmer with the highs.
        float n = F.a;
        float th = exp(-abs(n - 0.5) * 80.0);
        add += wSilk * pal(n * 0.8 + 0.2 + tint) * th * (0.03 + 0.07 * treb + 0.22 * hat + 0.08 * loud + 0.12 * swell);
    }
    if (wCaustic > 0.001) {
        // Light through rippling water (tileable water-caustic pattern). The coordinates sit far from zero
        // (-250) so the divisions below can never blow up; the ripples quicken and brighten with the music.
        vec2 cp = mod(q * 3.0 + vec2(ft * 0.04, ft * 0.03), TAU) - 250.0;
        vec2 i2 = cp; float cc = 1.0;
        for (int n = 0; n < 4; n++) {
            float tt = ft * 0.35 * (1.0 - 3.5 / float(n + 1));
            i2 = cp + vec2(cos(tt - i2.x) + sin(tt + i2.y), sin(tt - i2.y) + cos(tt + i2.x));
            cc += 1.0 / length(vec2(cp.x / (sin(i2.x + tt) / 0.005), cp.y / (cos(i2.y + tt) / 0.005)));
        }
        cc = 1.17 - pow(clamp(cc / 4.0, 0.0, 1.17), 1.4);
        float caust = min(pow(abs(cc), 8.0), 1.5);
        add += wCaustic * pal(0.5 + q.x * 0.15 + tint) * caust * (0.02 + 0.08 * loud + 0.12 * swell + 0.04 * treb + 0.12 * hat);
    }
    // Safety net: half-float buffers keep NaN/Inf forever (8-bit ones used to clamp them to black), and the
    // flow + bloom would smear them across the screen. Replace anything invalid with black, and cap
    // brightness like the old 8-bit buffers so nothing blows out.
    vec3 res3 = c + add;
    if (any(isnan(res3)) || any(isinf(res3))) res3 = vec3(0.0);
    o = vec4(clamp(res3, 0.0, 1.0), 1.0);
}"""

        const val DISPLAY_FRAG = """#version 300 es
precision highp float;
in vec2 uv;
out vec4 o;
uniform sampler2D fb;
uniform vec2 texel;
uniform vec3 bg;
uniform float ceiling;
uniform float glow, beat, time, exposure, shimmer, snare, echoAlpha, echoZoom, solarize, invertFlash, flash, gloss;
uniform int echoOrient;
float lum(vec3 c) { return dot(c, vec3(0.299, 0.587, 0.114)); }
float hash(vec2 p) { p = fract(p * vec2(123.34, 456.21)); p += dot(p, p + 45.32); return fract(p.x * p.y); }
void main() {
    vec3 c = textureLod(fb, uv, 0.0).rgb;
    if (echoAlpha > 0.001) {
        vec2 e = (uv - 0.5) / max(echoZoom, 0.2);
        if (echoOrient == 1 || echoOrient == 3) e.x = -e.x;
        if (echoOrient == 2 || echoOrient == 3) e.y = -e.y;
        c = mix(c, max(c, textureLod(fb, e + 0.5, 0.0).rgb), echoAlpha);
    }
    // Gloss: light the paint like a wet surface (brightness as height), soft key light from the top left.
    if (gloss > 0.001) {
        vec2 dx = vec2(texel.x * 2.0, 0.0), dy = vec2(0.0, texel.y * 2.0);
        float hL = lum(textureLod(fb, uv - dx, 1.0).rgb), hR = lum(textureLod(fb, uv + dx, 1.0).rgb);
        float hD = lum(textureLod(fb, uv - dy, 1.0).rgb), hU = lum(textureLod(fb, uv + dy, 1.0).rgb);
        vec3 n = normalize(vec3((hL - hR) * 5.0, (hD - hU) * 5.0, 1.0));
        vec3 L = normalize(vec3(-0.45, 0.55, 0.7));
        float diff = clamp(dot(n, L), 0.0, 1.0);
        float sp = pow(clamp(dot(reflect(-L, n), vec3(0.0, 0.0, 1.0)), 0.0, 1.0), 28.0);
        c = c * mix(1.0, 0.65 + 0.5 * diff, gloss) + sp * gloss * (0.25 + 0.35 * shimmer + 0.45 * snare) * (0.4 + c);
    }
    // Bloom: wide, soft light from the blurred mip levels.
    vec3 b = textureLod(fb, uv, 2.5).rgb * 0.55 + textureLod(fb, uv, 4.5).rgb * 0.45;
    c += b * glow * 0.85;
    c *= exposure * (1.0 + flash * beat * 0.35 + 0.05 * snare);
    c = mix(c, 1.0 - abs(1.0 - 2.0 * clamp(c, 0.0, 1.0)), solarize);
    c = mix(c, 1.0 - clamp(c, 0.0, 1.0), invertFlash * beat);
    // Brightness cap (slider): bright parts are eased down towards the ceiling, keeping their colour, so a
    // loud section can't blow the screen out to white. Dark and mid tones are barely touched.
    c = max(c, 0.0);
    float L = lum(c);
    if (L > 1e-4) c *= ceiling * (1.0 - exp(-L / ceiling)) / L;
    // Filmic tone curve: rich mids, highlights roll off softly instead of clipping.
    c = (c * (2.51 * c + 0.03)) / (c * (2.43 * c + 0.59) + 0.14);
    float v = smoothstep(1.2, 0.3, length(uv - 0.5) * 1.4);
    c = mix(bg, c, 0.6 + 0.4 * v);
    c += (hash(uv * 1000.0 + fract(time)) - 0.5) / 255.0; // dither: no banding in dark gradients
    o = vec4(c, 1.0);
}"""
    }
}
