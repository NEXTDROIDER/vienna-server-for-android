
package com.vienna.server.android

import android.os.Handler
import android.os.Looper
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream

object ViennaResourceInstaller {

    private const val RESOURCE_URL =
        "https://cocostore.wisp.uno/github/cdn/viennaRes.zip"

    private const val MAX_ARCHIVE_BYTES = 512L * 1024 * 1024
    private const val MAX_EXTRACTED_BYTES = 1024L * 1024 * 1024
    private const val MAX_ENTRIES = 50_000

    private const val ARCHIVE_NAME = "viennaRes.zip"
    private const val MARKER_NAME = ".resources-installed"
    private const val STAGING_NAME = ".resources-staging"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installing = false

    fun isInstalling(): Boolean = installing

    fun isInstalled(destination: File): Boolean {
        return File(destination, MARKER_NAME).isFile &&
            File(destination, "catalog/items.json").isFile
    }

    fun install(
        destination: File,
        force: Boolean = false,
        onProgress: (String) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        synchronized(this) {
            if (installing) {
                mainHandler.post {
                    onProgress("Установка ресурсов уже выполняется.")
                }
                return
            }
            installing = true
        }

        executor.execute {
            val result = runCatching {
                if (!force && isInstalled(destination)) {
                    report(onProgress, "Ресурсы уже установлены.")
                    return@runCatching
                }

                if (!destination.exists() && !destination.mkdirs()) {
                    throw IOException(
                        "Не удалось создать ${destination.absolutePath}"
                    )
                }

                if (!destination.isDirectory || !destination.canWrite()) {
                    throw IOException(
                        "Нет доступа на запись в ${destination.absolutePath}"
                    )
                }

                val archive = File(destination, ARCHIVE_NAME)
                val partial = File(destination, "$ARCHIVE_NAME.part")
                val staging = File(destination, STAGING_NAME)

                report(onProgress, "Скачивание ресурсов...")
                download(partial, onProgress)

                if (archive.exists() && !archive.delete()) {
                    throw IOException("Не удалось заменить старый ZIP")
                }

                if (!partial.renameTo(archive)) {
                    throw IOException("Не удалось сохранить загруженный ZIP")
                }

                if (staging.exists()) {
                    staging.deleteRecursively()
                }
                if (!staging.mkdirs()) {
                    throw IOException("Не удалось создать временную папку")
                }

                try {
                    report(onProgress, "Распаковка архива...")
                    extractSafely(archive, staging, onProgress)

                    val source = findResourceRoot(staging)

                    if (!File(source, "catalog/items.json").isFile) {
                        throw IOException(
                            "В архиве не найден catalog/items.json. " +
                                "Проверь структуру viennaRes.zip."
                        )
                    }

                    report(onProgress, "Копирование ресурсов в .vienna...")
                    copyContents(source, destination)

                    File(destination, MARKER_NAME).writeText(
                        "source=$RESOURCE_URL\n"
                    )

                    report(
                        onProgress,
                        "Ресурсы успешно установлены в " +
                            destination.absolutePath
                    )
                } finally {
                    staging.deleteRecursively()
                }
            }

            synchronized(this) {
                installing = false
            }

            mainHandler.post {
                onComplete(result)
            }
        }
    }

    private fun download(
        outputFile: File,
        onProgress: (String) -> Unit
    ) {
        var connection: HttpURLConnection? = null

        try {
            connection = (URL(RESOURCE_URL).openConnection()
                as HttpURLConnection).apply {
                connectTimeout = 15_000
                readTimeout = 30_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty("Accept", "application/zip")
                setRequestProperty("Accept-Encoding", "identity")
                connect()
            }

            val status = connection.responseCode
            if (status !in 200..299) {
                throw IOException("HTTP $status при скачивании ZIP")
            }

            if (!connection.url.protocol.equals("https", ignoreCase = true)) {
                throw IOException("Загрузка остановлена: нужен HTTPS")
            }

            val contentLength = connection.contentLengthLong
            if (contentLength > MAX_ARCHIVE_BYTES) {
                throw IOException("Архив превышает лимит 512 МБ")
            }

            var total = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(FileOutputStream(outputFile)).use { output ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        total += count
                        if (total > MAX_ARCHIVE_BYTES) {
                            throw IOException("Архив превышает лимит 512 МБ")
                        }

                        output.write(buffer, 0, count)

                        if (contentLength > 0) {
                            val percent =
                                (total * 100 / contentLength).toInt()
                                    .coerceIn(0, 100)
                            report(
                                onProgress,
                                "Скачивание: $percent% " +
                                    "(${total / 1024} КБ)"
                            )
                        } else {
                            report(
                                onProgress,
                                "Скачано: ${total / 1024} КБ"
                            )
                        }
                    }
                }
            }

