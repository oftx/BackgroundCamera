package github.oftx.backgroundcamera.util

import android.util.Log
import java.text.SimpleDateFormat
import java.util.Collections
import java.util.Date
import java.util.Locale

/**
 * A simple in-memory log manager to store and retrieve app logs for debugging.
 * This is a singleton object, so it's accessible from anywhere in the app.
 */
object LogManager {
    private const val MAX_LOG_LINES = 200
    private const val TAG = "AppLogger"

    // A thread-safe list to store log entries
    private val logs = Collections.synchronizedList(mutableListOf<String>())
    private val dateFormat = SimpleDateFormat("HH:mm:ss", Locale.US)

    /**
     * Adds a new log entry. This is the main method to be called from other parts of the app.
     * @param message The log message.
     */
    fun addLog(message: String) {
        val timestamp = dateFormat.format(Date())
        val logEntry = "$timestamp: $message"

        // Also log to standard Logcat for real-time debugging
        Log.d(TAG, message)

        synchronized(logs) {
            logs.add(logEntry)
            // Trim the log list if it exceeds the maximum size
            while (logs.size > MAX_LOG_LINES) {
                logs.removeAt(0)
            }
        }
    }

    /**
     * Retrieves all stored logs as a single formatted string.
     * @return A string containing all log entries, separated by newlines.
     */
    fun getLogs(): String {
        return synchronized(logs) {
            logs.joinToString("\n")
        }
    }

    /**
     * Clears all stored logs.
     */
    fun clearLogs() {
        synchronized(logs) {
            logs.clear()
        }
        addLog("Logs cleared.")
    }
}