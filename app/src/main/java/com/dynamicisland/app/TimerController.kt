package com.dynamicisland.app

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

/** Timer & stopwatch bawaan yang tampil sebagai "Live Activity" di island. */
object TimerController {
    enum class Mode { NONE, COUNTDOWN, STOPWATCH }

    var mode = Mode.NONE
        private set
    private var total = 0L
    private var accumulated = 0L
    private var runningSince = -1L
    private val h = Handler(Looper.getMainLooper())

    val running: Boolean get() = runningSince >= 0

    private val tick = object : Runnable {
        override fun run() {
            if (mode == Mode.NONE) return
            if (mode == Mode.COUNTDOWN && remaining() <= 0) {
                mode = Mode.NONE
                runningSince = -1
                IslandBus.post(IslandEvent.TimerDone)
                return
            }
            IslandBus.post(IslandEvent.TimerChanged)
            if (running) h.postDelayed(this, 250)
        }
    }

    private fun now() = SystemClock.elapsedRealtime()
    private fun elapsed() = accumulated + if (running) now() - runningSince else 0L
    private fun remaining() = total - elapsed()

    fun startCountdown(ms: Long) {
        mode = Mode.COUNTDOWN; total = ms; accumulated = 0; runningSince = now(); restart()
    }

    fun startStopwatch() {
        mode = Mode.STOPWATCH; total = 0; accumulated = 0; runningSince = now(); restart()
    }

    fun addTime(ms: Long) {
        if (mode == Mode.COUNTDOWN) { total += ms; restart() }
    }

    fun toggle() {
        if (mode == Mode.NONE) return
        if (running) {
            accumulated += now() - runningSince
            runningSince = -1
        } else {
            runningSince = now()
        }
        restart()
    }

    fun stop() {
        mode = Mode.NONE; runningSince = -1; accumulated = 0
        h.removeCallbacks(tick)
        IslandBus.post(IslandEvent.TimerChanged)
    }

    fun label(): String = if (mode == Mode.COUNTDOWN) "Timer" else "Stopwatch"

    fun text(): String = when (mode) {
        Mode.COUNTDOWN -> formatMs(remaining().coerceAtLeast(0) + 999)
        Mode.STOPWATCH -> formatMs(elapsed())
        Mode.NONE -> ""
    }

    private fun restart() {
        h.removeCallbacks(tick)
        h.post(tick)
    }
}
