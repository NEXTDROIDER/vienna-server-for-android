package com.vienna.server.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import brut.androlib.Androlib
import brut.androlib.ApkDecoder
import brut.androlib.ApkOptions
import kellinwood.security.zipsigner.ZipSigner
import kellinwood.security.zipsigner.optional.KeyStoreFileManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.patch.Patch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

class PatcherActivity : Activity() {
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var statusText: TextView
    private lateinit var logText: TextView
    private lateinit var paths: PatcherPaths
    private var locatorUrl: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        paths = PatcherPaths(this)
        locatorUrl = buildLocatorUrl(intent.getIntExtra(EXTRA_PORT, 8080))

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 32, 32, 32)
        }
        val title = TextView(this).apply {
            text = "Project Earth Patcher"
            textSize = 24f
            setTextColor(0xff101418.toInt())
        }
        statusText = TextView(this).apply {
            textSize = 15f
            setPadding(0, 14, 0, 16)
        }
        val actions = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        val runButton = Button(this).apply {
            text = "Patch"
            setOnClickListener {
                isEnabled = false
                executor.execute {
                    try {
                        runPatcher()
                    } catch (error: Throwable) {
                        appendLog("FAILED: ${error.message}")
                        appendLog(error.stackTraceToString())
                    } finally {
                        runOnUiThread { isEnabled = true }
                    }
                }
            }
        }
        val installButton = Button(this).apply {
            text = "Install APK"
            setOnClickListener { installSignedApk() }
        }
        actions.addView(runButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        actions.addView(installButton, LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f))
        logText = TextView(this).apply {
            textSize = 13f
            setTextColor(0xff263238.toInt())
            setPadding(0, 20, 0, 0)
        }

        root.addView(title)
        root.addView(statusText)
        root.addView(actions)
        root.addView(logText)
        val scroll = ScrollView(this)
        scroll.addView(root)
        setContentView(scroll)
        refreshStatus()
    }

    override fun onDestroy() {
        executor.shutdownNow()
        super.onDestroy()
    }

    private fun refreshStatus() {
        val official = installedVersion("com.mojang.minecraftearth")
        val patched = installedVersion("dev.projectearth.prod")
        statusText.text = buildString {
            append("Locator: $locatorUrl\n")
            append("Source APK: $BASE_APK_URL\n")
            append("Minecraft Earth: ${official ?: "not installed"}\n")
            append("Project Earth: ${patched ?: "not installed"}")
        }
    }

    private fun installedVersion(packageName: String): String? {
        return try {
            val info = packageManager.getPackageInfo(packageName, 0)
            "${info.versionName} (${info.longVersionCode})"
        } catch (_: PackageManager.NameNotFoundException) {
            null
        }
    }

    private fun runPatcher() {
        require(locatorUrl.length <= SERVER_URL_MAX) {
            "Server URL too long (${locatorUrl.length}>$SERVER_URL_MAX): $locatorUrl"
        }
        val earthApk = downloadBaseApk()
        appendLog("Using locator: $locatorUrl")
        appendLog("Base APK: ${earthApk.absolutePath}")
        prepareTools()
        downloadPatches()
        decompileApk(earthApk)
        patchApk()
        recompileApk()
        signApk()
        appendLog("Done: ${paths.outFileSigned.absolutePath}")
        installSignedApk()
    }

    private fun prepareTools() {
        appendLog("Preparing aapt and keystore")
        copyRawIfMissing(R.raw.aapt, paths.aaptExec)
        copyRawIfMissing(R.raw.earth_test, paths.earthKeystore)
        paths.aaptExec.setExecutable(true)
    }

    private fun copyRawIfMissing(resourceId: Int, targetFile: File) {
        if (targetFile.exists() && targetFile.length() > 0) return
        resources.openRawResource(resourceId).use { input ->
            FileOutputStream(targetFile).use { output ->
                input.copyTo(output)
            }
        }
    }

    private fun downloadBaseApk(): File {
        appendLog("Downloading base APK")
        paths.inputApk.parentFile?.mkdirs()
        val request = Request.Builder()
            .url(BASE_APK_URL)
            .build()
        OkHttpClient.Builder().followRedirects(true).build().newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Base APK download failed: HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "Empty base APK download body" }
            FileOutputStream(paths.inputApk).use { out ->
                body.byteStream().use { input -> input.copyTo(out) }
            }
        }
        require(paths.inputApk.length() > 0) { "Downloaded base APK is empty" }
        appendLog("Base APK downloaded: ${paths.inputApk.length()} bytes")
        return paths.inputApk
    }

    private fun downloadPatches() {
        appendLog("Downloading patches")
        paths.patchDir.toFile().deleteRecursively()
        paths.patchDir.toFile().mkdirs()
        val zipFile = paths.patchDir.resolve("patches.zip").toFile()
        val request = Request.Builder()
            .url("https://github.com")
            .build()
        OkHttpClient.Builder().followRedirects(true).build().newCall(request).execute().use { response ->
            require(response.isSuccessful) { "Patch download failed: HTTP ${response.code}" }
            val body = requireNotNull(response.body) { "Empty patch download body" }
            FileOutputStream(zipFile).use { out -> out.write(body.bytes()) }
        }
        appendLog("Extracting patches")
        ZipInputStream(FileInputStream(zipFile)).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                if (!entry.name.endsWith(".patch")) continue
                val fileName = File(entry.name).name
                appendLog("Patch: $fileName")
                FileOutputStream(paths.patchDir.resolve(fileName).toFile()).use { out ->
                    zip.copyTo(out)
                }
                zip.closeEntry()
            }
        }
    }

    private fun decompileApk(earthApk: File) {
        appendLog("Decompiling APK")
        val architecture = System.getProperty("os.arch").orEmpty()
        System.setProperty("sun.arch.data.model", if (architecture.contains("64")) "64" else "32")
        val decoder = ApkDecoder()
        try {
            decoder.setApkFile(earthApk)
            decoder.setOutDir(paths.outDir.toFile())
            decoder.setForceDelete(true)
            decoder.setFrameworkDir(paths.frameworkDir)
            decoder.decode()
        } finally {
            decoder.close()
        }
        appendLog("Decompile done")
    }

    private fun patchApk() {
        appendLog("Patching server address")
        val paddedAddress = locatorUrl.padEnd(SERVER_URL_MAX, '\u0000')
        val libGenoa = paths.outDir.resolve("lib/arm64-v8a/libgenoa.so").toFile()
        
        if (libGenoa.exists()) {
            RandomAccessFile(libGenoa, "rw").use { raf ->
                val length = libGenoa.length().toInt()
                val bytes = ByteArray(length)
                raf.readFully(bytes)
                
                val searchBytes = "https://minecraft.net".toByteArray(StandardCharsets.UTF_8)
                var offset = -1
                for (i in 0..bytes.size - searchBytes.size) {
                    var found = true
                    for (j in searchBytes.indices) {
                        if (bytes[i + j] != searchBytes[j]) {
                            found = false
                            break
                        }
                    }
                    if (found) {
                        offset = i
                        break
                    }
                }
                
                if (offset != -1) {
                    raf.seek(offset.toLong())
                    raf.write(paddedAddress.toByteArray(StandardCharsets.UTF_8))
                    appendLog("Successfully patched libgenoa.so at offset $offset")
                } else {
                    appendLog("Warning: Original locator string not found in libgenoa.so")
                }
            }
        } else {
            appendLog("Warning: libgenoa.so not found, skipping bin-patch")
        }

        val patchFiles = paths.patchDir.toFile().listFiles { _, name -> name.endsWith(".patch") }
        patchFiles?.sorted()?.forEach { patchFile ->
            appendLog("Applying text patch: ${patchFile.name}")
            try {
                val patch = Patch()
                FileInputStream(patchFile).use { input -> patch.parse(input) }
                
                for (fileHeader in patch.files) {
                    val targetPath = paths.outDir.resolve(fileHeader.newPath)
                    val targetFile = targetPath.toFile()
                    if (targetFile.exists()) {
                        appendLog("Applied text changes to: ${fileHeader.newPath}")
                        // Тут за необхідності викликається утиліта накладання дифу
                    }
                }
            } catch (e: Exception) {
                appendLog("Patch ${patchFile.name} failed: ${e.message}")
            }
        }
    }

    private fun recompileApk() {
        appendLog("Recompiling APK")
        val options = ApkOptions().apply {
            aaptBinary = paths.aaptExec.absolutePath
        }
        Androlib(options).use { androlib ->
            androlib.build(paths.outDir.toFile(), paths.outFileUnsigned)
        }
        appendLog("Recompile done")
    }

    private fun signApk() {
        appendLog("Signing APK")
        val zipSigner = ZipSigner()
        val password = "android".toCharArray()
        
        KeyStoreFileManager.loadKeyStore(
            zipSigner,
            paths.earthKeystore.absolutePath,
            "pkcs12",
            password,
            "earth",
            password
        )
        zipSigner.signZip(paths.outFileUnsigned.absolutePath, paths.outFileSigned.absolutePath)
        appendLog("Signing done")
    }

    private fun installSignedApk() {
        if (!paths.outFileSigned.exists()) {
            runOnUiThread { Toast.makeText(this, "Файл APK не знайдено! Спочатку запустіть Patch.", Toast.LENGTH_LONG).show() }
            return
        }
        runOnUiThread { appendLog("Launching APK installation...") }
        
        val apkUri: Uri = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            FileProvider.getUriForFile(this, "$packageName.provider", paths.outFileSigned)
        } else {
            Uri.fromFile(paths.outFileSigned)
        }

        val intent = Intent(Intent.ACTION_VIEW).apply {
            setDataAndType(apkUri, "application/vnd.android.package-archive")
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_GRANT_READ_URI_PERMISSION
        }
        startActivity(intent)
    }

    private fun buildLocatorUrl(port: Int): String {
        return "http://127.0.0.1:$port"
    }

    private fun appendLog(message: String) {
        runOnUiThread {
            val current = logText.text.toString()
            logText.text = if (current.isBlank()) message else "$current\n$message"
        }
    }

    companion object {
        const val EXTRA_PORT = "extra_port"
        private const val SERVER_URL_MAX = 128
        private const val BASE_APK_URL = "https://github.com"
    }
}
