package com.netveil.app.core

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

object AppLogger {
  private const val MAX_ENTRIES = 200
  private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

  private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
  val logs: StateFlow<List<LogEntry>> = _logs.asStateFlow()

  @Synchronized
  fun log(level: String, rawMessage: String) {
    val entry = LogEntry(
      timestamp = timeFormat.format(Date()),
      level = level,
      message = LogSanitizer.sanitize(rawMessage)
    )
    val updated = (_logs.value + entry).takeLast(MAX_ENTRIES)
    _logs.value = updated
  }

  fun info(msg: String) = log("INFO", msg)
  fun warn(msg: String) = log("WARN", msg)
  fun error(msg: String) = log("ERR", msg)

  @Synchronized
  fun clear() {
    _logs.value = emptyList()
  }
}
