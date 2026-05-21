package com.vienna.server.android

import org.json.JSONArray
import org.json.JSONObject
import java.util.Random
import java.util.UUID
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.sinh
import kotlin.math.tan

private const val ACTIVE_TILE_RADIUS = 3
private const val MIN_TAPPABLE_COUNT = 1
private const val MAX_TAPPABLE_COUNT = 3
private const val MIN_TAPPABLE_DURATION_MS = 2L * 60L * 1000L
private const val MAX_TAPPABLE_DURATION_MS = 5L * 60L * 1000L
private const val MIN_TAPPABLE_DELAY_MS = 1L * 60L * 1000L
private const val MAX_TAPPABLE_DELAY_MS = 2L * 60L * 1000L
private const val MIN_ENCOUNTER_DELAY_MS = 1L * 60L * 1000L
private const val MAX_ENCOUNTER_DELAY_MS = 2L * 60L * 1000L
private const val ENCOUNTER_CHANCE_PER_TILE = 4
private const val TILE_SCALE = 65536.0

data class ActiveTile(val tileX: Int, val tileY: Int, val firstActiveTime: Long, val latestActiveTime: Long)

class TappablesGenerator(private val staticData: StaticData, seed: Long) {
    private val random = Random(seed)

    fun generate(playerId: String, lat: Double, lon: Double, now: Long, radius: Double): JSONObject {
        val centerX = xToTile(lonToX(lon))
        val centerY = yToTile(latToY(lat))
        val activeTiles = mutableListOf<ActiveTile>()
        for (x in (centerX - ACTIVE_TILE_RADIUS)..(centerX + ACTIVE_TILE_RADIUS)) {
            for (y in (centerY - ACTIVE_TILE_RADIUS)..(centerY + ACTIVE_TILE_RADIUS)) {
                activeTiles += ActiveTile(x, y, now, now)
            }
        }

        val tappables = JSONArray()
        val encounters = JSONArray()
        for (tile in activeTiles) {
            repeat(randomInt(MIN_TAPPABLE_COUNT, MAX_TAPPABLE_COUNT)) {
                createTappable(tile.tileX, tile.tileY, now)?.let { tappables.put(it) }
            }
            if (random.nextInt(ENCOUNTER_CHANCE_PER_TILE) == 0) {
                createEncounter(tile.tileX, tile.tileY, now)?.let { encounters.put(it) }
            }
        }

        return JSONObject()
            .put("tappables", filterAround(tappables, lat, lon, radius))
            .put("encounters", filterAround(encounters, lat, lon, radius))
            .put("activeTiles", JSONArray(activeTiles.map { it.toJson() }))
            .put("inactiveTiles", JSONArray())
            .put("playerId", playerId)
    }

    private fun createTappable(tileX: Int, tileY: Int, now: Long): JSONObject? {
        if (staticData.tappables.isEmpty()) return null
        val config = staticData.tappables[random.nextInt(staticData.tappables.size)]
        val dropSet = chooseDropSet(config.optJSONArray("dropSets") ?: JSONArray())
        val itemCounts = config.optJSONObject("itemCounts") ?: JSONObject()
        val items = JSONArray()
        var rarity = "COMMON"
        for (index in 0 until dropSet.optJSONArray("items")!!.length()) {
            val itemId = dropSet.getJSONArray("items").getString(index)
            val countConfig = itemCounts.optJSONObject(itemId) ?: JSONObject().put("min", 1).put("max", 1)
            items.put(
                JSONObject()
                    .put("id", itemId)
                    .put("count", randomInt(countConfig.optInt("min", 1), countConfig.optInt("max", 1)))
            )
            rarity = maxRarity(rarity, staticData.rarityForItem(itemId))
        }
        val location = randomTileLocation(tileX, tileY)
        return JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("lat", location.first)
            .put("lon", location.second)
            .put("spawn_time", now + randomLong(MIN_TAPPABLE_DELAY_MS, MAX_TAPPABLE_DELAY_MS))
            .put("valid_for", randomLong(MIN_TAPPABLE_DURATION_MS, MAX_TAPPABLE_DURATION_MS))
            .put("icon", config.optString("icon"))
            .put("rarity", rarity)
            .put("items", items)
    }

