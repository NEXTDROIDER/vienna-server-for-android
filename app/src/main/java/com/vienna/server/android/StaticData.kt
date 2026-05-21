package com.vienna.server.android

import org.json.JSONArray
import org.json.JSONObject
import java.io.File

class StaticData private constructor(
    val items: List<JSONObject>,
    val levels: Map<Int, JSONObject>,
    val tappables: List<JSONObject>,
    val encounters: List<JSONObject>
) {
    companion object {
        fun load(root: File): StaticData? {
            val catalog = File(root, "catalog/items.json")
            val levelsDir = File(root, "levels")
            val tappablesDir = File(root, "tappables")
            val encountersDir = File(root, "encounters")
            if (!catalog.isFile || !levelsDir.isDirectory || !tappablesDir.isDirectory || !encountersDir.isDirectory) {
                return null
            }

            val levels = linkedMapOf<Int, JSONObject>()
            var level = 2
            while (true) {
                val file = File(levelsDir, "$level.json")
                if (!file.isFile) break
                levels[level] = JSONObject(file.readText())
                level++
            }

            return StaticData(
                items = JSONArray(catalog.readText()).toObjectList(),
                levels = levels,
                tappables = tappablesDir.jsonFiles().flatMap { JSONArray("[${it.readText()}]").toObjectList() },
                encounters = encountersDir.jsonFiles().flatMap { JSONArray("[${it.readText()}]").toObjectList() }
            )
        }
    }

    fun summary(): JSONObject = JSONObject()
        .put("items", items.size)
        .put("levels", levels.size)
        .put("tappables", tappables.size)
        .put("encounters", encounters.size)

    fun shopCatalog(): JSONObject {
        val sorted = items.sortedBy { it.optString("id") }
        return JSONObject().put("items", JSONArray(sorted))
    }

    fun levelReward(level: Int): JSONObject? = levels[level]

    fun rarityForItem(itemId: String): String {
        return items.firstOrNull { it.optString("id") == itemId }?.optString("rarity", "COMMON") ?: "COMMON"
    }
}

private fun File.jsonFiles(): List<File> {
    return listFiles { file -> file.isFile && file.extension.equals("json", ignoreCase = true) }
        ?.sortedBy { it.name }
        ?: emptyList()
}

private fun JSONArray.toObjectList(): List<JSONObject> {
    val out = mutableListOf<JSONObject>()
    for (index in 0 until length()) {
        out += getJSONObject(index)
    }
    return out
}
