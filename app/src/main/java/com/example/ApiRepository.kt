package com.example

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.util.Log
import com.squareup.moshi.Moshi
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.concurrent.TimeUnit

class ApiRepository(private val context: Context) {

    private val moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .build()

    // Check internet connectivity
    fun isOnline(): Boolean {
        return try {
            val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val network = connectivityManager.activeNetwork ?: return false
            val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        } catch (e: Exception) {
            Log.e("ApiRepository", "Error checking connectivity", e)
            false
        }
    }

    // --- Gemini Content Generation (Acting as JARVIS Brain) ---
    suspend fun generateJarvisResponse(userPrompt: String, history: List<ChatMessage>, apiKeyValue: String): String = withContext(Dispatchers.IO) {
        val dataManager = DataManager(context)
        
        // Handle Offline / Forced Offline or Empty Key
        if (dataManager.offlineModeActive || !isOnline() || apiKeyValue.isEmpty()) {
            return@withContext getOfflineResponse(userPrompt)
        }

        try {
            val endpoint = "https://generativelanguage.googleapis.com/v1beta/models/${Constants.GEMINI_MODEL}:generateContent?key=$apiKeyValue"

            // Synthesize chat history into Gemini contents format
            val contentsArray = JSONArray()

            // Optional: inject system prompt but to keep payload small, let's keep it compact and add a system instruction
            val systemObj = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", Constants.SYSTEM_PROMPT)))

            // Append conversational history
            // Limit to last 8 messages to keep latencies short
            val recentHistory = history.takeLast(8)
            for (msg in recentHistory) {
                if (msg.sender == "SYSTEM" || msg.sender == "ERROR") continue
                val role = if (msg.sender == "USER") "user" else "model"
                val partObj = JSONObject().put("parts", JSONArray().put(JSONObject().put("text", msg.text)))
                partObj.put("role", role)
                contentsArray.put(partObj)
            }

            // Append user prompt as the current message
            val currentPayloadUser = JSONObject()
                .put("role", "user")
                .put("parts", JSONArray().put(JSONObject().put("text", userPrompt)))
            contentsArray.put(currentPayloadUser)

            val rootJson = JSONObject()
                .put("contents", contentsArray)
                .put("systemInstruction", systemObj)
                // Add moderate temperature
                .put("generationConfig", JSONObject().put("temperature", 0.6))

            val requestBody = rootJson.toString().toRequestBody("application/json".toMediaType())
            val request = Request.Builder()
                .url(endpoint)
                .post(requestBody)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    val errBody = response.body?.string() ?: ""
                    Log.e("JARVIS", "Gemini HTTP failed: $errBody")
                    // Degrade to elegant offline fallback instead of dead crash
                    return@withContext "Apologies, sir. My satellite links returned an anomaly. Allow me to assist with offline subroutines:\n\n" + getOfflineResponse(userPrompt)
                }

                val responseBody = response.body?.string() ?: ""
                val rootObj = JSONObject(responseBody)
                val candidates = rootObj.getJSONArray("candidates")
                if (candidates.length() > 0) {
                    val cand = candidates.getJSONObject(0)
                    val contentObj = cand.getJSONObject("content")
                    val parts = contentObj.getJSONArray("parts")
                    if (parts.length() > 0) {
                        return@withContext parts.getJSONObject(0).getString("text")
                    }
                }
                "No verbal output captured, sir. Please repeat."
            }
        } catch (e: Exception) {
            Log.e("JARVIS", "Gemini API exception", e)
            "A slight power disruption in my cognitive relays, sir. Operating in offline failsafe:\n\n" + getOfflineResponse(userPrompt)
        }
    }

    // --- Offline Intelligent Reply Fallbacks ---
    private fun getOfflineResponse(prompt: String): String {
        val query = prompt.lowercase().trim()
        val dataManager = DataManager(context)

        // 1. Check if user asked to "learn" something
        // Match phrases like "remember that...", "learn that..."
        val learnRegex = Regex("^(?:learn|remember|save|store)\\s+that\\s+([\\w\\s\\d]+)\\s+(?:is|equals|equal)\\s+(.+)$", RegexOption.IGNORE_CASE)
        val matchResult = learnRegex.find(query)
        if (matchResult != null) {
            val key = matchResult.groupValues[1].trim()
            val value = matchResult.groupValues[2].trim()
            dataManager.learnFact(key, value)
            return "Understood, sir. I have saved '$key' as '$value' to my secure local memory sectors."
        }

        // 2. Check if user asked about something JARVIS learned/learned facts
        val memories = dataManager.getMemories()
        for (memory in memories) {
            if (query.contains(memory.key)) {
                return "Connecting local memory bank, sir. I recall you instructed me that ${memory.key} is: ${memory.value}."
            }
        }

        // 3. Command specific query matching
        for ((keyStr, respVal) in Constants.OFFLINE_ANSWERS_DATABASE) {
            if (query.contains(keyStr)) {
                return "$respVal"
            }
        }

        // 4. Checking study prompts offline
        if (query.contains("formula") || query.contains("physics formula") || query.contains("math formula")) {
            return "Displaying the formula quick-reference card, sir.\n\n" + Constants.FORMULA_SHEET
        }
        if (query.contains("study physics") || query.contains("physics guide")) {
            return Constants.SUBJECT_GUIDES["physics"] ?: ""
        }
        if (query.contains("study chemistry")) {
            return Constants.SUBJECT_GUIDES["chemistry"] ?: ""
        }
        if (query.contains("study mathematics") || query.contains("study maths")) {
            return Constants.SUBJECT_GUIDES["maths"] ?: ""
        }
        if (query.contains("study coding") || query.contains("study programming")) {
            return Constants.SUBJECT_GUIDES["coding"] ?: ""
        }

        // 5. Daily life presets offline
        if (query.contains("workout") || query.contains("exercise") || query.contains("fitness")) {
            return "Opening offline cardiovascular physical training template, sir.\n\n" + Constants.WORKOUT_PLAN_15M
        }
        if (query.contains("morning routine")) {
            return "Here is your morning high-performance layout, sir.\n\n" + Constants.MORNING_ROUTINE
        }
        if (query.contains("meal plan") || query.contains("diet plan")) {
            return "Compiling the weekly student fuel budget meal plan, sir.\n\n" + Constants.STUDENT_MEAL_PLAN
        }

        // 6. Action Tag triggers offline!
        if (query.contains("open whatsapp")) {
            return "Right away, sir. Synchronizing messaging logs. [OPEN:whatsapp]"
        }
        if (query.contains("open youtube")) {
            return "Opening holographic entertainment feed, sir. [OPEN:youtube]"
        }
        if (query.contains("open maps") || query.contains("where is")) {
            return "Powering telemetry satellites, sir. [OPEN:maps]"
        }
        if (query.contains("set alarm to") || query.contains("set alarm at")) {
            // Find numbers
            val digits = query.filter { it.isDigit() }
            if (digits.length == 4) {
                val hh = digits.substring(0, 2)
                val mm = digits.substring(2, 4)
                return "Alarm subroutines scheduled for $hh:$mm, sir. [ALARM:$hh:$mm:WakeUp]"
            } else if (digits.length == 3) {
                val hh = "0" + digits.substring(0, 1)
                val mm = digits.substring(1, 3)
                return "Alarm subroutines scheduled for $hh:$mm, sir. [ALARM:$hh:$mm:WakeUp]"
            }
            return "What time should I configure the alarm for, sir? Use HH:MM format."
        }
        if (query.contains("timer")) {
            val digits = query.filter { it.isDigit() }
            val mins = if (digits.isNotEmpty()) digits.toInt() else 5
            return "Beginning countdown for $mins minutes, sir. [TIMER:$mins]"
        }
        if (query.contains("todo") || query.contains("task")) {
            val taskLabel = prompt.replace(Regex("(?i)add todo|add task|todo"), "").trim()
            if (taskLabel.isNotEmpty()) {
                return "Duly noted. Appending '$taskLabel' to local task checklist. [TODO:$taskLabel]"
            }
        }
        if (query.contains("note") || query.contains("save note")) {
            val noteLabel = prompt.replace(Regex("(?i)add note|save note|note"), "").trim()
            if (noteLabel.isNotEmpty()) {
                return "Adding to secure logs, sir. [NOTE:$noteLabel]"
            }
        }

        // Default witty responses
        return Constants.OFFLINE_TALK_CONVERSATIONS.random()
    }

    // --- Weather Integration (Open-Meteo) ---
    suspend fun fetchWeather(lat: Double, lon: Double, cityName: String): WeatherInfo? = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext null
        try {
            val urlStr = "https://api.open-meteo.com/v1/forecast?latitude=$lat&longitude=$lon&current_weather=true&daily=temperature_2m_max,temperature_2m_min,precipitation_sum&timezone=auto"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.readTimeout = 10000
            connection.connectTimeout = 10000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val responseStr = InputStreamReader(connection.inputStream).use { it.readText() }
                val root = JSONObject(responseStr)
                val current = root.getJSONObject("current_weather")
                val daily = root.getJSONObject("daily")
                
                val currentTemp = current.getDouble("temperature")
                val windSpeed = current.getDouble("windspeed")
                
                // Get 3-day forecast details
                val daysArr = daily.getJSONArray("time")
                val maxsArr = daily.getJSONArray("temperature_2m_max")
                val minsArr = daily.getJSONArray("temperature_2m_min")
                val rainArr = daily.getJSONArray("precipitation_sum")

                val forecastList = mutableListOf<ForecastDay>()
                val daysName = listOf("Today", "Tomorrow", "Day After")
                
                val count = daily.getJSONArray("time").length().coerceAtMost(3)
                for (i in 0 until count) {
                    val label = if (i < daysName.size) daysName[i] else daysArr.getString(i)
                    val precSum = rainArr.optDouble(i, 0.0)
                    val desc = if (precSum > 2.0) "Rainy" else if (precSum > 0.1) "Showers" else "Sunny"
                    forecastList.add(ForecastDay(
                        dayName = label,
                        tempMax = maxsArr.getDouble(i),
                        tempMin = minsArr.getDouble(i),
                        description = desc
                    ))
                }

                return@withContext WeatherInfo(
                    cityName = cityName,
                    temp = currentTemp,
                    feelsLike = currentTemp + (if (windSpeed > 15) -1.5 else 0.5),// simple offsets
                    humidity = 65, // estimate
                    windSpeed = windSpeed,
                    precipitation = rainArr.optDouble(0, 0.0),
                    tempMax = maxsArr.optDouble(0, currentTemp + 2),
                    tempMin = minsArr.optDouble(0, currentTemp - 2),
                    forecast = forecastList
                )
            }
            null
        } catch (e: Exception) {
            Log.e("JARVIS", "Weather fetch exception", e)
            null
        }
    }

    // Geocodes local city name to lat/lon using Open-Meteo's geocoding endpoint
    suspend fun geocodeCity(cityName: String): Pair<Double, Double>? = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext null
        try {
            val encodedName = URLEncoder.encode(cityName, "UTF-8")
            val urlStr = "https://geocoding-api.open-meteo.com/v1/search?name=$encodedName&count=1&language=en&format=json"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.readTimeout = 10000
            connection.connectTimeout = 10000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val responseStr = InputStreamReader(connection.inputStream).use { it.readText() }
                val root = JSONObject(responseStr)
                if (root.has("results")) {
                    val results = root.getJSONArray("results")
                    if (results.length() > 0) {
                        val first = results.getJSONObject(0)
                        val lat = first.getDouble("latitude")
                        val lon = first.getDouble("longitude")
                        return@withContext Pair(lat, lon)
                    }
                }
            }
            null
        } catch (e: Exception) {
            Log.e("JARVIS", "Geocode exception", e)
            null
        }
    }

    // --- Yahoo Finance Live Stock Info (Free, Keyless endpoint) ---
    suspend fun fetchStockPrice(symbol: String): StockWatch? = withContext(Dispatchers.IO) {
        if (!isOnline()) return@withContext null
        try {
            val cleanSymbol = symbol.trim().uppercase()
            // Yahoo Finance Chart Endpoint is fast, public and returns metadata directly
            val urlStr = "https://query1.finance.yahoo.com/v8/finance/chart/$cleanSymbol"
            val url = URL(urlStr)
            val connection = url.openConnection() as HttpURLConnection
            connection.readTimeout = 10000
            connection.connectTimeout = 10000
            connection.requestMethod = "GET"

            if (connection.responseCode == 200) {
                val responseStr = InputStreamReader(connection.inputStream).use { it.readText() }
                val root = JSONObject(responseStr)
                val chart = root.getJSONObject("chart")
                val resultArr = chart.getJSONArray("result")
                if (resultArr.length() > 0) {
                    val result = resultArr.getJSONObject(0)
                    val meta = result.getJSONObject("meta")
                    
                    val price = meta.optDouble("regularMarketPrice", 0.0)
                    val prevClose = meta.optDouble("previousClose", price)
                    val changePct = if (prevClose != 0.0) {
                        ((price - prevClose) / prevClose) * 100
                    } else {
                        0.0
                    }
                    val isCrypto = cleanSymbol.contains("-") || cleanSymbol.endsWith("USD") || cleanSymbol.endsWith("INR")
                    return@withContext StockWatch(
                        symbol = cleanSymbol,
                        companyName = meta.optString("symbol", cleanSymbol),
                        price = price,
                        changePercent = changePct,
                        isCrypto = isCrypto
                    )
                }
            }
            null
        } catch (e: Exception) {
            Log.e("JARVIS", "Stock fetch exception", e)
            null
        }
    }
}
