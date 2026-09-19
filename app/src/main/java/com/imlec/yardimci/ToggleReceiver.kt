package com.imlec.yardimci

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Bildirimdeki Duraklat / Devam et dugmesi. */
class ToggleReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        Prefs.setEnabled(context, !Prefs.enabled(context))
        CursorAccessibilityService.instance?.onSettingsChanged()
    }
}
