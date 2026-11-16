package com.xeniaone.templeapp

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if ((intent.action == Intent.ACTION_BOOT_COMPLETED) || (intent.action == "android.intent.action.QUICKBOOT_POWERON")) {
            val prefs = context.getSharedPreferences("kiosk_settings", Context.MODE_PRIVATE)
            val isKioskModeOn = prefs.getBoolean("pref_kiosk_mode_enabled", false)
            if (isKioskModeOn) {
                val launchIntent = Intent(context, BillingActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                context.startActivity(launchIntent)
            }
        } else {
            // No action needed
        }
    }
}
