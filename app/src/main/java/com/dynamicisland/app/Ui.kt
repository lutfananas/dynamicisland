package com.dynamicisland.app

import android.content.Context
import android.graphics.Outline
import android.graphics.drawable.GradientDrawable
import android.view.View
import android.view.ViewOutlineProvider

fun Context.dp(v: Float): Int = (v * resources.displayMetrics.density + 0.5f).toInt()
fun Context.dp(v: Int): Int = dp(v.toFloat())

fun roundBg(color: Int, radius: Float) = GradientDrawable().apply {
    setColor(color)
    cornerRadius = radius
}

fun roundOutline(radius: Float) = object : ViewOutlineProvider() {
    override fun getOutline(view: View, outline: Outline) {
        outline.setRoundRect(0, 0, view.width, view.height, radius)
    }
}

object Palette {
    const val WHITE = 0xFFFFFFFF.toInt()
    const val SECONDARY = 0xB3FFFFFF.toInt()
    const val FAINT = 0x26FFFFFF
    const val ORANGE = 0xFFFF9F0A.toInt()
    const val GREEN = 0xFF30D158.toInt()
    const val RED = 0xFFFF453A.toInt()
}

fun formatMs(ms: Long): String {
    val total = (ms / 1000).coerceAtLeast(0)
    val h = total / 3600
    val m = (total % 3600) / 60
    val s = total % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}
