package com.imlec.yardimci

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Point
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
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

/**
 * İki net mod:
 *  INPUT  — odak editable yazı kutusu (WhatsApp, form). Oklar imleci kaydırır.
 *  SELECT — sistem seçim şeridi veya uzun basış, kutu değil. Oklar seçimi uzatır.
 * WebView ağacı taranmaz (çökme nedeni). typeAllMask yok.
 * Klavye açıkken oklar yazı alanının üstüne değil, klavyenin hemen üstünde ekran ortasında sabitlenir.
 */
class CursorAccessibilityService : AccessibilityService() {

    private enum class Mode { HIDE, INPUT, SELECT }

    companion object {
        @Volatile
        var instance: CursorAccessibilityService? = null
        private const val CHANNEL_ID = "cursor_status"
        private const val NOTIF_ID = 2001
        private const val TAG = "Imlec"
    }

    /** Beklenmeyen hataları sessizce yutma: logcat'e yaz (metin içeriği asla loglanmaz). */
    private fun warn(e: Throwable) {
        Log.w(TAG, "beklenmeyen hata", e)
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
    private var mode = Mode.HIDE

    private val updateRunnable = Runnable {
        try {
            update()
        } catch (e: Exception) {
            warn(e)
        }
    }
    private val hideRunnable = Runnable { hideOverlay() }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val channel = NotificationChannel(CHANNEL_ID, "Durum", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        showNotification()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return
        try {
            val t = event.eventType
            if (t != AccessibilityEvent.TYPE_VIEW_FOCUSED &&
                t != AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED &&
                t != AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED &&
                t != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED &&
                t != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED &&
                t != AccessibilityEvent.TYPE_WINDOWS_CHANGED
            ) {
                return
            }
            val src = event.source
            if (src != null &&
                (t == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ||
                    t == AccessibilityEvent.TYPE_VIEW_TEXT_SELECTION_CHANGED)
            ) {
                remember(src, t == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED)
            }
            src?.recycle()
            schedule()
        } catch (e: Exception) {
            warn(e)
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
        } catch (_: SecurityException) {
        }
    }

    fun onSettingsChanged() {
        showNotification()
        schedule()
    }

    private fun schedule() {
        handler.removeCallbacks(updateRunnable)
        handler.postDelayed(updateRunnable, 80)
    }

    private fun remember(src: AccessibilityNodeInfo, longPress: Boolean) {
        try {
            val range = hasRange(src)
            // Uzun basış yalnızca metin taşıyan öğede seçim sinyali sayılır (simge/düğme değil).
            val textual = longPress && !src.isPassword && !src.text.isNullOrEmpty()
            if (!range && !textual && !isInputField(src)) return
            lastPick?.recycle()
            lastPick = AccessibilityNodeInfo.obtain(src)
            src.getBoundsInScreen(lastAnchor)
            if (range || textual) {
                anchorAt = SystemClock.uptimeMillis()
            }
        } catch (e: Exception) {
            warn(e)
        }
    }

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
            .setContentText(if (on) "Aktif" else "Duraklatıldı")
            .setContentIntent(open)
            .addAction(0, if (on) "Duraklat" else "Devam et", toggle)
            .setOngoing(true)
            .setSilent(true)
            .build()
        try {
            NotificationManagerCompat.from(this).notify(NOTIF_ID, n)
        } catch (_: SecurityException) {
        }
    }

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

    private fun isInputField(n: AccessibilityNodeInfo): Boolean {
        if (!n.isEditable) return false
        val cls = n.className?.toString() ?: ""
        if (cls.contains("WebView", true)) return false
        return true
    }

    private fun hasRange(n: AccessibilityNodeInfo): Boolean {
        val s = n.textSelectionStart
        val e = n.textSelectionEnd
        return s >= 0 && e >= 0 && s != e
    }

    private fun focusedInput(): AccessibilityNodeInfo? {
        val root = rootInActiveWindow ?: return null
        val node = try {
            root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        } finally {
            root.recycle()
        } ?: return null
        return if (isInputField(node)) node else {
            node.recycle()
            null
        }
    }

