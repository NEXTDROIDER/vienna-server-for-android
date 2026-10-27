
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

object ViennaResourceInstaller {

    private const val RESOURCE_URL =
        "https://web.archive.org/web/2id_/https://cdn.mceserv.net/availableresourcepack/resourcepacks/dba38e59-091a-4826-b76a-a08d7de5a9e2-1301b0c257a311678123b9e7325d0d6c61db3c35"

    private const val MAX_DOWNLOAD_BYTES = 512L * 1024 * 1024

    private const val RESOURCE_FILE =
        "dba38e59-091a-4826-b76a-a08d7de5a9e2-1301b0c257a311678123b9e7325d0d6c61db3c35"

    private val executor = Executors.newSingleThreadExecutor()
    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile
    private var installing = false

    fun isInstalling(): Boolean = installing

    fun isInstalled(destination: File): Boolean {
        return File(destination, RESOURCE_FILE).let {
            it.isFile && it.length() > 0
        }
    }

    /**
     * destination — папка resourcepacks.
     */
    fun install(
        destination: File,
        force: Boolean = false,
        onProgress: (String) -> Unit,
        onComplete: (Result<Unit>) -> Unit
    ) {
        synchronized(this) {
            if (installing) {
                mainHandler.post {
                    onProgress("Загрузка уже выполняется.")
                }
                return
            }
            installing = true
        }

        executor.execute {
            val result = runCatching {
                if (!force && isInstalled(destination)) {
                    report(onProgress, "Ресурс-пак уже загружен.")
                    return@runCatching
                }

                if (!destination.exists() && !destination.mkdirs()) {
                    throw IOException(
                        "Не удалось создать папку: ${destination.absolutePath}"
                    )
                }

                if (!destination.isDirectory || !destination.canWrite()) {
                    throw IOException(
                        "Нет доступа к папке: ${destination.absolutePath}"
                    )
                }

                val target = File(destination, RESOURCE_FILE)
                val temporary = File(destination, "$RESOURCE_FILE.part")

                try {
                    report(onProgress, "Подключение к архиву...")
                    download(temporary, onProgress)

                    if (target.exists() && !target.delete()) {
                        throw IOException("Не удалось заменить старый файл")
                    }

                    if (!temporary.renameTo(target)) {
                        throw IOException(
                            "Не удалось сохранить ресурс-пак"
                        )
                    }

                    report(
                        onProgress,
                        "Ресурс-пак сохранён: ${target.absolutePath}"
                    )
                } finally {
                    temporary.delete()
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
                connectTimeout = 20_000
                readTimeout = 60_000
                instanceFollowRedirects = true
                requestMethod = "GET"
                setRequestProperty(
                    "User-Agent",
                    "ViennaResourceInstaller/1.0"
                )
                setRequestProperty("Accept-Encoding", "identity")
                connect()
            }

            val status = connection.responseCode

            if (status !in 200..299) {
                throw IOException("Ошибка HTTP: $status")
            }

            if (!connection.url.protocol.equals(
                    "https",
                    ignoreCase = true
                )
            ) {
                throw IOException("Загрузка разрешена только через HTTPS")
            }

            val contentLength = connection.contentLengthLong

            if (contentLength > MAX_DOWNLOAD_BYTES) {
                throw IOException("Файл превышает лимит 512 МБ")
            }

            var total = 0L

            BufferedInputStream(connection.inputStream).use { input ->
                BufferedOutputStream(
                    FileOutputStream(outputFile)
                ).use { output ->
                    val buffer = ByteArray(64 * 1024)

                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break

                        total += count

                        if (total > MAX_DOWNLOAD_BYTES) {
                            throw IOException(
                                "Файл превышает лимит 512 МБ"
                            )
                        }

                        output.write(buffer, 0, count)

                        val message = if (contentLength > 0) {
                            val percent =
                                (total * 100 / contentLength)
                                    .toInt()
                                    .coerceIn(0, 100)

                            "Скачивание: $percent% " +
                                "(${total / 1024} КБ)"
                        } else {
                            "Скачано: ${total / 1024} КБ"
                        }

                        report(onProgress, message)
                    }
                }
            }

            if (total == 0L) {
                throw IOException("Получен пустой файл")
            }

            // Проверяем, что сервер не вернул HTML-страницу ошибки.
            outputFile.inputStream().use { input ->
                val first = input.read()
                val second = input.read()

                if (
                    first == '<'.code ||
                    (first == 0x50 && second == 0x4B)
                ) {
                    if (first == '<'.code) {
                        throw IOException(
                            "Вместо файла получена HTML-страница. " +
                                "Возможно, архив не содержит этот ресурс."
                        )
                    }
                }
            }

        } catch (e: Exception) {
            outputFile.delete()
            throw e
        } finally {
            connection?.disconnect()
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
