
package com.vienna.server.android

import android.content.Context
import android.os.Environment
import java.io.File

class ViennaPaths(context: Context) {

    // Shared storage root: /storage/emulated/0/.vienna
    val rootDir: File = File(
        Environment.getExternalStorageDirectory(),
        ".vienna"
    ).apply {
        if (!exists() && !mkdirs()) {
            throw IllegalStateException(
                "Cannot create Vienna directory: $absolutePath"
            )
        }

        if (!isDirectory) {
            throw IllegalStateException(
                "Vienna path is not a directory: $absolutePath"
            )
        }
    }

    val dbFile: File = File(rootDir, "earth.db")

    // Static game resources: catalog, levels, tappables, encounters
    val staticDataDir: File = File(rootDir, "data").apply {
        mkdirs()
    }

    val modsDir: File = File(rootDir, "mods").apply {
        mkdirs()
    }

    val logsDir: File = File(rootDir, "logs").apply {
        mkdirs()
    }

    val buildplatesDir: File = File(rootDir, "buildplates").apply {
        mkdirs()
    }

    val resourcePackFile: File = File(rootDir, "resource-pack.zip")
}
