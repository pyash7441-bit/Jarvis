package com.example

import java.io.Serializable

enum class AppLang {
    EN, HI, MR
}

enum class OrbState {
    IDLE,       // Blue
    LISTENING,  // Green
    THINKING,   // Purple
    SPEAKING    // Gold
}

enum class HudTheme {
    ARC_BLUE,
    IRON_MAN_RED,
    GOLD_MODE
}

data class ChatMessage(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val sender: String, // "USER", "JARVIS", "SYSTEM", "ERROR"
    val timestamp: Long = System.currentTimeMillis()
) : Serializable

data class TodoItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val text: String,
    val isCompleted: Boolean = false,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable

data class NoteItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val title: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis()
) : Serializable

data class HabitItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val streak: Int = 0,
    val lastCompletedDate: String = "", // "YYYY-MM-DD"
    val createdAt: Long = System.currentTimeMillis()
) : Serializable

data class MedicineItem(
    val id: String = java.util.UUID.randomUUID().toString(),
    val name: String,
    val time: String, // "HH:MM"
    val isScheduled: Boolean = true
) : Serializable

data class StockWatch(
    val symbol: String,
    val companyName: String,
    val price: Double = 0.0,
    val changePercent: Double = 0.0,
    val isCrypto: Boolean = false
) : Serializable

data class WeatherInfo(
    val cityName: String,
    val temp: Double,
    val feelsLike: Double,
    val humidity: Int,
    val windSpeed: Double,
    val precipitation: Double,
    val tempMax: Double,
    val tempMin: Double,
    val forecast: List<ForecastDay>
) : Serializable

data class ForecastDay(
    val dayName: String,
    val tempMax: Double,
    val tempMin: Double,
    val description: String
) : Serializable

data class MemoryFact(
    val key: String,
    val value: String,
    val timestamp: Long = System.currentTimeMillis()
) : Serializable
