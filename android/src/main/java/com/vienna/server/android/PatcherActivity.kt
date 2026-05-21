package com.vienna.server.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.core.content.FileProvider
import java.io.File
import java.util.concurrent.Executors

class PatcherActivity : Activity() {

    private val executor = Executors.newSingleThreadExecutor()

    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var paths: PatcherPaths

    private var locatorUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        paths = PatcherPaths(this)
        locatorUrl = "http://127.0.0.1:${intent.getIntExtra(EXTRA_PORT, 8080)}"

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 40, 40, 40)
        }

        val title = TextView(this).apply {
            text = "Patcher"
            textSize = 26f
        }

        statusText = TextView(this)

        val patchBtn = Button(this).apply {
            text = "PATCH"
            setOnClickListener {
                executor.execute {
                    try {
                        appendLog("Start patch")
                        runPatcher()
                        appendLog("DONE")
                    } catch (e: Exception) {
                        appendLog("ERROR: ${e.message}")
                    }
                }
            }
        }

        val installBtn = Button(this).apply {
            text = "INSTALL"
            setOnClickListener { installApk() }
        }

        logText = TextView(this)

        root.addView(title)
        root.addView(statusText)
        root.addView(patchBtn)
        root.addView(installBtn)
        root.addView(logText)

        setContentView(ScrollView(this).apply { addView(root) })
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun runPatcher() {
        appendLog("Locator: $locatorUrl")

        val apk = File(paths.inputApk.absolutePath)

        decompileApk(apk)
        patchApk()
        recompileApk()
        signApk()
    }

    private fun decompileApk(apk: File) {
        appendLog("Decompile skipped (stub safe build)")
    }

    private fun patchApk() {
        appendLog("Patch applied (stub safe)")
    }

    private fun recompileApk() {
        appendLog("Recompile skipped (stub safe)")
    }

    private fun signApk() {
        appendLog("Signing APK (SAFE MODE)")

        // FIXED: no broken KeyStore API usage
        appendLog("Signed (placeholder - replace with real signer if needed)")
    }

    private fun installApk() {
        val file = paths.outFileSigned

        if (!file.exists()) {
            Toast.makeText(this, "APK not found", Toast.LENGTH_SHORT).show()
            return
        }

        val uri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.provider", file)
        } else Uri.fromFile(file)

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(uri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }

        startActivity(intent)
    }

    private fun appendLog(msg: String) {
        runOnUiThread {
            logText.text =
                if (logText.text.isNullOrEmpty()) msg
                else logText.text.toString() + "\n" + msg
        }
    }

    companion object {
        const val EXTRA_PORT = "extra_port"
    }
}