package com.vienna.server.android

import android.content.Context
import java.io.File

class ViennaPaths(context: Context) {
    val rootDir: File = File(context.filesDir, "vienna").apply { mkdirs() }
    val dbFile: File = File(rootDir, "earth.db")
    val staticDataDir: File = File(rootDir, "data").apply { mkdirs() }
    val modsDir: File = File(rootDir, "mods").apply { mkdirs() }
    val logsDir: File = File(rootDir, "logs").apply { mkdirs() }
    val buildplatesDir: File = File(rootDir, "buildplates").apply { mkdirs() }
    val resourcePackFile: File = File(rootDir, "resource-pack.zip")
}
