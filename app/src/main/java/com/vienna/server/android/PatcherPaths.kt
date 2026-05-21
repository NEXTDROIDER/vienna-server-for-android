package com.vienna.server.android

import android.content.Context
import android.os.Environment
import java.io.File
import java.nio.file.Path

class PatcherPaths(context: Context) {
    // Використовуємо спільну папку vienna в публічному сховищі
    val rootDir: File = File(Environment.getExternalStorageDirectory(), "vienna").apply { mkdirs() }
    val patcherDir: File = File(rootDir, "patcher").apply { mkdirs() }

    val inputApk: File = File(patcherDir, "base.apk")
    val aaptExec: File = File(patcherDir, "aapt")
    val earthKeystore: File = File(patcherDir, "earth_test.pkcs12")
    
    val outDir: Path = patcherDir.resolve("extracted").toPath()
    val patchDir: Path = patcherDir.resolve("patches").toPath()
    val frameworkDir: String = File(patcherDir, "framework").absolutePath

    val outFileUnsigned: File = File(patcherDir, "project-earth-unsigned.apk")
    val outFileSigned: File = File(patcherDir, "project-earth.apk")
}
