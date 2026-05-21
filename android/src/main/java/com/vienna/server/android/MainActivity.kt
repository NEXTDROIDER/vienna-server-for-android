package com.vienna.server.android

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.*
import android.text.InputType
import android.widget.*

class MainActivity : Activity() {

    private var server: ViennaHttpServer? = null

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var portInput: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        statusText = TextView(this).apply {
            text = "Stopped"
            textSize = 18f
        }

        portInput = EditText(this).apply {
            setText("8080")
            inputType = InputType.TYPE_CLASS_NUMBER
        }

        val startBtn = Button(this).apply {
            text = "START"
            setOnClickListener { startServer() }
        }

        val stopBtn = Button(this).apply {
            text = "STOP"
            setOnClickListener { stopServer() }
        }

        logText = TextView(this)

        root.addView(statusText)
        root.addView(portInput)
        root.addView(startBtn)
        root.addView(stopBtn)
        root.addView(logText)

        setContentView(root)
    }

    private fun startServer() {
        val port = portInput.text.toString().toIntOrNull() ?: 8080

        val instance = ViennaHttpServer(this, port) { msg ->
            runOnUiThread { appendLog(msg) }
        }

        instance.start()
        server = instance

        statusText.text = "Running on $port"
    }

    private fun stopServer() {
        server?.stop()
        server = null
        statusText.text = "Stopped"
    }

    private fun appendLog(msg: String) {
        logText.text =
            if (logText.text.isNullOrEmpty()) msg
            else logText.text.toString() + "\n" + msg
    }
}