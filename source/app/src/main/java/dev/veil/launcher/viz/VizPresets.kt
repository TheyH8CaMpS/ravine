package dev.veil.launcher.viz

/**
 * A visualizer preset, MilkDrop-style.
 *  - [flow]: the motion field that carries the previous frame along (see FLOW_* below).
 *  - [p]: smoothly blended parameters (motion, fade, colour sources, finishing, palette). Keys are the shader
 *    uniform names; anything missing falls back to [DEFAULTS].
 *  - Discrete settings ([flow], [echoOrient]) switch when the preset changes.
 * Palette: a + b·cos(2π(c·x + d)) with vec3 a,b,c,d. Keep `c` low (≈0.5) so a preset stays within one
 * colour family instead of cycling through the rainbow.
 */
class VizPreset(
    val name: String,
    val flow: Int = FLOW_WAVE,
    val echoOrient: Int = 0, // 0 none, 1 mirror left/right, 2 mirror up/down, 3 both
    vararg params: Pair<String, Float>,
) {
    val p: Map<String, Float> = DEFAULTS + params.toMap()

    companion object {
        const val FLOW_WAVE = 0
        const val FLOW_INK = 1      // smoky curl noise (Liquid Chrome's motion)
        const val FLOW_FLUID = 7    // full-screen stirred paint; kicks surge it, beats swell it

        /** Blended parameters, in the order the renderer uploads them. */
        val KEYS = listOf(
            // motion
            "zoom", "rot", "swirl", "warpAmp", "warpFreq", "warpSpeed", "decay", "advect", "cx", "cy", "punch",
            // colour sources
            "wLine", "wDye", "wMarble", "wPlasma", "wCaustic", "wSilk", "wSplat",
            // colour
            "themeMix", "pa0", "pa1", "pa2", "pb0", "pb1", "pb2", "pc0", "pc1", "pc2", "pd0", "pd1", "pd2",
            // finishing (display pass)
            "echoAlpha", "echoZoom", "solarize", "invertFlash", "flash", "gloss",
        )

        val DEFAULTS: Map<String, Float> = KEYS.associateWith { 0f } + mapOf(
            "zoom" to 1f, "warpFreq" to 6f, "warpSpeed" to 1f, "decay" to 0.96f, "cx" to 0.5f, "cy" to 0.5f,
            "themeMix" to 0.25f, "echoZoom" to 1f, "flash" to 0f, "punch" to 0f,
            "pa0" to .5f, "pa1" to .5f, "pa2" to .5f, "pb0" to .5f, "pb1" to .5f, "pb2" to .5f,
            "pc0" to 1f, "pc1" to 1f, "pc2" to 1f, "pd0" to 0f, "pd1" to .33f, "pd2" to .67f,
        )

        /** Palette helper: a, b, c, d as 3-component arrays. */
        fun pal(a: FloatArray, b: FloatArray, c: FloatArray, d: FloatArray) = arrayOf(
            "pa0" to a[0], "pa1" to a[1], "pa2" to a[2], "pb0" to b[0], "pb1" to b[1], "pb2" to b[2],
            "pc0" to c[0], "pc1" to c[1], "pc2" to c[2], "pd0" to d[0], "pd1" to d[1], "pd2" to d[2],
        )
        fun v(x: Float, y: Float, z: Float) = floatArrayOf(x, y, z)
    }
}

object VizPresets {
    private val P = VizPreset

    private fun preset(
        name: String,
        vararg params: Pair<String, Float>,
        flow: Int = VizPreset.FLOW_FLUID,
        echoOrient: Int = 0,
        palette: Array<Pair<String, Float>> = emptyArray(),
    ) = VizPreset(name, flow, echoOrient, *(palette + params))

    /** The usual fluid motion: whole-screen, slow, music-driven. */
    private val FLUID = arrayOf("warpAmp" to 0.005f, "warpFreq" to 1.3f, "warpSpeed" to 0.6f)

