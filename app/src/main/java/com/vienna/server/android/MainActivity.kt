package com.vienna.server.android

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private var server: ViennaHttpServer? = null
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var portInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
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
        }

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val startButton = Button(this).apply {
            text = "Start"
            setOnClickListener { startServer() }
        }
        val stopButton = Button(this).apply {
            text = "Stop"
            setOnClickListener { stopServer() }
        }
        val patcherButton = Button(this).apply {
            text = "Patcher"
            setOnClickListener {
                startActivity(
                    Intent(this@MainActivity, PatcherActivity::class.java)
                        .putExtra(PatcherActivity.EXTRA_PORT, portInput.text.toString().toIntOrNull() ?: 8080)
                )
            }
        }
        buttons.addView(startButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        buttons.addView(stopButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))

        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xff263238.toInt())
            setPadding(0, 20, 0, 0)
        }

        val scroller = ScrollView(this)
        scroller.addView(root)
        root.addView(title)
        root.addView(statusText)
        root.addView(portInput)
        root.addView(buttons)
        root.addView(patcherButton)
        root.addView(logText)
        setContentView(scroller)
    }

    override fun onDestroy() {
        stopServer()
        super.onDestroy()
    }

    private fun startServer() {
        if (server?.isRunning == true) return
        val port = portInput.text.toString().toIntOrNull() ?: 8080
        val instance = ViennaHttpServer(this, port) { message ->
            runOnUiThread { appendLog(message) }
        }
        try {
            instance.start()
            server = instance
            statusText.text = "Running on http://0.0.0.0:$port"
            appendLog("Server started on port $port")
            appendLog("Static data folder: ${instance.paths.staticDataDir.absolutePath}")
            appendLog("Buildplates folder: ${instance.paths.buildplatesDir.absolutePath}")
        } catch (error: Exception) {
            appendLog("Start failed: ${error.message}")
            instance.stop()
        }
    }

    private fun stopServer() {
        server?.stop()
        server = null
        if (::statusText.isInitialized) {
            statusText.text = "Stopped"
        }
    }

    private fun appendLog(message: String) {
        val current = logText.text.toString()
        logText.text = if (current.isBlank()) message else "$current\n$message"
    }
}
