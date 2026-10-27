
package com.vienna.server.android

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import java.io.File

class MainActivity : Activity() {

    private lateinit var portInput: EditText
    private lateinit var logView: TextView
    private lateinit var scrollView: ScrollView
    private lateinit var startButton: Button
    private lateinit var stopButton: Button
    private lateinit var resourcesButton: Button

    private var server: ViennaHttpServer? = null
    private var permissionDialogShown = false
    private var autoInstallChecked = false

    private val viennaDirectory = File("/sdcard/.vienna")

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        createUi()
        appendLog("Vienna Server for Android запущен.")
        appendLog("Папка ресурсов: ${viennaDirectory.absolutePath}")
    }

    override fun onResume() {
        super.onResume()

        if (hasStorageAccess()) {
            if (!autoInstallChecked) {
                autoInstallChecked = true
                ensureResources()
            }
        } else if (!permissionDialogShown) {
            permissionDialogShown = true
            requestStorageAccess()
        }
    }

    private fun createUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(20, 20, 20, 20)
        }

        val title = TextView(this).apply {
            text = "Vienna Server for Android"
            textSize = 22f
            gravity = Gravity.CENTER
        }
        root.addView(
            title,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        portInput = EditText(this).apply {
            hint = "Порт сервера"
            setSingleLine(true)
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
            setText("8080")
        }
        root.addView(
            portInput,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        startButton = Button(this).apply {
            text = "Запустить сервер"
            setOnClickListener { startServer() }
        }
        root.addView(startButton)

        stopButton = Button(this).apply {
            text = "Остановить сервер"
            setOnClickListener { stopServer() }
            isEnabled = false
        }
        root.addView(stopButton)

        resourcesButton = Button(this).apply {
            text = "Скачать и распаковать ресурсы"
            setOnClickListener {
                if (hasStorageAccess()) {
                    downloadResources(force = true)
                } else {
                    requestStorageAccess()
                }
            }
        }
        root.addView(resourcesButton)

        val patcherButton = Button(this).apply {
            text = "Patcher"
            setOnClickListener {
                try {
                    startActivity(
                        Intent(
                            this@MainActivity,
                            PatcherActivity::class.java
                        )
                    )
                } catch (e: Exception) {
                    appendLog("Не удалось открыть Patcher: ${e.message}")
                    Toast.makeText(
                        this@MainActivity,
                        "Не удалось открыть Patcher",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        }
        root.addView(patcherButton)

        val logTitle = TextView(this).apply {
            text = "Журнал"
            textSize = 18f
        }
        root.addView(logTitle)

        scrollView = ScrollView(this)
        logView = TextView(this).apply {
            textSize = 13f
            setTextIsSelectable(true)
        }
        scrollView.addView(
            logView,
            ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        )

        root.addView(
            scrollView,
            LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun hasStorageAccess(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Environment.isExternalStorageManager()
        } else {
            checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) ==
                android.content.pm.PackageManager.PERMISSION_GRANTED
        }
    }

    private fun requestStorageAccess() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AlertDialog.Builder(this)
                .setTitle("Доступ к файлам")
                .setMessage(
                    "Для загрузки ресурсов в /sdcard/.vienna/ " +
                        "приложению нужен доступ к файлам."
                )
                .setNegativeButton("Позже", null)
                .setPositiveButton("Настройки") { _, _ ->
                    try {
                        val intent = Intent(
                            Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                            Uri.parse("package:$packageName")
                        )
                        startActivity(intent)
                    } catch (_: Exception) {
                        startActivity(
                            Intent(
                                Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION
                            )
                        )
                    }
                }
                .show()
        } else {
            requestPermissions(
                arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE),
                STORAGE_PERMISSION_REQUEST
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (requestCode == STORAGE_PERMISSION_REQUEST &&
            hasStorageAccess()
        ) {
            ensureResources()
        } else if (requestCode == STORAGE_PERMISSION_REQUEST) {
            appendLog("Доступ к хранилищу не предоставлен.")
        }
    }

    private fun ensureResources() {
        if (ViennaResourceInstaller.isInstalling()) return

        if (ViennaResourceInstaller.isInstalled(viennaDirectory)) {
            appendLog("Ресурсы уже установлены.")
            return
        }

        downloadResources(force = false)
    }

    private fun downloadResources(force: Boolean) {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }

        if (ViennaResourceInstaller.isInstalling()) {
            appendLog("Загрузка ресурсов уже выполняется.")
            return
        }

        resourcesButton.isEnabled = false
        appendLog("Подготовка загрузки ресурсов...")

        ViennaResourceInstaller.install(
            destination = viennaDirectory,
            force = force,
            onProgress = { message ->
                appendLog(message)
            },
            onComplete = { result ->
                resourcesButton.isEnabled = true

                result.onSuccess {
                    appendLog("Установка ресурсов завершена.")
                    Toast.makeText(
                        this,
                        "Ресурсы установлены",
                        Toast.LENGTH_SHORT
                    ).show()
                }.onFailure { error ->
                    appendLog(
                        "Ошибка ресурсов: " +
                            (error.message ?: error.javaClass.simpleName)
                    )
                    Toast.makeText(
                        this,
                        "Не удалось установить ресурсы",
                        Toast.LENGTH_LONG
                    ).show()
                }
            }
        )
    }

    private fun startServer() {
        if (!hasStorageAccess()) {
            requestStorageAccess()
            return
        }

        val port = portInput.text.toString().toIntOrNull()

        if (port == null || port !in 1..65535) {
            Toast.makeText(
                this,
                "Укажи корректный порт от 1 до 65535",
                Toast.LENGTH_SHORT
            ).show()
            return
        }

        if (server?.isRunning() == true) {
            appendLog("Сервер уже запущен.")
            return
        }

        try {
            server = ViennaHttpServer(this, port) { message ->
                runOnUiThread {
                    appendLog(message)
                }
            }

            server?.start()

            startButton.isEnabled = false
            stopButton.isEnabled = true
            portInput.isEnabled = false

            appendLog("Сервер запущен на порту $port.")
        } catch (e: Exception) {
            server = null
            appendLog("Ошибка запуска сервера: ${e.message}")
        }
    }

    private fun stopServer() {
        try {
            server?.stop()
            appendLog("Сервер остановлен.")
        } catch (e: Exception) {
            appendLog("Ошибка остановки сервера: ${e.message}")
        } finally {
            server = null
            startButton.isEnabled = true
            stopButton.isEnabled = false
            portInput.isEnabled = true
        }
    }

    private fun appendLog(message: String) {
        if (!::logView.isInitialized) return

        val line = "[${java.text.SimpleDateFormat(
            "HH:mm:ss",
            java.util.Locale.getDefault()
        ).format(java.util.Date())}] $message\n"

        logView.append(line)

        if (::scrollView.isInitialized) {
            scrollView.post {
                scrollView.fullScroll(ScrollView.FOCUS_DOWN)
            }
        }

        try {
            val logDirectory = File(viennaDirectory, "logs")
            if (!logDirectory.exists()) {
                logDirectory.mkdirs()
            }

            File(logDirectory, "server.log").appendText(line)
        } catch (_: Exception) {
            // Ошибка записи лога не должна останавливать сервер.
        }
    }

    override fun onDestroy() {
        try {
            server?.stop()
        } catch (_: Exception) {
        }

        server = null
        super.onDestroy()
    }

    companion object {
        private const val STORAGE_PERMISSION_REQUEST = 701
    }
}
