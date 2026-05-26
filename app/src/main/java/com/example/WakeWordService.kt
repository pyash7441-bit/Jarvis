package com.example

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.PowerManager
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log

class WakeWordService : Service() {

    private var speechRecognizer: SpeechRecognizer? = null
    private var recognizerIntent: Intent? = null
    private var isListening = false
    private var wakeLock: PowerManager.WakeLock? = null

    companion object {
        const val CHANNEL_ID = "jarvis_wake_channel"
        const val NOTIFICATION_ID = 9183
        const val ACTION_START = "action_start_wake"
        const val ACTION_STOP = "action_stop_wake"
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        Log.d("WakeWordService", "WakeWordService Created.")
        createNotificationChannel()
        
        val hasMicPermission = androidx.core.content.ContextCompat.checkSelfPermission(
            this, android.Manifest.permission.RECORD_AUDIO
        ) == android.content.pm.PackageManager.PERMISSION_GRANTED

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q && hasMicPermission) {
                startForeground(
                    NOTIFICATION_ID,
                    getNotification(),
                    android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE
                )
            } else {
                startForeground(NOTIFICATION_ID, getNotification())
            }
        } catch (e: Exception) {
            Log.e("WakeWordService", "Failed to start foreground service safely. Trying generic fallback...", e)
            try {
                startForeground(NOTIFICATION_ID, getNotification())
            } catch (ex: Exception) {
                Log.e("WakeWordService", "Failed to start foreground service even as fallback.", ex)
            }
        }
        acquireWakeLock()
        
        // Prepare SpeechRecognizer on the main thread
        initializeRecognizer()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val action = intent?.action
        if (action == ACTION_STOP) {
            stopListening()
            stopSelf()
            return START_NOT_STICKY
        }

        startListening()
        return START_STICKY
    }

    private fun acquireWakeLock() {
        try {
            val powerManager = getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = powerManager.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "Jarvis::WakeServiceLock").apply {
                acquire(10 * 60 * 1000L /*10 minutes maximum to be safe*/)
            }
        } catch (e: Exception) {
            Log.e("WakeWordService", "Could not acquire wake lock safely", e)
        }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "JARVIS Wake Word Subroutines",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Active listening sub-relays for vocal 'Hey JARVIS' trigger detection."
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(channel)
        }
    }

    private fun getNotification(): Notification {
        val stopIntent = Intent(this, WakeWordService::class.java).apply {
            action = ACTION_STOP
        }
        val pStopIntent = PendingIntent.getService(this, 0, stopIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val openIntent = Intent(this, MainActivity::class.java)
        val pOpenIntent = PendingIntent.getActivity(this, 1, openIntent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)

        return builder
            .setContentTitle("JARVIS Vocal Scanner Active")
            .setContentText("Listening silently block for: 'Hey JARVIS', 'जार्विस'")
            .setSmallIcon(android.R.drawable.presence_online)
            .setContentIntent(pOpenIntent)
            .addAction(android.R.drawable.ic_menu_close_clear_cancel, "Deactivate Sensor", pStopIntent)
            .build()
    }

    private fun initializeRecognizer() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("WakeWordService", "RECORD_AUDIO permission not granted. Cannot initialize recognizer.")
            stopSelf()
            return
        }
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.e("WakeWordService", "Vocal recognition sensor unsupportable on this system.")
                return
            }

            speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
            speechRecognizer?.setRecognitionListener(object : RecognitionListener {
                override fun onReadyForSpeech(params: Bundle?) {}
                override fun onBeginningOfSpeech() {}
                override fun onRmsChanged(rmsdB: Float) {}
                override fun onBufferReceived(buffer: ByteArray?) {}
                override fun onEndOfSpeech() {}

                override fun onError(error: Int) {
                    // Speech recognizer times out or fails with no speech, auto restart loop
                    Log.d("WakeWordService", "Recognizer error state code: $error. Auto-cycling loop...")
                    if (isListening) {
                        restartListeningWithDelay()
                    }
                }

                override fun onResults(results: Bundle?) {
                    val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                    if (matches != null) {
                        for (text in matches) {
                            val cleanText = text.lowercase().trim()
                            if (cleanText.contains("hey jarvis") || 
                                cleanText.contains("jarvis") || 
                                cleanText.contains("जार्विस") || 
                                cleanText.contains("जार्व्हिस")
                            ) {
                                Log.d("WakeWordService", "Vocal activation caught: $cleanText!")
                                triggerActiveSectorsLaunch()
                                break
                            }
                        }
                    }
                    
                    if (isListening) {
                        startListening() // cycle loop
                    }
                }

                override fun onPartialResults(partialResults: Bundle?) {}
                override fun onEvent(eventType: Int, params: Bundle?) {}
            })
            recognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "en-US")
                putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
            }
        } catch (e: Exception) {
            Log.e("WakeWordService", "Error initializing speech recognizer in service", e)
        }
    }

    private fun startListening() {
        if (androidx.core.content.ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            Log.e("WakeWordService", "RECORD_AUDIO permission not granted. Cannot start listening.")
            stopSelf()
            return
        }
        try {
            isListening = true
            speechRecognizer?.startListening(recognizerIntent)
        } catch (e: Exception) {
            Log.e("WakeWordService", "Failed starting continuous recognition session", e)
        }
    }

    private fun restartListeningWithDelay() {
        // Debounced self restart loop to prevent high CPU utilization
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
            if (isListening) {
                startListening()
            }
        }, 1000)
    }

    private fun stopListening() {
        isListening = false
        try {
            speechRecognizer?.stopListening()
        } catch (e: Exception) {
            Log.e("WakeWordService", "Error stopping recognizer", e)
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("WakeWordService", "Error destroying recognizer", e)
        }
        speechRecognizer = null
    }

    private fun triggerActiveSectorsLaunch() {
        try {
            // Wake up app activity
            val forceOpenIntent = Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
                putExtra("WAKE_TRIGGERED", true)
            }
            startActivity(forceOpenIntent)
        } catch (e: Exception) {
            Log.e("WakeWordService", "Launch MainActivity from background error", e)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopListening()
        try {
            if (wakeLock?.isHeld == true) {
                wakeLock?.release()
            }
        } catch (e: Exception) {
            Log.e("WakeWordService", "Could not release wake lock safely", e)
        }
        Log.d("WakeWordService", "WakeWordService Destroyed.")
    }
}
