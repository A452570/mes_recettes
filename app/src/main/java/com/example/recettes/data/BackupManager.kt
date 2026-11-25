package com.example.recettes.data

import org.json.JSONArray
import org.json.JSONObject

/** Sérialisation des recettes en JSON lisible, pour la sauvegarde et le partage. */
object BackupManager {

    private const val FORMAT_VERSION = 1

    class BackupException(message: String) : Exception(message)

    fun toJson(recipes: List<Recipe>): String {
        val array = JSONArray()
        recipes.forEach { r ->
            array.put(
                JSONObject().apply {
                    put("title", r.title)
                    put("ingredients", r.ingredients)
                    put("instructions", r.instructions)
                    put("sourceUrl", r.sourceUrl)
                    put("totalTime", r.totalTime)
                    put("servings", r.servings)
                    put("createdAt", r.createdAt)
                }
            )
        }
        return JSONObject().apply {
            put("app", "recettes")
            put("version", FORMAT_VERSION)
            put("exportedAt", System.currentTimeMillis())
            put("recipes", array)
        }.toString(2)
    }

    /** Les recettes retournées ont toujours id = 0 : Room leur attribuera de nouveaux identifiants. */
    fun fromJson(text: String): List<Recipe> {
        val root = try {
            JSONObject(text.trim().removePrefix("﻿"))
        } catch (e: Exception) {
            throw BackupException("Ce fichier n'est pas une sauvegarde de recettes valide.")
        }
        if (root.optInt("version", 1) > FORMAT_VERSION) {
            throw BackupException("Cette sauvegarde vient d'une version plus récente de l'application.")
        }
        val array = root.optJSONArray("recipes")
            ?: throw BackupException("Ce fichier ne contient pas de recettes.")

        val result = mutableListOf<Recipe>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val title = o.optString("title").trim()
            if (title.isEmpty()) continue
            result += Recipe(
                title = title,
                ingredients = o.optString("ingredients"),
                instructions = o.optString("instructions"),
                sourceUrl = o.optString("sourceUrl"),
                totalTime = o.optString("totalTime"),
                servings = o.optString("servings"),
                createdAt = o.optLong("createdAt", System.currentTimeMillis())
            )
        }
        return result
    }
}
