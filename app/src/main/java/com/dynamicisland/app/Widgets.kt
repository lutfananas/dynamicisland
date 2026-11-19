package com.dynamicisland.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.SystemClock
import android.view.View
import kotlin.math.abs
import kotlin.math.sin

/** Visualizer musik animasi (bar memantul) — tidak butuh izin mikrofon. */
class VisualizerView(ctx: Context) : View(ctx) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Palette.WHITE }
    private val phases = floatArrayOf(0f, 1.7f, 3.1f, 4.4f)

    var color: Int = Palette.WHITE
        set(v) { field = v; paint.color = v; invalidate() }
    var playing = false
        set(v) { field = v; invalidate() }

    override fun onDraw(canvas: Canvas) {
        val n = 4
        val gap = width * 0.12f
        val bw = (width - gap * (n - 1)) / n
        val t = SystemClock.uptimeMillis() / 1000f
        for (i in 0 until n) {
            val amp = if (playing) {
                0.22f + 0.78f * abs(sin(t * (4.5f + i * 1.3f) + phases[i])) *
                    (0.55f + 0.45f * abs(sin(t * 1.7f + i)))
            } else 0.18f
            val bh = height * amp
            val x = i * (bw + gap)
            val top = (height - bh) / 2f
            canvas.drawRoundRect(x, top, x + bw, top + bh, bw / 2, bw / 2, paint)
        }
        // ~30 fps sudah cukup halus untuk bar visualizer; jauh lebih hemat daripada tiap frame (120 fps).
        if (playing && isShown && screenOn) postInvalidateDelayed(FRAME_MS)
    }

    companion object {
        private const val FRAME_MS = 33L

        @Volatile
        var screenOn = true
    }
}

/** Ikon baterai kecil bergaya iOS. */
class BatteryView(ctx: Context, private val level: Int, private val fill: Int) : View(ctx) {
    private val stroke = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = 0x80FFFFFF.toInt()
    }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun onDraw(canvas: Canvas) {
        val h = height.toFloat()
        val nub = width * 0.08f
        val bodyW = width - nub - h * 0.06f
        val sw = h * 0.09f
        stroke.strokeWidth = sw
        val r = h * 0.28f
        canvas.drawRoundRect(sw / 2, sw / 2, bodyW - sw / 2, h - sw / 2, r, r, stroke)
        val inset = sw * 1.8f
        val innerW = (bodyW - inset * 2) * level.coerceIn(0, 100) / 100f
        solid.color = fill
        canvas.drawRoundRect(inset, inset, inset + innerW, h - inset, r * 0.6f, r * 0.6f, solid)
        solid.color = 0x80FFFFFF.toInt()
        canvas.drawRoundRect(bodyW + h * 0.06f, h * 0.32f, width.toFloat(), h * 0.68f, nub / 2, nub / 2, solid)
    }
}
