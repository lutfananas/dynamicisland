package com.dynamicisland.app

import android.app.Notification
import android.app.NotificationManager
import android.content.ComponentName
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification

class NotifService : NotificationListenerService() {
    private lateinit var prefs: Prefs
    private val lastPosted = HashMap<String, Long>()
    private val recent = HashMap<String, Long>()

    override fun onListenerConnected() {
        prefs = Prefs(this)
        connected = true
        MediaTracker.attach(this, ComponentName(this, NotifService::class.java))
        // Navigasi yang sudah berjalan sebelum layanan tersambung.
        try {
            activeNotifications?.firstOrNull { NavTracker.isNav(it) }?.let { NavTracker.update(this, it) }
        } catch (_: Exception) {
        }
    }

    override fun onListenerDisconnected() {
        connected = false
        MediaTracker.detach()
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        try { handle(sbn) } catch (_: Exception) {}
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        IslandBus.post(IslandEvent.NotifRemoved(sbn.key))
        NavTracker.remove(sbn.key)
    }

    private fun handle(sbn: StatusBarNotification) {
        if (!::prefs.isInitialized) prefs = Prefs(this)
        val n = sbn.notification
        val pkg = sbn.packageName
        // Navigasi (Maps/Waze) ditangani terpisah sebagai live activity.
        if (NavTracker.isNav(sbn)) {
            if (prefs.bool(K.NAV)) NavTracker.update(this, sbn)
            return
        }
        if (pkg == packageName || !prefs.bool(K.SHOW_NOTIF)) return
        if (sbn.isOngoing || (n.flags and Notification.FLAG_GROUP_SUMMARY) != 0) return
        if (n.category == Notification.CATEGORY_TRANSPORT) return
        if (n.extras.getString(Notification.EXTRA_TEMPLATE).orEmpty().contains("MediaStyle")) return

        // Abaikan notifikasi senyap.
        val ranking = Ranking()
        if (currentRanking?.getRanking(sbn.key, ranking) == true &&
            ranking.importance < NotificationManager.IMPORTANCE_DEFAULT
        ) return

        // Update notifikasi yang sama tanpa "alert" lagi (mis. progres) tidak ditampilkan ulang.
        val seenBefore = lastPosted.containsKey(sbn.key)
        if (seenBefore && (n.flags and Notification.FLAG_ONLY_ALERT_ONCE) != 0) return
        if (lastPosted.size > 300) lastPosted.clear()
        lastPosted[sbn.key] = System.currentTimeMillis()

        // Cegah notifikasi ganda: banyak aplikasi chat mengirim isi yang sama dua kali
        // (kunci berbeda atau update cepat). Isi sama dalam 5 detik → abaikan.
        val ex0 = n.extras
        val sig = pkg + "|" + ex0.getCharSequence(Notification.EXTRA_TITLE) + "|" +
            (ex0.getCharSequence(Notification.EXTRA_BIG_TEXT) ?: ex0.getCharSequence(Notification.EXTRA_TEXT))
        val now = System.currentTimeMillis()
        recent.entries.removeAll { now - it.value > 5000 }
        if (recent.containsKey(sig)) return
        recent[sig] = now

        val seen = prefs.seen
        if (pkg !in seen) prefs.seen = seen + pkg
        if (pkg in prefs.blocked) return

        val ex = n.extras
        val title = ex.getCharSequence(Notification.EXTRA_TITLE)?.toString().orEmpty()
        val text = (ex.getCharSequence(Notification.EXTRA_BIG_TEXT)
            ?: ex.getCharSequence(Notification.EXTRA_TEXT))?.toString().orEmpty()
        if (title.isBlank() && text.isBlank()) return

        val label = try {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        } catch (_: Exception) { pkg }
        val icon = try { n.getLargeIcon()?.loadDrawable(this) } catch (_: Exception) { null }
            ?: try { packageManager.getApplicationIcon(pkg) } catch (_: Exception) { null }
        val reply = if (prefs.bool(K.REPLY)) {
            n.actions?.firstOrNull { a -> a.remoteInputs?.any { it.allowFreeFormInput } == true }
        } else null
        val otp = if (prefs.bool(K.OTP)) Otp.find("$title $text") else null

        IslandBus.post(
            IslandEvent.Notif(sbn.key, pkg, label, icon, title, text, n.contentIntent, reply, otp)
        )
    }

    companion object {
        @Volatile
        var connected = false
    }
}
