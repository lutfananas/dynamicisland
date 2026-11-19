package com.dynamicisland.app

import android.app.Notification
import android.app.PendingIntent
import android.graphics.drawable.Drawable
import android.os.Handler
import android.os.Looper

sealed class IslandEvent {
    data class Notif(
        val key: String,
        val pkg: String,
        val appLabel: String,
        val icon: Drawable?,
        val title: String,
        val text: String,
        val contentIntent: PendingIntent?,
        val replyAction: Notification.Action?,
        val otp: String?,
    ) : IslandEvent()

    data class NotifRemoved(val key: String) : IslandEvent()
    data object MediaChanged : IslandEvent()
    data object TimerChanged : IslandEvent()
    data object TimerDone : IslandEvent()
    data object PrefsChanged : IslandEvent()
    data object PreviewCharging : IslandEvent()
    data object OpenPanel : IslandEvent()
}

/** Penghubung sederhana antar komponen (semua berjalan di proses yang sama). */
object IslandBus {
    private val main = Handler(Looper.getMainLooper())

    @Volatile
    var listener: ((IslandEvent) -> Unit)? = null

    val serviceRunning: Boolean get() = listener != null

    fun post(e: IslandEvent) {
        main.post { listener?.invoke(e) }
    }
}
