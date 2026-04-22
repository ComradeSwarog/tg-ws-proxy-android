package com.github.tgwsproxy

import android.content.Context
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.io.PrintWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Application-level logger with file output, rotation, and configurable levels.
 * Writes to app-private files directory with max size rotation.
 */
object AppLogger {
    enum class Level(val priority: Int, val tag: String) {
        DEBUG(0, "DBG"),
        INFO(1, "INF"),
        WARN(2, "WRN"),
        ERROR(3, "ERR")
    }

    private const val MAX_LOG_SIZE = 2 * 1024 * 1024L // 2MB per file
    private const val MAX_LOG_FILES = 3
    private const val LOG_FILE_PREFIX = "proxy_log"
    private const val LOG_FILE_EXT = ".txt"

    @Volatile
    var minLevel: Level = Level.INFO

    private var logDir: File? = null
    private var currentLogFile: File? = null
    private var writer: PrintWriter? = null
    private val dateFormat = SimpleDateFormat("HH:mm:ss.SSS", Locale.US)
    private val fileDateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val lock = Any()

    /** In-memory log buffer for UI (last 2000 lines) */
    private val memoryBuffer = CircularStringBuffer(2000)

    fun init(context: Context) {
        synchronized(lock) {
            logDir = File(context.filesDir, "logs").also { it.mkdirs() }
            openCurrentFile()
        }
        // Load saved level
        val prefs = androidx.preference.PreferenceManager.getDefaultSharedPreferences(context)
        val levelStr = prefs.getString("log_level", "INFO") ?: "INFO"
        minLevel = try { Level.valueOf(levelStr) } catch (_: Exception) { Level.INFO }
    }

    fun setLevel(level: Level, context: Context? = null) {
        minLevel = level
        context?.let {
            androidx.preference.PreferenceManager.getDefaultSharedPreferences(it)
                .edit().putString("log_level", level.name).apply()
        }
    }

    fun d(tag: String, msg: String) = log(Level.DEBUG, tag, msg)
    fun i(tag: String, msg: String) = log(Level.INFO, tag, msg)
    fun w(tag: String, msg: String) = log(Level.WARN, tag, msg)
    fun e(tag: String, msg: String) = log(Level.ERROR, tag, msg)
    fun e(tag: String, msg: String, thr: Throwable?) {
        log(Level.ERROR, tag, msg)
        thr?.let { log(Level.ERROR, tag, it.stackTraceToString()) }
    }

    private fun log(level: Level, tag: String, msg: String) {
        if (level.priority < minLevel.priority) return

        val ts = dateFormat.format(Date())
        val line = "$ts ${level.tag}/$tag: $msg"

        // Always log to Android logcat
        when (level) {
            Level.DEBUG -> Log.d(tag, msg)
            Level.INFO -> Log.i(tag, msg)
            Level.WARN -> Log.w(tag, msg)
            Level.ERROR -> Log.e(tag, msg)
        }

        // Log to file
        synchronized(lock) {
            try {
                writer?.println(line)
                writer?.flush()
                checkRotation()
            } catch (_: Exception) {}
        }

        // Log to memory buffer
        memoryBuffer.add(line)
    }

    private fun openCurrentFile() {
        try {
            val dir = logDir ?: return
            val today = fileDateFormat.format(Date())
            currentLogFile = File(dir, "${LOG_FILE_PREFIX}_${today}${LOG_FILE_EXT}")
            writer = PrintWriter(FileWriter(currentLogFile, true), true)
        } catch (_: Exception) {}
    }

    private fun checkRotation() {
        val file = currentLogFile ?: return
        if (file.length() > MAX_LOG_SIZE) {
            try {
                writer?.close()
                // Rename current to dated backup
                val ts = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
                val backup = File(logDir, "${LOG_FILE_PREFIX}_${ts}${LOG_FILE_EXT}")
                file.renameTo(backup)
                // Clean old files
                cleanOldFiles()
                // Open new file
                openCurrentFile()
            } catch (_: Exception) {}
        }
    }

    private fun cleanOldFiles() {
        val dir = logDir ?: return
        val files = dir.listFiles()
            ?.filter { it.name.startsWith(LOG_FILE_PREFIX) && it.name.endsWith(LOG_FILE_EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?: return
        for (i in MAX_LOG_FILES until files.size) {
            files[i].delete()
        }
    }

    /** Get all log lines from memory buffer (for UI) */
    fun getLogLines(): String = memoryBuffer.toString()

    /** Get the current log file for sharing/export */
    fun getLogFiles(): List<File> {
        val dir = logDir ?: return emptyList()
        return dir.listFiles()
            ?.filter { it.name.startsWith(LOG_FILE_PREFIX) && it.name.endsWith(LOG_FILE_EXT) }
            ?.sortedByDescending { it.lastModified() }
            ?: emptyList()
    }

    /** Clear memory buffer and log files */
    fun clearAll() {
        synchronized(lock) {
            memoryBuffer.clear()
            writer?.close()
            writer = null
            val dir = logDir ?: return
            dir.listFiles()?.filter {
                it.name.startsWith(LOG_FILE_PREFIX) && it.name.endsWith(LOG_FILE_EXT)
            }?.forEach { it.delete() }
            openCurrentFile()
        }
    }

    /** Simple circular buffer for in-memory log lines */
    private class CircularStringBuffer(private val capacity: Int) {
        private val buffer = arrayOfNulls<String>(capacity)
        private var head = 0
        private var size = 0

        @Synchronized
        fun add(line: String) {
            buffer[head] = line
            head = (head + 1) % capacity
            if (size < capacity) size++
        }

        @Synchronized
        fun clear() {
            head = 0; size = 0
            for (i in buffer.indices) buffer[i] = null
        }

        @Synchronized
        override fun toString(): String {
            val sb = StringBuilder()
            val start = if (size < capacity) 0 else head
            for (i in 0 until size) {
                val idx = (start + i) % capacity
                buffer[idx]?.let { sb.append(it).append('\n') }
            }
            return sb.toString()
        }
    }
}