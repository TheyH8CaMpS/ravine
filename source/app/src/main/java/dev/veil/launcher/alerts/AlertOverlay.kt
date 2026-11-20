package dev.veil.launcher.alerts

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import dev.veil.launcher.R

/**
 * The alert chip (PROJECT.md D52): top right, over whatever is on screen, for 6 seconds. Drawn as an
 * accessibility overlay, so no extra permission is needed; it never takes focus or touches.
 */
class AlertOverlay(private val service: AccessibilityService) {
    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val main = Handler(Looper.getMainLooper())
    private var view: View? = null
    private val queue = ArrayDeque<ScoreAlerts.Alert>()

    private fun dp(v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, service.resources.displayMetrics)

    fun show(a: ScoreAlerts.Alert) {
        if (view != null) { queue.addLast(a); return }
        val chip = chip(service, a)
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT,
        ).apply { gravity = Gravity.TOP or Gravity.END; x = dp(32f).toInt(); y = dp(26f).toInt() }
        runCatching { wm.addView(chip, lp) }.onFailure { return }
        view = chip
        chip.alpha = 0f; chip.translationX = dp(18f)
        chip.animate().alpha(1f).translationX(0f).setDuration(320).start()
        main.postDelayed({
            chip.animate().alpha(0f).setDuration(380).withEndAction {
                runCatching { wm.removeView(chip) }
                view = null
                queue.removeFirstOrNull()?.let { next -> main.postDelayed({ show(next) }, 1_500) }
            }.start()
        }, 6_000)
    }

    companion object {
        private fun dp(c: Context, v: Float) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, c.resources.displayMetrics)

        /** The chip itself (also drawn by the previews). */
        fun chip(context: Context, a: ScoreAlerts.Alert): View {
        val color = (a.color and 0xFFFFFFFF).toInt()
            val chip = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                setPadding(dp(context, 8f).toInt(), dp(context, 7f).toInt(), dp(context, 16f).toInt(), dp(context, 7f).toInt())
                background = GradientDrawable().apply { setColor(0xD90D110E.toInt()); cornerRadius = dp(context, 30f) }
                elevation = dp(context, 6f)
            }
            val logo = ImageView(context).apply {
                layoutParams = LinearLayout.LayoutParams(dp(context, 18f).toInt(), dp(context, 18f).toInt()).apply { marginEnd = dp(context, 9f).toInt() }
                background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(color) }
            }
            a.logo?.let { url ->
                coil.Coil.imageLoader(context).enqueue(
                    coil.request.ImageRequest.Builder(context).data(url).size(96, 96).target(
                        onSuccess = { d -> logo.background = null; logo.setImageDrawable(d) },
                    ).build(),
                )
            }
            val text = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
            text.addView(TextView(context).apply {
                this.text = a.eyebrow
                setTextColor(lighten(color))
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 6.5f)
                letterSpacing = 0.14f
                typeface = ResourcesCompat.getFont(context, R.font.manrope)?.let { android.graphics.Typeface.create(it, android.graphics.Typeface.BOLD) }
            })
            text.addView(TextView(context).apply {
                this.text = a.line
                setTextColor(0xFFEDE7DA.toInt())
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                typeface = ResourcesCompat.getFont(context, R.font.instrument_serif)
            })
            chip.addView(logo); chip.addView(text)
            return chip
        }

        /** Team colours as text on the dark chip: lifted toward white so dark navy or black stays readable. */
        private fun lighten(c: Int): Int {
            val r = (c shr 16) and 0xFF; val g = (c shr 8) and 0xFF; val b = c and 0xFF
            val lum = (0.2126 * r + 0.7152 * g + 0.0722 * b) / 255
            if (lum >= 0.45) return c or 0xFF000000.toInt()
            val t = 0.55
            fun m(x: Int) = (x + (255 - x) * t).toInt()
            return (0xFF shl 24) or (m(r) shl 16) or (m(g) shl 8) or m(b)
        }
    }

    fun clear() {
        queue.clear()
        view?.let { runCatching { wm.removeView(it) } }
        view = null
    }

}
