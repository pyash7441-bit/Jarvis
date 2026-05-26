package com.example

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.AlarmClock
import android.provider.MediaStore
import android.util.Log
import android.widget.Toast
import java.net.URLEncoder

class CommandProcessor(private val context: Context) {

    // Parses a response string and executes any found actions inside [TAG:arguments]
    fun processResponseTags(response: String, onActionTriggered: (String) -> Unit): String {
        var cleanText = response

        // 1. WhatsApp Match: [OPEN:whatsapp] or specific packages
        val openRegex = Regex("\\[OPEN:([a-zA-Z0-9]+)\\]")
        openRegex.findAll(response).forEach { match ->
            val appName = match.groupValues[1].lowercase()
            triggerAppLaunch(appName)
            onActionTriggered("Initiating app: $appName")
            cleanText = cleanText.replace(match.value, "")
        }

        // 2. Alarm Match: [ALARM:HH:MM:label]
        val alarmRegex = Regex("\\[ALARM:(\\d{2}):(\\d{2}):([\\w\\s\\d]+)\\]")
        alarmRegex.findAll(response).forEach { match ->
            val hh = match.groupValues[1].toInt()
            val mm = match.groupValues[2].toInt()
            val label = match.groupValues[3]
            setExactAlarmNative(hh, mm, label)
            onActionTriggered("Scheduled Alarm: $hh:$mm for '$label'")
            cleanText = cleanText.replace(match.value, "")
        }

        // 3. Timer Match: [TIMER:minutes]
        val timerRegex = Regex("\\[TIMER:(\\d+)\\]")
        timerRegex.findAll(response).forEach { match ->
            val minutes = match.groupValues[1].toInt()
            setTimerNative(minutes)
            onActionTriggered("Started countdown timer: $minutes mins")
            cleanText = cleanText.replace(match.value, "")
        }

        // 4. Search Match: [SEARCH:query]
        val searchRegex = Regex("\\[SEARCH:(.+)\\]")
        searchRegex.findAll(response).forEach { match ->
            val query = match.groupValues[1]
            triggerWebSearch(query)
            onActionTriggered("Searching Google for '$query'")
            cleanText = cleanText.replace(match.value, "")
        }

        // 5. Call Match: [CALL:number]
        val callRegex = Regex("\\[CALL:([0-9\\+\\- ]+)\\]")
        callRegex.findAll(response).forEach { match ->
            val number = match.groupValues[1]
            triggerCall(number)
            onActionTriggered("Initiating Phone call to: $number")
            cleanText = cleanText.replace(match.value, "")
        }

        // 6. SMS Match: [SMS:number:message]
        val smsRegex = Regex("\\[SMS:([0-9\\+\\- ]+):(.+)\\]")
        smsRegex.findAll(response).forEach { match ->
            val number = match.groupValues[1]
            val message = match.groupValues[2]
            triggerSms(number, message)
            onActionTriggered("Sending SMS to: $number")
            cleanText = cleanText.replace(match.value, "")
        }

        // 7. Local Note Match: [NOTE:text]
        val noteRegex = Regex("\\[NOTE:(.+)\\]")
        noteRegex.findAll(response).forEach { match ->
            val noteContent = match.groupValues[1].trim()
            saveLocalNoteDirectly(noteContent)
            onActionTriggered("Saved note: '$noteContent'")
            cleanText = cleanText.replace(match.value, "")
        }

        // 8. Local Todo Match: [TODO:task]
        val todoRegex = Regex("\\[TODO:(.+)\\]")
        todoRegex.findAll(response).forEach { match ->
            val todoTitle = match.groupValues[1].trim()
            saveLocalTodoDirectly(todoTitle)
            onActionTriggered("Added task: '$todoTitle'")
            cleanText = cleanText.replace(match.value, "")
        }

        return cleanText.trim()
    }

    // Direct app launching routines (18+ native Android integrations)
    private fun triggerAppLaunch(appName: String) {
        val appData = Constants.NATIVE_APP_PACKAGES[appName] ?: return
        val packageName = appData.first
        val fallbackUrl = appData.second

        try {
            // Check custom hardware-specific launches
            when (appName) {
                "camera" -> {
                    val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
                "settings" -> {
                    val intent = Intent(android.provider.Settings.ACTION_SETTINGS).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
                "phone" -> {
                    val intent = Intent(Intent.ACTION_DIAL).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(intent)
                    return
                }
            }

            // Launch standard Package via package manager
            val intent = context.packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
            } else {
                // If the app is not installed, failover to browser url
                if (fallbackUrl.isNotEmpty()) {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse(fallbackUrl)).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                } else {
                    // Fall back to Play Store
                    val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$packageName")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(playStoreIntent)
                }
            }
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Failed to launch $appName", e)
            Toast.makeText(context, "Cannot open $appName: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Set Android System Alarm
    private fun setExactAlarmNative(hour: Int, minutes: Int, label: String) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_ALARM).apply {
                putExtra(AlarmClock.EXTRA_HOUR, hour)
                putExtra(AlarmClock.EXTRA_MINUTES, minutes)
                putExtra(AlarmClock.EXTRA_MESSAGE, label)
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Alarm schedule failed", e)
        }
    }

    // Set Android System Timer
    private fun setTimerNative(minutes: Int) {
        try {
            val intent = Intent(AlarmClock.ACTION_SET_TIMER).apply {
                putExtra(AlarmClock.EXTRA_LENGTH, minutes * 60)
                putExtra(AlarmClock.EXTRA_MESSAGE, "JARVIS Timer")
                putExtra(AlarmClock.EXTRA_SKIP_UI, true)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Timer start failed", e)
        }
    }

    // Trigger phone calling
    private fun triggerCall(number: String) {
        try {
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${number.trim()}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            // Check permission, fall back to Action_DIAL if CALL_PHONE is not yet approved
            val hasPerm = context.checkSelfPermission(android.Manifest.permission.CALL_PHONE) == android.content.pm.PackageManager.PERMISSION_GRANTED
            if (hasPerm) {
                context.startActivity(intent)
            } else {
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${number.trim()}")).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(dialIntent)
            }
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Call failed", e)
        }
    }

    // Send SMS pre-filled message
    private fun triggerSms(number: String, message: String) {
        try {
            val intent = Intent(Intent.ACTION_SENDTO).apply {
                data = Uri.parse("smsto:${number.trim()}")
                putExtra("sms_body", message)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandProcessor", "SMS trigger failed", e)
        }
    }

    // Trigger Google Search
    private fun triggerWebSearch(query: String) {
        try {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=${URLEncoder.encode(query, "UTF-8")}")).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (e: Exception) {
            Log.e("CommandProcessor", "Search failed", e)
        }
    }

    // Direct helper to save note
    private fun saveLocalNoteDirectly(content: String) {
        val manager = DataManager(context)
        val list = manager.getNotes().toMutableList()
        val wordLimit = content.split(" ").take(3).joinToString(" ")
        list.add(NoteItem(title = "$wordLimit...", content = content))
        manager.saveNotes(list)
    }

    // Direct helper to save todo
    private fun saveLocalTodoDirectly(content: String) {
        val manager = DataManager(context)
        val list = manager.getTodos().toMutableList()
        list.add(TodoItem(text = content))
        manager.saveTodos(list)
    }
}
