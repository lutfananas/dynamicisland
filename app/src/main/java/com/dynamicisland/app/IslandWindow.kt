package com.dynamicisland.app

import android.accessibilityservice.AccessibilityService
import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.app.ActivityOptions
import android.app.PendingIntent
import android.app.RemoteInput
import android.content.BroadcastReceiver
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.app.KeyguardManager
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Outline
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.display.DisplayManager
import android.media.AudioManager
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.ViewOutlineProvider
import android.view.WindowManager
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.view.Display
import android.view.animation.LinearInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@SuppressLint("ClickableViewAccessibility", "SetTextI18n")
class IslandWindow(private val svc: AccessibilityService) {

    private enum class Kind { NOTIF, MEDIA, CHARGE, LOW_BATT, RINGER, TIMER, TIMER_DONE, PANEL }

    private val ctx: Context = svc
    private val wm = svc.getSystemService(WindowManager::class.java)
    private val prefs = Prefs(svc)
    private val h = Handler(Looper.getMainLooper())

    private val root = IslandSurface(ctx)
    private val pill = FrameLayout(ctx)
    private var glowAnim: ValueAnimator? = null
    private val compact = LinearLayout(ctx)
    private val leftBox = FrameLayout(ctx)
    private val rightBox = FrameLayout(ctx)
    private val cLeftImg = ImageView(ctx)
    private val cLeftTxt = TextView(ctx)
    private val cRightTxt = TextView(ctx)
    private val cRightViz = VisualizerView(ctx)
    private val expanded = FrameLayout(ctx)
    // Jendela gambar: selebar layar & tembus sentuhan, tidak pernah bergeser ke samping
    // sehingga island tidak "melompat" saat ukuran jendela berubah.
    private val lp = WindowManager.LayoutParams()
    private var added = false
    // Jendela sentuh: tak terlihat, seukuran island, meneruskan sentuhan ke jendela gambar.
    private val touch = View(ctx)
    private val touchLp = WindowManager.LayoutParams()
    private var touchAdded = false
    private var touchRawX = 0f
    private var touchRawY = 0f
    private var locked = false

    private var expandedKind: Kind? = null
    private var shownCompact: Kind? = null
    private var notif: IslandEvent.Notif? = null
    private var replying = false
    private var battLevel = -1
    private var charging = false
    private var ringerMode = AudioManager.RINGER_MODE_NORMAL
    private var landscape = false
    private var lastTimeout = 5000L

    // Mode edit langsung
    private var editMode = false
    private var toolbar: View? = null
    private var posLabel: TextView? = null
    private val editLabels = HashMap<String, Pair<TextView, String>>()
    private var dragX = 0f
    private var dragY = 0f
    private var startOX = 0
    private var startOY = 0
    private var pendingX = 0
    private var pendingY = 0

    private var curW = 0f
    private var curH = 0f
    private var curR = 0f
    private var anim: ValueAnimator? = null
    private val collapseTask = Runnable { collapse() }

    // Referensi tampilan media (diperbarui di tempat, tanpa membangun ulang)
    private var mArt: ImageView? = null
    private var mTitle: TextView? = null
    private var mArtist: TextView? = null
    private var mPlay: ImageView? = null
    private var mSeek: SeekBar? = null
    private var mPos: TextView? = null
    private var mDur: TextView? = null
    private var mViz: VisualizerView? = null
    private var seeking = false
    private val progressTask = object : Runnable {
        override fun run() {
            if (expandedKind != Kind.MEDIA) return
            updateMediaProgress()
            h.postDelayed(this, 500)
        }
    }

    // Referensi tampilan timer
    private var tTime: TextView? = null
    private var tToggle: ImageView? = null

