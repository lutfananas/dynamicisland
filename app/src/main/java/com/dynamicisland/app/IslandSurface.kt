package com.dynamicisland.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.SweepGradient
import android.view.animation.Interpolator
import android.widget.FrameLayout
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Isi jendela island. Bentuk island (latar, garis edit, cahaya) digambar langsung di kanvas,
 * sehingga animasi ukuran hanya memicu redraw GPU — tanpa measure/layout ulang tiap frame.
 * Ini yang membuat animasi bisa berjalan mulus di 90/120 Hz.
 */
class IslandSurface(ctx: Context) : FrameLayout(ctx) {
    /** Ruang di sekeliling island untuk efek cahaya. */
    val pad = ctx.dp(10)

    /** Pergeseran horizontal pusat island dari tengah layar (px). */
    var centerOffset = 0f
    var shapeW = 1f
    var shapeH = 1f
    var radius = 0f
    var fillColor = Color.BLACK
    var strokeColor = 0
    var strokeWidth = 0f

    var glowAlpha = 0f
    var glowAngle = 0f
    var glowColor = Color.WHITE
    var glowRainbow = false
    var glowWidth = 6f

    private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val glow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val rect = RectF()
    private val matrix = Matrix()
    private var shader: SweepGradient? = null
    private var shaderKey = ""

    init {
        setWillNotDraw(false)
        clipChildren = false
    }

    fun shapeLeft() = width / 2f + centerOffset - shapeW / 2f

    override fun onDraw(canvas: Canvas) {
        val l = shapeLeft()
        val t = pad.toFloat()
        rect.set(l, t, l + shapeW, t + shapeH)
        fill.color = fillColor
        canvas.drawRoundRect(rect, radius, radius, fill)
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        val l = shapeLeft()
        val t = pad.toFloat()
        if (strokeWidth > 0f) {
            line.color = strokeColor
            line.strokeWidth = strokeWidth
            rect.set(l, t, l + shapeW, t + shapeH)
            canvas.drawRoundRect(rect, radius, radius, line)
        }
        if (glowAlpha > 0.01f) drawGlow(canvas, l, t)
    }

    private fun drawGlow(canvas: Canvas, l: Float, t: Float) {
        val gap = glowWidth * 0.6f
        rect.set(l - gap, t - gap, l + shapeW + gap, t + shapeH + gap)
        val rr = radius + gap
        val key = "$glowColor|$glowRainbow"
        val sh = shader.takeIf { key == shaderKey } ?: makeShader().also { shader = it; shaderKey = key }
        matrix.setRotate(glowAngle)
        matrix.postTranslate(rect.centerX(), rect.centerY())
        sh.setLocalMatrix(matrix)
        glow.shader = sh
        // Tiga lapisan: halo lebar & redup, tengah, lalu garis inti yang terang.
        for ((k, a) in GLOW_PASSES) {
            glow.strokeWidth = glowWidth * k
            glow.alpha = (255 * a * glowAlpha).toInt().coerceIn(0, 255)
            canvas.drawRoundRect(rect, rr, rr, glow)
        }
    }

    private fun makeShader(): SweepGradient {
        if (glowRainbow) {
            return SweepGradient(
                0f, 0f,
                intArrayOf(
                    0xFFFF375F.toInt(), 0xFFFF9F0A.toInt(), 0xFFFFD60A.toInt(), 0xFF30D158.toInt(),
                    0xFF64D2FF.toInt(), 0xFF0A84FF.toInt(), 0xFFBF5AF2.toInt(), 0xFFFF375F.toInt(),
                ),
                null,
            )
        }
        val c0 = glowColor and 0x00FFFFFF
        // Seperti komet: ekor memudar, kepala terang dengan ujung putih.
        return SweepGradient(
            0f, 0f,
            intArrayOf(c0, c0, c0 or 0x55000000, glowColor, Color.WHITE, c0),
            floatArrayOf(0f, 0.4f, 0.78f, 0.95f, 0.985f, 1f),
        )
    }

    private companion object {
        val GLOW_PASSES = listOf(3.4f to 0.16f, 1.9f to 0.35f, 1f to 1f)
    }
}

/**
 * Pegas teredam (damped spring). damping < 1 memberi sedikit pantulan;
 * mendekati 1 = melambat mulus tanpa memantul. Nilai di t = 1 tepat 1.
 */
class SpringInterpolator(damping: Float) : Interpolator {
    private val z = damping.coerceIn(0.3f, 0.98f)
    private val w0 = 6.4f / z
    private val wd = w0 * sqrt(1 - z * z)

    private fun raw(t: Float): Float {
        val e = exp(-z * w0 * t)
        return 1f - e * (cos(wd * t) + (z * w0 / wd) * sin(wd * t))
    }

    // Koreksi linear kecil agar kurva berakhir tepat di 1 (tanpa lompatan di frame terakhir).
    private val endError = 1f - raw(1f)

    override fun getInterpolation(t: Float): Float {
        if (t >= 1f) return 1f
        return raw(t) + endError * t
    }
}
