package com.vienna.server.android

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.text.InputType
import android.view.Gravity
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast

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
            setOnClickListener { checkStoragePermissionAndStart() }
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

    private fun checkStoragePermissionAndStart() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (Environment.isExternalStorageManager()) {
                startServer()
            } else {
                // Показуємо діалог із поясненням перед викликом системного екрану
                showPermissionRationaleDialog()
            }
        } else {
            startServer()
        }
    }

    private fun showPermissionRationaleDialog() {
        AlertDialog.Builder(this)
            .setTitle("Доступ до пам'яті пристрою")
            .setMessage("Для коректної роботи локального сервера та патчера додатку необхідно створити робочу папку 'vienna' у вашому загальному сховищі (/storage/emulated/0). Будь ласка, надайте дозвіл на керування всіма файлами у наступному системному вікні.")
            .setPositiveButton("Налаштування") { _, _ ->
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    startActivityForResult(intent, STORAGE_PERMISSION_CODE)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    startActivityForResult(intent, STORAGE_PERMISSION_CODE)
                }
            }
            .setNegativeButton("Скасувати") { dialog, _ ->
                dialog.dismiss()
                appendLog("Помилка: Користувач скасував запит дозволу.")
                Toast.makeText(this, "Без дозволу сервер не зможе працювати", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false) // Забороняємо закривати діалог тапом мимо або кнопкою назад
            .show()
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == STORAGE_PERMISSION_CODE) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                if (Environment.isExternalStorageManager()) {
                    startServer()
                } else {
                    appendLog("Помилка: Дозвіл на доступ до файлів відхилено.")
                    Toast.makeText(this, "Дозвіл відхилено. Сервер не запущено.", Toast.LENGTH_SHORT).show()
                }
            }
        }
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
            statusText.text = "Running on http://0.0.0:$port"
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

    companion object {
        private const val STORAGE_PERMISSION_CODE = 2296
    }
}
