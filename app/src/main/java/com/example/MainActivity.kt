package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationManager
import android.os.Bundle
import android.os.CountDownTimer
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

@OptIn(ExperimentalFoundationApi::class)
class MainActivity : ComponentActivity() {

    private lateinit var dataManager: DataManager
    private lateinit var apiRepository: ApiRepository
    private lateinit var commandProcessor: CommandProcessor
    private lateinit var jarvisAlarmManager: JarvisAlarmManager
    private var ttsManager: TtsManager? = null
    private var speechRecognizer: SpeechRecognizer? = null

    // State bindings
    private val chatMessages = mutableStateListOf<ChatMessage>()
    private var currentOrbState by mutableStateOf(OrbState.IDLE)
    private var currentTheme by mutableStateOf(HudTheme.ARC_BLUE)
    private var currentLang by mutableStateOf(AppLang.EN)
    private var offlineModeUnlocked by mutableStateOf(false)
    private var showPinScreen by mutableStateOf(false)

    // Location Weather parameters
    private var weatherState = mutableStateOf<WeatherInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        dataManager = DataManager(this)
        apiRepository = ApiRepository(this)
        commandProcessor = CommandProcessor(this)
        jarvisAlarmManager = JarvisAlarmManager(this)

        // Bootstrap data defaults
        currentTheme = dataManager.theme
        currentLang = dataManager.language
        offlineModeUnlocked = dataManager.offlineModeActive
        showPinScreen = dataManager.pinCode.isNotEmpty()

        // Init TTS Engines on Main UI Thread with premium callbacks
        ttsManager = TtsManager(
            context = this,
            onInitCompleted = {
                runOnUiThread {
                    // Speak greeting on launch
                    speakWittyGreeting()
                }
            },
            onSpeechStarted = {
                runOnUiThread {
                    currentOrbState = OrbState.SPEAKING
                }
            },
            onSpeechFinished = {
                runOnUiThread {
                    currentOrbState = OrbState.IDLE
                }
            }
        )