    // Senter
    private val cam: CameraManager? by lazy { ctx.getSystemService(CameraManager::class.java) }
    private var torchId: String? = null
    private var torchOn = false
    private val torchCb = object : CameraManager.TorchCallback() {
        override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
            if (cameraId == torchId) torchOn = enabled
        }
    }

    private val vib: Vibrator? by lazy {
        if (Build.VERSION.SDK_INT >= 31) ctx.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") ctx.getSystemService(Vibrator::class.java)
    }

    private val drawFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private val baseFlags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
        WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
        WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH or
        WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
        WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
        WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED

    private fun dp(v: Int) = ctx.dp(v)
    private fun dpf(v: Int) = ctx.dp(v).toFloat()

    // ---------------------------------------------------------------- siklus hidup

    fun attach() {
        landscape = ctx.resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

        // Konten di-clip mengikuti bentuk island yang digambar IslandSurface.
        pill.clipToOutline = true
        pill.outlineProvider = object : ViewOutlineProvider() {
            override fun getOutline(view: View, outline: Outline) {
                val l = view.width / 2f + root.centerOffset - root.shapeW / 2f
                outline.setRoundRect(
                    l.roundToInt(), 0, (l + root.shapeW).roundToInt(), root.shapeH.roundToInt(), root.radius,
                )
            }
        }
        val p = root.pad
        root.addView(pill, FrameLayout.LayoutParams(-1, -1).apply { topMargin = p })
        setupCompact()
        pill.addView(compact, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        pill.addView(expanded, FrameLayout.LayoutParams(1, 1, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        expanded.visibility = View.GONE
        compact.alpha = 0f
        setupGestures()

        val cutoutMode = if (Build.VERSION.SDK_INT >= 30)
            WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
        else WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES

        lp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        lp.format = PixelFormat.TRANSLUCENT
        lp.flags = drawFlags
        lp.gravity = Gravity.TOP or Gravity.START
        lp.layoutInDisplayCutoutMode = cutoutMode
        lp.x = 0
        lp.width = screenW()

        touchLp.type = WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
        touchLp.format = PixelFormat.TRANSPARENT
        touchLp.flags = baseFlags
        touchLp.gravity = Gravity.TOP or Gravity.START
        touchLp.layoutInDisplayCutoutMode = cutoutMode

        val idle = idleGeom()
        applyPosition()
        setGeom(idle[0], idle[1], idle[2])
        lp.height = ceil(idle[1]).toInt() + 2 * root.pad
        setTouchRect(idle[0], idle[1])
        requestHighRefreshRate()
        try {
            wm.addView(root, lp)
            added = true
            wm.addView(touch, touchLp)
            touchAdded = true
        } catch (_: Exception) {
            return
        }
        touch.setOnTouchListener { _, ev -> forwardTouch(ev); true }

        registerReceivers()
        setupTorch()
        IslandBus.listener = { onEvent(it) }
        render()
    }

    fun detach() {
        if (IslandBus.listener != null) IslandBus.listener = null
        try { ctx.unregisterReceiver(battRx) } catch (_: Exception) {}
        try { ctx.unregisterReceiver(ringerRx) } catch (_: Exception) {}
        try { ctx.unregisterReceiver(screenRx) } catch (_: Exception) {}
        try { cam?.unregisterTorchCallback(torchCb) } catch (_: Exception) {}
        anim?.cancel()
        glowAnim?.cancel()
        h.removeCallbacksAndMessages(null)
        if (touchAdded) {
            try { wm.removeView(touch) } catch (_: Exception) {}
            touchAdded = false
        }
        if (added) {
            try { wm.removeView(root) } catch (_: Exception) {}
            added = false
        }
    }

    fun onConfigChanged(c: Configuration) {
        landscape = c.orientation == Configuration.ORIENTATION_LANDSCAPE
        lp.width = screenW()
        render()
    }

    private fun screenW(): Int =
        if (Build.VERSION.SDK_INT >= 30) wm.currentWindowMetrics.bounds.width()
        else @Suppress("DEPRECATION") android.util.DisplayMetrics().also { wm.defaultDisplay.getRealMetrics(it) }.widthPixels

    /** Teruskan sentuhan dari jendela sentuh ke tampilan island di jendela gambar. */
    private fun forwardTouch(ev: MotionEvent) {
        if (ev.actionMasked == MotionEvent.ACTION_OUTSIDE) {
            val k = expandedKind
            // Saat membalas, ketukan di keyboard juga terhitung "di luar" — jangan tutup.
            if (!replying && (k == Kind.PANEL || k == Kind.MEDIA || k == Kind.TIMER)) collapse()
            return
        }
        touchRawX = ev.rawX
        touchRawY = ev.rawY
        val e = MotionEvent.obtain(ev)
        e.offsetLocation((touchLp.x - lp.x).toFloat(), (touchLp.y - lp.y).toFloat())
        root.dispatchTouchEvent(e)
        e.recycle()
    }

    /** Area sentuh = bentuk island (plus panel edit saat mode edit). */
    private fun setTouchRect(w: Float, hh: Float) {
        val tw = max(1, ceil(w).toInt())
        touchLp.width = tw
        touchLp.height = max(1, ceil(hh).toInt())
        touchLp.x = (screenW() / 2f + root.centerOffset - tw / 2f).roundToInt()
        touchLp.y = lp.y + root.pad
        if (touchAdded) try { wm.updateViewLayout(touch, touchLp) } catch (_: Exception) {}
    }

    private fun setTouchable(on: Boolean) {
        touchLp.flags = if (on) touchLp.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
        else touchLp.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
        if (touchAdded) try { wm.updateViewLayout(touch, touchLp) } catch (_: Exception) {}
    }

    // ---------------------------------------------------------------- penerima sistem

    private val battRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val scale = i.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
            val lvl = i.getIntExtra(BatteryManager.EXTRA_LEVEL, -1) * 100 / scale
            val plugged = i.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
            val prevLvl = battLevel
            val wasCharging = charging
            battLevel = lvl
            charging = plugged
            if (prevLvl != -1) {
                if (!wasCharging && charging && prefs.bool(K.CHARGE_POPUP)) {
                    haptic(); showTemp(Kind.CHARGE, 3500)
                    return
                }
                if (!charging && prevLvl > 20 && lvl in 0..20 && prefs.bool(K.LOW_BATT)) {
                    haptic(40); showTemp(Kind.LOW_BATT, 4000)
                    return
                }
            }
            refreshOngoing()
        }
    }

    private val ringerRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            val mode = i.getIntExtra(AudioManager.EXTRA_RINGER_MODE, AudioManager.RINGER_MODE_NORMAL)
            val changed = mode != ringerMode
            ringerMode = mode
            if (isInitialStickyBroadcast || !changed || !prefs.bool(K.RINGER)) return
            haptic(); showTemp(Kind.RINGER, 2200)
        }
    }

    /** Pantau layar kunci: island bisa tampil atau disembunyikan di sana. */
    private val screenRx = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) {
            updateLocked()
            if (i.action == Intent.ACTION_SCREEN_ON) root.invalidate()
        }
    }

    private fun updateLocked() {
        val now = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true
        if (now != locked) {
            locked = now
            render()
        }
    }

    private fun registerReceivers() {
        val sf = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_USER_PRESENT)
        }
        if (Build.VERSION.SDK_INT >= 33) ctx.registerReceiver(screenRx, sf, Context.RECEIVER_EXPORTED)
        else ctx.registerReceiver(screenRx, sf)
        locked = ctx.getSystemService(KeyguardManager::class.java)?.isKeyguardLocked == true

        val am = ctx.getSystemService(AudioManager::class.java)
        ringerMode = am?.ringerMode ?: AudioManager.RINGER_MODE_NORMAL
        if (Build.VERSION.SDK_INT >= 33) {
            ctx.registerReceiver(battRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_EXPORTED)
            ctx.registerReceiver(ringerRx, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION), Context.RECEIVER_EXPORTED)
        } else {
            ctx.registerReceiver(battRx, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ctx.registerReceiver(ringerRx, IntentFilter(AudioManager.RINGER_MODE_CHANGED_ACTION))
        }
    }

    private fun setupTorch() {
        try {
            val c = cam ?: return
            torchId = c.cameraIdList.firstOrNull {
                c.getCameraCharacteristics(it).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
            c.registerTorchCallback(torchCb, h)
        } catch (_: Exception) {
        }
    }

    // ---------------------------------------------------------------- event

    private fun onEvent(e: IslandEvent) {
        if (editMode && e !is IslandEvent.EditMode && e != IslandEvent.PrefsChanged) return
        when (e) {
            is IslandEvent.Notif -> {
                if (replying) return
                notif = e
                haptic()
                val extra = if (e.otp != null || e.replyAction != null) 3000L else 0L
                showTemp(Kind.NOTIF, prefs.int(K.NOTIF_TIMEOUT) * 1000L + extra)
            }
            is IslandEvent.NotifRemoved ->
                if (expandedKind == Kind.NOTIF && notif?.key == e.key && !replying) collapse()
            IslandEvent.MediaChanged -> refreshOngoing()
            IslandEvent.TimerChanged -> refreshOngoing()
            IslandEvent.TimerDone -> { haptic(400); showTemp(Kind.TIMER_DONE, 6000) }
            IslandEvent.PrefsChanged -> render()
            IslandEvent.PreviewCharging -> showTemp(Kind.CHARGE, 3500)
            IslandEvent.OpenPanel -> expand(Kind.PANEL)
            IslandEvent.Glow -> startGlow()
            is IslandEvent.EditMode -> setEditMode(e.on)
        }
    }

    private fun showTemp(kind: Kind, ms: Long) {
        expandedKind = kind
        render()
        scheduleCollapse(ms)
    }

    private fun expand(kind: Kind) {
        expandedKind = kind
        render()
        scheduleCollapse(8000)
    }

    private fun scheduleCollapse(ms: Long) {
        lastTimeout = ms
        h.removeCallbacks(collapseTask)
        h.postDelayed(collapseTask, ms)
    }

    private fun collapse() {
        h.removeCallbacks(collapseTask)
        if (replying) setReplying(false)
        if (expandedKind == null) return
        expandedKind = null
        notif = null
        render()
    }

    private fun refreshOngoing() {
        when (expandedKind) {
            null -> {
                val ck = compactKind()
                if (ck != shownCompact) render() else ck?.let { fillCompact(it) }
            }
            Kind.MEDIA -> if (MediaTracker.state == null) collapse() else updateMediaExpanded()
            Kind.TIMER -> if (TimerController.mode == TimerController.Mode.NONE) collapse() else updateTimerExpanded()
            else -> {}
        }
    }

    private fun compactKind(): Kind? {
        if (TimerController.mode != TimerController.Mode.NONE) return Kind.TIMER
        val m = MediaTracker.state
        if (prefs.bool(K.SHOW_MEDIA) && m != null && m.playing) return Kind.MEDIA
        if (charging && prefs.bool(K.CHARGE_COMPACT)) return Kind.CHARGE
        return null
    }

    // ---------------------------------------------------------------- geometri & animasi

    private fun idleGeom(): FloatArray {
        val w = dpf(prefs.int(K.WIDTH))
        val hh = dpf(prefs.int(K.HEIGHT))
        return floatArrayOf(w, hh, hh * prefs.int(K.RADIUS) / 100f)
    }

    private fun expandedWidth(): Int {
        val sw = ctx.resources.displayMetrics.widthPixels
        val w = min(sw * prefs.int(K.EXP_WIDTH) / 100, dp(460))
        return min(w, sw - 2 * abs(dp(prefs.int(K.OFFSET_X))) - dp(8)).coerceAtLeast(dp(160))
    }

    private fun accent(): Int = prefs.int(K.ACCENT)

    private fun mediaColor(): Int {
        val c = MediaTracker.state?.color
        return if (prefs.bool(K.ALBUM_COLOR) && c != null) c else accent()
    }

    private fun applyStyle() {
        val a = prefs.int(K.OPACITY).coerceIn(0, 100) * 255 / 100
        root.fillColor = (prefs.int(K.BG_COLOR) and 0xFFFFFF) or (a shl 24)
        root.invalidate()
    }

    private fun applyPosition() = applyOffsets(prefs.int(K.OFFSET_X), prefs.int(K.OFFSET_Y))

    private fun applyOffsets(xDp: Int, yDp: Int) {
        val off = dpf(xDp)
        root.centerOffset = off
        compact.translationX = off
        expanded.translationX = off
        toolbar?.translationX = off
        lp.x = 0
        lp.y = dp(yDp) - root.pad
        root.invalidate()
        pill.invalidateOutline()
    }

    /** Minta refresh rate tertinggi layar (mis. 120 Hz) untuk jendela island. */
    private fun requestHighRefreshRate() {
        try {
            val d = ctx.getSystemService(DisplayManager::class.java)?.getDisplay(Display.DEFAULT_DISPLAY) ?: return
            val best = d.supportedModes.filter {
                it.physicalWidth == d.mode.physicalWidth && it.physicalHeight == d.mode.physicalHeight
            }.maxByOrNull { it.refreshRate } ?: return
            lp.preferredDisplayModeId = best.modeId
            lp.preferredRefreshRate = best.refreshRate
        } catch (_: Exception) {
        }
    }

    private fun setFastFrames(on: Boolean) {
        if (Build.VERSION.SDK_INT >= 35) {
            root.requestedFrameRate = if (on) View.REQUESTED_FRAME_RATE_CATEGORY_HIGH else View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
        }
    }

    private fun setGeom(w: Float, hh: Float, r: Float) {
        curW = w; curH = hh; curR = r
        // Hanya redraw + outline baru: tidak ada layout pass per frame.
        root.shapeW = max(1f, w)
        root.shapeH = max(1f, hh)
        root.radius = r.coerceIn(0f, min(w, hh) / 2f)
        root.invalidate()
        pill.invalidateOutline()
    }

    private fun setWindowSize(w: Float, hh: Float) {
        // Lebar selalu selebar layar: hanya tinggi yang berubah (bertumpu di atas),
        // jadi tidak ada pergeseran horizontal saat jendela diubah ukurannya.
        val nw = screenW()
        val nh = max(1, ceil(hh).toInt()) + 2 * root.pad
        if (lp.width != nw || lp.height != nh) {
            lp.width = nw
            lp.height = nh
            updateWindow()
        }
    }

    private fun updateWindow() {
        if (added) try { wm.updateViewLayout(root, lp) } catch (_: Exception) {}
    }

    private fun animateTo(w: Float, hh: Float, r: Float, onEnd: (() -> Unit)? = null) {
        anim?.cancel()
        val sw = curW
        val sh = curH
        val sr = curR
        val bounce = prefs.bool(K.BOUNCE)
        val growing = w * hh >= sw * sh
        val grow = if (bounce && growing) 1.06f else 1.01f
        // Jendela dibuat cukup besar selama animasi (sekali saja), lalu dipas di akhir.
        setWindowSize(max(sw, w * grow) + dp(2), max(sh, hh * grow) + dp(2))
        setTouchRect(w, hh)
        val va = ValueAnimator.ofFloat(0f, 1f)
        anim = va
        va.duration = prefs.int(K.ANIM_MS).toLong()
        // Membesar: pegas dengan sedikit pantulan. Mengecil: pegas hampir tanpa pantulan, jadi tetap mulus.
        va.interpolator = SpringInterpolator(
            when {
                !bounce -> 0.98f
                growing -> 0.72f
                else -> 0.88f
            }
        )
        setFastFrames(true)
        va.addUpdateListener {
            val f = it.animatedValue as Float
            setGeom(sw + (w - sw) * f, sh + (hh - sh) * f, sr + (r - sr) * f)
        }
        va.addListener(object : AnimatorListenerAdapter() {
            var cancelled = false
            override fun onAnimationCancel(animation: Animator) { cancelled = true }
            override fun onAnimationEnd(animation: Animator) {
                setFastFrames(false)
                if (cancelled) return
                setGeom(w, hh, r)
                setWindowSize(w, hh)
                onEnd?.invoke()
            }
        })
        va.start()
    }

    private fun setVisible(visible: Boolean) {
        if (visible) {
            // Jangan potong animasi pegas setelah geser (ia sendiri mengembalikan alpha ke 1).
            if (!springing) {
                root.animate().cancel()
                root.alpha = 1f
            }
        } else {
            root.animate().cancel()
            springing = false
            root.translationX = 0f; root.translationY = 0f
            root.scaleX = 1f; root.scaleY = 1f
            root.animate().alpha(0f).setDuration(200).start()
        }
        setTouchable(visible)
    }

    // ---------------------------------------------------------------- render

    private fun render() {
        if (!added) return
        applyStyle()
        applyPosition()
        if (editMode) {
            renderEdit()
            return
        }
        if ((landscape && prefs.bool(K.HIDE_LANDSCAPE)) || (locked && !prefs.bool(K.LOCKSCREEN))) {
            root.visibility = View.GONE
            setTouchable(false)
            return
        }
        root.visibility = View.VISIBLE
        val idle = idleGeom()
        val dur = prefs.int(K.ANIM_MS).toLong()
        val kind = expandedKind

        if (kind != null) {
            shownCompact = null
            clearExpandedRefs()
            val tw = if (isWide(kind)) min(expandedWidth(), (idle[0] + dp(190)).toInt()) else expandedWidth()
            val content = buildExpanded(kind)
            content.measure(
                View.MeasureSpec.makeMeasureSpec(tw, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
            )
            val th = max(content.measuredHeight, idle[1].toInt())
            expanded.removeAllViews()
            expanded.addView(content, FrameLayout.LayoutParams(tw, th))
            expanded.layoutParams = FrameLayout.LayoutParams(tw, th, Gravity.TOP or Gravity.CENTER_HORIZONTAL)
            expanded.visibility = View.VISIBLE
            expanded.animate().cancel()
            expanded.alpha = 0f
            expanded.pivotX = tw / 2f
            expanded.pivotY = 0f
            expanded.scaleX = 0.92f
            expanded.scaleY = 0.92f
            expanded.animate().alpha(1f).scaleX(1f).scaleY(1f)
                .setStartDelay(dur / 5).setDuration(dur / 2).start()
            compact.animate().cancel()
            compact.animate().alpha(0f).setStartDelay(0).setDuration(dur / 5).start()
            setVisible(true)
            val r = min(dpf(prefs.int(K.EXP_RADIUS)), th / 2f)
            animateTo(tw.toFloat(), th.toFloat(), r)
            if (kind == Kind.MEDIA) { h.removeCallbacks(progressTask); h.post(progressTask) }
            return
        }

        clearExpandedRefs()
        expanded.animate().cancel()
        expanded.animate().alpha(0f).scaleX(0.9f).scaleY(0.9f).setStartDelay(0).setDuration(dur / 4).withEndAction {
            if (expandedKind == null) {
                expanded.removeAllViews()
                expanded.visibility = View.GONE
            }
        }.start()

        val ck = compactKind()
        shownCompact = ck
        if (ck != null) {
            val w = idle[0] + dpf(prefs.int(K.COMPACT_EXTRA))
            fillCompact(ck)
            compact.layoutParams = FrameLayout.LayoutParams(
                w.roundToInt(), idle[1].roundToInt(), Gravity.TOP or Gravity.CENTER_HORIZONTAL,
            )
            compact.animate().cancel()
            compact.animate().alpha(1f).setStartDelay(dur / 3).setDuration(dur / 2).start()
            setVisible(true)
            animateTo(w, idle[1], idle[2])
        } else {
            compact.animate().cancel()
            compact.animate().alpha(0f).setStartDelay(0).setDuration(dur / 5).start()
            val hide = prefs.bool(K.HIDE_IDLE)
            if (!hide) setVisible(true)
            animateTo(idle[0], idle[1], idle[2]) {
                if (hide && expandedKind == null && shownCompact == null) setVisible(false)
            }
        }
    }

    private fun isWide(k: Kind) = k == Kind.CHARGE || k == Kind.LOW_BATT || k == Kind.RINGER || k == Kind.TIMER_DONE

    private fun clearExpandedRefs() {
        h.removeCallbacks(progressTask)
        mArt = null; mTitle = null; mArtist = null; mPlay = null
        mSeek = null; mPos = null; mDur = null; mViz = null
        tTime = null; tToggle = null
    }

    // ---------------------------------------------------------------- mode ringkas

    private fun setupCompact() {
        compact.orientation = LinearLayout.HORIZONTAL
        compact.gravity = Gravity.CENTER_VERTICAL
        cLeftImg.scaleType = ImageView.ScaleType.CENTER_CROP
        cLeftImg.clipToOutline = true
        cLeftImg.outlineProvider = roundOutline(dpf(6))
        cLeftTxt.gravity = Gravity.CENTER
        cLeftTxt.includeFontPadding = false
        cRightTxt.gravity = Gravity.CENTER
        cRightTxt.typeface = Typeface.DEFAULT_BOLD
        cRightTxt.includeFontPadding = false
        cRightTxt.fontFeatureSettings = "tnum"
        leftBox.addView(cLeftImg, FrameLayout.LayoutParams(-1, -1))
        leftBox.addView(cLeftTxt, FrameLayout.LayoutParams(-1, -1))
        rightBox.addView(cRightTxt, FrameLayout.LayoutParams(-2, -2, Gravity.CENTER))
        rightBox.addView(cRightViz, FrameLayout.LayoutParams(1, 1, Gravity.CENTER))
        compact.addView(leftBox, LinearLayout.LayoutParams(1, 1))
        compact.addView(View(ctx), LinearLayout.LayoutParams(0, 1, 1f))
        compact.addView(rightBox, LinearLayout.LayoutParams(-2, -1))
    }

    private fun fillCompact(k: Kind) {
        val ih = idleGeom()[1]
        val s = (ih - dp(10)).roundToInt().coerceAtLeast(dp(12))
        val pad = ((ih - s) / 2).roundToInt() + dp(4)
        compact.setPadding(pad, 0, pad, 0)
        leftBox.layoutParams = LinearLayout.LayoutParams(s, s)
        cRightViz.layoutParams = FrameLayout.LayoutParams(s, (s * 0.72f).roundToInt(), Gravity.CENTER)
        cLeftTxt.setTextSize(TypedValue.COMPLEX_UNIT_PX, s * 0.62f)
        cRightTxt.setTextSize(TypedValue.COMPLEX_UNIT_PX, s * 0.6f)
        cLeftImg.visibility = View.GONE
        cLeftTxt.visibility = View.GONE
        cRightTxt.visibility = View.GONE
        cRightViz.visibility = View.GONE
        when (k) {
            Kind.TIMER -> {
                cLeftTxt.visibility = View.VISIBLE
                cLeftTxt.text = if (TimerController.mode == TimerController.Mode.COUNTDOWN) "⏲" else "⏱"
                cLeftTxt.setTextColor(Palette.ORANGE)
                cRightTxt.visibility = View.VISIBLE
                cRightTxt.text = TimerController.text()
                cRightTxt.setTextColor(if (TimerController.running) Palette.ORANGE else Palette.SECONDARY)
            }
            Kind.MEDIA -> {
                val st = MediaTracker.state
                cLeftImg.visibility = View.VISIBLE
                if (st?.art != null) cLeftImg.setImageBitmap(st.art) else cLeftImg.setImageDrawable(appIcon(st?.pkg))
                cRightViz.visibility = View.VISIBLE
                cRightViz.color = mediaColor()
                cRightViz.playing = st?.playing == true
            }
            Kind.CHARGE -> {
                cLeftTxt.visibility = View.VISIBLE
                cLeftTxt.text = "⚡"
                cLeftTxt.setTextColor(Palette.GREEN)
                cRightTxt.visibility = View.VISIBLE
                cRightTxt.text = "$battLevel%"
                cRightTxt.setTextColor(Palette.GREEN)
            }
            else -> {}
        }
    }

    // ---------------------------------------------------------------- gestur

    private fun setupGestures() {
        val gd = GestureDetector(ctx, object : GestureDetector.SimpleOnGestureListener() {
            override fun onDown(e: MotionEvent): Boolean {
                if (expandedKind != null && !replying) scheduleCollapse(max(lastTimeout, 4000))
                return true
            }

            override fun onSingleTapUp(e: MotionEvent): Boolean { onTap(); return true }
            override fun onLongPress(e: MotionEvent) = onLong()

            override fun onFling(e1: MotionEvent?, e2: MotionEvent, velocityX: Float, velocityY: Float): Boolean {
                if (abs(velocityY) > abs(velocityX)) {
                    if (velocityY < 0) collapse() else onSwipeDown()
                } else if (mediaVisible()) {
                    haptic()
                    if (velocityX < 0) MediaTracker.next() else MediaTracker.prev()
                }
                return true
            }
        })
        pill.setOnTouchListener { _, ev ->
            when {
                editMode -> handleDrag(ev)
                handleSwipe(ev, gd) -> {}
                else -> gd.onTouchEvent(ev)
            }
            true
        }
    }

    // ---------------------------------------------------------------- geser untuk menutup

    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var swiping = false
    private var swipeHorizontal = false
    private var lastSampleT = 0L
    private var lastSampleX = 0f
    private var lastSampleY = 0f
    private var velX = 0f
    private var velY = 0f
    private val slop by lazy { android.view.ViewConfiguration.get(ctx).scaledTouchSlop }

    /**
     * Island yang sedang diperluas bisa digeser (atas/kiri/kanan) mengikuti jari.
     * Dilepas cukup jauh/cepat → menutup dan kembali mulus ke bentuk awal; jika tidak → memantul balik.
     * Mengembalikan true bila event dipakai untuk geser.
     */
    private fun handleSwipe(ev: MotionEvent, gd: GestureDetector): Boolean {
        val kind = expandedKind ?: return false
        if (replying) return false
        val x = touchRawX
        val y = touchRawY
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                swipeStartX = x; swipeStartY = y
                swiping = false
                velX = 0f; velY = 0f
                lastSampleT = ev.eventTime; lastSampleX = x; lastSampleY = y
                if (springing) {
                    root.animate().cancel()
                    springing = false
                }
                return false
            }
            MotionEvent.ACTION_MOVE -> {
                val dt = (ev.eventTime - lastSampleT).coerceAtLeast(1)
                velX = 0.6f * velX + 0.4f * (x - lastSampleX) / dt
                velY = 0.6f * velY + 0.4f * (y - lastSampleY) / dt
                lastSampleT = ev.eventTime; lastSampleX = x; lastSampleY = y
                val dx = x - swipeStartX
                val dy = y - swipeStartY
                if (!swiping) {
                    val horizontalOk = kind != Kind.MEDIA // geser kiri/kanan pada musik = ganti lagu
                    when {
                        dy < -slop && abs(dy) > abs(dx) -> { swiping = true; swipeHorizontal = false }
                        horizontalOk && abs(dx) > slop && abs(dx) > abs(dy) -> { swiping = true; swipeHorizontal = true }
                    }
                    if (!swiping) return false
                    // Batalkan tap/long-press yang mungkin sedang dideteksi.
                    val cancel = MotionEvent.obtain(ev)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    gd.onTouchEvent(cancel)
                    cancel.recycle()
                    h.removeCallbacks(collapseTask)
                    root.pivotX = root.width / 2f + root.centerOffset
                    root.pivotY = root.pad.toFloat()
                }
                val tx = if (swipeHorizontal) dx else dx * 0.15f
                val ty = if (swipeHorizontal) 0f else if (dy < 0) dy else dy * 0.2f
                val dist = if (swipeHorizontal) abs(dx) else -dy
                val p = (dist / dpf(140)).coerceIn(0f, 1f)
                root.translationX = tx
                root.translationY = ty
                root.scaleX = 1f - 0.12f * p
                root.scaleY = 1f - 0.12f * p
                root.alpha = 1f - 0.4f * p
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                if (!swiping) return false
                swiping = false
                val dx = x - swipeStartX
                val dy = y - swipeStartY
                val fast = dpf(1) * 0.9f // ≈ 0,9 dp per ms
                val dismiss = ev.actionMasked == MotionEvent.ACTION_UP && if (swipeHorizontal) {
                    abs(dx) > dpf(70) || abs(velX) > fast
                } else {
                    -dy > dpf(36) || -velY > fast
                }
                springBack()
                if (dismiss) {
                    haptic()
                    collapse()
                } else {
                    scheduleCollapse(max(lastTimeout, 4000))
                }
                return true
            }
        }
        return swiping
    }

    /** Kembalikan posisi/skala dengan pegas — bersamaan dengan island mengecil ke bentuk awal. */
    private var springing = false

    private fun springBack() {
        root.animate().cancel()
        springing = true
        root.animate()
            .withEndAction { springing = false }
            .translationX(0f).translationY(0f)
            .scaleX(1f).scaleY(1f).alpha(1f)
            .setInterpolator(SpringInterpolator(0.86f))
            .setDuration(prefs.int(K.ANIM_MS).toLong())
            .start()
    }

    private fun mediaVisible() = expandedKind == Kind.MEDIA || (expandedKind == null && shownCompact == Kind.MEDIA)

    private fun onTap() {
        haptic()
        val k = expandedKind
        when {
            k == Kind.NOTIF -> { openNotif(); collapse() }
            k != null -> collapse()
            shownCompact == Kind.CHARGE -> showTemp(Kind.CHARGE, 3000)
            shownCompact != null -> expand(shownCompact!!)
            else -> if (prefs.bool(K.GLOW_ON)) startGlow()
        }
    }

    private fun onLong() {
        haptic(30)
        when {
            expandedKind == Kind.NOTIF -> { openNotif(); collapse() }
            mediaVisible() -> {
                if (!sendPI(MediaTracker.sessionActivity())) openPkg(MediaTracker.state?.pkg)
                collapse()
            }
            expandedKind == null && shownCompact == null -> openSettings()
            else -> collapse()
        }
    }

    private fun onSwipeDown() {
        if (expandedKind != null) return
        haptic()
        val c = shownCompact
        if (c != null && c != Kind.CHARGE) expand(c) else expand(Kind.PANEL)
    }

    // ---------------------------------------------------------------- konten diperluas

    private fun buildExpanded(k: Kind): View = when (k) {
        Kind.NOTIF -> buildNotif()
        Kind.MEDIA -> buildMedia()
        Kind.TIMER -> buildTimer()
        Kind.PANEL -> buildPanel()
        Kind.CHARGE -> buildStatus("⚡  Mengisi daya", Palette.WHITE, "$battLevel%", Palette.GREEN, battLevel)
        Kind.LOW_BATT -> buildStatus("Baterai lemah", Palette.RED, "$battLevel%", Palette.RED, battLevel)
        Kind.RINGER -> buildRinger()
        Kind.TIMER_DONE -> buildStatus("⏰  Timer selesai", Palette.ORANGE, "OK", Palette.WHITE, -1)
    }

    private fun tv(text: CharSequence, sp: Float, color: Int = Palette.WHITE, bold: Boolean = false) =
        TextView(ctx).apply {
            this.text = text
            textSize = sp
            setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }

    private fun iconBtn(res: Int, size: Int, tint: Int = Palette.WHITE, onClick: () -> Unit) =
        ImageView(ctx).apply {
            setImageResource(res)
            imageTintList = ColorStateList.valueOf(tint)
            scaleType = ImageView.ScaleType.FIT_CENTER
            val p = size / 6
            setPadding(p, p, p, p)
            background = roundBg(Palette.FAINT, size / 2f)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { haptic(); onClick() }
        }

    private fun chip(text: String, color: Int = Palette.FAINT, onClick: (TextView) -> Unit) =
        TextView(ctx).apply {
            this.text = text
            textSize = 13f
            typeface = Typeface.DEFAULT_BOLD
            setTextColor(if (color != Palette.FAINT && Color.luminance(color) > 0.5f) Color.BLACK else Palette.WHITE)
            background = roundBg(color, dpf(18))
            setPadding(dp(14), dp(8), dp(14), dp(8))
            setOnClickListener { haptic(); onClick(this) }
        }

    private fun lpw(w: Int, hh: Int, weight: Float = 0f, start: Int = 0, top: Int = 0) =
        LinearLayout.LayoutParams(w, hh, weight).apply { marginStart = start; topMargin = top }

    private fun buildNotif(): View {
        val n = notif ?: return View(ctx)
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val ic = ImageView(ctx).apply {
            setImageDrawable(n.icon)
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundOutline(dpf(11))
        }
        row.addView(ic, lpw(dp(44), dp(44)))
        val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        tcol.addView(tv("${n.appLabel} · sekarang", 11f, Palette.SECONDARY))
        if (n.title.isNotBlank()) tcol.addView(tv(n.title, 15f, Palette.WHITE, true))
        if (n.text.isNotBlank()) tcol.addView(tv(n.text, 13f, Palette.SECONDARY).apply { maxLines = 3 })
        row.addView(tcol, lpw(0, -2, 1f, dp(12)))
        col.addView(row)

        if (replying && n.replyAction != null) {
            val r2 = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            val et = EditText(ctx).apply {
                hint = "Balas ${n.title}…"
                setHintTextColor(Palette.SECONDARY)
                setTextColor(Palette.WHITE)
                textSize = 14f
                background = roundBg(Palette.FAINT, dpf(18))
                setPadding(dp(14), dp(8), dp(14), dp(8))
                imeOptions = EditorInfo.IME_ACTION_SEND
                inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_SENTENCES
                maxLines = 3
            }
            et.setOnEditorActionListener { _, id, _ ->
                if (id == EditorInfo.IME_ACTION_SEND) { sendReply(et.text.toString()); true } else false
            }
            r2.addView(et, lpw(0, -2, 1f))
            r2.addView(chip("Kirim", accent()) { sendReply(et.text.toString()) }, lpw(-2, -2, 0f, dp(8)))
            col.addView(r2, lpw(-1, -2, 0f, 0, dp(10)))
            h.postDelayed({
                et.requestFocus()
                ctx.getSystemService(InputMethodManager::class.java)?.showSoftInput(et, 0)
            }, 300)
        } else if (n.otp != null || n.replyAction != null) {
            val r2 = LinearLayout(ctx)
            n.otp?.let { code ->
                r2.addView(chip("Salin kode $code", accent()) { v ->
                    copy(code)
                    v.text = "Tersalin ✓"
                    h.postDelayed({ collapse() }, 800)
                })
            }
            if (n.replyAction != null) {
                r2.addView(chip("Balas") { setReplying(true) }, lpw(-2, -2, 0f, if (n.otp != null) dp(8) else 0))
            }
            col.addView(r2, lpw(-1, -2, 0f, 0, dp(10)))
        }
        return col
    }

    private fun buildMedia(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(12))
        }
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val art = ImageView(ctx).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            clipToOutline = true
            outlineProvider = roundOutline(dpf(12))
        }
        val tcol = LinearLayout(ctx).apply { orientation = LinearLayout.VERTICAL }
        val title = tv("", 16f, Palette.WHITE, true)
        val artist = tv("", 13f, Palette.SECONDARY)
        tcol.addView(title); tcol.addView(artist)
        val viz = VisualizerView(ctx)
        row.addView(art, lpw(dp(56), dp(56)))
        row.addView(tcol, lpw(0, -2, 1f, dp(12)))
        row.addView(viz, lpw(dp(26), dp(20), 0f, dp(8)))
        col.addView(row)

        val seekRow = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val pos = tv("0:00", 11f, Palette.SECONDARY).apply { fontFeatureSettings = "tnum" }
        val durT = tv("0:00", 11f, Palette.SECONDARY).apply { fontFeatureSettings = "tnum" }
        val seek = SeekBar(ctx).apply {
            max = 1000
            progressTintList = ColorStateList.valueOf(mediaColor())
            thumbTintList = ColorStateList.valueOf(Palette.WHITE)
            progressBackgroundTintList = ColorStateList.valueOf(0x40FFFFFF)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(s: SeekBar, p: Int, fromUser: Boolean) {}
                override fun onStartTrackingTouch(s: SeekBar) { seeking = true; h.removeCallbacks(collapseTask) }
                override fun onStopTrackingTouch(s: SeekBar) {
                    seeking = false
                    val d = MediaTracker.state?.duration ?: 0
                    if (d > 0) MediaTracker.seekTo(d * s.progress / 1000)
                    scheduleCollapse(8000)
                }
            })
        }
        seekRow.addView(pos)
        seekRow.addView(seek, lpw(0, -2, 1f))
        seekRow.addView(durT)
        col.addView(seekRow, lpw(-1, -2, 0f, 0, dp(10)))

        val controls = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        controls.addView(iconBtn(android.R.drawable.ic_media_previous, dp(42)) { MediaTracker.prev() })
        val play = iconBtn(android.R.drawable.ic_media_play, dp(54)) { MediaTracker.playPause() }
        controls.addView(play, lpw(dp(54), dp(54), 0f, dp(22)))
        controls.addView(iconBtn(android.R.drawable.ic_media_next, dp(42)) { MediaTracker.next() }, lpw(dp(42), dp(42), 0f, dp(22)))
        col.addView(controls, lpw(-1, -2, 0f, 0, dp(6)))

        mArt = art; mTitle = title; mArtist = artist; mPlay = play
        mSeek = seek; mPos = pos; mDur = durT; mViz = viz
        updateMediaExpanded()
        updateMediaProgress()
        return col
    }

    private fun updateMediaExpanded() {
        val st = MediaTracker.state ?: return
        mArt?.let { if (st.art != null) it.setImageBitmap(st.art) else it.setImageDrawable(appIcon(st.pkg)) }
        mTitle?.text = st.title.ifBlank { "Sedang diputar" }
        mArtist?.text = st.artist
        mPlay?.setImageResource(if (st.playing) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play)
        mViz?.color = mediaColor()
        mViz?.playing = st.playing
        mSeek?.progressTintList = ColorStateList.valueOf(mediaColor())
        mDur?.text = if (st.duration > 0) formatMs(st.duration) else "--:--"
    }

    private fun updateMediaProgress() {
        val st = MediaTracker.state ?: return
        if (seeking) return
        val p = MediaTracker.position()
        mPos?.text = formatMs(p)
        if (st.duration > 0) mSeek?.progress = (p * 1000 / st.duration).toInt().coerceIn(0, 1000)
    }

    private fun buildTimer(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(16), dp(12), dp(16), dp(14))
        }
        col.addView(tv(TimerController.label(), 13f, Palette.ORANGE, true))
        val time = tv(TimerController.text(), 40f, Palette.ORANGE, true).apply { fontFeatureSettings = "tnum" }
        col.addView(time)
        val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
        val toggle = iconBtn(android.R.drawable.ic_media_pause, dp(48), Palette.ORANGE) { TimerController.toggle() }
        row.addView(toggle)
        row.addView(
            iconBtn(android.R.drawable.ic_menu_close_clear_cancel, dp(48)) { TimerController.stop(); collapse() },
            lpw(dp(48), dp(48), 0f, dp(18)),
        )
        if (TimerController.mode == TimerController.Mode.COUNTDOWN) {
            row.addView(chip("+1 mnt") { TimerController.addTime(60_000) }, lpw(-2, -2, 0f, dp(18)))
        }
        col.addView(row, lpw(-2, -2, 0f, 0, dp(8)))
        tTime = time; tToggle = toggle
        updateTimerExpanded()
        return col
    }

    private fun updateTimerExpanded() {
        tTime?.text = TimerController.text()
        tTime?.setTextColor(if (TimerController.running) Palette.ORANGE else Palette.SECONDARY)
        tToggle?.setImageResource(
            if (TimerController.running) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play
        )
    }

    private fun buildStatus(label: String, labelColor: Int, value: String, valueColor: Int, level: Int): View {
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(18), dp(12), dp(18), dp(12))
        }
        row.addView(tv(label, 14f, labelColor, true), lpw(0, -2, 1f))
        row.addView(tv(value, 15f, valueColor, true))
        if (level >= 0) row.addView(BatteryView(ctx, level, valueColor), lpw(dp(30), dp(14), 0f, dp(8)))
        return row
    }

    private fun buildRinger(): View {
        val (label, color, icon) = when (ringerMode) {
            AudioManager.RINGER_MODE_SILENT -> Triple("Senyap", Palette.RED, android.R.drawable.ic_lock_silent_mode)
            AudioManager.RINGER_MODE_VIBRATE -> Triple("Getar", Palette.ORANGE, android.R.drawable.ic_lock_silent_mode)
            else -> Triple("Dering", Palette.WHITE, android.R.drawable.ic_lock_silent_mode_off)
        }
        val row = LinearLayout(ctx).apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(16), dp(10), dp(18), dp(10))
        }
        val ic = ImageView(ctx).apply {
            setImageResource(icon)
            imageTintList = ColorStateList.valueOf(color)
            background = roundBg(Palette.FAINT, dpf(14))
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        row.addView(ic, lpw(dp(28), dp(28)))
        row.addView(View(ctx), lpw(0, 1, 1f))
        row.addView(tv(label, 15f, color, true))
        return row
    }

    private fun buildPanel(): View {
        val col = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(12), dp(12), dp(14))
        }
        col.addView(tv("Mini apps", 12f, Palette.SECONDARY, true).apply { setPadding(dp(6), 0, 0, dp(10)) })
        val grid = LinearLayout(ctx)
        fun item(emoji: String, label: String, active: Boolean = false, action: () -> Unit) {
            val cell = LinearLayout(ctx).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                setOnClickListener { haptic(); action() }
            }
            val circle = TextView(ctx).apply {
                text = emoji
                textSize = 20f
                gravity = Gravity.CENTER
                background = roundBg(if (active) accent() else Palette.FAINT, dpf(24))
            }
            cell.addView(circle, lpw(dp(48), dp(48)))
            cell.addView(tv(label, 11f, Palette.SECONDARY).apply { gravity = Gravity.CENTER }, lpw(-2, -2, 0f, 0, dp(4)))
            grid.addView(cell, lpw(0, -2, 1f))
        }
        if (torchId != null) item("🔦", "Senter", torchOn) { toggleTorch() }
        item("⏲", "Timer 5m") { TimerController.startCountdown(5 * 60_000L); collapse() }
        item("⏱", "Stopwatch") { TimerController.startStopwatch(); collapse() }
        item("📸", "Screenshot") {
            collapse()
            h.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TAKE_SCREENSHOT) }, 600)
        }
        item("🔒", "Kunci") {
            collapse()
            h.postDelayed({ svc.performGlobalAction(AccessibilityService.GLOBAL_ACTION_LOCK_SCREEN) }, 300)
        }
        item("✏", "Edit") { collapse(); setEditMode(true) }
        item("⚙", "Atur") { collapse(); openSettings() }
        col.addView(grid, ViewGroup.LayoutParams(-1, -2))
        return col
    }

    // ---------------------------------------------------------------- cahaya keliling

    /** Cahaya yang berputar mengelilingi sisi luar island. */
    private fun startGlow() {
        glowAnim?.cancel()
        val laps = prefs.int(K.GLOW_LAPS).coerceAtLeast(1)
        val lapMs = prefs.int(K.GLOW_SPEED).coerceAtLeast(200)
        root.glowColor = prefs.int(K.GLOW_COLOR)
        root.glowRainbow = prefs.bool(K.GLOW_RAINBOW)
        root.glowWidth = ctx.dp(prefs.int(K.GLOW_WIDTH) / 2f).toFloat().coerceAtLeast(1f)
        val va = ValueAnimator.ofFloat(0f, 1f)
        glowAnim = va
        va.duration = laps.toLong() * lapMs
        va.interpolator = LinearInterpolator()
        va.addUpdateListener {
            val f = it.animatedValue as Float
            root.glowAngle = f * 360f * laps - 90f
            root.glowAlpha = min(1f, f / 0.12f) * min(1f, (1f - f) / 0.25f)
            root.invalidate()
        }
        va.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                root.glowAlpha = 0f
                root.invalidate()
                setFastFrames(false)
            }
        })
        setFastFrames(true)
        va.start()
    }

    // ---------------------------------------------------------------- mode edit

    private fun setEditMode(on: Boolean) {
        if (on == editMode) return
        h.removeCallbacks(collapseTask)
        if (replying) setReplying(false)
        expandedKind = null
        notif = null
        editMode = on
        if (on) {
            haptic(30)
            val tb = buildEditToolbar()
            toolbar = tb
            root.addView(tb, FrameLayout.LayoutParams(dp(280), -2, Gravity.TOP or Gravity.CENTER_HORIZONTAL))
        } else {
            toolbar?.let { root.removeView(it) }
            toolbar = null
            posLabel = null
            editLabels.clear()
            root.strokeWidth = 0f
            root.invalidate()
            haptic()
        }
        render()
    }

    private fun renderEdit() {
        root.visibility = View.VISIBLE
        anim?.cancel()
        clearExpandedRefs()
        expanded.animate().cancel()
        expanded.removeAllViews()
        expanded.visibility = View.GONE
        compact.animate().cancel()
        compact.alpha = 0f
        shownCompact = null
        root.animate().cancel()
        root.alpha = 1f
        root.strokeColor = accent()
        root.strokeWidth = dpf(2)
        val idle = idleGeom()
        setGeom(idle[0], idle[1], idle[2])
        val tb = toolbar ?: return
        val tlp = tb.layoutParams as FrameLayout.LayoutParams
        tlp.topMargin = root.pad + idle[1].roundToInt() + dp(12)
        tb.layoutParams = tlp
        updateEditLabels()
        tb.measure(
            View.MeasureSpec.makeMeasureSpec(dp(280), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        tb.translationX = root.centerOffset
        lp.width = screenW()
        lp.height = idle[1].roundToInt() + dp(12) + tb.measuredHeight + 2 * root.pad
        updateWindow()
        setTouchable(true)
        setTouchRect(max(idle[0], dpf(280)), idle[1] + dp(12) + tb.measuredHeight)
    }

    private fun updateEditLabels() {
        editLabels.forEach { (key, v) -> v.first.text = "${prefs.int(key)}${v.second}" }
        posLabel?.text = "Posisi  X ${prefs.int(K.OFFSET_X)} · Y ${prefs.int(K.OFFSET_Y)}"
    }

    private fun handleDrag(ev: MotionEvent) {
        val d = ctx.resources.displayMetrics.density
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                dragX = touchRawX; dragY = touchRawY
                startOX = prefs.int(K.OFFSET_X); startOY = prefs.int(K.OFFSET_Y)
                pendingX = startOX; pendingY = startOY
                haptic()
            }
            MotionEvent.ACTION_MOVE -> {
                val maxX = (ctx.resources.displayMetrics.widthPixels / d / 2).toInt()
                pendingX = (startOX + (touchRawX - dragX) / d).roundToInt().coerceIn(-maxX, maxX)
                pendingY = (startOY + (touchRawY - dragY) / d).roundToInt().coerceIn(0, 300)
                applyOffsets(pendingX, pendingY)
                updateWindow()
                val tb = toolbar
                val idle = idleGeom()
                setTouchRect(max(idle[0], dpf(280)), idle[1] + dp(12) + (tb?.height ?: 0))
                posLabel?.text = "Posisi  X $pendingX · Y $pendingY"
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                prefs.put(K.OFFSET_X, pendingX)
                prefs.put(K.OFFSET_Y, pendingY)
                updateEditLabels()
            }
        }
    }

    private fun changePref(key: String, delta: Int, min: Int, max: Int) {
        prefs.put(key, (prefs.int(key) + delta).coerceIn(min, max))
        renderEdit()
    }

    /** Tombol yang mengulang aksinya selama ditahan. */
    private fun repeatBtn(label: String, action: () -> Unit) = TextView(ctx).apply {
        text = label
        textSize = 18f
        typeface = Typeface.DEFAULT_BOLD
        gravity = Gravity.CENTER
        setTextColor(Palette.WHITE)
        background = roundBg(Palette.FAINT, dpf(17))
        val repeat = object : Runnable {
            override fun run() { action(); postDelayed(this, 70) }
        }
        setOnTouchListener { v, ev ->
            when (ev.actionMasked) {
                MotionEvent.ACTION_DOWN -> { haptic(); action(); v.postDelayed(repeat, 400) }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> v.removeCallbacks(repeat)
            }
            true
        }
    }

    private fun buildEditToolbar(): View {
        editLabels.clear()
        val card = LinearLayout(ctx).apply {
            orientation = LinearLayout.VERTICAL
            background = roundBg(0xF21C1C22.toInt(), dpf(20))
            setPadding(dp(14), dp(12), dp(14), dp(12))
        }
        card.addView(tv("Seret island untuk memindahkan", 12f, Palette.SECONDARY).apply { gravity = Gravity.CENTER }, lpw(-1, -2))
        val pos = tv("", 12f, accent(), true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
        posLabel = pos
        card.addView(pos, lpw(-1, -2, 0f, 0, dp(2)))

        fun stepper(label: String, key: String, step: Int, min: Int, max: Int, suffix: String) {
            val row = LinearLayout(ctx).apply { gravity = Gravity.CENTER_VERTICAL }
            row.addView(tv(label, 13f), lpw(0, -2, 1f))
            row.addView(repeatBtn("−") { changePref(key, -step, min, max) }, lpw(dp(34), dp(34)))
            val value = tv("", 13f, Palette.WHITE, true).apply { gravity = Gravity.CENTER; fontFeatureSettings = "tnum" }
            row.addView(value, lpw(dp(62), -2))
            row.addView(repeatBtn("+") { changePref(key, step, min, max) }, lpw(dp(34), dp(34)))
            editLabels[key] = value to suffix
            card.addView(row, lpw(-1, -2, 0f, 0, dp(8)))
        }
        stepper("Lebar", K.WIDTH, 2, 20, 300, " dp")
        stepper("Tinggi", K.HEIGHT, 1, 12, 80, " dp")
        stepper("Sudut", K.RADIUS, 2, 0, 50, "%")
        stepper("Sudut diperluas", K.EXP_RADIUS, 2, 8, 48, " dp")
        stepper("Lebar diperluas", K.EXP_WIDTH, 2, 60, 100, "%")

        val btns = LinearLayout(ctx).apply { gravity = Gravity.CENTER }
        btns.addView(chip("Tengahkan") { prefs.put(K.OFFSET_X, 0); renderEdit() })
        btns.addView(chip("Selesai", accent()) {
            setEditMode(false)
            IslandBus.post(IslandEvent.PrefsChanged)
        }, lpw(-2, -2, 0f, dp(10)))
        card.addView(btns, lpw(-1, -2, 0f, 0, dp(12)))
        return card
    }

    // ---------------------------------------------------------------- aksi

    private fun toggleTorch() {
        val id = torchId ?: return
        try { cam?.setTorchMode(id, !torchOn) } catch (_: Exception) {}
        h.postDelayed({ if (expandedKind == Kind.PANEL) render() }, 150)
        scheduleCollapse(8000)
    }

    private fun setReplying(on: Boolean) {
        replying = on
        if (on) {
            h.removeCallbacks(collapseTask)
            lp.flags = lp.flags and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()
            lp.softInputMode = WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING
            updateWindow()
            render()
        } else {
            ctx.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(root.windowToken, 0)
            lp.flags = lp.flags or WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
            updateWindow()
        }
    }

    private fun sendReply(text: String) {
        val a = notif?.replyAction
        if (a != null && text.isNotBlank()) {
            try {
                val intent = Intent()
                val b = Bundle()
                a.remoteInputs.forEach { b.putCharSequence(it.resultKey, text) }
                RemoteInput.addResultsToIntent(a.remoteInputs, intent, b)
                a.actionIntent.send(ctx, 0, intent)
            } catch (_: Exception) {
            }
        }
        setReplying(false)
        collapse()
    }

    private fun copy(text: String) {
        try {
            ctx.getSystemService(ClipboardManager::class.java)
                ?.setPrimaryClip(ClipData.newPlainText("OTP", text))
        } catch (_: Exception) {
        }
    }

    private fun openNotif() {
        val n = notif ?: return
        if (!sendPI(n.contentIntent)) openPkg(n.pkg)
    }

    private fun sendPI(pi: PendingIntent?): Boolean {
        if (pi == null) return false
        return try {
            if (Build.VERSION.SDK_INT >= 34) {
                @Suppress("DEPRECATION")
                val o = ActivityOptions.makeBasic().setPendingIntentBackgroundActivityStartMode(
                    ActivityOptions.MODE_BACKGROUND_ACTIVITY_START_ALLOWED
                )
                pi.send(ctx, 0, null, null, null, null, o.toBundle())
            } else {
                pi.send()
            }
            true
        } catch (_: Exception) {
            false
        }
    }

    private fun openPkg(pkg: String?) {
        if (pkg == null) return
        try {
            ctx.packageManager.getLaunchIntentForPackage(pkg)?.let {
                it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                ctx.startActivity(it)
            }
        } catch (_: Exception) {
        }
    }

    private fun openSettings() {
        try {
            ctx.startActivity(Intent(ctx, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        } catch (_: Exception) {
        }
    }

    private fun appIcon(pkg: String?) =
        try { if (pkg != null) ctx.packageManager.getApplicationIcon(pkg) else null } catch (_: Exception) { null }

    private fun haptic(ms: Long = 12) {
        if (!prefs.bool(K.HAPTICS)) return
        try { vib?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE)) } catch (_: Exception) {}
    }
}