    /** Klavyenin gerçek üst sınırı; okları yazı alanından çıkarıp buraya sabitlemek için. */
    private fun imeRect(): Rect? {
        val ws = try {
            windows
        } catch (_: Exception) {
            return null
        }
        var best: Rect? = null
        for (w in ws) {
            if (w.type != AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            val b = Rect()
            try {
                w.getBoundsInScreen(b)
            } catch (e: Exception) {
                warn(e)
                continue
            }
            if (b.isEmpty || b.top <= 0) continue
            val previous = best
            if (previous == null || b.height() > previous.height()) best = Rect(b)
        }
        return best
    }

    private fun imeVisible(): Boolean = imeRect() != null

    /** Sistem Kopyala şeridi: kısa, geniş, tam ekran değil. */
    private fun actionModeRect(): Rect? {
        val ws = try {
            windows
        } catch (_: Exception) {
            return null
        }
        val scr = screen()
        val minH = dp(36)
        val maxH = dp(88)
        val minW = dp(140)
        var best: Rect? = null
        for (w in ws) {
            if (w.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
            if (w.type == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue
            val b = Rect()
            w.getBoundsInScreen(b)
            if (b.height() in minH..maxH &&
                b.width() in minW..(scr.x - dp(16)) &&
                b.top in dp(40)..(scr.y - dp(80))
            ) {
                best = Rect(b)
            }
        }
        return best
    }

    private fun classify(): Mode {
        val input = focusedInput()
        val inputRange = input != null && hasRange(input)
        val recentSelect = SystemClock.uptimeMillis() - anchorAt < 6000 && !lastAnchor.isEmpty
        // Menü buluşsal yöntemi yalnızca seçim kanıtı varken çalışır: rastgele küçük bir pencere
        // (snackbar, başka overlay) seçim menüsü sanılmasın.
        val menu = if (recentSelect || inputRange) actionModeRect() else null
        val pickEditable = try {
            lastPick?.refresh() == true && lastPick?.let { isInputField(it) } == true
        } catch (e: Exception) {
            warn(e)
            false
        }

        val result = when {
            menu != null && input == null -> Mode.SELECT
            menu != null && inputRange -> Mode.SELECT
            input != null && (imeVisible() || input.isFocused) -> Mode.INPUT
            recentSelect && !pickEditable -> Mode.SELECT
            else -> Mode.HIDE
        }
        input?.recycle()
        return result
    }

    private fun update() {
        if (!Prefs.enabled(this)) {
            hideOverlay()
            return
        }
        mode = classify()
        when (mode) {
            Mode.HIDE -> {
                if (attached) {
                    handler.removeCallbacks(hideRunnable)
                    handler.postDelayed(hideRunnable, 200)
                }
            }
            Mode.INPUT -> {
                handler.removeCallbacks(hideRunnable)
                val n = focusedInput()
                if (n != null) {
                    swapTarget(n)
                    val ime = imeRect()
                    if (ime != null) placeAboveKeyboard(ime) else positionOverlay(n)
                    n.recycle()
                } else {
                    hideOverlay()
                }
            }
            Mode.SELECT -> {
                handler.removeCallbacks(hideRunnable)
                lastPick?.let { p ->
                    try {
                        if (p.refresh()) swapTarget(p)
                    } catch (e: Exception) {
                        warn(e)
                    }
                }
                val ime = imeRect()
                val menu = actionModeRect()
                if (ime != null) placeAboveKeyboard(ime)
                else if (menu != null) placeAboveMenu(menu)
                else if (!lastAnchor.isEmpty) placeAt(lastAnchor.centerX(), lastAnchor.top)
                else hideOverlay()
            }
        }
    }

    private fun swapTarget(n: AccessibilityNodeInfo) {
        target?.recycle()
        target = AccessibilityNodeInfo.obtain(n)
    }

    private fun caretRect(node: AccessibilityNodeInfo, bounds: Rect): Rect? {
        if (node.isPassword) return null
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
            ) null else out
        } catch (_: Exception) {
            null
        }
    }

    private fun positionOverlay(node: AccessibilityNodeInfo) {
        ensureOverlay()
        val view = overlay ?: return
        val lp = overlayLp ?: return
        val bounds = Rect()
        node.getBoundsInScreen(bounds)
        if (bounds.isEmpty) {
            hideOverlay()
            return
        }
        val caret = caretRect(node, bounds)
        val w = view.measuredWidth
        val h = view.measuredHeight
        val gap = dp(8)
        val refX = caret?.left ?: bounds.centerX()
        val topRef = caret?.top ?: bounds.top
        val bottomRef = caret?.bottom ?: bounds.bottom
        val scr = screen()
        val x = (refX - w / 2).coerceIn(dp(4), max(dp(4), scr.x - w - dp(4)))
        var y = topRef - h - gap
        if (y < dp(24)) y = bottomRef + gap
        applyPos(view, lp, x, y)
    }

    /** Klavye açıkken sabit hedef: ekranın ortası, klavyenin hemen üstü. */
    private fun placeAboveKeyboard(ime: Rect) {
        ensureOverlay()
        val view = overlay ?: return
        val lp = overlayLp ?: return
        val scr = screen()
        val x = (scr.x - view.measuredWidth) / 2
        val y = ime.top - view.measuredHeight - dp(6)
        applyPos(view, lp, x, y)
    }

    private fun placeAboveMenu(menu: Rect) {
        ensureOverlay()
        val view = overlay ?: return
        val lp = overlayLp ?: return
        val w = view.measuredWidth
        val h = view.measuredHeight
        var y = menu.top - h - dp(6)
        if (y < dp(24)) y = menu.bottom + dp(6)
        applyPos(view, lp, menu.centerX() - w / 2, y)
    }

    private fun placeAt(refX: Int, topRef: Int) {
        ensureOverlay()
        val view = overlay ?: return
        val lp = overlayLp ?: return
        val h = view.measuredHeight
        var y = topRef - h - dp(8)
        if (y < dp(24)) y = topRef + dp(8)
        applyPos(view, lp, refX - view.measuredWidth / 2, y)
    }

    private fun ensureOverlay() {
        val sizeDp = Prefs.sizeDp(this)
        if (overlay == null || builtSizeDp != sizeDp) buildOverlay(sizeDp)
    }

    private fun applyPos(view: View, lp: WindowManager.LayoutParams, x: Int, y: Int) {
        val scr = screen()
        val nx = x.coerceIn(0, max(0, scr.x - view.measuredWidth))
        val ny = y.coerceIn(0, max(0, scr.y - view.measuredHeight))
        try {
            if (!attached) {
                lp.x = nx
                lp.y = ny
                wm.addView(view, lp)
                attached = true
            } else if (lp.x != nx || lp.y != ny) {
                lp.x = nx
                lp.y = ny
                wm.updateViewLayout(view, lp)
            }
        } catch (e: Exception) {
            warn(e)
            attached = false
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
        frame.contentDescription = if (dir < 0) "sola" else "sağa"
        frame.setOnTouchListener(RepeatTouch(dir))
        return frame
    }

    private fun hideOverlay() {
        handler.removeCallbacks(hideRunnable)
        val v = overlay
        if (v != null && attached) {
            try {
                wm.removeView(v)
            } catch (_: IllegalArgumentException) {
            }
        }
        attached = false
    }

    private inner class RepeatTouch(private val dir: Int) : View.OnTouchListener {
        private var count = 0
        private val tick = object : Runnable {
            override fun run() {
                if (!attached) return
                move(dir, true)
                count++
                val delay = when {
                    count < 6 -> 90L
                    count < 20 -> 55L
                    else -> 32L
                }
                handler.postDelayed(this, delay)
            }
        }

        override fun onTouch(v: View, e: MotionEvent): Boolean {
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    v.alpha = 0.55f
                    move(dir, false)
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

    private val breaker: android.icu.text.BreakIterator by lazy {
        android.icu.text.BreakIterator.getCharacterInstance()
    }

    /** Bir "kullanıcı karakteri" (grapheme) ileri/geri: ZWJ emojiler, bayraklar, cilt tonları tek adım. */
    private fun step(text: CharSequence, pos: Int, dir: Int): Int {
        val len = text.length
        if (len == 0) return 0
        val p = pos.coerceIn(0, len)
        try {
            val bi = breaker
            bi.setText(text.toString())
            val r = if (dir > 0) bi.following(p) else bi.preceding(p)
            if (r != android.icu.text.BreakIterator.DONE) return r.coerceIn(0, len)
            return if (dir > 0) len else 0
        } catch (e: Exception) {
            warn(e)
        }
        return stepUtf16(text, p, dir)
    }

    /** Yedek: yalnızca surrogate çiftine saygılı adım. */
    private fun stepUtf16(text: CharSequence, pos: Int, dir: Int): Int {
        val len = text.length
        if (dir > 0) {
            var n = pos + 1
            if (pos < len && n < len && Character.isHighSurrogate(text[pos]) && Character.isLowSurrogate(text[n])) n++
            return n.coerceAtMost(len)
        }
        if (pos <= 0) return 0
        var n = pos - 1
        if (n > 0 && n < len && Character.isLowSurrogate(text[n]) && Character.isHighSurrogate(text[n - 1])) n--
        return n.coerceAtLeast(0)
    }

    private fun refreshOk(n: AccessibilityNodeInfo): Boolean =
        try {
            n.refresh()
        } catch (e: Exception) {
            warn(e)
            false
        }

    private fun setSel(node: AccessibilityNodeInfo, start: Int, end: Int): Boolean {
        val args = Bundle()
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, start)
        args.putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, end)
        return try {
            node.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, args)
        } catch (e: Exception) {
            warn(e)
            false
        }
    }

    /**
     * Sistemin karakter granülerliği: grapheme/RTL güvenli, metni çekmez.
     * extend=true: seçimi uzatır, anchor korunur, yalnızca odak ucu oynar.
     */
    private fun moveByGranularity(node: AccessibilityNodeInfo, dir: Int, extend: Boolean): Boolean {
        val args = Bundle()
        args.putInt(
            AccessibilityNodeInfo.ACTION_ARGUMENT_MOVEMENT_GRANULARITY_INT,
            AccessibilityNodeInfo.MOVEMENT_GRANULARITY_CHARACTER
        )
        args.putBoolean(AccessibilityNodeInfo.ACTION_ARGUMENT_EXTEND_SELECTION_BOOLEAN, extend)
        val action = if (dir > 0)
            AccessibilityNodeInfo.ACTION_NEXT_AT_MOVEMENT_GRANULARITY
        else
            AccessibilityNodeInfo.ACTION_PREVIOUS_AT_MOVEMENT_GRANULARITY
        return try {
            node.performAction(action, args)
        } catch (e: Exception) {
            warn(e)
            false
        }
    }

    private fun dragHandle(dir: Int) {
        // Seçim menüsü görünmüyorsa asla dokunma jesti gönderme (yanlış yere basmayı önler).
        val menu = actionModeRect() ?: return
        val box = Rect(lastAnchor)
        val scr = screen()
        if (box.isEmpty || box.width() > scr.x * 0.9 || box.height() > scr.y * 0.5) {
            box.set(menu.centerX() - dp(20), menu.bottom, menu.centerX() + dp(20), menu.bottom + dp(40))
        }
        val x = if (dir > 0) box.right.toFloat() - 6f else box.left.toFloat() + 6f
        val y = box.bottom.toFloat() - 6f
        val path = Path()
        path.moveTo(x, y)
        path.lineTo(x + dir * dp(28), y)
        try {
            dispatchGesture(
                GestureDescription.Builder()
                    .addStroke(GestureDescription.StrokeDescription(path, 0, 80))
                    .build(),
                null,
                null
            )
        } catch (e: Exception) {
            warn(e)
        }
    }

    /** repeat=true: basılı tutma tıkı; her tıkta refresh/metin çekmeden hızlı yol kullanılır. */
    private fun move(dir: Int, repeat: Boolean) {
        try {
            when (mode) {
                Mode.INPUT -> moveInput(dir, repeat)
                Mode.SELECT -> moveSelect(dir)
                Mode.HIDE -> {}
            }
        } catch (e: Exception) {
            warn(e)
        }
    }

    private fun moveInput(dir: Int, repeat: Boolean) {
        val node = target ?: focusedInput() ?: return
        if (!repeat && refreshOk(node) && hasRange(node)) {
            // İlk basış: aralık seçiliyse ok tuşu gibi daralt (sola = başa, sağa = sona).
            val lo = minOf(node.textSelectionStart, node.textSelectionEnd)
            val hi = maxOf(node.textSelectionStart, node.textSelectionEnd)
            val p = if (dir < 0) lo else hi
            setSel(node, p, p)
            return
        }
        if (moveByGranularity(node, dir, false)) return

        // Yedek yol (uygulama granülerliği desteklemiyorsa). Şifre alanında metne hiç bakılmaz.
        if (node.isPassword) return
        val live = if (refreshOk(node)) node else (focusedInput() ?: return)
        val text: CharSequence = if (live.isShowingHintText) "" else (live.text ?: "")
        var start = live.textSelectionStart
        var end = live.textSelectionEnd
        if (start < 0 || end < 0) {
            start = text.length
            end = text.length
        }
        val lo = minOf(start, end)
        val hi = maxOf(start, end)
        val p = if (lo != hi) {
            if (dir < 0) lo else hi
        } else {
            step(text, hi, dir)
        }
        setSel(live, p, p)
    }

    private fun moveSelect(dir: Int) {
        val node = target?.also { refreshOk(it) } ?: lastPick
        if (node != null) {
            // 1) Sistemin seçim uzatması: anchor korunur, yalnızca odak ucu oynar.
            if (moveByGranularity(node, dir, true)) return

            // 2) Yedek: anchor = textSelectionStart, odak = textSelectionEnd. Swap YOK, anchor sabit kalır.
            if (isInputField(node) && !node.isPassword) {
                val text: CharSequence = if (node.isShowingHintText) "" else (node.text ?: "")
                val anchor = node.textSelectionStart
                val focus = node.textSelectionEnd
                if (anchor >= 0 && focus >= 0 && text.isNotEmpty()) {
                    val nf = step(text, focus, dir).coerceIn(0, text.length)
                    if (nf != focus && setSel(node, anchor, nf)) return
                }
            }
        }
        // 3) Son çare (web seçimi): yalnızca yakın zamanda gerçek seçim sinyali varsa.
        if (SystemClock.uptimeMillis() - anchorAt < 6000) {
            handler.post { dragHandle(dir) }
        }
    }
}