    private fun createEncounter(tileX: Int, tileY: Int, now: Long): JSONObject? {
        if (staticData.encounters.isEmpty()) return null
        val config = staticData.encounters[random.nextInt(staticData.encounters.size)]
        val location = randomTileLocation(tileX, tileY)
        return JSONObject()
            .put("id", UUID.randomUUID().toString())
            .put("lat", location.first)
            .put("lon", location.second)
            .put("spawn_time", now + randomLong(MIN_ENCOUNTER_DELAY_MS, MAX_ENCOUNTER_DELAY_MS))
            .put("valid_for", config.optLong("duration", 60) * 1000L)
            .put("icon", config.optString("icon"))
            .put("rarity", config.optString("rarity", "COMMON"))
            .put("encounter_buildplate_id", config.optString("encounterBuildplateId"))
    }

    private fun chooseDropSet(dropSets: JSONArray): JSONObject {
        if (dropSets.length() == 0) return JSONObject().put("items", JSONArray()).put("chance", 1)
        var total = 0
        for (index in 0 until dropSets.length()) total += dropSets.getJSONObject(index).optInt("chance", 0)
        var roll = random.nextInt(total.coerceAtLeast(1))
        for (index in 0 until dropSets.length()) {
            val candidate = dropSets.getJSONObject(index)
            if (roll < candidate.optInt("chance", 0)) return candidate
            roll -= candidate.optInt("chance", 0)
        }
        return dropSets.getJSONObject(0)
    }

    private fun filterAround(items: JSONArray, lat: Double, lon: Double, radius: Double): JSONArray {
        val filtered = JSONArray()
        for (index in 0 until items.length()) {
            val item = items.getJSONObject(index)
            if (withinRadius(item.getDouble("lat"), item.getDouble("lon"), lat, lon, radius)) {
                filtered.put(item)
            }
        }
        return filtered
    }

    private fun randomInt(min: Int, max: Int): Int = min + random.nextInt((max - min + 1).coerceAtLeast(1))
    private fun randomLong(min: Long, max: Long): Long = min + (Math.abs(random.nextLong()) % (max - min + 1).coerceAtLeast(1))

    private fun randomTileLocation(tileX: Int, tileY: Int): Pair<Double, Double> {
        val bounds = tileBounds(tileX, tileY)
        return Pair(
            bounds.second + random.nextDouble() * (bounds.first - bounds.second),
            bounds.third + random.nextDouble() * (bounds.fourth - bounds.third)
        )
    }
}

private fun ActiveTile.toJson(): JSONObject = JSONObject()
    .put("tile_x", tileX)
    .put("tile_y", tileY)
    .put("first_active_time", firstActiveTime)
    .put("latest_active_time", latestActiveTime)

private fun maxRarity(left: String, right: String): String {
    val order = listOf("COMMON", "UNCOMMON", "RARE", "EPIC", "LEGENDARY", "OOBE")
    return if (order.indexOf(right).coerceAtLeast(0) > order.indexOf(left).coerceAtLeast(0)) right else left
}

private fun tileBounds(tileX: Int, tileY: Int): Bounds = Bounds(
    yToLat(tileY.toDouble() / TILE_SCALE),
    yToLat((tileY + 1).toDouble() / TILE_SCALE),
    xToLon(tileX.toDouble() / TILE_SCALE),
    xToLon((tileX + 1).toDouble() / TILE_SCALE)
)

private data class Bounds(val first: Double, val second: Double, val third: Double, val fourth: Double)

private fun xToLon(x: Double): Double = Math.toDegrees((x * 2.0 - 1.0) * PI)
private fun yToLat(y: Double): Double = Math.toDegrees(atan(sinh((1.0 - y * 2.0) * PI)))
private fun lonToX(lon: Double): Double = (1.0 + Math.toRadians(lon) / PI) / 2.0
private fun latToY(lat: Double): Double {
    val radians = Math.toRadians(lat)
    return (1.0 - ln(tan(radians) + 1.0 / cos(radians)) / PI) / 2.0
}
private fun xToTile(x: Double): Int = floor(x * TILE_SCALE).toInt()
private fun yToTile(y: Double): Int = floor(y * TILE_SCALE).toInt()
private fun withinRadius(targetLat: Double, targetLon: Double, lat: Double, lon: Double, radius: Double): Boolean {
    val dx = lonToX(targetLon) * TILE_SCALE - lonToX(lon) * TILE_SCALE
    val dy = latToY(targetLat) * TILE_SCALE - latToY(lat) * TILE_SCALE
    return dx * dx + dy * dy <= radius * radius
}
