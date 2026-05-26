package com.example

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.media.RingtoneManager
import android.os.Build
import android.os.Vibrator
import android.os.VibratorManager
import android.os.VibrationEffect
import android.util.Log
import androidx.core.app.NotificationCompat

class AlarmReceiver : BroadcastReceiver() {

    companion object {
        const val CHANNEL_ID = "jarvis_alerts"
        const val CHANNEL_NAME = "JARVIS HUD Daily Alerts"
    }

    override fun onReceive(context: Context, intent: Intent) {
        try {
            val message = intent.getStringExtra(JarvisAlarmManager.EXTRA_ALARM_MESSAGE) ?: "Sir, I have scheduled reminders requiring attention."
            val alarmId = intent.getIntExtra(JarvisAlarmManager.EXTRA_ALARM_ID, 12345)

            Log.d("AlarmReceiver", "Received alarm trigger: $message (ID: $alarmId)")

            // 1. Trigger Vibration
            triggerVibration(context)

            // 2. Build & Dispatch Android Alert Notification
            val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            createNotificationChannel(notificationManager)

            // Prepare opening app main screen on click
            val openAppIntent = Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
            }
            val pendingIntent = PendingIntent.getActivity(
                context,
                alarmId,
                openAppIntent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )

            val soundUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
            val builder = NotificationCompat.Builder(context, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.stat_notify_chat) // default system icon
                .setContentTitle("J.A.R.V.I.S. Prompt")
                .setContentText(message)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setSound(soundUri)
                .setAutoCancel(true)
                .setContentIntent(pendingIntent)
                .setCategory(NotificationCompat.CATEGORY_ALARM)

            notificationManager.notify(alarmId, builder.build())

            // 3. Reschedule water reminder if repeating
            if (alarmId == JarvisAlarmManager.WATER_ALARM_ID) {
                val manager = DataManager(context)
                if (manager.waterReminderEnabled) {
                    // Schedule next glass alarm interval
                    val alarmScheduler = JarvisAlarmManager(context)
                    alarmScheduler.scheduleWaterReminder(manager.waterReminderInterval)
                }
            }
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Error receiving scheduled alarm broadcast", e)
        }
    }

    private fun triggerVibration(context: Context) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
                val vibrator = vibratorManager.defaultVibrator
                vibrator.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 300, 150, 300), -1))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
                vibrator.vibrate(longArrayOf(0, 300, 150, 300), -1)
            }
        } catch (e: Exception) {
            Log.e("AlarmReceiver", "Vibration failed", e)
        }
    }

    private fun createNotificationChannel(manager: NotificationManager) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Urgent notifications for medicine schedules and water trackers from JARVIS HUD."
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 300, 150, 300)
            }
            manager.createNotificationChannel(channel)
        }
    }
}
