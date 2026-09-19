package com.imlec.yardimci

import android.accessibilityservice.AccessibilityService
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.SystemClock
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import kotlin.math.max
import kotlin.math.min

/**
 * Metin kutusuna odaklanilinca imlecin yakininda mini sol/sag oklar cizer.
 * Oklar odaktaki kutunun secimini ACTION_SET_SELECTION ile bir karakter kaydirir.
 * Yazilan metin okunmaz/kaydedilmez; yalnizca imlec konumu ve uzunluk icin kullanilir.
 */
class CursorAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: CursorAccessibilityService? = null
        private const val CHANNEL_ID = "cursor_status"
        private const val NOTIF_ID = 2001
    }

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var wm: WindowManager
    private var overlay: LinearLayout? = null
    private var overlayLp: WindowManager.LayoutParams? = null
    private var attached = false
    private var builtSizeDp = -1
    private var lastPick: AccessibilityNodeInfo? = null
    private var target: AccessibilityNodeInfo? = null
    private val lastAnchor = Rect()
    private var anchorAt = 0L

    private val updateRunnable = Runnable { update() }
    private val hideRunnable = Runnable { hideOverlay() }

    // ---------- yasam dongusu ----------

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val channel = NotificationChannel(CHANNEL_ID, "Durum", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        showNotification()
        schedule()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        when (event.eventType) {
            AccessibilityEvent.TYPE_VIEW_FOCUSED,
            AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED,
            AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED,
            AccessibilityEvent.TYPE_VIEW_CLICKED,
            AccessibilityEvent.TYPE_VIEW_LONG_CLICKED,
            AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED,
            AccessibilityEvent.TYPE_WINDOWS_CHANGED -> {
                val src = event.source
                if (src != null &&
                    (event.eventType == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED ||
                        event.eventType == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
                ) {
                    lastPick?.recycle()
                    lastPick = AccessibilityNodeInfo.obtain(src)
                    src.getBoundsInScreen(lastAnchor)
                    anchorAt = SystemClock.uptimeMillis()
                    src.recycle()
                } else {
                    src?.recycle()
                }
                schedule()
            }
        }
    }

    override fun onInterrupt() {}

    override fun onUnbind(intent: Intent?): Boolean {
        teardown()
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        teardown()
        super.onDestroy()
    }

    private fun teardown() {
        instance = null
        handler.removeCallbacksAndMessages(null)
        hideOverlay()
        lastPick?.recycle()
        lastPick = null
        target?.recycle()
        target = null
        try {
            NotificationManagerCompat.from(this).cancel(NOTIF_ID)
        } catch (e: SecurityException) {
        }
    }

    /** Ana ekrandan veya bildirimden ayar degisince cagrilir. */
    fun onSettingsChanged() {
        showNotification()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, 60)
    }

    // ---------- bildirim ----------

    private fun showNotification() {
        val on = Prefs.enabled(this)
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val toggle = PendingIntent.getBroadcast(
            this, 1, Intent(this, ToggleReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        val n = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat)
            .setContentTitle("İmleç yardımcısı")
            .setContentText(if (on) "Aktif: metin kutusunda oklar görünür" else "Duraklatıldı")
            .setContentIntent(open)
            .addAction(0, if (on) "Duraklat" else "Devam et", toggle)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
        } catch (e: SecurityException) {
        }
    }

    // ---------- yardimcilar ----------

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    @Suppress("DEPRECATION")
    private fun screen(): Point {
        return if (Build.VERSION.SDK_INT >= 30) {
            val b = wm.currentWindowMetrics.bounds
            Point(b.width(), b.height())
        } else {
            val p = Point()
            wm.defaultDisplay.getRealSize(p)
            p
        }
    }

    private fun focusedEditable(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val node = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: return null
        return if (node.isEditable) node else null
    }

    private fun hasRange(n: AccessibilityNodeInfo): Boolean {
        val s = n.textSelectionStart
        val e = n.textSelectionEnd
        return s >= 0 && e >= 0 && s != e
    }

    private fun findSelection(n: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        if (hasRange(n) || (n.isEditable && n.isFocused)) {
            return AccessibilityNodeInfo.obtain(n)
        }
        for (i in 0 until n.childCount) {
            val c = n.getChild(i) ?: continue
            val f = findSelection(c)
            c.recycle()
            if (f != null) return f
        }
        return null
    }

    private fun pickNode(): AccessibilityNodeInfo? {
        focusedEditable()?.let { return it }
        lastPick?.let { p ->
            if (p.refresh()) return AccessibilityNodeInfo.obtain(p)
        }
        val root = rootInActiveWindow ?: return null
        val found = findSelection(root)
        root.recycle()
        return found
    }

    /** Sistem seçim şeridi (Kopyala / Tümünü seç) küçük bir pencere olarak durur. */
    private fun actionModeRect(): Rect? {
        val ws = try {
            windows
        } catch (_: Exception) {
            return null
        }
        val scr = screen()
        val minH = dp(32)
        val maxH = dp(100)
        val minW = dp(96)
        var best: Rect? = null
        for (w in ws) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val b = Rect()
            w.getBoundsInScreen(b)
            if (b.height() in minH..maxH && b.width() in minW until scr.x - dp(4) &&
                b.top > dp(36) && b.bottom < scr.y - dp(24)
            ) {
                best = b
            }
        }
        return best
    }

    // ---------- gosterme / konumlandirma ----------

    private fun update() {
        if (!Prefs.enabled(this)) {
            hideOverlay()
            return
        }
        val menu = actionModeRect()
        val node = pickNode()
        val recent = SystemClock.uptimeMillis() - anchorAt < 8000 && !lastAnchor.isEmpty
        if (node == null && menu == null && !recent) {
            if (attached) {
                handler.removeCallbacks(hideRunnable)
                handler.postDelayed(hideRunnable, 250)
            }
            return
        }
        handler.removeCallbacks(hideRunnable)
        if (node != null) {
            target?.recycle()
            target = AccessibilityNodeInfo.obtain(node)
        }
        when {
            menu != null -> placeCovering(menu)
            node != null -> positionOverlay(node)
            else -> placeAt(lastAnchor.centerX(), lastAnchor.top)
        }
        node?.recycle()
    }

    private fun caretRect(node: AccessibilityNodeInfo, bounds: Rect): Rect? {
        val text = node.text ?: return null
        if (node.isShowingHintText || text.isEmpty()) return null
        val end = node.textSelectionEnd
        if (end < 0) return null
        val idx = if (end > 0) end - 1 else 0
        if (idx >= text.length) return null
        return try {
            val args = Bundle()
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_START_INDEX, idx)
            args.putInt(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_ARG_LENGTH, 1)
            if (!node.refreshWithExtraData(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY, args)) {
                return null
            }
            @Suppress("DEPRECATION")
            val arr = node.extras.getParcelableArray(AccessibilityNodeInfo.EXTRA_DATA_TEXT_CHARACTER_LOCATION_KEY)
            val r = arr?.firstOrNull() as? RectF ?: return null
            val x = (if (end > 0) r.right else r.left).toInt()
            val out = Rect(x, r.top.toInt(), x, r.bottom.toInt())
            val pad = dp(8)
            if (out.top < bounds.top - pad || out.bottom > bounds.bottom + pad ||
                out.left < bounds.left - pad || out.left > bounds.right + pad
            ) {
                null
            } else {
                out
            }
        } catch (e: Exception) {
            null
        }
    }

    private fun positionOverlay(node: AccessibilityNodeInfo) {
        val sizeDp = Prefs.sizeDp(this)
        if (overlay == null || builtSizeDp != sizeDp) buildOverlay(sizeDp)
        val view = overlay ?: return
        val lp = overlayLp ?: return

        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            hideOverlay()
            return
        }
        val scr = screen()
        val caret = caretRect(node, bounds)
        val w = view.measuredWidth
        val h = view.measuredHeight
        val gap = dp(8)

        val refX = caret?.left ?: bounds.centerX()
        val topRef = caret?.top ?: bounds.top
        val bottomRef = caret?.bottom ?: bounds.bottom

        val x = (refX - w / 2).coerceIn(dp(4), max(dp(4), scr.x - w - dp(4)))
        // Sistem seçim menüsü seçimin hemen üstünde durur; aynı yere, overlay z-order üstte.
        var y = topRef - h - gap
        if (y < dp(24)) y = bottomRef + gap
        y = y.coerceIn(0, max(0, scr.y - h))

        if (!attached) {
            lp.x = x
            lp.y = y
            wm.addView(view, lp)
            attached = true
        } else if (lp.x != x || lp.y != y) {
            lp.x = x
            lp.y = y
            wm.updateViewLayout(view, lp)
        }
    }

    private fun buildOverlay(sizeDp: Int) {
        hideOverlay()
        val h = dp(sizeDp)
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        row.gravity = Gravity.CENTER_VERTICAL
        val bg = GradientDrawable()
        bg.setColor(0xE6202024.toInt())
        bg.cornerRadius = h / 2f
        bg.setStroke(dp(1), 0x40FFFFFF)
        row.background = bg

        row.addView(arrowButton(R.drawable.ic_arrow_left, -1, h), LinearLayout.LayoutParams(h + dp(12), h))
        val divider = View(this)
        divider.setBackgroundColor(0x33FFFFFF)
        row.addView(divider, LinearLayout.LayoutParams(dp(1), (h * 0.5f).toInt()))
        row.addView(arrowButton(R.drawable.ic_arrow_right, 1, h), LinearLayout.LayoutParams(h + dp(12), h))

        val unspec = View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED)
        row.measure(unspec, unspec)

        overlay = row
        builtSizeDp = sizeDp
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS or
                WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        overlayLp = lp
    }

    private fun arrowButton(res: Int, dir: Int, h: Int): View {
        val frame = FrameLayout(this)
        val img = ImageView(this)
        img.setImageResource(res)
        img.setColorFilter(Color.WHITE)
        val s = (h * 0.5f).toInt()
        frame.addView(img, FrameLayout.LayoutParams(s, s, Gravity.CENTER))
        frame.contentDescription = if (dir < 0) "İmleci sola kaydır" else "İmleci sağa kaydır"
        frame.setOnTouchListener(RepeatTouch(dir))
        return frame
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideRunnable)
        val v = overlay
        if (v != null && attached) {
            try {
                wm.removeView(v)
            } catch (e: IllegalArgumentException) {
            }
        }
        attached = false
    }

    // ---------- imleci kaydirma ----------

    /** Basili tutunca hizlanan tekrar. */
    private inner class RepeatTouch(private val dir: Int) : View.OnTouchListener {
        private var count = 0
        private val tick = object : Runnable {
            override fun run() {
                if (!attached) return
                move(dir)
                count++
                val delay = when {
                    count < 6 -> 90L
                    count < 20 -> 55L
                    else -> 28L
                }
                handler.postDelayed(this, delay)
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.55f
                    move(dir)
                    count = 0
                    handler.removeCallbacks(tick)
                    handler.postDelayed(tick, 380)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    v.alpha = 1f
                    handler.removeCallbacks(tick)
                }
            }
            return true
        }
    }

    private fun step(text: CharSequence, pos: Int, dir: Int): Int {
        val len = text.length
        if (len == 0) return max(0, pos + dir)
        if (dir > 0) {
            var n = pos + 1
            if (pos < len && n < len && Character.isHighSurrogate(text[pos]) && Character.isLowSurrogate(text[n])) n++
            return n
        }
        if (pos <= 0) return 0
        var n = pos - 1
        if (n > 0 && n < len && Character.isLowSurrogate(text[n]) && Character.isHighSurrogate(text[n - 1])) n--
        return n
    }

    private fun move(dir: Int) {
        val node = target?.also { it.refresh() } ?: focusedEditable() ?: return
        val text: CharSequence = if (node.isShowingHintText) "" else (node.text ?: "")
        var start = node.textSelectionStart
        var end = node.textSelectionEnd
        if (start < 0 || end < 0) {
            start = text.length
            end = text.length
        }
        val target = if (start != end) {
            if (dir < 0) min(start, end) else max(start, end)
        } else {
            step(text, end, dir)
        }
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, target)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, target)
        node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
    }
}
