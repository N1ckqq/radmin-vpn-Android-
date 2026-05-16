package com.radminvpn.android.util

import android.util.Log
import com.radminvpn.android.model.LogEntry
import com.radminvpn.android.model.LogLevel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Centralized logging system that outputs to both Logcat and UI log panel.
 * Singleton — accessible from anywhere.
 */
object VpnLog {

    private const val MAX_LOGS = 200

    private val _logs = MutableStateFlow<List<LogEntry>>(emptyList())
    val logs: StateFlow<List<LogEntry>> = _logs

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        addEntry(LogLevel.DEBUG, tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        addEntry(LogLevel.INFO, tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        addEntry(LogLevel.WARNING, tag, message)
    }

    fun e(tag: String, message: String) {
        Log.e(tag, message)
        addEntry(LogLevel.ERROR, tag, message)
    }

    fun success(tag: String, message: String) {
        Log.i(tag, message)
        addEntry(LogLevel.SUCCESS, tag, message)
    }

    fun clear() {
        _logs.value = emptyList()
    }

    private fun addEntry(level: LogLevel, tag: String, message: String) {
        val entry = LogEntry(
            timestamp = System.currentTimeMillis(),
            level = level,
            tag = tag,
            message = message
        )
        val current = _logs.value.toMutableList()
        current.add(entry)
        if (current.size > MAX_LOGS) {
            current.removeAt(0)
        }
        _logs.value = current
    }
}
