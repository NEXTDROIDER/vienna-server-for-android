
package com.vienna.server.android

import android.os.Environment
import android.util.Log
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

object ServerLogger {
    private val lock = Any()

    private fun logFile(): File {
        val dir = File(
            Environment.getExternalStorageDirectory(),
            ".vienna/logs"
        )

        if (!dir.exists() && !dir.mkdirs()) {
            throw IllegalStateException(
                "Cannot create log directory: ${dir.absolutePath}"
            )
        }

        return File(dir, "server.log")
    }

    fun log(message: String, error: Throwable? = null) {
        val timestamp = SimpleDateFormat(
            "yyyy-MM-dd HH:mm:ss",
            Locale.getDefault()
        ).format(Date())

        val fullMessage = buildString {
            append("[$timestamp] ")
            append(message)
            if (error != null) {
                append("\n")
                append(Log.getStackTraceString(error))
            }
        }

        Log.d("ViennaServer", fullMessage)

        synchronized(lock) {
            runCatching {
                FileWriter(logFile(), true).use {
                    it.appendLine(fullMessage)
                }
            }.onFailure {
                Log.e("ViennaServer", "Failed to write log file", it)
            }
        }
    }
}
