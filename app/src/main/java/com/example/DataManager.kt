package com.example

import android.content.Context
import android.content.SharedPreferences
import com.squareup.moshi.Moshi
import com.squareup.moshi.Types
import com.squareup.moshi.kotlin.reflect.KotlinJsonAdapterFactory
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class DataManager(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences("jarvis_ultra_prefs", Context.MODE_PRIVATE)

    private val moshi: Moshi = Moshi.Builder()
        .addLast(KotlinJsonAdapterFactory())
        .build()

    // Key Constants
    companion object {
        private const val KEY_LANG = "language"
        private const val KEY_THEME = "hud_theme"
        private const val KEY_PIN = "pin_code"
        private const val KEY_WATER_COUNT = "water_glasses"
        private const val KEY_WATER_DATE = "water_last_date"
        private const val KEY_WATER_INTERVAL = "water_interval_min"
        private const val KEY_WATER_ALERT_ENABLED = "water_alert_enabled"
        
        private const val KEY_TODOS = "todo_list"
        private const val KEY_NOTES = "notes_list"
        private const val KEY_HABITS = "habits_list"
        private const val KEY_MEDICINES = "medicines_list"
        private const val KEY_WATCHLIST = "stock_watchlist"
        private const val KEY_MEMORIES = "learned_facts"
        private const val KEY_WAKE_WORD_ENABLED = "wake_word_active"
        private const val KEY_OFFLINE_MODE = "offline_active"
    }

    // --- System Config ---
    var language: AppLang
        get() {
            val name = prefs.getString(KEY_LANG, AppLang.EN.name) ?: AppLang.EN.name
            return try { AppLang.valueOf(name) } catch (e: Exception) { AppLang.EN }
        }
        set(value) {
            prefs.edit().putString(KEY_LANG, value.name).apply()
        }

    var theme: HudTheme
        get() {
            val name = prefs.getString(KEY_THEME, HudTheme.ARC_BLUE.name) ?: HudTheme.ARC_BLUE.name
            return try { HudTheme.valueOf(name) } catch (e: Exception) { HudTheme.ARC_BLUE }
        }
        set(value) {
            prefs.edit().putString(KEY_THEME, value.name).apply()
        }

    var pinCode: String
        get() = prefs.getString(KEY_PIN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PIN, value).apply()

    var wakeWordEnabled: Boolean
        get() = prefs.getBoolean(KEY_WAKE_WORD_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WAKE_WORD_ENABLED, value).apply()

    var offlineModeActive: Boolean
        get() = prefs.getBoolean(KEY_OFFLINE_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_OFFLINE_MODE, value).apply()

    // --- Water Tracker (with daily reset automatically on launch) ---
    val waterCount: Int
        get() {
            checkAndResetDailyWater()
            return prefs.getInt(KEY_WATER_COUNT, 0)
        }

    fun incrementWater(): Int {
        checkAndResetDailyWater()
        val current = prefs.getInt(KEY_WATER_COUNT, 0)
        val newCount = (current + 1).coerceAtMost(20)
        prefs.edit().putInt(KEY_WATER_COUNT, newCount).apply()
        return newCount
    }

    fun resetWater() {
        prefs.edit()
            .putInt(KEY_WATER_COUNT, 0)
            .putString(KEY_WATER_DATE, getCurrentDateString())
            .apply()
    }

    var waterReminderInterval: Int
        get() = prefs.getInt(KEY_WATER_INTERVAL, 60)
        set(value) = prefs.edit().putInt(KEY_WATER_INTERVAL, value).apply()

    var waterReminderEnabled: Boolean
        get() = prefs.getBoolean(KEY_WATER_ALERT_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_WATER_ALERT_ENABLED, value).apply()

    private fun checkAndResetDailyWater() {
        val lastDate = prefs.getString(KEY_WATER_DATE, "")
        val today = getCurrentDateString()
        if (lastDate != today) {
            prefs.edit()
                .putInt(KEY_WATER_COUNT, 0)
                .putString(KEY_WATER_DATE, today)
                .apply()
        }
    }

    private fun getCurrentDateString(): String {
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(Date())
    }

    // --- Todo List ---
    fun getTodos(): List<TodoItem> {
        val json = prefs.getString(KEY_TODOS, null) ?: return emptyList()
        val type = Types.newParameterizedType(List::class.java, TodoItem::class.java)
        return try {
            moshi.adapter<List<TodoItem>>(type).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveTodos(list: List<TodoItem>) {
        val type = Types.newParameterizedType(List::class.java, TodoItem::class.java)
        val json = moshi.adapter<List<TodoItem>>(type).toJson(list)
        prefs.edit().putString(KEY_TODOS, json).apply()
    }

    // --- Quick Notes ---
    fun getNotes(): List<NoteItem> {
        val json = prefs.getString(KEY_NOTES, null) ?: return emptyList()
        val type = Types.newParameterizedType(List::class.java, NoteItem::class.java)
        return try {
            moshi.adapter<List<NoteItem>>(type).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveNotes(list: List<NoteItem>) {
        val type = Types.newParameterizedType(List::class.java, NoteItem::class.java)
        val json = moshi.adapter<List<NoteItem>>(type).toJson(list)
        prefs.edit().putString(KEY_NOTES, json).apply()
    }

    // --- Habit Tracker ---
    fun getHabits(): List<HabitItem> {
        val json = prefs.getString(KEY_HABITS, null) ?: return emptyList()
        val type = Types.newParameterizedType(List::class.java, HabitItem::class.java)
        val habits = try {
            moshi.adapter<List<HabitItem>>(type).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
        return checkAndResetHabitStreak(habits)
    }

    fun saveHabits(list: List<HabitItem>) {
        val type = Types.newParameterizedType(List::class.java, HabitItem::class.java)
        val json = moshi.adapter<List<HabitItem>>(type).toJson(list)
        prefs.edit().putString(KEY_HABITS, json).apply()
    }

    private fun checkAndResetHabitStreak(list: List<HabitItem>): List<HabitItem> {
        val today = getCurrentDateString()
        val yesterday = getYesterdayDateString()
        var modified = false
        val newList = list.map { habit ->
            if (habit.lastCompletedDate.isNotEmpty() &&
                habit.lastCompletedDate != today &&
                habit.lastCompletedDate != yesterday
            ) {
                modified = true
                habit.copy(streak = 0)
            } else {
                habit
            }
        }
        if (modified) {
            saveHabits(newList)
        }
        return newList
    }

    private fun getYesterdayDateString(): String {
        val cal = java.util.Calendar.getInstance()
        cal.add(java.util.Calendar.DATE, -1)
        return SimpleDateFormat("yyyy-MM-dd", Locale.getDefault()).format(cal.time)
    }

    // --- Medicine Reminders ---
    fun getMedicines(): List<MedicineItem> {
        val json = prefs.getString(KEY_MEDICINES, null) ?: return emptyList()
        val type = Types.newParameterizedType(List::class.java, MedicineItem::class.java)
        return try {
            moshi.adapter<List<MedicineItem>>(type).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveMedicines(list: List<MedicineItem>) {
        val type = Types.newParameterizedType(List::class.java, MedicineItem::class.java)
        val json = moshi.adapter<List<MedicineItem>>(type).toJson(list)
        prefs.edit().putString(KEY_MEDICINES, json).apply()
    }

    // --- Stock Watchlist ---
    var stockWatchlist: String
        get() = prefs.getString(KEY_WATCHLIST, "AAPL,TSLA,BTC-USD,RELIANCE.BSE,TCS.NS") ?: "AAPL,TSLA,BTC-USD"
        set(value) = prefs.edit().putString(KEY_WATCHLIST, value).apply()

    // --- Learned Facts / Offline Brain ("Learn and Remember Offline without API Key") ---
    fun getMemories(): List<MemoryFact> {
        val json = prefs.getString(KEY_MEMORIES, null) ?: return emptyList()
        val type = Types.newParameterizedType(List::class.java, MemoryFact::class.java)
        return try {
            moshi.adapter<List<MemoryFact>>(type).fromJson(json) ?: emptyList()
        } catch (e: Exception) {
            emptyList()
        }
    }

    fun saveMemories(list: List<MemoryFact>) {
        val type = Types.newParameterizedType(List::class.java, MemoryFact::class.java)
        val json = moshi.adapter<List<MemoryFact>>(type).toJson(list)
        prefs.edit().putString(KEY_MEMORIES, json).apply()
    }

    fun learnFact(key: String, value: String) {
        val list = getMemories().toMutableList()
        val cleanKey = key.lowercase().trim()
        val existingIndex = list.indexOfFirst { it.key == cleanKey }
        if (existingIndex >= 0) {
            list[existingIndex] = MemoryFact(cleanKey, value)
        } else {
            list.add(MemoryFact(cleanKey, value))
        }
        saveMemories(list)
    }

    fun forgetFact(key: String) {
        val list = getMemories().toMutableList()
        val cleanKey = key.lowercase().trim()
        list.removeAll { it.key == cleanKey }
        saveMemories(list)
    }

    fun lookupFact(query: String): String? {
        val list = getMemories()
        val cleanQuery = query.lowercase().trim()
        // Try precise match or partial match
        val match = list.find { cleanQuery.contains(it.key) || it.key.contains(cleanQuery) }
        return match?.value
    }

    // --- Clear All Data ---
    fun clearAll() {
        prefs.edit().clear().apply()
    }
}
