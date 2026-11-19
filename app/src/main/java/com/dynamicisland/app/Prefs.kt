package com.dynamicisland.app

import android.content.Context

object K {
    // Tampilan
    const val WIDTH = "width"
    const val HEIGHT = "height"
    const val OFFSET_X = "offset_x"
    const val OFFSET_Y = "offset_y"
    const val RADIUS = "radius"
    const val EXP_RADIUS = "exp_radius"
    const val EXP_WIDTH = "exp_width"
    const val COMPACT_EXTRA = "compact_extra"
    const val OPACITY = "opacity"
    const val BG_COLOR = "bg_color"
    const val ACCENT = "accent"
    const val ALBUM_COLOR = "album_color"

    // Animasi
    const val ANIM_MS = "anim_ms"
    const val BOUNCE = "bounce"

    // Perilaku
    const val NOTIF_TIMEOUT = "notif_timeout"
    const val SHOW_NOTIF = "show_notif"
    const val SHOW_MEDIA = "show_media"
    const val CHARGE_POPUP = "charge_popup"
    const val CHARGE_COMPACT = "charge_compact"
    const val LOW_BATT = "low_batt"
    const val RINGER = "ringer"
    const val OTP = "otp"
    const val REPLY = "reply"
    const val HAPTICS = "haptics"
    const val HIDE_LANDSCAPE = "hide_landscape"
    const val HIDE_IDLE = "hide_idle"
    const val LOCKSCREEN = "lockscreen"
    const val NAV = "nav"
    const val NAV_ALERT = "nav_alert"

    // Cahaya saat island kosong diketuk
    const val GLOW_ON = "glow_on"
    const val GLOW_COLOR = "glow_color"
    const val GLOW_RAINBOW = "glow_rainbow"
    const val GLOW_WIDTH = "glow_width"
    const val GLOW_SPEED = "glow_speed"
    const val GLOW_LAPS = "glow_laps"

    val LOOK_KEYS = listOf(
        WIDTH, HEIGHT, OFFSET_X, OFFSET_Y, RADIUS, EXP_RADIUS, EXP_WIDTH,
        COMPACT_EXTRA, OPACITY, BG_COLOR, ACCENT, ANIM_MS, BOUNCE, ALBUM_COLOR,
        GLOW_COLOR, GLOW_RAINBOW, GLOW_WIDTH, GLOW_SPEED, GLOW_LAPS,
    )

    val INTS = mapOf(
        WIDTH to 118,
        HEIGHT to 34,
        OFFSET_X to 0,
        OFFSET_Y to 10,
        RADIUS to 50,
        EXP_RADIUS to 36,
        EXP_WIDTH to 94,
        COMPACT_EXTRA to 84,
        OPACITY to 100,
        BG_COLOR to 0xFF000000.toInt(),
        ACCENT to 0xFF30D158.toInt(),
        ANIM_MS to 520,
        NOTIF_TIMEOUT to 5,
        GLOW_COLOR to 0xFF64D2FF.toInt(),
        GLOW_WIDTH to 5, // dalam setengah dp (5 = 2,5 dp)
        GLOW_SPEED to 900,
        GLOW_LAPS to 2,
    )

    val BOOLS = mapOf(
        ALBUM_COLOR to true,
        BOUNCE to true,
        SHOW_NOTIF to true,
        SHOW_MEDIA to true,
        CHARGE_POPUP to true,
        CHARGE_COMPACT to false,
        LOW_BATT to true,
        RINGER to true,
        OTP to true,
        REPLY to true,
        HAPTICS to true,
        HIDE_LANDSCAPE to true,
        HIDE_IDLE to false,
        LOCKSCREEN to true,
        NAV to true,
        NAV_ALERT to true,
        GLOW_ON to true,
        GLOW_RAINBOW to false,
    )
}

class Prefs(ctx: Context) {
    private val sp = ctx.applicationContext.getSharedPreferences("island_prefs", Context.MODE_PRIVATE)

    fun int(k: String): Int = sp.getInt(k, K.INTS[k] ?: 0)
    fun bool(k: String): Boolean = sp.getBoolean(k, K.BOOLS[k] ?: false)
    fun put(k: String, v: Int) = sp.edit().putInt(k, v).apply()
    fun put(k: String, v: Boolean) = sp.edit().putBoolean(k, v).apply()

    var blocked: Set<String>
        get() = sp.getStringSet("blocked", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("blocked", v).apply()

    var seen: Set<String>
        get() = sp.getStringSet("seen", emptySet())!!.toSet()
        set(v) = sp.edit().putStringSet("seen", v).apply()

    fun resetLook() {
        val e = sp.edit()
        K.LOOK_KEYS.forEach { e.remove(it) }
        e.apply()
    }
}
