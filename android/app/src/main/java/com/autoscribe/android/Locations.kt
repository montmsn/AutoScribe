package com.autoscribe.android

import android.content.Context
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

object Locations {
    private const val FILE_NAME = "locations.json"
    private const val URL_STATUS = "https://api.windscribe.com/WebsiteStatus"

    fun load(context: Context): List<Pop> {
        val downloaded = File(context.filesDir, FILE_NAME)
        val text = if (downloaded.exists()) {
            downloaded.readText()
        } else {
            context.assets.open(FILE_NAME).bufferedReader().use { it.readText() }
        }
        return parse(text)
    }

    fun parse(text: String): List<Pop> {
        val result = mutableListOf<Pop>()
        val locations = JSONObject(text).optJSONObject("data")?.optJSONObject("locations") ?: return result
        for (id in locations.keys()) {
            val entry = locations.optJSONObject(id) ?: continue
            val country = entry.optString("name", "Unknown").trim()
            val code = entry.optString("country_code", "").trim()
            val continent = entry.optString("continent", "Unknown").trim()
            val countryPremium = entry.optInt("premium_only", 0) == 1
            val pops = entry.optJSONArray("pops") ?: continue
            for (i in 0 until pops.length()) {
                val pop = pops.optJSONObject(i) ?: continue
                if (pop.optInt("status", 1) != 1) continue // offline/disabled nodes
                val name = pop.optString("name", "").trim()
                val premium = countryPremium || pop.optInt("is_premium", 0) == 1
                if (name.isNotEmpty()) result += Pop(country, code, continent, name, premium)
            }
        }
        return result.sortedWith(compareBy({ it.country }, { it.name }))
    }

    /** Downloads the current list from windscribe.com; the old file stays if anything fails. */
    fun download(context: Context) {
        val conn = URL(URL_STATUS).openConnection() as HttpURLConnection
        conn.connectTimeout = 20_000
        conn.readTimeout = 20_000
        conn.setRequestProperty("Accept", "application/json")
        // The same placeholder token windscribe.com's own server list page sends.
        conn.setRequestProperty("Authorization", "Bearer 1234")
        val text = try {
            conn.inputStream.bufferedReader().use { it.readText() }
        } finally {
            conn.disconnect()
        }
        require(parse(text).isNotEmpty()) { "the server list has no servers in it" }
        val tmp = File(context.filesDir, "$FILE_NAME.tmp")
        tmp.writeText(text)
        tmp.renameTo(File(context.filesDir, FILE_NAME))
    }
}
