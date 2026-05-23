package com.vienna.server.android

import android.content.Context
import java.io.File

class ViennaPaths(context: Context) {
    
    // Безопасно получаем путь к внешней памяти (/sdcard/) через Android API.
    // Это гарантирует работу на Android 11, 12, 13, 14+ и любых флагманах.
    val rootDir: File = run {
        // Получаем путь вида /storage/emulated/0/Android/data/com.vienna.server.android/files
        val externalDir = context.getExternalFilesDir(null) 
            ?: context.filesDir // Резервный вариант во внутреннюю память, если SD-карта недоступна
            
        // Поднимаемся на 4 уровня вверх, чтобы выйти из Android/data/... в корень (/sdcard/)
        var parentDir = externalDir
        for (i in 1..4) {
            parentDir = parentDir.parentFile ?: parentDir
        }
        
        // Создаем целевую папку "vienna" в корне /sdcard/
        File(parentDir, "vienna").apply { mkdirs() }
    }

    val dbFile: File = File(rootDir, "earth.db")
    val staticDataDir: File = File(rootDir, "data").apply { mkdirs() }
    val modsDir: File = File(rootDir, "mods").apply { mkdirs() }
    val logsDir: File = File(rootDir, "logs").apply { mkdirs() }
    val buildplatesDir: File = File(rootDir, "buildplates").apply { mkdirs() }
    val resourcePackFile: File = File(rootDir, "resource-pack.zip")
}