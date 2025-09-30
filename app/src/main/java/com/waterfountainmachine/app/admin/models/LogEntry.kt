package com.waterfountainmachine.app.admin.models

data class LogEntry(
    val id: String,
    val timestamp: Long,
    val level: Level,
    val tag: String,
    val message: String
) {
    enum class Level {
        ERROR,
        WARNING,
        INFO,
        DEBUG
    }
}