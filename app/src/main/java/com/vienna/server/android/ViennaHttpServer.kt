package com.vienna.server.android

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import kotlin.concurrent.thread

private const val VERSION = "0.0.5-android"
private const val DEFAULT_RESOURCE_PACK_PATH =
    "/availableresourcepack/resourcepacks/dba38e59-091a-4826-b76a-a08d7de5a9e2-1301b0c257a311678123b9e7325d0d6c61db3c35"

class ViennaHttpServer(
    context: Context,
    private val port: Int,
    private val logger: (String) -> Unit
) {
    val paths = ViennaPaths(context)
    private val db = EarthDb(context, paths.dbFile)
    private val workers: ExecutorService = Executors.newCachedThreadPool()
    private var socket: ServerSocket? = null
    @Volatile var isRunning: Boolean = false
        private set

    fun start() {
        socket = ServerSocket(port)
        isRunning = true
        thread(name = "vienna-http-server", isDaemon = true) {
            while (isRunning) {
                try {
                    val client = socket?.accept() ?: break
                    workers.execute { handleClient(client) }
                } catch (error: Exception) {
                    if (isRunning) logger("Accept error: ${error.message}")
                }
            }
        }
    }

    fun stop() {
        isRunning = false
        socket?.close()
        socket = null
        workers.shutdownNow()
    }

    private fun handleClient(client: Socket) {
        client.use {
            val input = BufferedInputStream(it.getInputStream())
            val output = BufferedOutputStream(it.getOutputStream())
            val request = readRequest(input) ?: return
            val response = try {
                route(request)
            } catch (error: Exception) {
                logger("${request.method} ${request.path}: ${error.message}")
                HttpResponse.json(500, JSONObject().put("error", error.message ?: "internal error"))
            }
            output.write(response.toBytes(request.method == "HEAD"))
            output.flush()
        }
    }

    private fun route(request: HttpRequest): HttpResponse {
        val parts = request.path.trim('/').split('/').filter { it.isNotBlank() }
        val staticData = StaticData.load(paths.staticDataDir)

        if (request.path == DEFAULT_RESOURCE_PACK_PATH && (request.method == "GET" || request.method == "HEAD")) {
            return resourcePack()
        }

        return when {
            request.method == "GET" && request.path == "/" ->
                HttpResponse.text(200, "vienna apiserver $VERSION with 0 VMA mod(s)")

            request.method == "GET" && request.path == "/version" ->
                HttpResponse.json(200, JSONObject().put("version", VERSION))

            request.method == "GET" && request.path == "/health" -> health(staticData)

            request.method == "GET" && request.path == "/auth/config" ->
                HttpResponse.json(200, JSONObject().put("customLoginOnly", false).put("microsoftAccountVerification", true))

            request.method == "GET" && request.path == "/mods" ->
                HttpResponse.jsonArray(200, JSONArray())

            request.method == "GET" && request.path == "/static/summary" ->
                staticData?.let { HttpResponse.json(200, it.summary()) } ?: unavailable()

            request.method == "GET" && request.path == "/shop/catalog" ->
                staticData?.let { HttpResponse.json(200, it.shopCatalog()) } ?: unavailable()

            request.method == "GET" && parts.size == 3 && parts[0] == "levels" && parts[2] == "rewards" ->
                levelRewards(staticData, parts[1].toIntOrNull())

            request.method == "GET" && parts.size == 3 && parts[0] == "players" && parts[2] == "items" ->
                getItems(parts[1])

            request.method == "POST" && parts.size == 3 && parts[0] == "players" && parts[2] == "items" ->
                addItem(parts[1], request.jsonBody())

            request.method == "GET" && parts.size == 3 && parts[0] == "players" && parts[2] == "roles" ->
                getRoles(parts[1])

            request.method == "PUT" && parts.size == 3 && parts[0] == "players" && parts[2] == "roles" ->
                setRoles(parts[1], request.jsonBody())

            request.method == "GET" && request.path == "/buildplates" ->
                listBuildplates()

            request.method == "GET" && parts.size == 3 && parts[0] == "players" && parts[2] == "buildplates" ->
                getBuildplates(parts[1])

            request.method == "POST" && parts.size == 3 && parts[0] == "players" && parts[2] == "buildplates" ->
                grantBuildplate(parts[1], request.jsonBody())

            request.method == "POST" && request.path == "/data/import" ->
                importData(request.jsonBody())

            request.method == "POST" && request.path == "/tappables/generate" ->
                generateTappables(staticData, request.jsonBody())

            request.method == "GET" && request.path == "/logs" ->
                listLogs()

            request.method == "DELETE" && request.path == "/logs" ->
                clearLogs()

            request.method == "GET" && parts.size == 2 && parts[0] == "logs" ->
                readLog(parts[1])

            request.method == "GET" && request.path == "/player/environment" ->
                locator(request)

            request.method == "GET" && request.path == "/api/v1.1/player/environment" ->
                locator(request)

            request.method == "GET" && parts.size >= 3 && parts[0] == "debug" && parts[1] == "hooks" ->
                HttpResponse.json(200, JSONObject().put("status", "ok").put("hook", parts[2]))

            else -> HttpResponse.json(404, JSONObject().put("error", "not found"))
        }
    }

    private fun health(staticData: StaticData?): HttpResponse {
        return HttpResponse.json(
            200,
            JSONObject()
                .put("status", "ok")
                .put("version", VERSION)
                .put("db", paths.dbFile.absolutePath)
                .put("staticData", paths.staticDataDir.absolutePath)
                .put("staticDataLoaded", staticData != null)
                .put("tappablesLoaded", staticData != null)
                .put("modsDir", paths.modsDir.absolutePath)
                .put("buildplatesDir", paths.buildplatesDir.absolutePath)
                .put("loadedMods", 0)
                .put("customLoginOnly", false)
                .put("maxTileCacheSize", 2048)
        )
    }

    private fun levelRewards(staticData: StaticData?, level: Int?): HttpResponse {
        if (staticData == null) return unavailable()
        val reward = level?.let { staticData.levelReward(it) } ?: return HttpResponse.json(404, JSONObject().put("error", "not found"))
        return HttpResponse.json(200, JSONObject().put("level", level).put("reward", reward))
    }

    private fun getItems(playerId: String): HttpResponse {
        val record = db.get("player_items", playerId, JSONObject().put("items", JSONObject()))
        return HttpResponse.json(
            200,
            JSONObject()
                .put("playerId", playerId)
                .put("version", record.version)
                .put("items", record.value.optJSONObject("items") ?: JSONObject())
        )
    }

    private fun addItem(playerId: String, body: JSONObject): HttpResponse {
        val record = db.get("player_items", playerId, JSONObject().put("items", JSONObject()))
        val items = record.value.optJSONObject("items") ?: JSONObject()
        val itemId = body.getString("item_id")
        val updated = (items.optInt(itemId, 0) + body.optInt("count", 0)).coerceAtLeast(0)
        if (updated == 0) items.remove(itemId) else items.put(itemId, updated)
        val value = JSONObject().put("items", items)
        val version = db.update("player_items", playerId, value)
        return HttpResponse.json(200, JSONObject().put("playerId", playerId).put("version", version).put("items", items))
    }

    private fun getRoles(playerId: String): HttpResponse {
        val record = db.get("player_roles", playerId, JSONObject().put("roles", JSONArray()))
        return HttpResponse.json(200, JSONObject().put("playerId", playerId).put("version", record.version).put("roles", record.value.optJSONArray("roles") ?: JSONArray()))
    }

    private fun setRoles(playerId: String, body: JSONObject): HttpResponse {
        val roles = body.optJSONArray("roles") ?: JSONArray()
        val sorted = (0 until roles.length()).map { roles.getString(it) }.distinct().sorted()
        val value = JSONObject().put("roles", JSONArray(sorted))
        val version = db.update("player_roles", playerId, value)
        return HttpResponse.json(200, JSONObject().put("playerId", playerId).put("version", version).put("roles", value.getJSONArray("roles")))
    }

    private fun listBuildplates(): HttpResponse {
        val buildplates = JSONArray()
        paths.buildplatesDir.listFiles { file -> file.isDirectory }
            ?.sortedBy { it.name }
            ?.forEach {
                buildplates.put(JSONObject().put("id", it.name).put("name", it.name).put("path", it.absolutePath))
            }
        return HttpResponse.json(200, JSONObject().put("buildplates", buildplates))
    }

    private fun getBuildplates(playerId: String): HttpResponse {
        val record = db.get("player_buildplates", playerId, JSONObject().put("buildplates", JSONObject()))
        return HttpResponse.json(200, JSONObject().put("playerId", playerId).put("version", record.version).put("buildplates", record.value.optJSONObject("buildplates") ?: JSONObject()))
    }

    private fun grantBuildplate(playerId: String, body: JSONObject): HttpResponse {
        val id = body.getString("buildplate_id").trim()
        if (id.isEmpty() || id.contains("/") || id.contains("\\") || id.contains("..")) {
            return HttpResponse.json(400, JSONObject().put("error", "invalid buildplate id"))
        }
        val folder = File(paths.buildplatesDir, id)
        if (!folder.isDirectory) return HttpResponse.json(404, JSONObject().put("error", "buildplate not found"))
        val record = db.get("player_buildplates", playerId, JSONObject().put("buildplates", JSONObject()))
        val buildplates = record.value.optJSONObject("buildplates") ?: JSONObject()
        val granted = JSONObject()
            .put("id", id)
            .put("name", body.optString("name", id))
            .put("folder", id)
            .put("granted_at_ms", System.currentTimeMillis())
        buildplates.put(id, granted)
        val version = db.update("player_buildplates", playerId, JSONObject().put("buildplates", buildplates))
        return HttpResponse.json(200, JSONObject().put("playerId", playerId).put("version", version).put("granted", granted))
    }

    private fun importData(body: JSONObject): HttpResponse {
        val merge = body.optBoolean("merge", false)
        val records = body.optJSONArray("records") ?: JSONArray()
        val skipped = JSONArray()
        val warnings = JSONArray()
        var imported = 0
        for (index in 0 until records.length()) {
            val record = records.getJSONObject(index)
            val type = record.optString("type")
            val id = record.optString("id")
            if (type.isBlank() || id.isBlank()) {
                warnings.put(JSONObject().put("type", type).put("id", id).put("warning", "empty type or id"))
                continue
            }
            val existing = db.get(type, id)
            if (existing.version > 1 && !merge) {
                skipped.put(JSONObject().put("type", type).put("id", id).put("version", existing.version).put("reason", "already exists"))
                continue
            }
            if (existing.version > 1) {
                warnings.put(JSONObject().put("type", type).put("id", id).put("warning", "merged over existing value").put("previousVersion", existing.version))
            }
            db.update(type, id, record.getJSONObject("value"))
            imported++
        }
        return HttpResponse.json(200, JSONObject().put("imported", imported).put("skipped", skipped).put("warnings", warnings))
    }

    private fun generateTappables(staticData: StaticData?, body: JSONObject): HttpResponse {
        if (staticData == null) return unavailable()
        val result = TappablesGenerator(staticData, body.optLong("seed", 0))
            .generate(
                body.getString("player_id"),
                body.getDouble("lat"),
                body.getDouble("lon"),
                body.optLong("now", System.currentTimeMillis()),
                body.optDouble("radius", 5.0)
            )
        return HttpResponse.json(200, result)
    }

    private fun listLogs(): HttpResponse {
        val logs = JSONArray()
        paths.logsDir.listFiles { file -> file.isFile }
            ?.sortedByDescending { it.lastModified() }
            ?.forEach { logs.put(JSONObject().put("name", it.name).put("bytes", it.length()).put("modified_ms", it.lastModified())) }
        return HttpResponse.json(200, JSONObject().put("logs", logs))
    }

    private fun readLog(name: String): HttpResponse {
        if (name.contains("/") || name.contains("\\") || name.contains("..")) return HttpResponse.json(400, JSONObject().put("error", "bad file name"))
        val file = File(paths.logsDir, name)
        if (!file.isFile) return HttpResponse.json(404, JSONObject().put("error", "not found"))
        val text = file.readText().takeLast(64 * 1024)
        return HttpResponse.text(200, text)
    }

    private fun clearLogs(): HttpResponse {
        paths.logsDir.listFiles { file -> file.isFile }?.forEach { it.delete() }
        return HttpResponse.empty(204)
    }

    private fun locator(request: HttpRequest): HttpResponse {
        val host = request.headers["host"] ?: "127.0.0.1:$port"
        val base = "http://$host"
        val serviceEnvironment = JSONObject()
            .put("serviceUri", base)
            .put("cdnUri", base)
            .put("playfabTitleId", "20CA2")
        val serviceEnvironments = JSONObject().put("production", serviceEnvironment)
        val supportedEnvironments = JSONObject().put("2020.1217.02", JSONArray(listOf("production")))
        return HttpResponse.json(
            200,
            JSONObject()
                .put("result", JSONObject().put("serviceEnvironments", serviceEnvironments).put("supportedEnvironments", supportedEnvironments))
                .put("updates", JSONObject())
        )
    }

    private fun resourcePack(): HttpResponse {
        if (!paths.resourcePackFile.isFile) return HttpResponse.json(404, JSONObject().put("error", "resource pack not found"))
        return HttpResponse.bytes(200, paths.resourcePackFile.readBytes(), "application/zip")
    }

    private fun unavailable(): HttpResponse = HttpResponse.json(503, JSONObject().put("error", "static data is not available"))
}

