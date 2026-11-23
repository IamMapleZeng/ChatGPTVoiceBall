package io.github.iammaplezeng.chatgptvoiceball.model

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

data class LogEntry(
    val timestamp: String,
    val level: String,
    val message: String
)

object LogRepository {
    private const val MAX_ENTRIES = 120
    private val formatter = SimpleDateFormat("HH:mm:ss.SSS", Locale.getDefault())

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

    fun info(msg: String) = append("INFO", msg)
    fun warn(msg: String) = append("WARN", msg)
    fun error(msg: String) = append("ERR", msg)

    @Synchronized
    private fun append(level: String, msg: String) {
        val entry = LogEntry(formatter.format(Date()), level, msg)
        val updated = (_logs.value + entry).takeLast(MAX_ENTRIES)
        _logs.value = updated
    }

    fun clear() {
        _logs.value = emptyList()
    }

    fun exportText(): String = _logs.value.joinToString("\n") {
        "[${it.timestamp}] [${it.level}] ${it.message}"
    }
}
