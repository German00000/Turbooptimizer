package com.turbooptimizer.game

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import kotlin.math.abs

data class OverlayStats(
    val fps: String,
    val temp: String,
    val ram: String,
    val cpu: String,
    val battery: String,
    val time: String,
)

/**
 * Panel flotante minimalista (TYPE_APPLICATION_OVERLAY, no enfocable).
 * - Arrastrar: mover.  - Toque: alternar compacto/completo.  - «×»: ocultar por completo.
 * Usa Views clásicas (sin Compose) para ser ligero y no depender de un ciclo de vida.
 */
class OverlayController(private val context: Context, private val onHideRequested: () -> Unit) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var text: TextView? = null
    private var compact = false
    private var last: OverlayStats? = null

    private val params = WindowManager.LayoutParams(
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.WRAP_CONTENT,
        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
        PixelFormat.TRANSLUCENT,
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = dp(12)
        y = dp(80)
    }

    val isShowing: Boolean get() = root != null

    private fun dp(v: Int) = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics).toInt()

    @SuppressLint("ClickableViewAccessibility", "SetTextI18n")
    fun show() {
        if (root != null || !Settings.canDrawOverlays(context)) return
        val tv = TextView(context).apply {
            setTextColor(Color.WHITE)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 11f)
            typeface = Typeface.MONOSPACE
            setLineSpacing(0f, 1.1f)
            text = "TurboOptimizer"
        }
        val close = TextView(context).apply {
            setTextColor(Color.parseColor("#B0FFFFFF"))
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f)
            text = "  ×"
            contentDescription = "Ocultar overlay"
            setOnClickListener { onHideRequested() }
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(10), dp(6), dp(8), dp(6))
            background = GradientDrawable().apply {
                cornerRadius = dp(12).toFloat()
                setColor(Color.parseColor("#B30B0F14"))
                setStroke(dp(1), Color.parseColor("#6600D1B2"))
            }
            addView(tv)
            addView(close)
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        var moved = false
        layout.setOnTouchListener { v, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = params.x; startY = params.y; moved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - downX
                    val dy = e.rawY - downY
                    if (abs(dx) > dp(4) || abs(dy) > dp(4)) moved = true
                    if (moved) {
                        params.x = startX + dx.toInt()
                        params.y = startY + dy.toInt()
                        runCatching { wm.updateViewLayout(v, params) }
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!moved) {
                        compact = !compact
                        last?.let { render(it) }
                        v.performClick()
                    }
                    true
                }
                else -> false
            }
        }
        runCatching { wm.addView(layout, params) }.onSuccess {
            root = layout
            text = tv
            last?.let { render(it) }
        }
    }

    fun update(stats: OverlayStats) {
        last = stats
        render(stats)
    }

    private fun render(s: OverlayStats) {
        val tv = text ?: return
        tv.text = if (compact) "${s.fps} · ${s.temp}"
        else "FPS ${s.fps}   ${s.time}\nTemp ${s.temp}  RAM ${s.ram}\nCPU ${s.cpu}  BAT ${s.battery}"
    }

    fun hide() {
        root?.let { v -> runCatching { wm.removeViewImmediate(v) } }
        root = null
        text = null
    }
}
