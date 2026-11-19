package com.dynamicisland.app

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.media.MediaMetadata
import android.media.session.MediaController
import android.media.session.MediaSessionManager
import android.media.session.PlaybackState
import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Melacak sesi media yang aktif (Spotify, YouTube Music, dll.). */
object MediaTracker {
    data class State(
        val title: String,
        val artist: String,
        val art: Bitmap?,
        val playing: Boolean,
        val duration: Long,
        val pkg: String,
        val color: Int?,
    )

    var state: State? = null
        private set

    private val main = Handler(Looper.getMainLooper())
    private var controller: MediaController? = null
    private var msm: MediaSessionManager? = null
    private var component: ComponentName? = null
    private var lastArt: Bitmap? = null
    private var lastColor: Int? = null

    private val sessionsListener =
        MediaSessionManager.OnActiveSessionsChangedListener { pick(it) }

    private val callback = object : MediaController.Callback() {
        override fun onPlaybackStateChanged(state: PlaybackState?) {
            if (state?.state != PlaybackState.STATE_PLAYING) rescan() else refresh()
        }

        override fun onMetadataChanged(metadata: MediaMetadata?) = refresh()

        override fun onSessionDestroyed() {
            controller?.unregisterCallback(this)
            controller = null
            rescan()
        }
    }

    fun attach(ctx: Context, cn: ComponentName) {
        val m = ctx.getSystemService(MediaSessionManager::class.java) ?: return
        msm = m
        component = cn
        try {
            m.addOnActiveSessionsChangedListener(sessionsListener, cn, main)
            pick(m.getActiveSessions(cn))
        } catch (_: SecurityException) {
        }
    }

    fun detach() {
        try { msm?.removeOnActiveSessionsChangedListener(sessionsListener) } catch (_: Exception) {}
        controller?.unregisterCallback(callback)
        controller = null
        state = null
        IslandBus.post(IslandEvent.MediaChanged)
    }

    private fun rescan() {
        val m = msm
        val cn = component
        if (m != null && cn != null) {
            try { pick(m.getActiveSessions(cn)); return } catch (_: SecurityException) {}
        }
        refresh()
    }

    private fun pick(list: List<MediaController>?) {
        val l = list.orEmpty()
        val best = l.firstOrNull { it.playbackState?.state == PlaybackState.STATE_PLAYING }
            ?: l.firstOrNull { it.sessionToken == controller?.sessionToken }
            ?: l.firstOrNull()
        if (best?.sessionToken != controller?.sessionToken) {
            controller?.unregisterCallback(callback)
            controller = best
            best?.registerCallback(callback, main)
        }
        refresh()
    }

    private fun refresh() {
        val c = controller
        state = if (c == null) null else {
            val md = c.metadata
            val title = md?.getString(MediaMetadata.METADATA_KEY_TITLE)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_TITLE) ?: ""
            val artist = md?.getString(MediaMetadata.METADATA_KEY_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_ALBUM_ARTIST)
                ?: md?.getString(MediaMetadata.METADATA_KEY_DISPLAY_SUBTITLE) ?: ""
            val art = md?.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_ART)
                ?: md?.getBitmap(MediaMetadata.METADATA_KEY_DISPLAY_ICON)
            if (art !== lastArt) {
                lastArt = art
                lastColor = vibrantColor(art)
            }
            val playing = c.playbackState?.state == PlaybackState.STATE_PLAYING
            if (title.isBlank() && !playing) null
            else State(
                title, artist, art, playing,
                md?.getLong(MediaMetadata.METADATA_KEY_DURATION) ?: 0L,
                c.packageName, lastColor,
            )
        }
        IslandBus.post(IslandEvent.MediaChanged)
    }

    /** Warna rata-rata dari album art yang dibuat lebih cerah — untuk visualizer. */
    private fun vibrantColor(b: Bitmap?): Int? {
        if (b == null) return null
        return try {
            val soft = if (b.config == Bitmap.Config.HARDWARE) b.copy(Bitmap.Config.ARGB_8888, false) else b
            val px = Bitmap.createScaledBitmap(soft, 1, 1, true).getPixel(0, 0)
            val hsv = FloatArray(3)
            Color.colorToHSV(px, hsv)
            hsv[1] = hsv[1].coerceAtLeast(0.45f)
            hsv[2] = hsv[2].coerceAtLeast(0.85f)
            Color.HSVToColor(hsv)
        } catch (_: Exception) {
            null
        }
    }

    fun position(): Long {
        val ps = controller?.playbackState ?: return 0
        var p = ps.position
        if (ps.state == PlaybackState.STATE_PLAYING && ps.lastPositionUpdateTime > 0) {
            p += ((SystemClock.elapsedRealtime() - ps.lastPositionUpdateTime) * ps.playbackSpeed).toLong()
        }
        return p
    }

    fun playPause() {
        val c = controller ?: return
        if (c.playbackState?.state == PlaybackState.STATE_PLAYING) c.transportControls.pause()
        else c.transportControls.play()
    }

    fun next() = controller?.transportControls?.skipToNext()
    fun prev() = controller?.transportControls?.skipToPrevious()
    fun seekTo(ms: Long) = controller?.transportControls?.seekTo(ms)
    fun sessionActivity(): PendingIntent? = controller?.sessionActivity
}
