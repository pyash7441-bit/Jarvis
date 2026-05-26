package com.example

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.util.Log
import java.util.Calendar

class JarvisAlarmManager(private val context: Context) {

    private val alarmManager = context.getSystemService(Context.ALARM_SERVICE) as AlarmManager

    companion object {
        const val EXTRA_ALARM_MESSAGE = "alarm_message"
        const val EXTRA_ALARM_ID = "alarm_id"
        
        const val BASE_MEDICINE_ID = 20000
        const val WATER_ALARM_ID = 99999
    }

    // Schedule a medicine alarm at daily HH:MM
    fun scheduleMedicineAlarm(medicine: MedicineItem) {
        val timeParts = medicine.time.split(":")
        if (timeParts.size != 2) return

        val hour = timeParts[0].toIntOrNull() ?: return
        val minute = timeParts[1].toIntOrNull() ?: return

        val calendar = Calendar.getInstance().apply {
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
            // If the time is in the past, schedule it for tomorrow
            if (timeInMillis <= System.currentTimeMillis()) {
                add(Calendar.DAY_OF_YEAR, 1)
            }
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_MESSAGE, "Sir, it is time to take your medicine: ${medicine.name}")
            putExtra(EXTRA_ALARM_ID, BASE_MEDICINE_ID + medicine.id.hashCode())
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            BASE_MEDICINE_ID + medicine.id.hashCode(),
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarmGeneric(calendar.timeInMillis, pendingIntent)
        Log.d("JarvisAlarm", "Scheduled medicine ${medicine.name} at ${medicine.time}")
    }

    fun cancelMedicineAlarm(medicine: MedicineItem) {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            BASE_MEDICINE_ID + medicine.id.hashCode(),
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    // Schedule recurring water trackers
    fun scheduleWaterReminder(minutes: Int) {
        val calendar = Calendar.getInstance().apply {
            add(Calendar.MINUTE, minutes)
        }

        val intent = Intent(context, AlarmReceiver::class.java).apply {
            putExtra(EXTRA_ALARM_MESSAGE, "Sir, please take a glass of water to maintain hydration levels.")
            putExtra(EXTRA_ALARM_ID, WATER_ALARM_ID)
        }

        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATER_ALARM_ID,
            intent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        scheduleAlarmGeneric(calendar.timeInMillis, pendingIntent)
        Log.d("JarvisAlarm", "Scheduled water reminder in $minutes minutes.")
    }

    fun cancelWaterReminder() {
        val intent = Intent(context, AlarmReceiver::class.java)
        val pendingIntent = PendingIntent.getBroadcast(
            context,
            WATER_ALARM_ID,
            intent,
            PendingIntent.FLAG_NO_CREATE or PendingIntent.FLAG_IMMUTABLE
        )
        if (pendingIntent != null) {
            alarmManager.cancel(pendingIntent)
            pendingIntent.cancel()
        }
    }

    private fun scheduleAlarmGeneric(timeMillis: Long, pendingIntent: PendingIntent) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                if (alarmManager.canScheduleExactAlarms()) {
                    alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
                } else {
                    alarmManager.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
                }
            } else {
                alarmManager.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
            }
        } catch (e: SecurityException) {
            Log.e("JarvisAlarm", "Failed scheduling exact alarm", e)
            // Fall back to general setting
            alarmManager.set(AlarmManager.RTC_WAKEUP, timeMillis, pendingIntent)
        }
    }

    // Refresh reschedule all alarms (e.g. on boot up or app initiation)
    fun rescheduleAllConfiguredAlarms() {
        val manager = DataManager(context)
        
        // 1. Medicines
        val meds = manager.getMedicines()
        for (med in meds) {
            if (med.isScheduled) {
                scheduleMedicineAlarm(med)
            }
        }

        // 2. Water
        if (manager.waterReminderEnabled) {
            scheduleWaterReminder(manager.waterReminderInterval)
        }
    }
}
