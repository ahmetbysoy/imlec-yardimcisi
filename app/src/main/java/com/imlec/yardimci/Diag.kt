package com.imlec.yardimci

import android.os.SystemClock

/**
 * Bellek içi kısa tanılama kaydı (en fazla 200 satır, diske yazılmaz).
 * Yalnızca modlar ve sayılar tutulur: yazılan metin ASLA kaydedilmez.
 */
object Diag {
    private const val MAX = 200
    private val lines = ArrayDeque<String>()
    private val t0 = SystemClock.uptimeMillis()

    @Synchronized
    fun log(msg: String) {
        val t = SystemClock.uptimeMillis() - t0
        lines.addLast("${t / 1000}.${(t % 1000).toString().padStart(3, '0')} $msg")
        while (lines.size > MAX) lines.removeFirst()
    }

    @Synchronized
    fun dump(): String = lines.joinToString("\n")

    @Synchronized
    fun clear() {
        lines.clear()
    }
}