private data class HttpRequest(
    val method: String,
    val path: String,
    val headers: Map<String, String>,
    val body: String
) {
    fun jsonBody(): JSONObject = if (body.isBlank()) JSONObject() else JSONObject(body)
}

private class HttpResponse(
    private val status: Int,
    private val body: ByteArray,
    private val contentType: String
) {
    fun toBytes(headOnly: Boolean): ByteArray {
        val reason = when (status) {
            200 -> "OK"
            204 -> "No Content"
            400 -> "Bad Request"
            404 -> "Not Found"
            500 -> "Internal Server Error"
            503 -> "Service Unavailable"
            else -> "OK"
        }
        val header = buildString {
            append("HTTP/1.1 $status $reason\r\n")
            append("Content-Type: $contentType\r\n")
            append("Content-Length: ${body.size}\r\n")
            append("Connection: close\r\n")
            append("Access-Control-Allow-Origin: *\r\n")
            append("\r\n")
        }.toByteArray(StandardCharsets.UTF_8)
        return if (headOnly) header else header + body
    }

    companion object {
        fun json(status: Int, value: JSONObject) = HttpResponse(status, value.toString().toByteArray(StandardCharsets.UTF_8), "application/json")
        fun jsonArray(status: Int, value: JSONArray) = HttpResponse(status, value.toString().toByteArray(StandardCharsets.UTF_8), "application/json")
        fun text(status: Int, value: String) = HttpResponse(status, value.toByteArray(StandardCharsets.UTF_8), "text/plain; charset=utf-8")
        fun bytes(status: Int, value: ByteArray, contentType: String) = HttpResponse(status, value, contentType)
        fun empty(status: Int) = HttpResponse(status, ByteArray(0), "text/plain")
    }
}

