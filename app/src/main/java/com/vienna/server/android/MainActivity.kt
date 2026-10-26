package com.vienna.server.android

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.io.File

class MainActivity : Activity() {

    private var server: ViennaHttpServer? = null

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var portInput: EditText
    private lateinit var scroller: ScrollView

    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var patcherButton: Button

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildUi()

        appendLog("Vienna Server initialized")
        appendLog(
            "Android version: ${Build.VERSION.RELEASE} " +
                "(API ${Build.VERSION.SDK_INT})"
        )

        if (!hasStorageAccess()) {
            appendLog("Storage access is required.")
            requestStorageAccess()
        } else {
            appendLog("Storage access granted.")
            ensureLogDirectory()
        }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 16)
        }

        val title = TextView(this).apply {
            text = "Vienna Server"
            textSize = 26f
            setTextColor(0xff101418.toInt())
        }

        statusText = TextView(this).apply {
            text = "Stopped"
            textSize = 16f
            setPadding(0, 16, 0, 12)
        }

        portInput = EditText(this).apply {
            hint = "Port"
            setText("8080")
            inputType = InputType.TYPE_CLASS_NUMBER
            setSingleLine(true)
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        startButton = Button(this).apply {
            text = "Start"
            setOnClickListener {
                startServer()
            }
        }

        stopButton = Button(this).apply {
            text = "Stop"
            isEnabled = false
            setOnClickListener {
                stopServer()
            }
        }

        patcherButton = Button(this).apply {
            text = "Patcher"

            setOnClickListener {
                if (!hasStorageAccess()) {
                    appendLog(
                        "Grant storage access before opening Patcher."
                    )
                    requestStorageAccess()
                    return@setOnClickListener
                }

                val port = portInput.text.toString()
                    .toIntOrNull() ?: 8080

                try {
                    val intent = Intent(
                        this@MainActivity,
                        PatcherActivity::class.java
                    ).putExtra(
                        PatcherActivity.EXTRA_PORT,
                        port
                    )

                    startActivity(intent)
                } catch (error: Exception) {
                    appendLog(
                        "Could not open Patcher: ${error.message}"
                    )
                    ServerLogger.log(
                        "Patcher launch failed",
                        error
                    )
                }
            }
        }

        buttons.addView(
            startButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        buttons.addView(
            stopButton,
            android.widget.LinearLayout.LayoutParams(
                0,
                android.widget.LinearLayout.LayoutParams.WRAP_CONTENT,
                1f
            )
        )

        val logTitle = TextView(this).apply {
            text = "Server logs"
            textSize = 18f
            setPadding(0, 20, 0, 8)
        }

        logText = TextView(this).apply {
            textSize = 12f
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xff263238.toInt())
            setTextIsSelectable(true)
            setPadding(12, 12, 12, 12)
            setBackgroundColor(0xfff1f3f4.toInt())
            text = ""
        }

        scroller = ScrollView(this).apply {
            isFillViewport = true

            addView(
                logText,
                android.widget.FrameLayout.LayoutParams(
                    android.widget.FrameLayout.LayoutParams.MATCH_PARENT,
                    android.widget.FrameLayout.LayoutParams.WRAP_CONTENT
                )
            )
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(portInput)
        root.addView(buttons)
        root.addView(patcherButton)
        root.addView(logTitle)

        root.addView(
            scroller,
            android.widget.LinearLayout.LayoutParams(
                android.widget.LinearLayout.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun hasStorageAccess(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
            Environment.isExternalStorageManager()
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            appendLog(
                "Check storage permissions in Android settings."
            )
            return
        }

        try {
            val intent = Intent(
                Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivity(intent)
        } catch (error: Exception) {
            try {
                startActivity(
                    Intent(
                        Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                    )
                )
            } catch (fallbackError: Exception) {
                appendLog(
                    "Could not open storage settings: " +
                        "${fallbackError.message}"
                )

                ServerLogger.log(
                    "Storage settings launch failed",
                    fallbackError
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()

        if (!::statusText.isInitialized) return

        if (hasStorageAccess()) {
            statusText.text = if (server?.isRunning == true) {
                "Running on port ${portInput.text}"
            } else {
                "Stopped"
            }

            appendLog("Storage access: granted")
            ensureLogDirectory()
        } else {
            statusText.text = "Storage permission required"
        }
    }

    private fun ensureLogDirectory() {
        try {
            val directory = File(
                Environment.getExternalStorageDirectory(),
                ".vienna/logs"
            )

            if (!directory.exists() && !directory.mkdirs()) {
                appendLog(
                    "Could not create log directory: " +
                        directory.absolutePath
                )
                return
            }

            appendLog(
                "Log file: " +
                    File(directory, "server.log").absolutePath
            )
        } catch (error: Exception) {
            appendLog(
                "Log directory error: ${error.message}"
            )

            ServerLogger.log(
                "Log directory initialization failed",
                error
            )
        }
    }

    private fun startServer() {
        if (server?.isRunning == true) {
            appendLog("Server is already running.")
            return
        }

        if (!hasStorageAccess()) {
            appendLog(
                "Cannot start: grant all-files access first."
            )
            requestStorageAccess()
            return
        }

        val port = portInput.text.toString().toIntOrNull()

        if (port == null || port !in 1..65535) {
            appendLog(
                "Invalid port. Enter a number between 1 and 65535."
            )
            return
        }

        startButton.isEnabled = false
        appendLog("Starting Vienna Server on port $port...")

        val instance = ViennaHttpServer(this, port) { message ->
            runOnUiThread {
                appendLog(message)
            }
        }

        try {
            instance.start()
            server = instance

            statusText.text = "Running on port $port"
            stopButton.isEnabled = true

            appendLog("Server started: http://0.0.0.0:$port")
            appendLog(
                "Root directory: " +
                    instance.paths.rootDir.absolutePath
            )
            appendLog(
                "Database: " +
                    instance.paths.dbFile.absolutePath
            )
            appendLog(
                "Static data: " +
                    instance.paths.staticDataDir.absolutePath
            )
            appendLog(
                "Mods: " +
                    instance.paths.modsDir.absolutePath
            )
            appendLog(
                "Buildplates: " +
                    instance.paths.buildplatesDir.absolutePath
            )
            appendLog(
                "Logs: " +
                    instance.paths.logsDir.absolutePath
            )

            ServerLogger.log("Server started on port $port")
        } catch (error: Exception) {
            statusText.text = "Start failed"

            appendLog(
                "Start failed: ${error.message}"
            )

            ServerLogger.log(
                "Server startup failed",
                error
            )

            try {
                instance.stop()
            } catch (stopError: Exception) {
                ServerLogger.log(
                    "Error stopping failed server",
                    stopError
                )
            }

            server = null
            stopButton.isEnabled = false
        } finally {
            startButton.isEnabled = true
        }
    }

    private fun stopServer() {
        val instance = server

        if (instance == null) {
            statusText.text = "Stopped"
            stopButton.isEnabled = false
            return
        }

        appendLog("Stopping Vienna Server...")

        try {
            instance.stop()
            appendLog("Server stopped.")
            ServerLogger.log("Server stopped")
        } catch (error: Exception) {
            appendLog(
                "Stop failed: ${error.message}"
            )

            ServerLogger.log(
                "Server stop failed",
                error
            )
        } finally {
            server = null
            statusText.text = "Stopped"
            stopButton.isEnabled = false
            startButton.isEnabled = true
        }
    }

    private fun appendLog(message: String) {
        if (!::logText.isInitialized) return

        runOnUiThread {
            val current = logText.text.toString()

            logText.text = if (current.isBlank()) {
                message
            } else {
                "$current\n$message"
            }

            scroller.post {
                scroller.fullScroll(View.FOCUS_DOWN)
            }
        }

        ServerLogger.log(message)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}xception) {
            statusText.text = "Start failed"

            appendLog(
                "Start failed: ${error.message}"
            )

            ServerLogger.log(
                "Server startup failed",
                error
            )

            try {
                instance.stop()
            } catch (stopError: Exception) {
                ServerLogger.log(
                    "Error stopping failed server",
                    stopError
                )
            }

            server = null
            stopButton.isEnabled = false
        } finally {
            startButton.isEnabled = true
        }
    }

    private fun stopServer() {
        val instance = server

        if (instance == null) {
            statusText.text = "Stopped"
            stopButton.isEnabled = false
            return
        }

        appendLog("Stopping Vienna Server...")

        try {
            instance.stop()

            appendLog("Server stopped.")
            ServerLogger.log("Server stopped")
        } catch (error: Exception) {
            appendLog(
                "Stop failed: ${error.message}"
            )

            ServerLogger.log(
                "Server stop failed",
                error
            )
        } finally {
            server = null
            statusText.text = "Stopped"
            stopButton.isEnabled = false
            startButton.isEnabled = true
        }
    }

    private fun appendLog(message: String) {
        if (!::logText.isInitialized) return

        runOnUiThread {
            val current = logText.text.toString()

            logText.text = if (current.isBlank()) {
                message
            } else {
                "$current\n$message"
            }

            scroller.post {
                scroller.fullScroll(View.FOCUS_DOWN)
            }
        }

        ServerLogger.log(message)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}nUiThread {
            val current = logText.text.toString()

            logText.text = if (current.isBlank()) {
                message
            } else {
                "$current\n$message"
            }

            scroller.post {
                scroller.fullScroll(View.FOCUS_DOWN)
            }
        }

        // Write to the persistent log file.
        // ServerLogger handles file-write failures internally.
        ServerLogger.log(message)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }
}