    /**
     * Full-screen, smooth and flowing: nothing anchored to the centre, no hard flashes. The music drives the
     * flow speed (kicks surge it), a bloom of colour on every beat, the size of the dye drops, the shimmer on
     * threads/highlights and the overall brightness.
     */
    val all = listOf(
        // ---- Ravine: two-colour palettes (a ± b at the ends of the range; d = 0.5 starts at the dark one) ----
        // Forest floor to sunlit moss, in slow silk and dye.
        preset("Moss", *FLUID, "decay" to 0.98f,
            "wSilk" to 0.6f, "wDye" to 0.8f, "wSplat" to 0.5f, "gloss" to 0.3f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.315f, .405f, .25f), P.v(.235f, .245f, .15f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f))),
        // Redwood bark to canyon terracotta, polished like grain in wood.
        preset("Redwood", *FLUID, "warpFreq" to 1.2f, "decay" to 0.978f,
            "wMarble" to 0.9f, "wDye" to 0.4f, "wSplat" to 0.5f, "gloss" to 0.7f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.475f, .26f, .175f), P.v(.325f, .19f, .125f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f))),
        // Coastal fog rolling over charcoal hills.
        preset("Fog Line", *FLUID, "warpSpeed" to 0.45f, "decay" to 0.984f,
            "wDye" to 0.6f, "wSilk" to 0.5f, "wSplat" to 0.4f, "wPlasma" to 0.2f, "gloss" to 0.15f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.42f, .44f, .41f), P.v(.32f, .32f, .3f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f))),
        // Forest green warming into Ravine's brass.
        preset("Brass and Pine", *FLUID, "decay" to 0.978f,
            "wMarble" to 0.6f, "wDye" to 0.6f, "wSplat" to 0.5f, "gloss" to 0.8f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.43f, .38f, .255f), P.v(.36f, .28f, .175f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f))),
        // Dodger blue under stadium lights.
        preset("Dodger Blue", *FLUID, "decay" to 0.978f,
            "wSilk" to 0.6f, "wDye" to 0.7f, "wSplat" to 0.6f, "gloss" to 0.6f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.4f, .575f, .705f), P.v(.4f, .225f, .095f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f))),
        // Silver and black: polished, a chrome edge on every beat.
        preset("Silver and Black", *FLUID, "decay" to 0.98f,
            "wMarble" to 0.7f, "wDye" to 0.5f, "wSplat" to 0.6f, "solarize" to 0.3f, "gloss" to 1f, "themeMix" to 0.0f,
            palette = P.pal(P.v(.4f, .405f, .42f), P.v(.35f, .355f, .36f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f))),
        // Purple and gold (blue runs the other way so purple and gold sit at the two ends).
        preset("Purple and Gold", *FLUID, "decay" to 0.978f,
            "wDye" to 0.8f, "wMarble" to 0.4f, "wSplat" to 0.6f, "gloss" to 0.6f, "themeMix" to 0.0f,
            palette = P.pal(P.v(.64f, .45f, .35f), P.v(.31f, .3f, .15f), P.v(.5f, .5f, .5f), P.v(.5f, .5f, 0f))),
        // ---- Veil's originals ----
        // Molten metal: curl-noise flow, a scope line, solarized into chrome. (The original.)
        preset("Liquid Chrome",
            "warpAmp" to 0.004f, "warpFreq" to 3f, "warpSpeed" to 0.8f, "decay" to 0.965f, "wLine" to 1f,
            "solarize" to 0.55f, "themeMix" to 0.4f, "flash" to 0.4f, "punch" to 1f,
            flow = VizPreset.FLOW_INK, palette = P.pal(P.v(.55f, .55f, .6f), P.v(.45f, .45f, .45f), P.v(1f, 1f, 1f), P.v(0f, .1f, .2f))),
        // Liquid Chrome's big sibling: the whole screen is poured metal, veins catching the light.
        preset("Chrome Tide", *FLUID, "warpFreq" to 1.4f, "decay" to 0.972f,
            "wMarble" to 1f, "wDye" to 0.5f, "wSplat" to 0.6f, "solarize" to 0.45f, "gloss" to 0.5f, "themeMix" to 0.35f,
            palette = P.pal(P.v(.55f, .55f, .6f), P.v(.45f, .45f, .45f), P.v(1f, 1f, 1f), P.v(0f, .1f, .2f))),
        // Liquid silver: glossy mercury rolling and pooling, catching a soft key light.
        preset("Mercury", *FLUID, "warpFreq" to 1.1f, "decay" to 0.978f,
            "wDye" to 0.8f, "wSplat" to 0.8f, "wPlasma" to 0.3f, "gloss" to 1f, "themeMix" to 0.1f,
            palette = P.pal(P.v(.55f, .57f, .62f), P.v(.3f, .3f, .32f), P.v(.4f, .4f, .4f), P.v(0f, .02f, .06f))),
        // Soft clouds of colour drifting and folding into each other.
        preset("Nebula Drift", *FLUID, "warpFreq" to 1.2f, "decay" to 0.982f,
            "wDye" to 1f, "wPlasma" to 0.8f, "wSplat" to 0.5f, "gloss" to 0.2f,
            palette = P.pal(P.v(.5f, .4f, .6f), P.v(.4f, .3f, .35f), P.v(.6f, .6f, .6f), P.v(.65f, .8f, .95f))),
        // Fine silk threads curling across the screen.
        preset("Silk", *FLUID, "warpFreq" to 1.6f, "warpAmp" to 0.004f, "decay" to 0.975f,
            "wSilk" to 1f, "wDye" to 0.25f, "wSplat" to 0.4f, "gloss" to 0.35f, "themeMix" to 0.3f,
            palette = P.pal(P.v(.6f, .55f, .65f), P.v(.35f, .35f, .3f), P.v(.5f, .5f, .5f), P.v(.1f, .3f, .55f))),
        // Paper marbling: veins of colour stirred into swirls, each vein tuned to its own frequency.
        preset("Marble", *FLUID, "warpAmp" to 0.006f, "decay" to 0.978f,
            "wMarble" to 1f, "wPlasma" to 0.4f, "wSplat" to 0.5f, "gloss" to 0.6f,
            palette = P.pal(P.v(.55f, .5f, .45f), P.v(.4f, .35f, .3f), P.v(.7f, .7f, .7f), P.v(.05f, .2f, .35f))),
        // Black and gold marbling, polished.
        preset("Gold Leaf", *FLUID, "warpFreq" to 1.2f, "decay" to 0.978f,
            "wMarble" to 1f, "wSplat" to 0.5f, "wDye" to 0.3f, "gloss" to 1f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.55f, .42f, .2f), P.v(.45f, .35f, .18f), P.v(.5f, .5f, .5f), P.v(0f, .02f, .05f))),
        // Iridescent oil on water.
        preset("Oil Slick", *FLUID, "warpFreq" to 1.1f, "warpSpeed" to 0.5f, "decay" to 0.98f,
            "wMarble" to 0.8f, "wDye" to 0.4f, "wSplat" to 0.5f, "solarize" to 0.2f, "gloss" to 0.8f, "themeMix" to 0.1f,
            palette = P.pal(P.v(.5f, .5f, .5f), P.v(.5f, .5f, .5f), P.v(1f, 1f, 1f), P.v(0f, .33f, .67f))),
        // Light through rippling water, the ripples stirred by the music.
        preset("Caustics", *FLUID, "warpAmp" to 0.003f, "warpFreq" to 1.5f, "decay" to 0.94f,
            "wCaustic" to 1f, "wPlasma" to 0.5f, "wSplat" to 0.3f, "themeMix" to 0.2f,
            palette = P.pal(P.v(.35f, .55f, .65f), P.v(.3f, .35f, .35f), P.v(.5f, .5f, .5f), P.v(.4f, .5f, .6f))),
        // Big slow blobs of warm colour, like a lava lamp.
        preset("Lava Lamp", *FLUID, "warpAmp" to 0.004f, "warpFreq" to 0.9f, "warpSpeed" to 0.4f, "decay" to 0.986f,
            "advect" to 0.15f, "wDye" to 1f, "wSplat" to 0.6f, "gloss" to 0.7f,
            palette = P.pal(P.v(.55f, .35f, .3f), P.v(.45f, .35f, .3f), P.v(.5f, .5f, .5f), P.v(0f, .1f, .2f))),
        // Warm dusk colours drifting into each other.
        preset("Sunset Haze", *FLUID, "warpFreq" to 1.1f, "decay" to 0.982f,
            "wDye" to 1f, "wPlasma" to 0.6f, "wSplat" to 0.5f, "gloss" to 0.25f,
            palette = P.pal(P.v(.6f, .4f, .45f), P.v(.4f, .3f, .3f), P.v(.5f, .5f, .5f), P.v(0f, .12f, .35f))),
        // Deep red velvet folds.
        preset("Velvet", *FLUID, "warpFreq" to 1.2f, "decay" to 0.98f,
            "wDye" to 0.9f, "wPlasma" to 0.5f, "wSplat" to 0.6f, "gloss" to 0.6f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.45f, .15f, .2f), P.v(.35f, .15f, .2f), P.v(.5f, .5f, .5f), P.v(0f, .05f, .1f))),
        // Dark water with glowing blooms on every beat, like plankton lighting up.
        preset("Bioluminescence", *FLUID, "warpFreq" to 1.3f, "decay" to 0.975f,
            "wSplat" to 1f, "wSilk" to 0.5f, "wDye" to 0.4f, "gloss" to 0.3f, "themeMix" to 0.1f,
            palette = P.pal(P.v(.1f, .45f, .45f), P.v(.15f, .45f, .4f), P.v(.5f, .5f, .5f), P.v(.5f, .55f, .6f))),
        // Pale smoke curling through a dark room.
        preset("Smoke", *FLUID, "warpFreq" to 1.4f, "decay" to 0.978f,
            "wDye" to 0.6f, "wSilk" to 0.5f, "wSplat" to 0.5f, "wPlasma" to 0.2f, "gloss" to 0.2f, "themeMix" to 0.1f,
            palette = P.pal(P.v(.55f, .55f, .58f), P.v(.35f, .35f, .35f), P.v(.3f, .3f, .3f), P.v(0f, 0f, 0f))),
        // Northern lights: silk ribbons drifting slowly upward across the whole sky.
        preset("Aurora Flow", *FLUID, "warpAmp" to 0.004f, "warpSpeed" to 0.5f, "decay" to 0.978f,
            "advect" to 0.25f, "wSilk" to 0.9f, "wPlasma" to 0.4f, "wSplat" to 0.3f, "themeMix" to 0.3f,
            palette = P.pal(P.v(.4f, .6f, .5f), P.v(.4f, .4f, .5f), P.v(.6f, .6f, .6f), P.v(.3f, .5f, .8f))),
        // Deep ocean currents: dark blues with glowing threads.
        preset("Deep Current", *FLUID, "warpFreq" to 1.0f, "warpSpeed" to 0.45f, "decay" to 0.982f,
            "wDye" to 0.6f, "wSilk" to 0.6f, "wSplat" to 0.4f, "gloss" to 0.4f, "themeMix" to 0.15f,
            palette = P.pal(P.v(.2f, .35f, .5f), P.v(.25f, .3f, .35f), P.v(.5f, .5f, .5f), P.v(.5f, .6f, .7f))),
        // Fire: heat rising through smoke. Glowing flame-like threads curl upward, the glow swells on every beat.
        preset("Ember Rise",
            "warpAmp" to 0.005f, "warpFreq" to 3.5f, "warpSpeed" to 0.9f, "decay" to 0.968f, "advect" to 0.7f,
            "wSilk" to 0.8f, "wDye" to 0.7f, "wSplat" to 0.7f, "wPlasma" to 0.25f, "gloss" to 0.3f, "themeMix" to 0.1f,
            flow = VizPreset.FLOW_INK, palette = P.pal(P.v(.5f, .3f, .1f), P.v(.5f, .4f, .2f), P.v(.5f, .5f, .5f), P.v(0f, .1f, .2f))),
        // Ink dropped in water, mirrored left/right like a Rorschach test.
        preset("Ink Bloom",
            "warpAmp" to 0.008f, "warpFreq" to 2.5f, "warpSpeed" to 0.4f, "decay" to 0.985f,
            "wDye" to 0.7f, "wSplat" to 0.5f, "echoAlpha" to 0.45f, "themeMix" to 0.2f,
            flow = VizPreset.FLOW_INK, echoOrient = 1,
            palette = P.pal(P.v(.35f, .3f, .45f), P.v(.35f, .3f, .35f), P.v(.6f, .6f, .6f), P.v(.65f, .75f, .9f))),
        // Indigo ink spreading through dark water.
        preset("Midnight Ink", *FLUID, "decay" to 0.982f,
            "wDye" to 0.7f, "wSilk" to 0.3f, "wSplat" to 0.6f, "gloss" to 0.4f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.2f, .2f, .4f), P.v(.2f, .2f, .3f), P.v(.5f, .5f, .5f), P.v(.6f, .65f, .75f))),
        // Polished green stone, veined and glossy.
        preset("Emerald", *FLUID, "warpFreq" to 1.2f, "decay" to 0.978f,
            "wMarble" to 0.8f, "wSplat" to 0.5f, "gloss" to 0.8f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.15f, .4f, .3f), P.v(.15f, .35f, .25f), P.v(.5f, .5f, .5f), P.v(.3f, .35f, .4f))),
        // Deep purple crystal, slowly turning.
        preset("Amethyst", *FLUID, "decay" to 0.98f,
            "wDye" to 0.8f, "wSplat" to 0.5f, "gloss" to 0.7f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.35f, .2f, .45f), P.v(.25f, .15f, .3f), P.v(.5f, .5f, .5f), P.v(.75f, .8f, .9f))),
        // Grey ash drifting, with embers glowing on the beat.
        preset("Ash and Ember", *FLUID, "decay" to 0.978f, "advect" to 0.12f,
            "wDye" to 0.5f, "wSilk" to 0.4f, "wSplat" to 0.8f, "gloss" to 0.2f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.4f, .3f, .25f), P.v(.35f, .25f, .2f), P.v(.5f, .5f, .5f), P.v(0f, .05f, .1f))),
        // Shallow rock pools: dappled light over teal and green.
        preset("Tidepool", *FLUID, "decay" to 0.96f,
            "wCaustic" to 0.6f, "wDye" to 0.5f, "wSplat" to 0.4f, "gloss" to 0.3f,
            palette = P.pal(P.v(.25f, .45f, .45f), P.v(.25f, .3f, .3f), P.v(.5f, .5f, .5f), P.v(.35f, .45f, .5f))),
        // Pink and gold, polished like jewellery.
        preset("Rose Gold", *FLUID, "decay" to 0.978f,
            "wMarble" to 0.7f, "wDye" to 0.4f, "wSplat" to 0.5f, "gloss" to 1f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.65f, .45f, .42f), P.v(.3f, .25f, .2f), P.v(.5f, .5f, .5f), P.v(0f, .08f, .15f))),
        // Ice: pale blue silk under a cold light.
        preset("Glacier", *FLUID, "warpSpeed" to 0.45f, "decay" to 0.98f,
            "wSilk" to 0.7f, "wPlasma" to 0.5f, "wSplat" to 0.4f, "gloss" to 0.6f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.55f, .7f, .8f), P.v(.3f, .25f, .2f), P.v(.5f, .5f, .5f), P.v(.55f, .6f, .65f))),
        // Dusk to night: deep blue melting into a last orange glow.
        preset("Twilight", *FLUID, "decay" to 0.982f,
            "wDye" to 1f, "wPlasma" to 0.3f, "wSplat" to 0.5f, "gloss" to 0.3f,
            palette = P.pal(P.v(.35f, .25f, .4f), P.v(.35f, .25f, .3f), P.v(.5f, .5f, .5f), P.v(0f, .15f, .45f))),
        // Orange shapes gliding under rippling water.
        preset("Koi Pond", *FLUID, "decay" to 0.975f,
            "wDye" to 0.8f, "wCaustic" to 0.4f, "wSplat" to 0.5f, "gloss" to 0.5f,
            palette = P.pal(P.v(.5f, .4f, .35f), P.v(.45f, .3f, .3f), P.v(.5f, .5f, .5f), P.v(0f, .1f, .55f))),
        // Black glass: almost nothing but glossy highlights moving with the music.
        preset("Obsidian", *FLUID, "decay" to 0.982f,
            "wDye" to 0.7f, "wSplat" to 0.7f, "gloss" to 1f, "themeMix" to 0.15f,
            palette = P.pal(P.v(.14f, .14f, .17f), P.v(.12f, .12f, .14f), P.v(.5f, .5f, .5f), P.v(.6f, .62f, .7f))),
        // Weathered copper with verdigris veins.
        preset("Copper Patina", *FLUID, "decay" to 0.978f,
            "wMarble" to 0.8f, "wSplat" to 0.4f, "gloss" to 0.8f, "themeMix" to 0.05f,
            palette = P.pal(P.v(.4f, .35f, .3f), P.v(.3f, .25f, .2f), P.v(.6f, .6f, .6f), P.v(.05f, .35f, .4f))),
    )

    // ---- Endless variety ----------------------------------------------------------------------

    /** Curated colour families for generated presets. */
    class Palette(val name: String, val a: FloatArray, val b: FloatArray, val c: FloatArray, val d: FloatArray, val bright: Boolean)

    private val palettes = listOf(
        Palette("Indigo", P.v(.2f, .2f, .4f), P.v(.2f, .2f, .3f), P.v(.5f, .5f, .5f), P.v(.6f, .65f, .75f), false),
        Palette("Ember", P.v(.45f, .25f, .15f), P.v(.4f, .3f, .2f), P.v(.5f, .5f, .5f), P.v(0f, .08f, .15f), false),
        Palette("Jade", P.v(.15f, .4f, .32f), P.v(.15f, .3f, .25f), P.v(.5f, .5f, .5f), P.v(.3f, .35f, .4f), false),
        Palette("Plum", P.v(.35f, .2f, .4f), P.v(.25f, .15f, .3f), P.v(.5f, .5f, .5f), P.v(.75f, .82f, .9f), false),
        Palette("Ocean", P.v(.2f, .35f, .5f), P.v(.25f, .3f, .35f), P.v(.5f, .5f, .5f), P.v(.5f, .6f, .7f), false),
        Palette("Crimson", P.v(.45f, .15f, .2f), P.v(.35f, .15f, .2f), P.v(.5f, .5f, .5f), P.v(0f, .05f, .1f), false),
        Palette("Smoke", P.v(.4f, .4f, .43f), P.v(.3f, .3f, .3f), P.v(.3f, .3f, .3f), P.v(0f, 0f, 0f), false),
        Palette("Copper", P.v(.4f, .3f, .25f), P.v(.3f, .25f, .2f), P.v(.6f, .6f, .6f), P.v(.05f, .3f, .4f), false),
        Palette("Dusk", P.v(.35f, .25f, .4f), P.v(.35f, .25f, .3f), P.v(.5f, .5f, .5f), P.v(0f, .15f, .45f), false),
        Palette("Abyss", P.v(.1f, .3f, .35f), P.v(.12f, .3f, .3f), P.v(.5f, .5f, .5f), P.v(.5f, .55f, .6f), false),
        Palette("Gold", P.v(.6f, .48f, .25f), P.v(.4f, .32f, .18f), P.v(.5f, .5f, .5f), P.v(0f, .02f, .05f), true),
        Palette("Coral", P.v(.65f, .45f, .42f), P.v(.3f, .28f, .25f), P.v(.5f, .5f, .5f), P.v(0f, .1f, .2f), true),
        Palette("Frost", P.v(.55f, .68f, .8f), P.v(.3f, .25f, .2f), P.v(.5f, .5f, .5f), P.v(.55f, .6f, .65f), true),
        Palette("Silver", P.v(.55f, .57f, .62f), P.v(.3f, .3f, .32f), P.v(.4f, .4f, .4f), P.v(0f, .02f, .06f), true),
        Palette("Prism", P.v(.5f, .5f, .5f), P.v(.45f, .45f, .45f), P.v(1f, 1f, 1f), P.v(0f, .33f, .67f), true),
    )

    /**
     * A brand-new preset from the curated building blocks: one main texture, beat blooms, a curated palette,
     * random gloss and motion. Named after its palette and texture ("Indigo Silk", "Copper Marble"...).
     */
    fun generate(rnd: kotlin.random.Random): VizPreset {
        val pal = palettes.random(rnd)
        val textures = listOf("Drift" to "wDye", "Marble" to "wMarble", "Silk" to "wSilk", "Waters" to "wCaustic", "Clouds" to "wPlasma")
        val (noun, main) = textures.random(rnd)
        val params = mutableListOf(
            main to (if (main == "wPlasma") 1f else 0.7f + 0.3f * rnd.nextFloat()),
            "wSplat" to 0.35f + 0.5f * rnd.nextFloat(),
            "warpAmp" to 0.004f + 0.002f * rnd.nextFloat(),
            "warpFreq" to 0.9f + 0.7f * rnd.nextFloat(),
            "warpSpeed" to 0.4f + 0.4f * rnd.nextFloat(),
            "decay" to 0.975f + 0.009f * rnd.nextFloat(),
            "gloss" to rnd.nextFloat(),
            "themeMix" to 0.05f + 0.15f * rnd.nextFloat(),
        )
        // Clouds alone are too plain: always pair them with drops of dye.
        if (main == "wPlasma") params += "wDye" to 0.6f + 0.3f * rnd.nextFloat()
        // A second, quieter texture now and then.
        if (rnd.nextFloat() < 0.6f) {
            val second = textures.map { it.second }.filter { it != main && it != "wCaustic" && it != "wDye" }.random(rnd)
            params += second to 0.25f + 0.35f * rnd.nextFloat()
        }
        if (main == "wCaustic") params += "decay" to 0.955f + 0.015f * rnd.nextFloat()
        if (rnd.nextFloat() < 0.25f) params += "advect" to (rnd.nextFloat() - 0.3f) * 0.4f
        if (pal.bright && rnd.nextFloat() < 0.15f) params += "solarize" to 0.2f + 0.2f * rnd.nextFloat()
        return VizPreset(
            "${pal.name} $noun", VizPreset.FLOW_FLUID, 0,
            *(P.pal(pal.a, pal.b, pal.c, pal.d) + params.toTypedArray()),
        )
    }

    /** Subtle re-roll of a preset's look each time it comes up, so it never looks exactly the same. */
    fun vary(p: VizPreset, rnd: kotlin.random.Random): Map<String, Float> {
        val m = p.p.toMutableMap()
        fun jitter(k: String, amount: Float) { m[k] = m.getValue(k) * (1f + (rnd.nextFloat() * 2f - 1f) * amount) }
        if (p.flow == VizPreset.FLOW_FLUID) {
            jitter("warpFreq", 0.18f); jitter("warpAmp", 0.15f); jitter("warpSpeed", 0.2f)
        }
        // Nudge the colours within their family.
        val shift = (rnd.nextFloat() * 2f - 1f) * 0.06f
        for (k in listOf("pd0", "pd1", "pd2")) m[k] = m.getValue(k) + shift
        for (k in listOf("gloss", "wSplat", "wDye", "wSilk", "wMarble")) if (m.getValue(k) > 0f) jitter(k, 0.15f)
        return m
    }
}