private fun readRequest(input: BufferedInputStream): HttpRequest? {
    val headerBytes = mutableListOf<Byte>()
    var previous = 0
    var matched = 0
    while (true) {
        val current = input.read()
        if (current < 0) return null
        headerBytes += current.toByte()
        matched = when {
            matched == 0 && current == '\r'.code -> 1
            matched == 1 && current == '\n'.code -> 2
            matched == 2 && current == '\r'.code -> 3
            matched == 3 && current == '\n'.code -> 4
            current == '\r'.code && previous != '\r'.code -> 1
            else -> 0
        }
        previous = current
        if (matched == 4) break
        if (headerBytes.size > 64 * 1024) return null
    }
    val headerText = headerBytes.toByteArray().toString(StandardCharsets.UTF_8)
    val lines = headerText.split("\r\n").filter { it.isNotBlank() }
    val requestLine = lines.firstOrNull()?.split(" ") ?: return null
    val headers = lines.drop(1).mapNotNull {
        val index = it.indexOf(':')
        if (index <= 0) null else it.substring(0, index).lowercase(Locale.US) to it.substring(index + 1).trim()
    }.toMap()
    val length = headers["content-length"]?.toIntOrNull() ?: 0
    val bodyBytes = ByteArray(length)
    var read = 0
    while (read < length) {
        val count = input.read(bodyBytes, read, length - read)
        if (count < 0) break
        read += count
    }
    val rawPath = requestLine.getOrElse(1) { "/" }.substringBefore('?')
    return HttpRequest(
        method = requestLine[0].uppercase(Locale.US),
        path = URLDecoder.decode(rawPath, "UTF-8"),
        headers = headers,
        body = bodyBytes.toString(StandardCharsets.UTF_8)
    )
}
