package com.dynamicisland.app

import android.app.Activity
import android.content.ComponentName
import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.view.Gravity
import android.view.View
import android.widget.CheckBox
import android.widget.HorizontalScrollView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView
import android.widget.Toast
import kotlin.math.roundToInt

@Suppress("SetTextI18n", "UseSwitchCompatOrMaterialCode")
class MainActivity : Activity() {
    private lateinit var prefs: Prefs
    private lateinit var list: LinearLayout

    private val bgColor = 0xFF0E0E12.toInt()
    private val cardColor = 0xFF1C1C22.toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        prefs = Prefs(this)
        @Suppress("DEPRECATION")
        run {
            window.statusBarColor = bgColor
            window.navigationBarColor = bgColor
        }
        val scroll = ScrollView(this).apply { setBackgroundColor(bgColor) }
        list = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(40))
        }
        scroll.addView(list)
        scroll.setOnApplyWindowInsetsListener { v, ins ->
            @Suppress("DEPRECATION")
            v.setPadding(0, ins.systemWindowInsetTop, 0, ins.systemWindowInsetBottom)
            ins
        }
        setContentView(scroll)
    }

    override fun onResume() {
        super.onResume()
        build()
    }

    // ------------------------------------------------------------------ builder

    private fun build() {
        list.removeAllViews()
        list.addView(text("Dynamic Island", 30f, bold = true))
        list.addView(text("Island bergaya iPhone yang bisa kamu atur sesukanya.", 14f, Palette.SECONDARY))

        permissionsCard()
        lookCard()
        animationCard()
        behaviourCard()
        miniAppsCard()
        appsCard()
        testCard()

        list.addView(
            text(
                "Gestur: ketuk = buka/tutup · tahan = buka aplikasi · geser ke atas = sembunyikan · " +
                    "geser ke bawah = perluas / panel mini apps · geser kiri/kanan saat musik = lagu berikut/sebelumnya.",
                12f, Palette.SECONDARY,
            ).apply { setPadding(dp(6), dp(20), dp(6), 0) }
        )
    }

    private fun text(s: String, sp: Float, color: Int = Palette.WHITE, bold: Boolean = false) =
        TextView(this).apply {
            text = s
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }

    private fun card(title: String): LinearLayout {
        list.addView(text(title.uppercase(), 12f, 0xFF8E8E93.toInt(), true).apply {
            letterSpacing = 0.08f
            setPadding(dp(6), dp(22), 0, dp(8))
        })
        val c = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(cardColor, dp(18).toFloat())
            setPadding(dp(16), dp(10), dp(16), dp(14))
        }
        list.addView(c, LinearLayout.LayoutParams(-1, -2))
        return c
    }

    private fun button(label: String, primary: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = label
        textSize = 14f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        val accent = prefs.int(K.ACCENT)
        setTextColor(if (primary && Color.luminance(accent) > 0.5f) Color.BLACK else Palette.WHITE)
        background = roundBg(if (primary) accent else 0xFF2C2C34.toInt(), dp(20).toFloat())
        setPadding(dp(16), dp(10), dp(16), dp(10))
        setOnClickListener { onClick() }
    }

    private fun buttonRow(c: LinearLayout, vararg items: Pair<String, () -> Unit>) {
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false }
        val row = LinearLayout(this)
        items.forEachIndexed { i, (label, action) ->
            row.addView(button(label, onClick = action), LinearLayout.LayoutParams(-2, -2).apply {
                if (i > 0) marginStart = dp(8)
            })
        }
        hs.addView(row)
        c.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun changed() = IslandBus.post(IslandEvent.PrefsChanged)

    private fun slider(c: LinearLayout, label: String, key: String, min: Int, max: Int, suffix: String) {
        val t = text("", 14f)
        fun upd(v: Int) { t.text = "$label  ·  $v$suffix" }
        upd(prefs.int(key))
        val sb = SeekBar(this).apply {
            this.max = max - min
            progress = prefs.int(key) - min
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {
                    if (!fromUser) return
                    val v = p + min
                    upd(v)
                    prefs.put(key, v)
                    changed()
                }
                override fun onStartTrackingTouch(s: SeekBar) {}
                override fun onStopTrackingTouch(s: SeekBar) {}
            })
        }
        c.addView(t, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        c.addView(sb)
    }

    private fun toggle(c: LinearLayout, label: String, key: String, hint: String? = null) {
        val sw = Switch(this).apply {
            text = label
            textSize = 15f
            setTextColor(Palette.WHITE)
            isChecked = prefs.bool(key)
            setOnCheckedChangeListener { _, on -> prefs.put(key, on); changed() }
        }
        c.addView(sw, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
        if (hint != null) c.addView(text(hint, 12f, Palette.SECONDARY))
    }

    private fun colors(c: LinearLayout, label: String, key: String, options: List<Int>) {
        c.addView(text(label, 14f), LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(14) })
        val row = LinearLayout(this)
        val swatches = mutableListOf<Pair<View, Int>>()
        fun paint() {
            val cur = prefs.int(key)
            swatches.forEach { (v, col) ->
                (v.background as GradientDrawable).setStroke(
                    dp(3), if (col == cur) Palette.WHITE else 0x40FFFFFF
                )
            }
        }
        options.forEach { col ->
            val v = View(this).apply {
                background = GradientDrawable().apply {
                    shape = GradientDrawable.OVAL
                    setColor(col)
                }
                setOnClickListener { prefs.put(key, col); paint(); changed() }
            }
            swatches += v to col
            row.addView(v, LinearLayout.LayoutParams(dp(34), dp(34)).apply { marginEnd = dp(10) })
        }
        paint()
        val hs = HorizontalScrollView(this).apply { isHorizontalScrollBarEnabled = false; addView(row) }
        c.addView(hs, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) })
    }

    // ------------------------------------------------------------------ kartu

    private fun permissionsCard() {
        val c = card("Izin")
        val acc = IslandBus.serviceRunning || isAccessibilityEnabled()
        val nl = NotifService.connected || isListenerEnabled()
        statusRow(c, "Layanan aksesibilitas", acc, "Menampilkan island di atas layar") {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }
        statusRow(c, "Akses notifikasi", nl, "Notifikasi, musik, balas cepat & OTP") {
            startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }
        if (!acc) {
            c.addView(
                text(
                    "Android 13+: jika layanan tidak bisa diaktifkan (abu-abu), buka Info aplikasi → menu ⋮ " +
                        "→ \"Izinkan setelan terbatas\", lalu coba lagi.",
                    12f, Palette.ORANGE,
                ).apply { setPadding(0, dp(10), 0, 0) }
            )
            buttonRow(c, "Info aplikasi" to {
                startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
            })
        }
    }

    private fun statusRow(c: LinearLayout, title: String, ok: Boolean, desc: String, open: () -> Unit) {
        val row = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val col = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        col.addView(text(title, 15f, bold = true))
        col.addView(text(if (ok) "Aktif ✓" else "Belum aktif — $desc", 12f, if (ok) Palette.GREEN else Palette.ORANGE))
        row.addView(col, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(button(if (ok) "Atur" else "Aktifkan", primary = !ok, onClick = open))
        c.addView(row, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(10) })
    }

    private fun lookCard() {
        val c = card("Tampilan")
        slider(c, "Lebar", K.WIDTH, 40, 260, " dp")
        slider(c, "Tinggi", K.HEIGHT, 16, 64, " dp")
        slider(c, "Posisi vertikal", K.OFFSET_Y, 0, 120, " dp")
        slider(c, "Posisi horizontal", K.OFFSET_X, -200, 200, " dp")
        slider(c, "Kebulatan sudut", K.RADIUS, 0, 50, "%")
        slider(c, "Lebar tambahan (mode ringkas)", K.COMPACT_EXTRA, 30, 160, " dp")
        slider(c, "Lebar saat diperluas", K.EXP_WIDTH, 60, 100, "%")
        slider(c, "Sudut saat diperluas", K.EXP_RADIUS, 8, 48, " dp")
        slider(c, "Opasitas", K.OPACITY, 30, 100, "%")
        colors(
            c, "Warna latar", K.BG_COLOR,
            listOf(0xFF000000, 0xFF1C1C1E, 0xFF0B1730, 0xFF1F0B33, 0xFF0B2A1E, 0xFF330B10).map { it.toInt() },
        )
        colors(
            c, "Warna aksen", K.ACCENT,
            listOf(
                0xFF30D158, 0xFF0A84FF, 0xFFFF9F0A, 0xFFFF375F,
                0xFFBF5AF2, 0xFFFFD60A, 0xFF64D2FF, 0xFFFFFFFF,
            ).map { it.toInt() },
        )
        toggle(c, "Warna visualizer ikuti album", K.ALBUM_COLOR)
        buttonRow(
            c,
            "Sejajarkan ke kamera" to { autoAlign() },
            "Reset tampilan" to { prefs.resetLook(); changed(); build() },
        )
    }

    private fun animationCard() {
        val c = card("Animasi")
        slider(c, "Durasi", K.ANIM_MS, 150, 1000, " ms")
        toggle(c, "Efek pegas (bounce)", K.BOUNCE, "Island sedikit memantul saat membesar/mengecil, seperti iPhone.")
    }

    private fun behaviourCard() {
        val c = card("Fitur")
        slider(c, "Lama notifikasi tampil", K.NOTIF_TIMEOUT, 2, 15, " detik")
        toggle(c, "Notifikasi pop-up", K.SHOW_NOTIF)
        toggle(c, "Balas langsung dari island", K.REPLY, "Untuk aplikasi chat yang mendukung balasan cepat.")
        toggle(c, "Deteksi & salin kode OTP", K.OTP, "Tombol \"Salin kode\" muncul otomatis untuk SMS/notifikasi verifikasi.")
        toggle(c, "Kontrol musik + visualizer", K.SHOW_MEDIA)
        toggle(c, "Animasi saat mulai mengisi daya", K.CHARGE_POPUP)
        toggle(c, "Indikator pengisian di mode ringkas", K.CHARGE_COMPACT)
        toggle(c, "Peringatan baterai lemah (20%)", K.LOW_BATT)
        toggle(c, "Indikator mode senyap / getar", K.RINGER)
        toggle(c, "Ketuk island kosong = panel mini apps", K.PANEL_TAP)
        toggle(c, "Getaran haptik", K.HAPTICS)
        toggle(c, "Sembunyikan saat landscape", K.HIDE_LANDSCAPE)
        toggle(c, "Sembunyikan saat tidak ada aktivitas", K.HIDE_IDLE, "Cocok jika kamera ponselmu tidak di tengah.")
    }

    private fun miniAppsCard() {
        val c = card("Timer & Stopwatch")
        c.addView(text("Berjalan sebagai Live Activity di island. Ketuk island untuk jeda / tambah waktu.", 12f, Palette.SECONDARY))
        fun timer(min: Int) = "$min mnt" to { requireService { TimerController.startCountdown(min * 60_000L) } }
        buttonRow(c, timer(1), timer(5), timer(10), timer(15), timer(25))
        buttonRow(
            c,
            "Stopwatch" to { requireService { TimerController.startStopwatch() } },
            "Hentikan" to { TimerController.stop() },
        )
    }

    private fun appsCard() {
        val c = card("Aplikasi")
        val seen = prefs.seen
        if (seen.isEmpty()) {
            c.addView(text("Aplikasi akan muncul di sini setelah mengirim notifikasi. Hapus centang untuk menyembunyikannya dari island.", 13f, Palette.SECONDARY))
            return
        }
        val pm = packageManager
        val labeled = seen.map { pkg ->
            pkg to (try { pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString() } catch (_: Exception) { pkg })
        }.sortedBy { it.second.lowercase() }
        labeled.forEach { (pkg, label) ->
            val cb = CheckBox(this).apply {
                text = label
                textSize = 15f
                setTextColor(Palette.WHITE)
                isChecked = pkg !in prefs.blocked
                setOnCheckedChangeListener { _, on ->
                    prefs.blocked = if (on) prefs.blocked - pkg else prefs.blocked + pkg
                }
            }
            c.addView(cb)
        }
    }

    private fun testCard() {
        val c = card("Coba")
        buttonRow(
            c,
            "Notifikasi + OTP" to {
                requireService {
                    IslandBus.post(
                        IslandEvent.Notif(
                            "preview", packageName, "Dynamic Island", applicationInfo.loadIcon(packageManager),
                            "Bank Contoh", "Kode verifikasi kamu 482913. Jangan berikan ke siapa pun.",
                            null, null, Otp.find("Kode verifikasi kamu 482913"),
                        )
                    )
                }
            },
            "Mengisi daya" to { requireService { IslandBus.post(IslandEvent.PreviewCharging) } },
            "Panel mini apps" to { requireService { IslandBus.post(IslandEvent.OpenPanel) } },
        )
    }

    // ------------------------------------------------------------------ util

    private fun requireService(action: () -> Unit) {
        if (!IslandBus.serviceRunning) {
            Toast.makeText(this, "Aktifkan layanan aksesibilitas dulu", Toast.LENGTH_SHORT).show()
            return
        }
        action()
    }

    /** Membaca posisi lubang kamera dari display cutout lalu menyesuaikan island. */
    private fun autoAlign() {
        val cut = window.decorView.rootWindowInsets?.displayCutout
        val sw = resources.displayMetrics.widthPixels
        val r = cut?.boundingRects?.firstOrNull { it.top <= dp(4) && it.width() < sw / 2 }
        if (r == null) {
            Toast.makeText(this, "Lubang kamera tidak terdeteksi — island diletakkan di tengah", Toast.LENGTH_SHORT).show()
            prefs.put(K.OFFSET_X, 0)
            changed(); build()
            return
        }
        val d = resources.displayMetrics.density
        val dia = minOf(r.width(), r.height())
        val centerY = r.bottom - dia / 2f
        val hPx = (dia + dp(10)).coerceIn(dp(24), dp(48))
        prefs.put(K.HEIGHT, (hPx / d).roundToInt())
        prefs.put(K.OFFSET_Y, ((centerY - hPx / 2f) / d).roundToInt().coerceAtLeast(0))
        prefs.put(K.OFFSET_X, ((r.exactCenterX() - sw / 2f) / d).roundToInt())
        if (prefs.int(K.WIDTH) < prefs.int(K.HEIGHT) * 3) prefs.put(K.WIDTH, prefs.int(K.HEIGHT) * 3)
        Toast.makeText(this, "Island disejajarkan dengan kamera", Toast.LENGTH_SHORT).show()
        changed(); build()
    }

    private fun isAccessibilityEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES) ?: return false
        val me = ComponentName(this, IslandService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    private fun isListenerEnabled(): Boolean {
        val enabled = Settings.Secure.getString(contentResolver, "enabled_notification_listeners") ?: return false
        return enabled.split(':').any { ComponentName.unflattenFromString(it)?.packageName == packageName }
    }
}