            if (total == 0L) {
                throw IOException("Сервер вернул пустой архив")
            }

            // ZIP должен начинаться с сигнатуры PK.
            outputFile.inputStream().use {
                val first = it.read()
                val second = it.read()
                if (first != 0x50 || second != 0x4B) {
                    throw IOException(
                        "Ответ сервера не похож на ZIP-архив"
                    )
                }
            }
        } catch (e: Exception) {
            outputFile.delete()
            throw e
        } finally {
            connection?.disconnect()
        }
    }

    private fun extractSafely(
        archive: File,
        staging: File,
        onProgress: (String) -> Unit
    ) {
        val root = staging.canonicalFile
        var entries = 0
        var extractedBytes = 0L

        ZipInputStream(
            BufferedInputStream(archive.inputStream())
        ).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                entries++

                if (entries > MAX_ENTRIES) {
                    throw IOException("В архиве слишком много файлов")
                }

                val entryName = entry.name

                if (
                    entryName.isBlank() ||
                    entryName.startsWith("/") ||
                    entryName.contains('\\') ||
                    entryName.split('/').any {
                        it == ".." || it == "."
                    }
                ) {
                    throw IOException("Недопустимый путь в ZIP: $entryName")
                }

                val target = File(root, entryName).canonicalFile
                val rootPrefix = root.path + File.separator

                if (!target.path.startsWith(rootPrefix)) {
                    throw IOException("Выход за папку распаковки: $entryName")
                }

                if (entry.isDirectory) {
                    if (!target.exists() && !target.mkdirs()) {
                        throw IOException(
                            "Не удалось создать каталог: $entryName"
                        )
                    }
                } else {
                    val parent = target.parentFile
                        ?: throw IOException("Некорректный путь: $entryName")

                    if (!parent.exists() && !parent.mkdirs()) {
                        throw IOException(
                            "Не удалось создать каталог: ${parent.path}"
                        )
                    }

                    BufferedOutputStream(FileOutputStream(target)).use { output ->
                        val buffer = ByteArray(32 * 1024)

                        while (true) {
                            val count = zip.read(buffer)
                            if (count < 0) break

                            extractedBytes += count
                            if (extractedBytes > MAX_EXTRACTED_BYTES) {
                                throw IOException(
                                    "Распакованные данные превышают 1 ГБ"
                                )
                            }

                            output.write(buffer, 0, count)
                        }
                    }
                }

                zip.closeEntry()

                if (entries % 100 == 0) {
                    report(onProgress, "Распаковано записей: $entries")
                }
            }
        }

        if (entries == 0) {
            throw IOException("ZIP-архив пуст или повреждён")
        }
    }

    private fun findResourceRoot(staging: File): File {
        if (File(staging, "catalog/items.json").isFile) {
            return staging
        }

        val children = staging.listFiles()?.filter {
            it.name != "__MACOSX"
        } ?: emptyList()

        if (children.size == 1 && children[0].isDirectory) {
            val nested = children[0]
            if (File(nested, "catalog/items.json").isFile) {
                return nested
            }
        }

        return staging
    }

    private fun copyContents(source: File, destination: File) {
        val children = source.listFiles()
            ?: throw IOException("Не удалось прочитать файлы ресурсов")

        for (child in children) {
            if (child.name == STAGING_NAME) continue

            val target = File(destination, child.name)

            if (child.isDirectory) {
                if (target.exists() && !target.isDirectory) {
                    if (!target.delete()) {
                        throw IOException(
                            "Не удалось заменить ${target.path}"
                        )
                    }
                }

                if (!target.exists() && !target.mkdirs()) {
                    throw IOException(
                        "Не удалось создать ${target.path}"
                    )
                }

                copyContents(child, target)
            } else {
                val parent = target.parentFile
                    ?: throw IOException("Некорректный путь ${target.path}")

                if (!parent.exists() && !parent.mkdirs()) {
                    throw IOException(
                        "Не удалось создать ${parent.path}"
                    )
                }

                child.copyTo(target, overwrite = true)
            }
        }
    }

    private fun report(
        callback: (String) -> Unit,
        message: String
    ) {
        mainHandler.post {
            callback(message)
        }
    }
}