        // Init System Speech Recognizer if permission granted
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            initializeSpeechRecognizerOnMain()
        }

        // Auto Refresh scheduled alarms on startup
        jarvisAlarmManager.rescheduleAllConfiguredAlarms()

        // Sync initial weather logs
        fetchStandardWeatherDefault()

        // Start continuous background vocal scanner if set and permission is granted
        if (dataManager.wakeWordEnabled && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED) {
            triggerBackgroundVocalScanner(true)
        }

        setContent {
            MyApplicationTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = getThemeBackgroundColor(currentTheme)
                ) {
                    if (showPinScreen) {
                        PinLockOverlay(
                            savedPin = dataManager.pinCode,
                            onPinPassed = {
                                showPinScreen = false
                            }
                        )
                    } else {
                        MainHudDashboard()
                    }
                }
            }
        }
    }

    private fun triggerBackgroundVocalScanner(start: Boolean) {
        if (start && ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Vocal scanner requires microphone permissions, sir.", Toast.LENGTH_LONG).show()
            return
        }
        try {
            val serviceIntent = Intent(this, WakeWordService::class.java).apply {
                action = if (start) WakeWordService.ACTION_START else WakeWordService.ACTION_STOP
            }
            if (start) {
                ContextCompat.startForegroundService(this, serviceIntent)
            } else {
                stopService(serviceIntent)
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Vocal service trigger failure", e)
        }
    }

    // Handles the UI greeting parameters
    private fun speakWittyGreeting() {
        val hour = Calendar.getInstance().get(Calendar.HOUR_OF_DAY)
        val welcome = when {
            hour < 12 -> "Good morning, sir. Diagnostics are fully green. I am online and listening."
            hour < 17 -> "Good afternoon, sir. My satellite subroutines are locked and operating optimally."
            else -> "Good evening, sir. The Arc Core is glowing steadily. What shall we construct tonight?"
        }
        chatMessages.add(ChatMessage(text = welcome, sender = "JARVIS"))
        ttsManager?.speak(welcome, currentLang)
    }

    private fun initializeSpeechRecognizerOnMain() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Log.w("MainActivity", "Speech recognizer initialization deferred: Record Audio permission not granted yet.")
            return
        }
        try {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                Log.w("MainActivity", "Speech recognizer not accessible on device.")
                return
            }
            if (speechRecognizer == null) {
                speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                    setRecognitionListener(object : RecognitionListener {
                        override fun onReadyForSpeech(params: Bundle?) {
                            runOnUiThread {
                                currentOrbState = OrbState.LISTENING
                            }
                        }
                        override fun onBeginningOfSpeech() {}
                        override fun onRmsChanged(rmsdB: Float) {}
                        override fun onBufferReceived(buffer: ByteArray?) {}
                        override fun onEndOfSpeech() {}
                        override fun onError(error: Int) {
                            runOnUiThread {
                                currentOrbState = OrbState.IDLE
                                val desc = when (error) {
                                    SpeechRecognizer.ERROR_NO_MATCH -> "No speech matching caught, sir."
                                    SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS -> "Permission denied: Microphone access locked."
                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT, SpeechRecognizer.ERROR_NETWORK -> "Sensor network timeout, sir."
                                    else -> "Relay anomaly: code $error"
                                }
                                chatMessages.add(ChatMessage(text = desc, sender = "ERROR"))
                                ttsManager?.speak(desc, currentLang)
                            }
                        }

                        override fun onResults(results: Bundle?) {
                            runOnUiThread {
                                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                                if (!matches.isNullOrEmpty()) {
                                    val speechText = matches[0]
                                    chatMessages.add(ChatMessage(text = speechText, sender = "USER"))
                                    processUserStatement(speechText)
                                } else {
                                    currentOrbState = OrbState.IDLE
                                }
                            }
                        }

                        override fun onPartialResults(partialResults: Bundle?) {}
                        override fun onEvent(eventType: Int, params: Bundle?) {}
                    })
                }
            }
        } catch (e: Exception) {
            Log.e("MainActivity", "Failed to initialize speech recognizer on main thread", e)
        }
    }

    // Process spoken input or textual prompt
    private fun processUserStatement(text: String) {
        currentOrbState = OrbState.THINKING
        lifecycleScope.launch {
            val key = if (currentTheme == HudTheme.GOLD_MODE) "GOLD_API_KEY" else BuildConfig.GEMINI_API_KEY
            val rawReply = apiRepository.generateJarvisResponse(text, chatMessages.toList(), key)

            // Strip action tags, execute corresponding intents
            val finalReply = commandProcessor.processResponseTags(rawReply) { actionStatus ->
                // System notification diagnostic feedback
                chatMessages.add(ChatMessage(text = "[System Action]: $actionStatus", sender = "SYSTEM"))
            }

            chatMessages.add(ChatMessage(text = finalReply, sender = "JARVIS"))
            ttsManager?.speak(finalReply, currentLang)
        }
    }

    private fun triggerVoiceSensorTaptoSpeak() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Microphone access is locked. Please configure permissions tab, sir.", Toast.LENGTH_LONG).show()
            return
        }

        // Initialize dynamically if deferred
        if (speechRecognizer == null) {
            initializeSpeechRecognizerOnMain()
        }

        ttsManager?.stop() // stop current speech
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, when (currentLang) {
                AppLang.EN -> "en-US"
                AppLang.HI -> "hi-IN"
                AppLang.MR -> "mr-IN"
            })
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, false)
        }
        
        try {
            speechRecognizer?.startListening(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Mic sensor failed: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    // Default GPS/City weather loading
    @SuppressLint("MissingPermission")
    private fun fetchStandardWeatherDefault() {
        lifecycleScope.launch {
            try {
                var lat = 19.076
                var lon = 72.877
                var cityName = "Mumbai"

                val isGpSAllowed = ContextCompat.checkSelfPermission(this@MainActivity, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
                if (isGpSAllowed) {
                    val lm = getSystemService(Context.LOCATION_SERVICE) as LocationManager
                    val loc: Location? = lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER) ?: lm.getLastKnownLocation(LocationManager.GPS_PROVIDER)
                    if (loc != null) {
                        lat = loc.latitude
                        lon = loc.longitude
                        cityName = "GPS Location"
                    }
                }

                val result = apiRepository.fetchWeather(lat, lon, cityName)
                if (result != null) {
                    weatherState.value = result
                }
            } catch (e: Exception) {
                Log.e("MainActivity", "Weather sync error", e)
            }
        }
    }

    // --- Core Master UI Layout Composable ---
    @Composable
    fun MainHudDashboard() {
        val context = LocalContext.current
        var activePanelIndex by remember { mutableStateOf(0) }
        var textPrompt by remember { mutableStateOf("") }
        val currentColors = getHudThemeColors(currentTheme)

        // Permission launcher
        val permissionLauncher = rememberLauncherForActivityResult(
            contract = ActivityResultContracts.RequestMultiplePermissions()
        ) { permissions ->
            val micGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
            if (micGranted) {
                initializeSpeechRecognizerOnMain()
                if (dataManager.wakeWordEnabled) {
                    triggerBackgroundVocalScanner(true)
                }
            } else {
                Toast.makeText(context, "Voice command capability is suspended due to locked mic permissions.", Toast.LENGTH_LONG).show()
            }
        }

        LaunchedEffect(Unit) {
            // Request permissions on startup
            permissionLauncher.launch(
                arrayOf(
                    Manifest.permission.RECORD_AUDIO,
                    Manifest.permission.ACCESS_COARSE_LOCATION,
                    Manifest.permission.POST_NOTIFICATIONS,
                    Manifest.permission.CALL_PHONE
                )
            )
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing)
                .drawBehind {
                    // Draw digital tactical grids & sci-fi scanning lines overlay
                    drawSciFiBackgroundGrids(currentColors.first)
                }
        ) {
            // 1. Diagnostics System Status Header
            DiagnosticsHeader(activePanelIndex)

            // 2. Main content swap panels with Orb in the Middle
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                // Interactive core displays depending on panel
                when (activePanelIndex) {
                    0 -> ChatMessagesPanel(currentColors.first) { activePanelIndex = it }
                    1 -> StudyToolsPanel(currentColors.first)
                    2 -> EarnMoneyPanel(currentColors.first)
                    3 -> DailyLifePanel(currentColors.first)
                    4 -> HealthDeskPanel(currentColors.first)
                    5 -> StockWatchPanel(currentColors.first)
                    6 -> WeatherForecastPanel(currentColors.first)
                    7 -> HabitTrackerPanel(currentColors.first)
                    8 -> ChecklistPanel(currentColors.first)
                    9 -> CoreSystemSettingsPanel()
                }

                // Superimposed Hovering Arc Reactor micro orb at bottom-right corner for quick taps!
                if (activePanelIndex != 0) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(16.dp)
                            .size(72.dp)
                            .clip(CircleShape)
                            .clickable { triggerVoiceSensorTaptoSpeak() },
                        contentAlignment = Alignment.Center
                    ) {
                        ArcReactorOrbGraphics(
                            state = currentOrbState,
                            colors = currentColors,
                            size = 72.dp
                        )
                    }
                }
            }

            // 3. Central Controller Dock (Displayed inside chat tab)
            if (activePanelIndex == 0) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                    border = BorderStroke(1.dp, currentColors.first.copy(alpha = 0.3f))
                ) {
                    Column(
                        modifier = Modifier.padding(8.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        // The Majestic Pulsing Arc Reactor Orb
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(120.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceEvenly
                        ) {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("SYSTEM RELAY", fontSize = 10.sp, color = currentColors.first.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
                                Text(
                                    text = when (currentOrbState) {
                                        OrbState.IDLE -> "ONLINE"
                                        OrbState.LISTENING -> "LISTENING"
                                        OrbState.THINKING -> "COMPUTING"
                                        OrbState.SPEAKING -> "VOCALIZING"
                                    },
                                    fontSize = 12.sp,
                                    color = when (currentOrbState) {
                                        OrbState.IDLE -> currentColors.first
                                        OrbState.LISTENING -> Color.Green
                                        OrbState.THINKING -> Color(0xFFD080FF)
                                        OrbState.SPEAKING -> Color(0xFFFFD700)
                                    },
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }

                            // Tap to Speak Arc Reactor Orb
                            Box(
                                modifier = Modifier
                                    .size(100.dp)
                                    .clip(CircleShape)
                                    .clickable { triggerVoiceSensorTaptoSpeak() }
                                    .testTag("submit_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                ArcReactorOrbGraphics(
                                    state = currentOrbState,
                                    colors = currentColors,
                                    size = 100.dp
                                )
                            }

                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text("WAVE OSCILLATOR", fontSize = 10.sp, color = currentColors.first.copy(alpha = 0.7f), fontFamily = FontFamily.Monospace)
                                SpeechOscillatorVisualizer(currentOrbState, currentColors.first)
                            }
                        }

                        // Text query box
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            TextField(
                                value = textPrompt,
                                onValueChange = { textPrompt = it },
                                modifier = Modifier
                                    .weight(1f)
                                    .height(52.dp)
                                    .testTag("username_input"),
                                placeholder = { Text("Command J.A.R.V.I.S., sir...", fontSize = 13.sp, color = currentColors.first.copy(alpha = 0.5f)) },
                                colors = TextFieldDefaults.colors(
                                    focusedContainerColor = Color.Black.copy(alpha = 0.5f),
                                    unfocusedContainerColor = Color.Black.copy(alpha = 0.3f),
                                    focusedIndicatorColor = currentColors.first,
                                    unfocusedIndicatorColor = currentColors.first.copy(alpha = 0.3f),
                                    focusedTextColor = Color.White,
                                    unfocusedTextColor = Color.White
                                ),
                                textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace),
                                singleLine = true,
                                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Text)
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            IconButton(
                                onClick = {
                                    if (textPrompt.isNotEmpty()) {
                                        chatMessages.add(ChatMessage(text = textPrompt, sender = "USER"))
                                        processUserStatement(textPrompt)
                                        textPrompt = ""
                                    }
                                },
                                modifier = Modifier
                                    .size(52.dp)
                                    .background(currentColors.first.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                                    .border(1.dp, currentColors.first.copy(alpha = 0.4f), RoundedCornerShape(4.dp))
                            ) {
                                Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send", tint = currentColors.first)
                            }
                        }
                    }
                }
            }

            // 4. Scrollable tactical panel selector (Tabs)
            ScrollablePanelTabDock(
                activeIndex = activePanelIndex,
                onActiveChange = { activePanelIndex = it },
                accentColor = currentColors.first
            )
        }
    }

    // --- Sub Panels / Tabs Modules ---

    @Composable
    fun ChatMessagesPanel(accent: Color, onPanelChange: (Int) -> Unit) {
        val currentColors = getHudThemeColors(currentTheme)
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(8.dp)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 4.dp, vertical = 2.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    "TACTICAL CHAT INTERFACE",
                    fontSize = 11.sp,
                    color = accent,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace
                )
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    IconButton(
                        onClick = { exportChatHistoryLogs() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Share, contentDescription = "Export Chat", tint = accent, modifier = Modifier.size(16.dp))
                    }
                    IconButton(
                        onClick = { chatMessages.clear() },
                        modifier = Modifier.size(28.dp)
                    ) {
                        Icon(Icons.Filled.Delete, contentDescription = "Clear Chat", tint = accent, modifier = Modifier.size(16.dp))
                    }
                }
            }

            if (chatMessages.isEmpty()) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .padding(12.dp)
                ) {
                    // Overlays:
                    // 1. Top left microscopic badges (🛡️, ⚡) with thin cyan border and background
                    Row(
                        modifier = Modifier.align(Alignment.TopStart),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(accent.copy(alpha = 0.05f), CircleShape)
                                .border(1.dp, accent.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("🛡️", fontSize = 12.sp)
                        }
                        Box(
                            modifier = Modifier
                                .size(32.dp)
                                .background(accent.copy(alpha = 0.05f), CircleShape)
                                .border(1.dp, accent.copy(alpha = 0.3f), CircleShape),
                            contentAlignment = Alignment.Center
                        ) {
                            Text("⚡", fontSize = 12.sp)
                        }
                    }

                    // 2. Top right progress meters & hydration (High Density Power Reserve statistics)
                    Column(
                        modifier = Modifier.align(Alignment.TopEnd),
                        horizontalAlignment = Alignment.End,
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text("RESERVE", fontSize = 8.sp, color = accent.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                            Box(
                                modifier = Modifier
                                    .width(48.dp)
                                    .height(6.dp)
                                    .background(accent.copy(alpha = 0.15f), RoundedCornerShape(3.dp))
                                    .border(1.dp, accent.copy(alpha = 0.3f), RoundedCornerShape(3.dp))
                            ) {
                                Box(
                                    modifier = Modifier
                                        .fillMaxHeight()
                                        .fillMaxWidth(0.75f)
                                        .background(accent, RoundedCornerShape(3.dp))
                                )
                            }
                        }

                        Text(
                            text = "${dataManager.waterCount}/8 L",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            fontFamily = FontFamily.Monospace
                        )
                        Text(
                            text = "HYDRATION LEVEL",
                            fontSize = 8.sp,
                            color = accent.copy(alpha = 0.5f),
                            fontFamily = FontFamily.Monospace
                        )
                    }

                    // 3. Central Cockpit layout containing Large Arc Reactor Orb + Ready Subtitles + Sound Waves
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.Center)
                            .padding(top = 28.dp, bottom = 120.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        ArcReactorOrbGraphics(
                            state = currentOrbState,
                            colors = currentColors,
                            size = 180.dp
                        )

                        Spacer(modifier = Modifier.height(16.dp))

                        Text(
                            text = "READY FOR COMMAND, SIR",
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold,
                            color = accent,
                            fontFamily = FontFamily.Monospace,
                            letterSpacing = 1.sp
                        )

                        Spacer(modifier = Modifier.height(6.dp))

                        Row(
                            horizontalArrangement = Arrangement.spacedBy(3.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val active = currentOrbState == OrbState.LISTENING || currentOrbState == OrbState.SPEAKING
                            val waveCount = 8
                            val transition = rememberInfiniteTransition()
                            val animValues = List(waveCount) { index ->
                                transition.animateFloat(
                                    initialValue = 0.2f,
                                    targetValue = 1.0f,
                                    animationSpec = infiniteRepeatable(
                                        animation = tween(180 + (index * 70), easing = FastOutSlowInEasing),
                                        repeatMode = RepeatMode.Reverse
                                    )
                                )
                            }
                            for (i in 0 until waveCount) {
                                val speedFactor = if (active) animValues[i].value else 0.15f
                                Box(
                                    modifier = Modifier
                                        .width(3.dp)
                                        .height(16.dp * speedFactor)
                                        .background(accent, RoundedCornerShape(1.dp))
                                )
                            }
                        }
                    }

                    // 4. Fine-grained cyber data grid cards & horizontal quick tags scrolling at the baseline
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .align(Alignment.BottomCenter)
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(10.dp)
                        ) {
                            // Cyber stock card widget with translucent dark-slate backdrop & accent frame
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(84.dp)
                                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.2f)), RoundedCornerShape(12.dp)),
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.8f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("STOCKS", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent, fontFamily = FontFamily.Monospace)
                                        Box(
                                            modifier = Modifier
                                                .background(Color(0xFF10B981).copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("+2.4%", fontSize = 8.sp, color = Color(0xFF10B981), fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Column {
                                        Text("RELIANCE.BSE", fontSize = 12.sp, color = Color.White, fontFamily = FontFamily.Monospace)
                                        Text("₹2,942.50", fontSize = 10.sp, color = accent.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }

                            // Active custom task list checklist status card
                            Card(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(84.dp)
                                    .border(BorderStroke(1.dp, accent.copy(alpha = 0.2f)), RoundedCornerShape(12.dp))
                                    .clickable { onPanelChange(8) },
                                colors = CardDefaults.cardColors(containerColor = Color(0xFF0F172A).copy(alpha = 0.8f))
                            ) {
                                Column(
                                    modifier = Modifier
                                        .fillMaxSize()
                                        .padding(10.dp),
                                    verticalArrangement = Arrangement.SpaceBetween
                                ) {
                                    Row(
                                        modifier = Modifier.fillMaxWidth(),
                                        horizontalArrangement = Arrangement.SpaceBetween,
                                        verticalAlignment = Alignment.CenterVertically
                                    ) {
                                        Text("OFFLINE TASK", fontSize = 9.sp, fontWeight = FontWeight.Bold, color = accent, fontFamily = FontFamily.Monospace)
                                        Box(
                                            modifier = Modifier
                                                .background(accent.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                                                .padding(horizontal = 4.dp, vertical = 1.dp)
                                        ) {
                                            Text("ACTIVE", fontSize = 8.sp, color = accent, fontWeight = FontWeight.Bold)
                                        }
                                    }
                                    Column {
                                        val taskList = dataManager.getTodos()
                                        val firstActiveTask = taskList.firstOrNull { !it.isCompleted }?.text ?: "Complete Math Lab Report"
                                        Text(
                                            text = firstActiveTask,
                                            fontSize = 11.sp,
                                            color = Color.White,
                                            maxLines = 1,
                                            fontFamily = FontFamily.Monospace
                                        )
                                        Text("Due: 4:00 PM", fontSize = 9.sp, color = accent.copy(alpha = 0.6f), fontFamily = FontFamily.Monospace)
                                    }
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))

                        // High fidelity scroll badges shortcut panels
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            val badges = listOf(
                                "SETTINGS" to 9,
                                "FIVERR" to 2,
                                "WEATHER" to 6,
                                "DAILY ROUTINE" to 3,
                                "STUDY MATRIX" to 1
                            )
                            badges.forEach { (label, targetIndex) ->
                                Box(
                                    modifier = Modifier
                                        .background(accent.copy(alpha = 0.1f), CircleShape)
                                        .border(1.dp, accent.copy(alpha = 0.3f), CircleShape)
                                        .clickable { onPanelChange(targetIndex) }
                                        .padding(horizontal = 12.dp, vertical = 6.dp)
                                ) {
                                    Text(
                                        text = label,
                                        fontSize = 9.sp,
                                        color = Color.White,
                                        fontWeight = FontWeight.Bold,
                                        fontFamily = FontFamily.Monospace
                                    )
                                }
                            }
                        }
                    }
                }
            } else {
                LazyColumn(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    reverseLayout = false
                ) {
                    items(chatMessages) { msg ->
                        ChatBubbleLayout(msg, accent)
                    }
                }
            }
        }
    }

    @Composable
    fun ChatBubbleLayout(msg: ChatMessage, accent: Color) {
        val alignment = if (msg.sender == "USER") Alignment.End else Alignment.Start
        val bgColor = when (msg.sender) {
            "USER" -> accent.copy(alpha = 0.15f)
            "JARVIS" -> Color.Black.copy(alpha = 0.6f)
            "SYSTEM" -> Color(0xFFD080FF).copy(alpha = 0.1f)
            else -> Color.Red.copy(alpha = 0.1f)
        }
        val borderColor = when (msg.sender) {
            "USER" -> accent.copy(alpha = 0.5f)
            "JARVIS" -> accent.copy(alpha = 0.25f)
            "SYSTEM" -> Color(0xFFD080FF).copy(alpha = 0.3f)
            else -> Color.Red.copy(alpha = 0.3f)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 4.dp),
            contentAlignment = when (msg.sender) {
                "USER" -> Alignment.CenterEnd
                else -> Alignment.CenterStart
            }
        ) {
            Card(
                modifier = Modifier
                    .widthIn(max = 280.dp)
                    .border(BorderStroke(1.dp, borderColor), RoundedCornerShape(6.dp)),
                colors = CardDefaults.cardColors(containerColor = bgColor)
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    Text(
                        text = if (msg.sender == "USER") "SIR" else msg.sender,
                        fontSize = 9.sp,
                        color = if (msg.sender == "USER") accent else Color.White.copy(alpha = 0.7f),
                        fontWeight = FontWeight.Bold,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = msg.text,
                        fontSize = 13.sp,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // Panels 1: Study Tools Panel
    @Composable
    fun StudyToolsPanel(accent: Color) {
        var queryText by remember { mutableStateOf("") }
        var studyOutput by remember { mutableStateOf("Input queries, sir, or select quick templates...") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("STUDY COGNITIVE SECTORS", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Step solver & Essay intro form box
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    TextField(
                        value = queryText,
                        onValueChange = { queryText = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("E.g. 2x + 10 = 30 or Topic...", fontSize = 12.sp, color = Color.Gray) },
                        colors = TextFieldDefaults.colors(
                            focusedContainerColor = Color.Black,
                            unfocusedContainerColor = Color.Black,
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        Button(
                            onClick = {
                                if (queryText.isEmpty()) {
                                    studyOutput = "Syntax error. Enter algebraic query, sir."
                                    return@Button
                                }
                                studyOutput = "STEP-BY-STEP MATHEMATICAL SOLUTION:\n\n" +
                                        "Query: $queryText\n" +
                                        "Step 1: Parse algebraic bounds...\n" +
                                        "Step 2: Balance numeric values sequentially:\n" +
                                        "        - Shifting constant parameters...\n" +
                                        "Step 3: Dividers computation complete.\n" +
                                        "Result: Solved offlinely!\n" +
                                        "Answer estimation: x is ready."
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.2f))
                        ) {
                            Text("Math Solver", fontSize = 11.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                if (queryText.isEmpty()) {
                                    studyOutput = "Please enter visual topic theme, sir."
                                    return@Button
                                }
                                studyOutput = "ESSAY INTRODUCTION BLUEPRINT:\n\n" +
                                        "\"In the modern landscape, '$queryText' representing a multifaceted paradigm shift...\""
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.2f))
                        ) {
                            Text("Essay Intro", fontSize = 11.sp, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Output view
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.2f))
            ) {
                Text(
                    text = studyOutput,
                    modifier = Modifier.padding(10.dp),
                    fontSize = 12.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick templates grids
            Text("QUICK STUDY SECTORS", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { studyOutput = Constants.FORMULA_SHEET },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f))
                ) {
                    Text("Formula Sheet", fontSize = 9.sp, color = Color.White)
                }
                Button(
                    onClick = { studyOutput = "STUDY WEEK EXAM TIMETABLE:\n• Day 1-2: Core Theory concepts recap\n• Day 3: Mock tests & diagnostics\n• Day 4: Complex formulas drill\n• Day 5: Exam ready!" },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f))
                ) {
                    Text("Exam Plan", fontSize = 9.sp, color = Color.White)
                }
            }
        }
    }

    // Panel 2: Earn Money
    @Composable
    fun EarnMoneyPanel(accent: Color) {
        var clientName by remember { mutableStateOf("") }
        var amountBill by remember { mutableStateOf("") }
        var workDesc by remember { mutableStateOf("") }
        var sheetOutput by remember { mutableStateOf("Income Blueprint summary displays here...") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("STUDENT REVENUE BLUEPRINTS", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Client invoice generator
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("CLIENT BILLING GENERATOR (.TXT)", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    TextField(
                        value = clientName,
                        onValueChange = { clientName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Client/Professor Name", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextField(
                            value = amountBill,
                            onValueChange = { amountBill = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Amount 1000 INR", fontSize = 11.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TextField(
                            value = workDesc,
                            onValueChange = { workDesc = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Graphics / Web Coding", fontSize = 11.sp) }
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            if (clientName.isEmpty() || amountBill.isEmpty()) return@Button
                            val billStr = "==============================\n" +
                                    "  JARVIS BILLING COGNITIONS\n" +
                                    "==============================\n" +
                                    "Client Name: $clientName\n" +
                                    "Work Log: $workDesc\n" +
                                    "Service Fee: $amountBill INR\n" +
                                    "Date: ${getCurrentFormattedDate()}\n" +
                                    "Uplink ID: JV-${Random().nextInt(90000) + 10000}\n" +
                                    "Status: Pending sir"
                            sheetOutput = billStr
                            exportStringTextFile("Invoice-$clientName.txt", billStr)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("Create & Download Invoice", color = Color.Black, fontWeight = FontWeight.Bold)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Indian Student low budget ideas
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { sheetOutput = Constants.INCOME_GUIDES["fiverr"].orEmpty() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f))
                ) {
                    Text("Fiverr Guide", fontSize = 10.sp)
                }
                Button(
                    onClick = { sheetOutput = Constants.INCOME_GUIDES["upwork"].orEmpty() },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f))
                ) {
                    Text("Upwork Guide", fontSize = 10.sp)
                }
                Button(
                    onClick = {
                        sheetOutput = "10 INDIAN STUDENT LOW INVESTMENT IDEAS:\n\n" +
                                "1. Local Content/Social handle management (${'$'}0)\n" +
                                "2. Digital Banner styling for stores (${'$'}0)\n" +
                                "3. Custom exam mock papers preparation (${'$'}0)\n" +
                                "4. Old textbook buy-back reseller program (${'$'}10)\n" +
                                "5. Regional Language Translation subroutines (${'$'}0)"
                    },
                    modifier = Modifier.weight(1.2f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.15f))
                ) {
                    Text("Local Ideas", fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Output log
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.2f))
            ) {
                Text(
                    text = sheetOutput,
                    modifier = Modifier.padding(10.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        }
    }

    // Panel 3: Daily Life Panel
    @Composable
    fun DailyLifePanel(accent: Color) {
        var lifeOutput by remember { mutableStateOf("Co-ordinate routine metrics...") }
        var isTimerActive by remember { mutableStateOf(false) }
        var timerTick by remember { mutableStateOf(900) } // 15 mins (15 * 60)
        var countdownTimerRef: CountDownTimer? by remember { mutableStateOf(null) }

        DisposableEffect(Unit) {
            onDispose {
                countdownTimerRef?.cancel()
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("DAILY ROUTINE & TRAINING", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // 15m cardio interactive timer
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
            ) {
                Column(
                    modifier = Modifier.padding(10.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("15-MIN CARDIO INTENSE SCANNER", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(
                        text = String.format("%02d:%02d", timerTick / 60, timerTick % 60),
                        fontSize = 32.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                        fontFamily = FontFamily.Monospace
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        Button(
                            onClick = {
                                if (isTimerActive) {
                                    countdownTimerRef?.cancel()
                                    isTimerActive = false
                                } else {
                                    isTimerActive = true
                                    countdownTimerRef = object : CountDownTimer(timerTick * 1000L, 1000) {
                                        override fun onTick(millisUntilFinished: Long) {
                                            timerTick = (millisUntilFinished / 1000L).toInt()
                                        }
                                        override fun onFinish() {
                                            isTimerActive = false
                                            timerTick = 900
                                            ttsManager?.speak("Well completed, sir. Rest subroutines are complete.", currentLang)
                                        }
                                    }.start()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.2f))
                        ) {
                            Text(if (isTimerActive) "Pause" else "Start 15m Burn")
                        }
                        Button(
                            onClick = {
                                countdownTimerRef?.cancel()
                                isTimerActive = false
                                timerTick = 900
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.2f))
                        ) {
                            Text("Reset")
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Button(
                    onClick = { lifeOutput = Constants.WORKOUT_PLAN_15M },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f))
                ) {
                    Text("Workout", fontSize = 10.sp)
                }
                Button(
                    onClick = { lifeOutput = Constants.MORNING_ROUTINE },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f))
                ) {
                    Text("Routine", fontSize = 10.sp)
                }
                Button(
                    onClick = { lifeOutput = Constants.STUDENT_MEAL_PLAN },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f))
                ) {
                    Text("Meal Plan", fontSize = 10.sp)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 100.dp),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.7f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.2f))
            ) {
                Text(
                    text = lifeOutput,
                    modifier = Modifier.padding(10.dp),
                    fontSize = 11.sp,
                    fontFamily = FontFamily.Monospace,
                    color = Color.White
                )
            }
        }
    }

    // Panel 4: Health Desk Panel
    @Composable
    fun HealthDeskPanel(accent: Color) {
        var waterCountState by remember { mutableStateOf(dataManager.waterCount) }
        var heightInput by remember { mutableStateOf("") }
        var weightInput by remember { mutableStateOf("") }
        var bmiResult by remember { mutableStateOf("Input BMI metrics...") }

        // Medicine form parameters
        var medName by remember { mutableStateOf("") }
        var medTime by remember { mutableStateOf("") }
        var medicinesList = remember { mutableStateListOf<MedicineItem>().apply { addAll(dataManager.getMedicines()) } }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("BIOMETRICS & HEAL DESK", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Water Tracker (Visual glass stack representation)
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("HYDRATION GLASS TRACKER (RESET DAILY)", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text("Glasses target: $waterCountState / 8", fontSize = 13.sp, color = Color.White)
                        Button(
                            onClick = {
                                val count = dataManager.incrementWater()
                                waterCountState = count
                                if (count == 8) {
                                    ttsManager?.speak("Splendid, sir. You have successfully achieved your daily hydration goal.", currentLang)
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text("Drink 1 Glass", color = Color.Black, fontWeight = FontWeight.Bold)
                        }
                    }
                    Spacer(modifier = Modifier.height(8.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        for (i in 1..8) {
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(30.dp)
                                    .background(
                                        if (i <= waterCountState) accent.copy(alpha = 0.8f) else Color.DarkGray,
                                        RoundedCornerShape(2.dp)
                                    )
                                    .border(1.dp, Color.Black)
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // BMI Calculator
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("BMI COMPUTATIONS SENSOR", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextField(
                            value = heightInput,
                            onValueChange = { heightInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Height (cm)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                        TextField(
                            value = weightInput,
                            onValueChange = { weightInput = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Weight (kg)") },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                        )
                    }
                    Spacer(modifier = Modifier.height(6.dp))
                    Button(
                        onClick = {
                            val h = heightInput.toDoubleOrNull() ?: return@Button
                            val w = weightInput.toDoubleOrNull() ?: return@Button
                            val bmi = w / ((h / 100) * (h / 100))
                            val category = when {
                                bmi < 18.5 -> "UNDERWEIGHT (Yellow)"
                                bmi < 24.9 -> "OPTIMAL FIT (Green)"
                                bmi < 29.9 -> "OVERWEIGHT (Orange)"
                                else -> "OBESE RISK (Red)"
                            }
                            bmiResult = String.format("BMI Ratio: %.2f\nAssessment: %s", bmi, category)
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.2f))
                    ) {
                        Text("Compute BMI", color = Color.White)
                    }
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = bmiResult,
                        fontSize = 12.sp,
                        fontFamily = FontFamily.Monospace,
                        color = Color.White
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Medicine reminder panel
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.3f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("MEDICINE ALARMS PROTOCOL", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                    TextField(
                        value = medName,
                        onValueChange = { medName = it },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text("Medicine Name", fontSize = 12.sp) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        TextField(
                            value = medTime,
                            onValueChange = { medTime = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Time HH:MM (eg 08:30)", fontSize = 12.sp) }
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (medName.isEmpty() || medTime.isEmpty()) return@Button
                                val newMed = MedicineItem(name = medName, time = medTime)
                                medicinesList.add(newMed)
                                dataManager.saveMedicines(medicinesList.toList())
                                jarvisAlarmManager.scheduleMedicineAlarm(newMed)
                                medName = ""
                                medTime = ""
                                Toast.makeText(this@MainActivity, "Medicine scheduled, sir.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accent)
                        ) {
                            Text("Add Alert", color = Color.Black)
                        }
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Text("ACTIVE MEDICINE ALERTS:", fontSize = 10.sp, color = accent, fontFamily = FontFamily.Monospace)
                    medicinesList.forEach { med ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 2.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text("💊 ${med.name} at [${med.time}]", fontSize = 12.sp, color = Color.White)
                            IconButton(
                                onClick = {
                                    jarvisAlarmManager.cancelMedicineAlarm(med)
                                    medicinesList.remove(med)
                                    dataManager.saveMedicines(medicinesList.toList())
                                },
                                modifier = Modifier.size(24.dp)
                            ) {
                                Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(16.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Panel 5: Stock Watch Panel
    @Composable
    fun StockWatchPanel(accent: Color) {
        var querySymbol by remember { mutableStateOf("") }
        var lookupStockState by remember { mutableStateOf<StockWatch?>(null) }
        var watchlistSymbols = remember { mutableStateListOf<String>().apply { addAll(dataManager.stockWatchlist.split(",").filter { it.isNotEmpty() }) } }
        var stockListDisplay = remember { mutableStateListOf<StockWatch>() }

        LaunchedEffect(watchlistSymbols.size) {
            // Fetch prices for watchlist
            stockListDisplay.clear()
            watchlistSymbols.forEach { sym ->
                val res = apiRepository.fetchStockPrice(sym)
                if (res != null) {
                    stockListDisplay.add(res)
                }
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text("EQUITIES & CRYPTO SCANNER", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Search bar
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = querySymbol,
                    onValueChange = { querySymbol = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("AAPL, BTC-USD, RELIANCE.BSE", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (querySymbol.isEmpty()) return@Button
                        lifecycleScope.launch {
                            val res = apiRepository.fetchStockPrice(querySymbol)
                            if (res != null) {
                                lookupStockState = res
                                if (!watchlistSymbols.contains(res.symbol)) {
                                    watchlistSymbols.add(res.symbol)
                                    dataManager.stockWatchlist = watchlistSymbols.joinToString(",")
                                }
                            } else {
                                Toast.makeText(this@MainActivity, "Ticker details resolved offline/failed.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Search", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Single item lookup view
            lookupStockState?.let { stock ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.5f))
                ) {
                    Row(
                        modifier = Modifier.padding(10.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(stock.symbol, fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold)
                            Text("Yahoo Finance Core Live Node", fontSize = 10.sp, color = Color.Gray)
                        }
                        Column(horizontalAlignment = Alignment.End) {
                            Text(String.format("$%.2f", stock.price), fontSize = 16.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text(
                                text = String.format("%.2f%%", stock.changePercent),
                                color = if (stock.changePercent >= 0) Color.Green else Color.Red,
                                fontSize = 12.sp
                            )
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text("WATCHLIST DOCK (TAP TO REFRESH, LONG PRESS DELETES)", fontSize = 10.sp, color = accent, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(stockListDisplay) { stock ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .border(1.dp, accent.copy(alpha = 0.15f), RoundedCornerShape(4.dp))
                            .combinedClickable(
                                onClick = {
                                    // refresh single item
                                    lifecycleScope.launch {
                                        val refreshed = apiRepository.fetchStockPrice(stock.symbol)
                                        if (refreshed != null) {
                                            val index = stockListDisplay.indexOfFirst { it.symbol == stock.symbol }
                                            if (index >= 0) {
                                                stockListDisplay[index] = refreshed
                                            }
                                        }
                                    }
                                },
                                onLongClick = {
                                    watchlistSymbols.remove(stock.symbol)
                                    dataManager.stockWatchlist = watchlistSymbols.joinToString(",")
                                    stockListDisplay.remove(stock)
                                }
                            )
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(stock.symbol, color = accent, fontSize = 14.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Bold)
                        Row {
                            Text(String.format("$%.2f", stock.price), color = Color.White, fontSize = 13.sp, fontFamily = FontFamily.Monospace)
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = String.format("%.2f%%", stock.changePercent),
                                color = if (stock.changePercent >= 0) Color.Green else Color.Red,
                                fontSize = 12.sp,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                }
            }
        }
    }

    // Panel 6: Weather Forecast Panel
    @Composable
    fun WeatherForecastPanel(accent: Color) {
        var queryCity by remember { mutableStateOf("") }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text("METEOROLOGICAL TELEMETRY", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Query Search bar
            Row(modifier = Modifier.fillMaxWidth()) {
                TextField(
                    value = queryCity,
                    onValueChange = { queryCity = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Enter City...", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (queryCity.isEmpty()) return@Button
                        lifecycleScope.launch {
                            val coords = apiRepository.geocodeCity(queryCity)
                            if (coords != null) {
                                val info = apiRepository.fetchWeather(coords.first, coords.second, queryCity)
                                if (info != null) {
                                    weatherState.value = info
                                    ttsManager?.speak("Weather resolved for $queryCity, sir. The temperature is ${info.temp} degrees.", currentLang)
                                }
                            } else {
                                Toast.makeText(this@MainActivity, "Geocoding failure: city not found.", Toast.LENGTH_SHORT).show()
                            }
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Search", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Quick preset selectors
            val standardCities = listOf("Mumbai", "Delhi", "Pune", "Bangalore", "Jaipur", "London")
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                standardCities.forEach { city ->
                    Button(
                        onClick = {
                            lifecycleScope.launch {
                                val coords = apiRepository.geocodeCity(city)
                                if (coords != null) {
                                    val info = apiRepository.fetchWeather(coords.first, coords.second, city)
                                    if (info != null) {
                                        weatherState.value = info
                                    }
                                }
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(horizontal = 8.dp)
                    ) {
                        Text(city, fontSize = 10.sp, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Forecast logs
            weatherState.value?.let { weather ->
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.5f)),
                    border = BorderStroke(1.dp, accent.copy(alpha = 0.4f))
                ) {
                    Column(modifier = Modifier.padding(10.dp)) {
                        Text("CITY: ${weather.cityName.uppercase()}", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Column {
                                Text("Temp: ${weather.temp}°C", fontSize = 24.sp, color = Color.White, fontWeight = FontWeight.Bold)
                                Text("Feels like: ${weather.feelsLike}°C", fontSize = 12.sp, color = Color.LightGray)
                            }
                            Column(horizontalAlignment = Alignment.End) {
                                Text("Humidity: ${weather.humidity}%", fontSize = 12.sp, color = Color.LightGray)
                                Text("Wind: ${weather.windSpeed} km/h", fontSize = 12.sp, color = Color.LightGray)
                            }
                        }

                        Spacer(modifier = Modifier.height(10.dp))
                        Text("3-DAY RADAR FORECAST ESTIMATES:", fontSize = 10.sp, color = accent, fontFamily = FontFamily.Monospace)
                        Spacer(modifier = Modifier.height(4.dp))
                        weather.forecast.forEach { fd ->
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 2.dp),
                                horizontalArrangement = Arrangement.SpaceBetween
                            ) {
                                Text(fd.dayName, color = Color.White, fontSize = 12.sp)
                                Text("Max: ${fd.tempMax} / Min: ${fd.tempMin}°C [${fd.description}]", color = accent, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
                            }
                        }
                    }
                }
            } ?: Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
                contentAlignment = Alignment.Center
            ) {
                Text("Hydrate network coordinates, weather state currently offline...", color = Color.DarkGray, fontSize = 12.sp)
            }
        }
    }

    // Panel 7: Habit Tracker Panel
    @Composable
    fun HabitTrackerPanel(accent: Color) {
        var habitName by remember { mutableStateOf("") }
        var habitsList = remember { mutableStateListOf<HabitItem>().apply { addAll(dataManager.getHabits()) } }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Text("PERSISTENT ROUTINES & STREAKS", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Add Form
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = habitName,
                    onValueChange = { habitName = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Exercise, Reading, Meditate...", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (habitName.isEmpty()) return@Button
                        val model = HabitItem(name = habitName)
                        habitsList.add(model)
                        dataManager.saveHabits(habitsList.toList())
                        habitName = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Add", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            // Presets row
            val presets = listOf("Cold Shower", "Code 1hr", "Sleep early", "No Socials")
            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                presets.forEach { label ->
                    Button(
                        onClick = {
                            if (!habitsList.any { it.name.lowercase() == label.lowercase() }) {
                                val model = HabitItem(name = label)
                                habitsList.add(model)
                                dataManager.saveHabits(habitsList.toList())
                            }
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text(label, fontSize = 9.sp, color = Color.White)
                    }
                }
            }

            Spacer(modifier = Modifier.height(10.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(habitsList) { habit ->
                    val today = SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
                    val isCompletedToday = habit.lastCompletedDate == today

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp)
                            .background(Color.Black.copy(alpha = 0.3f), RoundedCornerShape(4.dp))
                            .border(1.dp, accent.copy(alpha = 0.2f), RoundedCornerShape(4.dp))
                            .padding(8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text(habit.name, fontSize = 14.sp, color = Color.White)
                            Text("Streak Count: 🔥 ${habit.streak} days", fontSize = 12.sp, color = accent, fontWeight = FontWeight.Bold)
                        }
                        Row {
                            Button(
                                onClick = {
                                    if (!isCompletedToday) {
                                        val updatedHabit = habit.copy(
                                            streak = habit.streak + 1,
                                            lastCompletedDate = today
                                        )
                                        val index = habitsList.indexOfFirst { it.id == habit.id }
                                        if (index >= 0) {
                                            habitsList[index] = updatedHabit
                                            dataManager.saveHabits(habitsList.toList())
                                        }
                                    }
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = if (isCompletedToday) Color.DarkGray else accent,
                                    disabledContainerColor = accent.copy(alpha = 0.2f)
                                ),
                                enabled = !isCompletedToday
                            ) {
                                Text(if (isCompletedToday) "Locked" else "Mark Done", fontSize = 11.sp, color = Color.White)
                            }
                            Spacer(modifier = Modifier.width(4.dp))
                            IconButton(onClick = {
                                habitsList.remove(habit)
                                dataManager.saveHabits(habitsList.toList())
                            }) {
                                Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(18.dp))
                            }
                        }
                    }
                }
            }
        }
    }

    // Panel 8: Todo Checklist & Notes
    @Composable
    fun ChecklistPanel(accent: Color) {
        var todoText by remember { mutableStateOf("") }
        var todoItems = remember { mutableStateListOf<TodoItem>().apply { addAll(dataManager.getTodos()) } }

        var noteTitle by remember { mutableStateOf("") }
        var noteBody by remember { mutableStateOf("") }
        var noteItems = remember { mutableStateListOf<NoteItem>().apply { addAll(dataManager.getNotes()) } }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(12.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("TASKS & CHRONICLES", fontSize = 14.sp, color = accent, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
                Row {
                    Button(
                        onClick = {
                            val listStr = todoItems.joinToString("\n") { "[${if (it.isCompleted) "X" else " "}] ${it.text}" }
                            exportStringTextFile("TODO-List.txt", "==============================\n  JARVIS TASK CHECKLIST\n==============================\n\n$listStr")
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = accent.copy(alpha = 0.1f)),
                        contentPadding = PaddingValues(horizontal = 6.dp)
                    ) {
                        Text("Export Todo", fontSize = 10.sp, color = Color.White)
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))

            // Todo Input Box
            Row(modifier = Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextField(
                    value = todoText,
                    onValueChange = { todoText = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Compile Mark VI armor...", fontSize = 12.sp) }
                )
                Spacer(modifier = Modifier.width(6.dp))
                Button(
                    onClick = {
                        if (todoText.isEmpty()) return@Button
                        val item = TodoItem(text = todoText)
                        todoItems.add(item)
                        dataManager.saveTodos(todoItems.toList())
                        todoText = ""
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = accent)
                ) {
                    Text("Add", color = Color.Black)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Checklist lists
            LazyColumn(modifier = Modifier.height(130.dp)) {
                items(todoItems) { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                            Checkbox(
                                checked = item.isCompleted,
                                onCheckedChange = { isChecked ->
                                    val updated = item.copy(isCompleted = isChecked)
                                    val index = todoItems.indexOfFirst { it.id == item.id }
                                    if (index >= 0) {
                                        todoItems[index] = updated
                                        dataManager.saveTodos(todoItems.toList())
                                    }
                                }
                            )
                            Text(
                                text = item.text,
                                fontSize = 13.sp,
                                color = if (item.isCompleted) Color.Gray else Color.White,
                                textDecoration = if (item.isCompleted) androidx.compose.ui.text.style.TextDecoration.LineThrough else null,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                        IconButton(
                            onClick = {
                                todoItems.remove(item)
                                dataManager.saveTodos(todoItems.toList())
                            },
                            modifier = Modifier.size(28.dp)
                        ) {
                            Icon(Icons.Filled.Delete, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))
            Divider(color = accent.copy(alpha = 0.3f))
            Spacer(modifier = Modifier.height(6.dp))

            // Notebooks sector
            Text("SECURED MEMO NOTEBOOKS", fontSize = 11.sp, color = accent, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(4.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accent.copy(alpha = 0.1f))
            ) {
                Column(modifier = Modifier.padding(8.dp)) {
                    TextField(
                        value = noteTitle,
                        onValueChange = { noteTitle = it },
                        modifier = Modifier.fillMaxWidth().height(44.dp),
                        placeholder = { Text("Memo Title", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    TextField(
                        value = noteBody,
                        onValueChange = { noteBody = it },
                        modifier = Modifier.fillMaxWidth().height(60.dp),
                        placeholder = { Text("Enter detail memos structure...", fontSize = 11.sp) }
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Button(
                        onClick = {
                            if (noteTitle.isEmpty() || noteBody.isEmpty()) return@Button
                            val item = NoteItem(title = noteTitle, content = noteBody)
                            noteItems.add(item)
                            dataManager.saveNotes(noteItems.toList())
                            noteTitle = ""
                            noteBody = ""
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(containerColor = accent)
                    ) {
                        Text("Save Secured Note", color = Color.Black)
                    }
                }
            }

            Spacer(modifier = Modifier.height(6.dp))

            LazyColumn(modifier = Modifier.weight(1f)) {
                items(noteItems) { nt ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 2.dp)
                            .background(Color.Black.copy(alpha = 0.3f))
                            .border(1.dp, accent.copy(alpha = 0.15f))
                            .padding(6.dp),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Column {
                            Text(nt.title, color = accent, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                            Text(nt.content, color = Color.White, fontSize = 12.sp)
                        }
                        IconButton(onClick = {
                            noteItems.remove(nt)
                            dataManager.saveNotes(noteItems.toList())
                        }) {
                            Icon(Icons.Filled.Close, contentDescription = "Delete", tint = Color.Red, modifier = Modifier.size(16.dp))
                        }
                    }
                }
            }
        }
    }

    // Panel 9: Settings
    @Composable
    fun CoreSystemSettingsPanel() {
        val accentColors = getHudThemeColors(currentTheme)
        var pinToConfigure by remember { mutableStateOf("") }
        var pinStatusString by remember { mutableStateOf(if (dataManager.pinCode.isEmpty()) "UNSECURED" else "ENCRYPTED LOCKED") }
        var isWakeWordOn by remember { mutableStateOf(dataManager.wakeWordEnabled) }
        var isOfflineOn by remember { mutableStateOf(dataManager.offlineModeActive) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(12.dp)
        ) {
            Text("SYSTEM CONFIGURATOR SENSORS", fontSize = 14.sp, color = accentColors.first, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
            Spacer(modifier = Modifier.height(8.dp))

            // Languages row
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accentColors.first.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("SYSTEM SYNAPSE LANGUAGE", fontSize = 11.sp, color = accentColors.first, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = {
                                dataManager.language = AppLang.EN
                                currentLang = AppLang.EN
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentLang == AppLang.EN) accentColors.first else Color.DarkGray)
                        ) {
                            Text("ENG-GB", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                dataManager.language = AppLang.HI
                                currentLang = AppLang.HI
                            },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentLang == AppLang.HI) accentColors.first else Color.DarkGray)
                        ) {
                            Text("HINDI (हिंदी)", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                dataManager.language = AppLang.MR
                                currentLang = AppLang.MR
                            },
                            modifier = Modifier.weight(1.5f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentLang == AppLang.MR) accentColors.first else Color.DarkGray)
                        ) {
                            Text("MARATHI (मराठी)", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Theme switcher
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accentColors.first.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("HUD GRAPHICS THEME SELECTOR", fontSize = 11.sp, color = accentColors.first, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Button(
                            onClick = {
                                dataManager.theme = HudTheme.ARC_BLUE
                                currentTheme = HudTheme.ARC_BLUE
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentTheme == HudTheme.ARC_BLUE) accentColors.first else Color.DarkGray)
                        ) {
                            Text("Arc Blue", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                dataManager.theme = HudTheme.IRON_MAN_RED
                                currentTheme = HudTheme.IRON_MAN_RED
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentTheme == HudTheme.IRON_MAN_RED) accentColors.first else Color.DarkGray)
                        ) {
                            Text("Crimson HUD", fontSize = 10.sp, color = Color.White)
                        }
                        Button(
                            onClick = {
                                dataManager.theme = HudTheme.GOLD_MODE
                                currentTheme = HudTheme.GOLD_MODE
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(containerColor = if (currentTheme == HudTheme.GOLD_MODE) accentColors.first else Color.DarkGray)
                        ) {
                            Text("Gold Core", fontSize = 10.sp, color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Wake word and Offline toggles
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accentColors.first.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("WAKE WORD INTENTS", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Silently scanning 'Hey JARVIS' background", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = isWakeWordOn,
                            onCheckedChange = { isChecked ->
                                isWakeWordOn = isChecked
                                dataManager.wakeWordEnabled = isChecked
                                triggerBackgroundVocalScanner(isChecked)
                            }
                        )
                    }

                    Spacer(modifier = Modifier.height(6.dp))
                    Divider(color = accentColors.first.copy(alpha = 0.2f))
                    Spacer(modifier = Modifier.height(6.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column {
                            Text("FORCE OFFLINE SAFE RELAYS", fontSize = 13.sp, color = Color.White, fontWeight = FontWeight.Bold)
                            Text("Direct local queries & zero networks use", fontSize = 10.sp, color = Color.Gray)
                        }
                        Switch(
                            checked = isOfflineOn,
                            onCheckedChange = { isChecked ->
                                isOfflineOn = isChecked
                                dataManager.offlineModeActive = isChecked
                            }
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Secure PIN Configurations
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.3f)),
                border = BorderStroke(1.dp, accentColors.first.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("BIOMETRICS STARK PIN CONFIGS", fontSize = 11.sp, color = accentColors.first, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text("Current Status: $pinStatusString", fontSize = 12.sp, color = Color.White)
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextField(
                            value = pinToConfigure,
                            onValueChange = { if (it.length <= 4) pinToConfigure = it },
                            modifier = Modifier.weight(1f),
                            placeholder = { Text("Enter 4 digits PIN", fontSize = 12.sp) },
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            visualTransformation = PasswordVisualTransformation()
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Button(
                            onClick = {
                                if (pinToConfigure.length == 4) {
                                    dataManager.pinCode = pinToConfigure
                                    pinStatusString = "ENCRYPTED LOCKED"
                                    pinToConfigure = ""
                                    Toast.makeText(this@MainActivity, "Encryption Lock configured sir.", Toast.LENGTH_SHORT).show()
                                }
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = accentColors.first)
                        ) {
                            Text("Save", color = Color.Black)
                        }
                    }
                    if (pinStatusString == "ENCRYPTED LOCKED") {
                        Spacer(modifier = Modifier.height(4.dp))
                        Button(
                            onClick = {
                                dataManager.pinCode = ""
                                pinStatusString = "UNSECURED"
                                Toast.makeText(this@MainActivity, "Encryption Lock cleared, sir.", Toast.LENGTH_SHORT).show()
                            },
                            colors = ButtonDefaults.buttonColors(containerColor = Color.Red.copy(alpha = 0.8f))
                        ) {
                            Text("Clear Locked Protocol", color = Color.White)
                        }
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            // Diagnostic stats
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = Color.Black.copy(alpha = 0.6f)),
                border = BorderStroke(1.dp, accentColors.first.copy(alpha = 0.2f))
            ) {
                Column(modifier = Modifier.padding(10.dp)) {
                    Text("DIAGNOSTICS CHASSIS STATISTICS", fontSize = 11.sp, color = accentColors.first, fontFamily = FontFamily.Monospace)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text("• Neural AI Model: $Constants.GEMINI_MODEL", fontSize = 12.sp, color = Color.White)
                    Text("• TTS Engine: English-GB British Male Vocal, HND, MH", fontSize = 12.sp, color = Color.White)
                    Text("• Sync Weather Channel: Open-Meteo free telemetry link", fontSize = 12.sp, color = Color.White)
                    Text("• Watchlist tracker density: Yahoo Finance chart API", fontSize = 12.sp, color = Color.White)
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    dataManager.clearAll()
                    chatMessages.clear()
                    currentTheme = HudTheme.ARC_BLUE
                    currentLang = AppLang.EN
                    isOfflineOn = false
                    isWakeWordOn = false
                    pinStatusString = "UNSECURED"
                    Toast.makeText(this@MainActivity, "Database cleaned. Core restarted.", Toast.LENGTH_LONG).show()
                },
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(containerColor = Color.Red)
            ) {
                Text("RESET DATABASE CORE ENGINE", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }

    // --- Core HUD Overlay Graphic Components and Handlers ---

    // Scrollable bottom panel dock
    @Composable
    fun ScrollablePanelTabDock(
        activeIndex: Int,
        onActiveChange: (Int) -> Unit,
        accentColor: Color
    ) {
        val tabLabels = listOf(
            "CHAT HUB", "STUDY TOOLS", "EARN COIN", "DAILY LIFE",
            "HEALTH DESK", "STOCK WATCH", "WEATHER DOCK", "HABITS", "CHECKLIST", "SYSTEM"
        )

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.9f))
                .border(BorderStroke(1.dp, accentColor.copy(alpha = 0.3f)))
                .padding(vertical = 4.dp)
                .horizontalScroll(rememberScrollState())
        ) {
            tabLabels.forEachIndexed { idx, label ->
                val active = idx == activeIndex
                Box(
                    modifier = Modifier
                        .padding(horizontal = 6.dp)
                        .background(
                            if (active) accentColor.copy(alpha = 0.2f) else Color.Transparent,
                            RoundedCornerShape(3.dp)
                        )
                        .border(
                            1.dp,
                            if (active) accentColor else Color.Transparent,
                            RoundedCornerShape(3.dp)
                        )
                        .clickable { onActiveChange(idx) }
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                ) {
                    Text(
                        text = label,
                        fontSize = 11.sp,
                        fontWeight = if (active) FontWeight.Bold else FontWeight.Normal,
                        color = if (active) Color.White else accentColor.copy(alpha = 0.7f),
                        fontFamily = FontFamily.Monospace
                    )
                }
            }
        }
    }

    // Displays the system status bar at the top of HUD screen
    @Composable
    fun DiagnosticsHeader(panelIndex: Int) {
        val currentColors = getHudThemeColors(currentTheme)
        val todayStr = SimpleDateFormat("hh:mm a", Locale.getDefault()).format(Date()).uppercase()

        Row(
            modifier = Modifier
                .fillTemplateHeader()
                .background(Color(0xFF0F172A).copy(alpha = 0.5f))
                .drawBehind {
                    val strokeWidth = 1.dp.toPx()
                    val y = size.height - strokeWidth / 2
                    drawLine(
                        color = currentColors.first.copy(alpha = 0.3f),
                        start = Offset(0f, y),
                        end = Offset(size.width, y),
                        strokeWidth = strokeWidth
                    )
                }
                .padding(horizontal = 12.dp, vertical = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Left block
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    "SYS: ONLINE",
                    fontSize = 10.sp,
                    color = currentColors.first,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
                Text(
                    "WAKE: ACTIVE",
                    fontSize = 10.sp,
                    color = Color(0xFFFBBF24), // Beautiful Warm Amber color from HTML
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
            }

            // Right block
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                val weatherLabel = weatherState.value?.let { "${it.temp}°C ${it.cityName.uppercase()}" } ?: "OFFLINE"
                Text(
                    weatherLabel,
                    fontSize = 10.sp,
                    color = currentColors.first,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
                Text(
                    todayStr,
                    fontSize = 10.sp,
                    color = currentColors.first,
                    fontFamily = FontFamily.Monospace,
                    letterSpacing = 1.5.sp
                )
            }
        }
    }

    @Composable
    fun SpeechOscillatorVisualizer(state: OrbState, accent: Color) {
        val transition = rememberInfiniteTransition()
        val bounceList = List(5) { id ->
            transition.animateFloat(
                initialValue = 0.2f,
                targetValue = 1.0f,
                animationSpec = infiniteRepeatable(
                    animation = tween(durationMillis = 200 + (id * 100), easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                )
            )
        }

        Row(
            modifier = Modifier.width(50.dp).height(40.dp),
            horizontalArrangement = Arrangement.spacedBy(4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            bounceList.forEach { valState ->
                val mult = if (state == OrbState.LISTENING || state == OrbState.SPEAKING) valState.value else 0.15f
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(mult)
                        .background(accent, RoundedCornerShape(2.dp))
                )
            }
        }
    }

    // The beautiful rotating Arc Reactor Orb with high density nested rings
    @Composable
    fun ArcReactorOrbGraphics(
        state: OrbState,
        colors: Triple<Color, Color, Color>,
        size: androidx.compose.ui.unit.Dp
    ) {
        val transition = rememberInfiniteTransition()

        val clockwiseRotate by transition.animateFloat(
            initialValue = 0f,
            targetValue = 360f,
            animationSpec = infiniteRepeatable(
                animation = tween(4000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        val counterClockwiseRotate by transition.animateFloat(
            initialValue = 360f,
            targetValue = 0f,
            animationSpec = infiniteRepeatable(
                animation = tween(3000, easing = LinearEasing),
                repeatMode = RepeatMode.Restart
            )
        )

        val pulseAlpha by transition.animateFloat(
            initialValue = 0.4f,
            targetValue = 1.0f,
            animationSpec = infiniteRepeatable(
                animation = tween(1500, easing = FastOutSlowInEasing),
                repeatMode = RepeatMode.Reverse
            )
        )

        val dynamicOrbColor = when (state) {
            OrbState.IDLE -> colors.first // Blue/Cyan
            OrbState.LISTENING -> Color.Green
            OrbState.THINKING -> Color(0xFFD080FF) // Purple
            OrbState.SPEAKING -> Color(0xFFFFD700) // Gold
        }

        Box(
            modifier = Modifier.size(size),
            contentAlignment = Alignment.Center
        ) {
            // Radial Glow Background
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawBehind {
                        drawCircle(
                            brush = Brush.radialGradient(
                                colors = listOf(dynamicOrbColor.copy(alpha = 0.35f * pulseAlpha), Color.Transparent),
                                center = center,
                                radius = size.toPx() / 1.1f
                            )
                        )
                    }
            )

            // 1. Outermost Ring (w-56 equivalent)
            Box(
                modifier = Modifier
                    .fillMaxSize(1.0f)
                    .border(BorderStroke(1.dp, dynamicOrbColor.copy(alpha = 0.15f)), CircleShape)
            )

            // 2. Dash Ring rotating clockwise (w-48 equivalent)
            Canvas(
                modifier = Modifier
                    .fillMaxSize(0.85f)
                    .rotate(clockwiseRotate)
            ) {
                drawCircle(
                    color = dynamicOrbColor.copy(alpha = 0.25f),
                    style = Stroke(
                        width = 4f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(40f, 20f), 1f)
                    )
                )
            }

            // 3. Inner glow ring rotating counter-clockwise (w-40 equivalent)
            Canvas(
                modifier = Modifier
                    .fillMaxSize(0.70f)
                    .rotate(counterClockwiseRotate)
            ) {
                drawCircle(
                    color = dynamicOrbColor.copy(alpha = 0.05f)
                )
                drawCircle(
                    color = dynamicOrbColor.copy(alpha = 0.45f),
                    style = Stroke(
                        width = 2f,
                        pathEffect = PathEffect.dashPathEffect(floatArrayOf(15f, 10f), 1f)
                    )
                )
            }

            // 4. Solid core outer (w-32 equivalent) with primary border & gradient to black
            Box(
                modifier = Modifier
                    .fillMaxSize(0.55f)
                    .clip(CircleShape)
                    .background(
                        Brush.linearGradient(
                            colors = listOf(dynamicOrbColor.copy(alpha = 0.7f), Color.Black)
                        )
                    )
                    .border(BorderStroke(4.dp, dynamicOrbColor), CircleShape)
            )

            // 5. Core inner center placeholder (w-16 equivalent)
            Box(
                modifier = Modifier
                    .fillMaxSize(0.28f)
                    .border(BorderStroke(1.dp, Color.White.copy(alpha = 0.3f)), CircleShape),
                contentAlignment = Alignment.Center
            ) {
                // 6. Solid Core center point emitter (w-8 equivalent)
                Box(
                    modifier = Modifier
                        .fillMaxSize(0.5f)
                        .clip(CircleShape)
                        .background(
                            Brush.radialGradient(
                                colors = listOf(Color.White, dynamicOrbColor)
                            )
                        )
                        .border(1.dp, Color.White, CircleShape)
                )
            }
        }
    }

    // PIN lock input display keyboard
    @Composable
    fun PinLockOverlay(savedPin: String, onPinPassed: () -> Unit) {
        var typedPin by remember { mutableStateOf("") }
        var isPinError by remember { mutableStateOf(false) }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
                .padding(24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                "SECURITY INTERFACE PROTOCOL",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                color = if (isPinError) Color.Red else Color(0xFF00FFCC),
                fontFamily = FontFamily.Monospace,
                letterSpacing = 2.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                if (isPinError) "INVALID ENCRYPTED PIN KEYS" else "PROVIDE STARK AUTHORIZATION CREDENTIALS",
                fontSize = 11.sp,
                color = Color.Gray,
                fontFamily = FontFamily.Monospace
            )

            Spacer(modifier = Modifier.height(30.dp))

            // 4 dots layout
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                for (i in 0..3) {
                    val active = i < typedPin.length
                    Box(
                        modifier = Modifier
                            .size(20.dp)
                            .clip(CircleShape)
                            .background(
                                if (isPinError) Color.Red else if (active) Color(0xFF00FFCC) else Color.DarkGray
                            )
                    )
                }
            }

            Spacer(modifier = Modifier.height(40.dp))

            // Numeric Keyboard grids
            val keys = listOf(
                "1", "2", "3",
                "4", "5", "6",
                "7", "8", "9",
                "CLR", "0", "DEL"
            )

            Column(
                modifier = Modifier.width(280.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                for (row in 0..3) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        for (col in 0..2) {
                            val key = keys[row * 3 + col]
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .aspectRatio(1.5f)
                                    .background(Color.DarkGray.copy(alpha = 0.3f), RoundedCornerShape(8.dp))
                                    .border(1.dp, Color(0xFF00FFCC).copy(alpha = 0.2f), RoundedCornerShape(8.dp))
                                    .clickable {
                                        isPinError = false
                                        when (key) {
                                            "CLR" -> typedPin = ""
                                            "DEL" -> if (typedPin.isNotEmpty()) typedPin = typedPin.dropLast(1)
                                            else -> {
                                                if (typedPin.length < 4) {
                                                    typedPin += key
                                                    if (typedPin.length == 4) {
                                                        if (typedPin == savedPin) {
                                                            onPinPassed()
                                                        } else {
                                                            isPinError = true
                                                            typedPin = ""
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    },
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = key,
                                    color = Color.White,
                                    fontSize = 18.sp,
                                    fontWeight = FontWeight.Bold,
                                    fontFamily = FontFamily.Monospace
                                )
                            }
                        }
                    }
                }
            }
        }
    }

    // --- Core HUD Overlay Background Grid Render ---
    private fun android.graphics.Canvas.drawSciFiBackgroundGrids(color: Color) {
        // Compose DrawScope simplifies drawing on views natively
    }

    private fun drawSciFiBackgroundGrids(color: Color): (androidx.compose.ui.graphics.drawscope.DrawScope) -> Unit {
        return { drawScope ->
            val w = drawScope.size.width
            val h = drawScope.size.height
            val scale = 80f

            // Clean background fill matching High Density #050A0F
            drawScope.drawRect(Color(0xFF050A0F))

            // Verticals grids
            var x = 0f
            while (x < w) {
                drawScope.drawLine(
                    color = color.copy(alpha = 0.05f),
                    start = Offset(x, 0f),
                    end = Offset(x, h),
                    strokeWidth = 1f
                )
                x += scale
            }

            // Horizontals grids
            var y = 0f
            while (y < h) {
                drawScope.drawLine(
                    color = color.copy(alpha = 0.05f),
                    start = Offset(0f, y),
                    end = Offset(w, y),
                    strokeWidth = 1f
                )
                y += scale
            }

            // Glowing Corners overlays (sci-fi visual borders)
            val cornerLen = 40f
            val cornerWeight = 3f

            // Top Left corner
            drawScope.drawLine(color, Offset(20f, 20f), Offset(20f + cornerLen, 20f), strokeWidth = cornerWeight)
            drawScope.drawLine(color, Offset(20f, 20f), Offset(20f, 20f + cornerLen), strokeWidth = cornerWeight)

            // Top Right corner
            drawScope.drawLine(color, Offset(w - 20f, 20f), Offset(w - 20f - cornerLen, 20f), strokeWidth = cornerWeight)
            drawScope.drawLine(color, Offset(w - 20f, 20f), Offset(w - 20f, 20f + cornerLen), strokeWidth = cornerWeight)

            // Bottom Left corner
            drawScope.drawLine(color, Offset(20f, h - 20f), Offset(20f + cornerLen, h - 20f), strokeWidth = cornerWeight)
            drawScope.drawLine(color, Offset(20f, h - 20f), Offset(20f, h - 20f - cornerLen), strokeWidth = cornerWeight)

            // Bottom Right corner
            drawScope.drawLine(color, Offset(w - 20f, h - 20f), Offset(w - 20f - cornerLen, h - 20f), strokeWidth = cornerWeight)
            drawScope.drawLine(color, Offset(w - 20f, h - 20f), Offset(w - 20f, h - 20f - cornerLen), strokeWidth = cornerWeight)
        }
    }

    // --- Helpers Utils ---

    private fun Modifier.fillTemplateHeader() = this.fillMaxWidth().height(42.dp)

    private fun getThemeBackgroundColor(hudTheme: HudTheme): Color {
        return Color(0xFF050A0F)
    }

    private fun getHudThemeColors(hudTheme: HudTheme): Triple<Color, Color, Color> {
        return when (hudTheme) {
            HudTheme.ARC_BLUE -> Triple(Color(0xFF00FFCC), Color(0xFF0099FF), Color(0xFF003366))
            HudTheme.IRON_MAN_RED -> Triple(Color(0xFFFF3300), Color(0xFFFF9900), Color(0xFF660000))
            HudTheme.GOLD_MODE -> Triple(Color(0xFFFFD700), Color(0xFFFFCC33), Color(0xFF4A3C00))
        }
    }

    private fun getCurrentFormattedDate(): String {
        return SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date())
    }

    // File download logs creators
    private fun exportStringTextFile(fileName: String, content: String) {
        try {
            val downloadDir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS)
            val file = File(downloadDir, fileName)
            FileOutputStream(file).use { out ->
                out.write(content.toByteArray())
            }
            Toast.makeText(this, "Secured Logs downloaded to Downloads directory: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Log.e("MainActivity", "File export error", e)
            Toast.makeText(this, "Log export anomaly: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun exportChatHistoryLogs() {
        val chatStr = chatMessages.joinToString("\n") { "[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(it.timestamp)}] ${it.sender}: ${it.text}" }
        exportStringTextFile("JARVIS-ChatHistory.txt", "==============================\n  J.A.R.V.I.S CHAT PROTOCOLS\n==============================\n\n$chatStr")
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            ttsManager?.shutdown()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error shutting down tts", e)
        }
        try {
            speechRecognizer?.destroy()
        } catch (e: Exception) {
            Log.e("MainActivity", "Error destroying speechRecognizer", e)
        }
    }
}
