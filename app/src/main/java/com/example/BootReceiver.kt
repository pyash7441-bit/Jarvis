package com.example

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        try {
            if (intent.action == Intent.ACTION_BOOT_COMPLETED) {
                Log.d("BootReceiver", "Device reboot detected. Rebuilding alarm subroutines...")
                val scheduler = JarvisAlarmManager(context)
                scheduler.rescheduleAllConfiguredAlarms()
            }
        } catch (e: Exception) {
            Log.e("BootReceiver", "Error handling boot broadcast", e)
        }
    }
}
