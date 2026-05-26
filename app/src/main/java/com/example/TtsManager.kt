package com.example

import android.content.Context
import android.speech.tts.TextToSpeech
import android.speech.tts.Voice
import android.util.Log
import java.util.Locale

class TtsManager(
    private val context: Context, 
    private val onInitCompleted: () -> Unit,
    private val onSpeechStarted: () -> Unit = {},
    private val onSpeechFinished: () -> Unit = {}
) {

    private var tts: TextToSpeech? = null
    private var isReady = false

    init {
        tts = TextToSpeech(context) { status ->
            if (status == TextToSpeech.SUCCESS) {
                isReady = true
                configureBritishMaleVoice()
                setupUtteranceListener()
                onInitCompleted()
            } else {
                Log.e("TtsManager", "TextToSpeech initialization failed.")
            }
        }
    }

    private fun setupUtteranceListener() {
        tts?.setOnUtteranceProgressListener(object : android.speech.tts.UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                onSpeechStarted()
            }
            override fun onDone(utteranceId: String?) {
                onSpeechFinished()
            }
            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                onSpeechFinished()
            }
        })
    }

    private fun configureBritishMaleVoice() {
        if (tts == null) return
        try {
            // Best effort for a deep British accent (en_GB)
            val britishLocale = Locale("en", "GB")
            val availableVoices = tts?.voices ?: emptySet()
            var selectedVoice: Voice? = null

            // Search for en-gb male voice
            for (voice in availableVoices) {
                val voiceName = voice.name.lowercase()
                if (voice.locale.language == "en" && voice.locale.country == "GB") {
                    if (voiceName.contains("male") || voiceName.contains("m-")) {
                        selectedVoice = voice
                        break
                    }
                }
            }

            // Fallback to any British voice
            if (selectedVoice == null) {
                for (voice in availableVoices) {
                    if (voice.locale.language == "en" && voice.locale.country == "GB") {
                        selectedVoice = voice
                        break
                    }
                }
            }

            if (selectedVoice != null) {
                tts?.voice = selectedVoice
                Log.d("TtsManager", "Selected UK Male/Fallback Voice: ${selectedVoice.name}")
            } else {
                tts?.language = britishLocale
                Log.d("TtsManager", "Set language to UK Locale fallback.")
            }

            // Stark-like JARVIS speaks with moderate pace and deep resonance
            tts?.setPitch(0.95f)
            tts?.setSpeechRate(0.95f)
        } catch (e: Exception) {
            Log.e("TtsManager", "Voice configuration failed", e)
        }
    }

    // Speak a text string in the chosen language context
    fun speak(text: String, lang: AppLang) {
        if (!isReady || tts == null) {
            Log.e("TtsManager", "TTS is not ready or null.")
            return
        }

        try {
            val desiredLocale = when (lang) {
                AppLang.EN -> Locale("en", "GB")
                AppLang.HI -> Locale("hi", "IN")
                AppLang.MR -> Locale("mr", "IN")
            }

            // Set language locale
            val result = tts?.setLanguage(desiredLocale)
            if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                Log.e("TtsManager", "Locale ${desiredLocale.language} missing/not supported.")
                // Force fallback to english
                tts?.setLanguage(Locale.US)
            }

            // For non-English languages, reset speech parameters to sound normal
            if (lang != AppLang.EN) {
                tts?.setPitch(1.0f)
                tts?.setSpeechRate(1.0f)
            } else {
                // Keep British accent pitch/speech rate
                tts?.setPitch(0.95f)
                tts?.setSpeechRate(0.95f)
            }

            // Queue up speech
            tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "JARVIS_ULTRA_SPEECH_ID")
        } catch (e: Exception) {
            Log.e("TtsManager", "Speak occurred exception", e)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun shutdown() {
        tts?.shutdown()
        tts = null
        isReady = false
    }
}
