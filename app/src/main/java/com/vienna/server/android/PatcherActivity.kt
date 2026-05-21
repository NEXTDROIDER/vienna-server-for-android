package com.vienna.server.android

import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.FileProvider
import brut.androlib.Androlib
import brut.androlib.ApkDecoder
import brut.androlib.ApkOptions
import kellinwood.security.zipsigner.ZipSigner
import kellinwood.security.zipsigner.optional.KeyStoreFileManager
import okhttp3.OkHttpClient
import okhttp3.Request
import org.eclipse.jgit.api.Git
import org.eclipse.jgit.patch.FileHeader
import org.eclipse.jgit.patch.Patch
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.io.InputStream
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets
import java.nio.file.Path
import java.security.PrivateKey
import java.security.cert.X509Certificate
import java.util.Arrays
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
        val earthApk = findEarthApk()
        appendLog("Using locator: $locatorUrl")
        appendLog("Minecraft Earth APK: ${earthApk.absolutePath}")
        prepareTools()
        downloadPatches()
        decompileApk(earthApk)
        patchApk()
        recompileApk()
        signApk()
        appendLog("Done: ${paths.outFileSigned.absolutePath}")
        installSignedApk()
    }

    private fun findEarthApk(): File {
        val info = packageManager.getApplicationInfo("com.mojang.minecraftearth", 0)
        return File(info.sourceDir)
    }

    private fun prepareTools() {
        appendLog("Preparing aapt and keystore")
        copyRawIfMissing(R.raw.aapt, paths.aaptExec)
        copyRawIfMissing(R.raw.earth_test, paths.earthKeystore)
        paths.aaptExec.setExecutable(true)
    }

    private fun downloadPatches() {
        appendLog("Downloading patches")
        paths.patchDir.toFile().deleteRecursively()
        paths.patchDir.toFile().mkdirs()
        val zipFile = paths.patchDir.resolve("patches.zip").toFile()
        val request = Request.Builder()
            .url("https://github.com/Project-Earth-Team/Patches/archive/main.zip")
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
        RandomAccessFile(paths.outDir.resolve("lib/arm64-v8a/libgenoa.so").toFile(), "rw").use { raf ->
            raf.seek(0x0514D05D)
            raf.write(paddedAddress.toByteArray(StandardCharsets.UTF_8))
            raf.seek(0x22A6DC8)
            raf.write(0x540005CB)
        }

        Git.init().setDirectory(paths.outDir.toFile()).call().use { git ->
            val patchFiles = paths.patchDir.toFile().listFiles()
                ?.filter { it.name.endsWith(".patch") }
                ?.sortedBy { it.name }
                ?: emptyList()
            for (file in patchFiles) {
                appendLog("Applying ${file.name}")
                FileInputStream(file).use { stream ->
                    val patch = Patch()
                    patch.parse(stream)
                    for (header in patch.files) {
                        if (header.patchType == FileHeader.PatchType.UNIFIED) {
                            normalizeFile(paths.outDir.resolve(header.oldPath).toFile())
                        }
                    }
                }
                git.apply().setPatch(FileInputStream(file)).call()
            }
        }
        appendLog("Patch done")
    }

    private fun recompileApk() {
        appendLog("Recompiling APK")
        val apkOptions = ApkOptions().apply {
            frameworkFolderLocation = paths.frameworkDir
            aaptPath = paths.aaptExec.absolutePath
        }
        Androlib(apkOptions).build(paths.outDir.toFile(), paths.outFile)
        appendLog("Recompile done")
    }

    private fun signApk() {
        appendLog("Signing APK")
        val keystore = KeyStoreFileManager.loadKeyStore(paths.earthKeystore.path, "earth_test".toCharArray())
        val cert = keystore.getCertificate("earth_test") as X509Certificate
        val key = keystore.getKey("earth_test", "earth_test".toCharArray()) as PrivateKey
        val signer = ZipSigner()
        signer.setKeys("earth_test", cert, key, "SHA1withRSA", null)
        signer.signZip(paths.outFile.toString(), paths.outFileSigned.toString())
        appendLog("Sign done")
    }

    private fun installSignedApk() {
        if (!paths.outFileSigned.isFile) {
            appendLog("Signed APK not found yet")
            return
        }
        val uri: Uri = FileProvider.getUriForFile(this, "$packageName.provider", paths.outFileSigned)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        startActivity(intent)
    }

    private fun copyRawIfMissing(resourceId: Int, target: File) {
        if (target.exists()) return
        target.parentFile?.mkdirs()
        resources.openRawResource(resourceId).use { input ->
            FileOutputStream(target).use { output -> input.copyTo(output) }
        }
    }

    private fun normalizeFile(file: File) {
        if (!file.exists()) return
        val text = file.readLines().joinToString("\n")
        file.writeText(text)
    }

    private fun buildLocatorUrl(port: Int): String = "http://${NetworkUtils.localIpv4()}:$port"

    private fun appendLog(message: String) {
        runOnUiThread {
            val current = logText.text.toString()
            logText.text = if (current.isBlank()) message else "$current\n$message"
        }
    }

    private class PatcherPaths(activity: Activity) {
        private val externalCache = requireNotNull(activity.externalCacheDir) { "External cache is unavailable" }
        private val externalFiles = requireNotNull(activity.getExternalFilesDir("")) { "External files dir is unavailable" }
        val patchDir: Path = externalCache.toPath().resolve("patches")
        val outDir: Path = externalCache.toPath().resolve("com.mojang.minecraftearth")
        val outFile: File = externalCache.toPath().resolve("dev.projectearth.prod.unsigned.apk").toFile()
        val outFileSigned: File = externalFiles.toPath().resolve("dev.projectearth.prod.apk").toFile()
        val frameworkDir: String = externalCache.toPath().resolve("framework").toString()
        val aaptExec: File = activity.filesDir.toPath().resolve("aapt").toFile()
        val earthKeystore: File = activity.filesDir.toPath().resolve("earth_test.jks").toFile()
    }

    companion object {
        const val EXTRA_PORT = "com.vienna.server.android.PORT"
        private const val SERVER_URL_MAX = 27
    }
}
