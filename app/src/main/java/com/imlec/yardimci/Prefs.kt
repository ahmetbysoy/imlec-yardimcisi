package com.imlec.yardimci

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.provider.Settings
import androidx.core.content.ContextCompat

object Prefs {
    private const val DEFAULT_SIZE_DP = 36
    private const val MIN_SIZE_DP = 32
    private const val MAX_SIZE_DP = 48
    private const val SIZE_MIGRATION = "sizeDpCompactV1"

    private fun p(ctx: Context) = ctx.getSharedPreferences("imlec", Context.MODE_PRIVATE)

    fun enabled(ctx: Context): Boolean = p(ctx).getBoolean("enabled", true)
    fun setEnabled(ctx: Context, v: Boolean) = p(ctx).edit().putBoolean("enabled", v).apply()

    fun sizeDp(ctx: Context): Int {
        val prefs = p(ctx)
        if (!prefs.getBoolean(SIZE_MIGRATION, false)) {
            // Önceki varsayılan 44 dp idi; kompakt sürümde yalnızca eski varsayılanı küçült.
            val old = prefs.getInt("sizeDp", DEFAULT_SIZE_DP)
            val compact = if (old == 44) DEFAULT_SIZE_DP else old.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
            prefs.edit().putInt("sizeDp", compact).putBoolean(SIZE_MIGRATION, true).apply()
            return compact
        }
        return prefs.getInt("sizeDp", DEFAULT_SIZE_DP).coerceIn(MIN_SIZE_DP, MAX_SIZE_DP)
    }

    fun setSizeDp(ctx: Context, v: Int) {
        p(ctx).edit()
            .putInt("sizeDp", v.coerceIn(MIN_SIZE_DP, MAX_SIZE_DP))
            .putBoolean(SIZE_MIGRATION, true)
            .apply()
    }
}

object Perms {
    fun notifications(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED

    fun accessibility(ctx: Context): Boolean {
        val enabled = Settings.Secure.getString(
            ctx.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
        ) ?: return false
        val me = ComponentName(ctx, CursorAccessibilityService::class.java)
        return enabled.split(':').any { ComponentName.unflattenFromString(it) == me }
    }

    fun openAccessibilitySettings(ctx: Context) {
        ctx.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
    }
}
