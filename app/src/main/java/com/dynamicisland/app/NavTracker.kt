package com.dynamicisland.app

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper
import android.service.notification.StatusBarNotification

/**
 * Membaca notifikasi navigasi (Google Maps, Waze, dll.) dan menyajikannya sebagai
 * "Live Activity" navigasi: belokan berikutnya, sisa jarak, waktu, dan jam tiba.
 * Tidak memakai GPS sendiri — data diambil dari aplikasi navigasi yang sudah berjalan.
 */
object NavTracker {
    private val NAV_PKGS = setOf(
        "com.google.android.apps.maps",
        "com.google.android.apps.mapslite",
        "com.waze",
        "com.here.app.maps",
        "net.osmand",
        "net.osmand.plus",
        "app.organicmaps",
        "com.sygic.aura",
        "ru.yandex.yandexnavi",
        "com.tomtom.gplay.navapp",
        "com.citymapper.app.release",
    )

    data class State(
        val pkg: String,
        val icon: Drawable?,
        /** Jarak ke manuver berikutnya, mis. "300 m". */
        val step: String?,
        val stepMeters: Double?,
        /** Instruksi, mis. "Belok kanan ke Jl. Sudirman". */
        val instruction: String,
        /** Sisa jarak ke tujuan, mis. "6,1 km". */
        val remaining: String?,
        val remainingMeters: Double?,
        /** Sisa waktu, mis. "15 mnt". */
        val minutes: String?,
        /** Jam tiba, mis. "14.32". */
        val eta: String?,
        /** 0..1 dari awal navigasi, atau null bila belum diketahui. */
        val progress: Float?,
        val contentIntent: PendingIntent?,
        val exitAction: Notification.Action?,
    )

    var state: State? = null
        private set
    var turnSoon = false
        private set

    private var key: String? = null
    private var startMeters: Double? = null
    private var lastStepMeters: Double? = null
    private val main = Handler(Looper.getMainLooper())

    private val distRe = Regex("(\\d+(?:[.,]\\d+)?)\\s?(km|mi|m|ft|yd)\\b", RegexOption.IGNORE_CASE)
    private val timeRe = Regex(
        "(\\d+\\s?(?:jam|j|hr|hrs|h|hour|hours)(?:\\s?\\d+\\s?(?:mnt|menit|min|mins|m))?|\\d+\\s?(?:mnt|menit|min|mins))\\b",
        RegexOption.IGNORE_CASE,
    )
    private val clockRe = Regex("\\b([01]?\\d|2[0-3])[.:]([0-5]\\d)(\\s?[AaPp][Mm])?\\b")
    private val exitRe = Regex("(keluar|exit|akhiri|stop|end|selesai)", RegexOption.IGNORE_CASE)

    fun isNav(sbn: StatusBarNotification): Boolean {
        if (sbn.packageName !in NAV_PKGS) return false
        val n = sbn.notification
        return sbn.isOngoing || n.category == Notification.CATEGORY_NAVIGATION
    }

    fun update(ctx: Context, sbn: StatusBarNotification) {
        val n = sbn.notification
        val ex = n.extras
        fun cs(k: String) = ex.getCharSequence(k)?.toString()?.trim()?.takeIf { it.isNotEmpty() }
        val title = cs(Notification.EXTRA_TITLE)
        val text = cs(Notification.EXTRA_TEXT)
        val sub = cs(Notification.EXTRA_SUB_TEXT)
        val big = cs(Notification.EXTRA_BIG_TEXT)
        val info = cs(Notification.EXTRA_INFO_TEXT)
        val fields = listOfNotNull(sub, text, big, info, title)
        if (fields.isEmpty()) return

        // Ringkasan perjalanan = teks yang memuat sisa waktu (mis. "15 mnt · 6,1 km · 14.32").
        val summary = fields.firstOrNull { timeRe.containsMatchIn(it) && it != title }
            ?: fields.firstOrNull { timeRe.containsMatchIn(it) }
        val remainingMatch = summary?.let { distRe.find(it) }
        val minutes = summary?.let { timeRe.find(it)?.value }
        val eta = summary?.let { s ->
            val all = clockRe.findAll(s).toList()
            all.lastOrNull()?.value?.trim()
        }

        // Jarak ke belokan: biasanya judul seperti "300 m" atau "300 m · Belok kanan".
        val stepMatch = title?.takeIf { it != summary }?.let { distRe.find(it) }
        val titleIsDistance = title != null && stepMatch != null && title.length <= stepMatch.value.length + 3
        val instruction = listOfNotNull(
            if (titleIsDistance) null else title?.takeIf { it != summary },
            text?.takeIf { it != summary && it != title },
        ).joinToString(" · ").ifBlank { title ?: text ?: "" }

        val remainingM = remainingMatch?.let { meters(it) }
        if (sbn.key != key) {
            key = sbn.key
            startMeters = null
            lastStepMeters = null
        }
        if (remainingM != null && (startMeters == null || remainingM > startMeters!!)) startMeters = remainingM
        val progress = if (remainingM != null && startMeters != null && startMeters!! > 0) {
            (1 - remainingM / startMeters!!).toFloat().coerceIn(0f, 1f)
        } else null

        val stepM = stepMatch?.let { meters(it) }
        // Menjelang belokan: melewati batas 150 m (dari jauh ke dekat).
        val prev = lastStepMeters
        turnSoon = stepM != null && stepM <= 150 && (prev == null || prev > 150)
        lastStepMeters = stepM

        val icon = try { n.getLargeIcon()?.loadDrawable(ctx) } catch (_: Exception) { null }
            ?: try { n.smallIcon?.loadDrawable(ctx.createPackageContext(sbn.packageName, 0)) } catch (_: Exception) { null }
        val exit = n.actions?.firstOrNull { exitRe.containsMatchIn(it.title ?: "") }

        state = State(
            sbn.packageName, icon, stepMatch?.value, stepM, instruction,
            remainingMatch?.value, remainingM, minutes, eta, progress, n.contentIntent, exit,
        )
        IslandBus.post(IslandEvent.NavChanged)
    }

    fun remove(sbnKey: String) {
        if (sbnKey != key) return
        clear()
    }

    private fun clear() {
        key = null
        state = null
        turnSoon = false
        startMeters = null
        lastStepMeters = null
        IslandBus.post(IslandEvent.NavChanged)
    }

    /** Contoh navigasi untuk mencoba tampilan (hilang sendiri setelah 20 detik). */
    fun preview(ctx: Context) {
        key = "preview"
        turnSoon = true
        state = State(
            ctx.packageName, null, "250 m", 250.0, "Belok kanan ke Jl. Jend. Sudirman",
            "4,2 km", 4200.0, "12 mnt", "14.32", 0.35f, null, null,
        )
        IslandBus.post(IslandEvent.NavChanged)
        main.postDelayed({ if (key == "preview") clear() }, 20_000)
    }

    private fun meters(m: MatchResult): Double? {
        val v = m.groupValues[1].replace(',', '.').toDoubleOrNull() ?: return null
        return when (m.groupValues[2].lowercase()) {
            "km" -> v * 1000
            "mi" -> v * 1609.34
            "ft" -> v * 0.3048
            "yd" -> v * 0.9144
            else -> v
        }
    }
}
