package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * TapRadioTool — Gemini-callable tool for searching, playing, and managing
 * internet radio stations and podcasts via TapRadio.
 *
 * Actions:
 *   play   — play a station by name (fuzzy match saved list) or direct URL
 *   search — query Radio Browser API (30k+ public stations)
 *   list   — return saved station names
 *   stop   — stop current playback
 *   add    — add a station to saved list
 */
class TapRadioTool(private val context: Context) : AiTapTool {
    override val name = "tapradio"

    companion object {
        private const val TAG = "TapRadioTool"
        private const val RADIO_PREFS_KEY = "tapradio_stations"
        private val RADIO_BROWSER_SERVERS = listOf(
            "https://de1.api.radio-browser.info",
            "https://nl1.api.radio-browser.info",
            "https://at1.api.radio-browser.info"
        )
    }

    private val prefs by lazy {
        context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"]?.trim()?.lowercase() ?: "list"
        val query = args["query"]?.trim() ?: ""

        Log.d(TAG, "action=$action query=$query")

        return when (action) {
            "play" -> playStation(query)
            "search" -> searchStations(query)
            "list" -> listStations()
            "stop" -> stopPlayback()
            "add" -> addStation(query, args["name"] ?: "", args["genre"] ?: "")
            else -> Result.success("Unknown TapRadio action: $action. Use play, search, list, stop, or add.")
        }
    }

    private suspend fun playStation(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please specify a station name or URL to play.")

        // If it looks like a URL, open in browser directly
        if (query.startsWith("http://") || query.startsWith("https://")) {
            clearNowPlaying()
            return Result.success("open_taplink:$query\nOpening stream in browser: $query")
        }

        // Fuzzy match against saved stations
        val stations = getSavedStations()
        val queryLower = query.lowercase()
        val match = stations.firstOrNull { station ->
            val name = station.optString("name", "").lowercase()
            val genre = station.optString("genre", "").lowercase()
            name.contains(queryLower) || queryLower.contains(name) || genre.contains(queryLower)
        }

        if (match != null) {
            val url = match.optString("url", "")
            val stationName = match.optString("name", "Unknown")
            val genre = match.optString("genre", "")
            if (url.isNotBlank()) {
                clearNowPlaying()
                return Result.success("open_taplink:$url\nOpening $stationName in browser ($genre)")
            }
        }

        // Not found in saved — try Radio Browser search then play first result
        val searchResults = searchRadioBrowser(query, limit = 3)
        if (searchResults.isNotEmpty()) {
            val first = searchResults[0]
            val url = first.optString("url_resolved", first.optString("url", ""))
            val stationName = first.optString("name", "Unknown")
            val genre = first.optString("tags", "")
            if (url.isNotBlank()) {
                clearNowPlaying()
                return Result.success("open_taplink:$url\nOpening $stationName in browser ($genre)")
            }
        }

        return Result.success("No station found matching '$query'. Try 'search $query' to discover stations.")
    }

    private suspend fun searchStations(query: String): Result<String> {
        if (query.isBlank()) return Result.success("Please provide a search term (e.g. genre, station name, or country).")

        val results = searchRadioBrowser(query, limit = 5)
        if (results.isEmpty()) {
            return Result.success("No stations found for '$query'. Try a different search term.")
        }

        val sb = StringBuilder("Found ${results.size} stations:\n")
        for (station in results) {
            val stationName = station.optString("name", "Unknown").take(40)
            val tags = station.optString("tags", "").take(30)
            val country = station.optString("country", "")
            val codec = station.optString("codec", "")
            sb.append("• $stationName")
            if (tags.isNotBlank()) sb.append(" ($tags)")
            if (country.isNotBlank()) sb.append(" — $country")
            if (codec.isNotBlank()) sb.append(" [$codec]")
            sb.append("\n")
        }
        sb.append("\nSay 'play [station name]' to start listening.")
        return Result.success(sb.toString())
    }

    private fun listStations(): Result<String> {
        val stations = getSavedStations()
        if (stations.isEmpty()) {
            return Result.success("No saved radio stations. Add stations in the TapRadio companion app, or say 'search [genre]' to discover new ones.")
        }

        val sb = StringBuilder("Saved stations (${stations.size}):\n")
        for (i in 0 until stations.size) {
            val s = stations[i]
            val stationName = s.optString("name", "Unknown")
            val genre = s.optString("genre", "")
            val fav = s.optBoolean("fav", false)
            sb.append(if (fav) "★ " else "• ")
            sb.append(stationName)
            if (genre.isNotBlank()) sb.append(" ($genre)")
            sb.append("\n")
        }
        sb.append("\nSay 'play [station name]' to start listening.")
        return Result.success(sb.toString())
    }

    private fun stopPlayback(): Result<String> {
        clearNowPlaying()
        return Result.success("TapRadio stopped.")
    }

    private fun addStation(url: String, stationName: String, genre: String): Result<String> {
        if (url.isBlank()) return Result.success("Please provide a stream URL to add.")
        val stations = getSavedStationsMutable()
        val newStation = JSONObject()
            .put("name", stationName.ifBlank { "New Station" })
            .put("url", url)
            .put("genre", genre.ifBlank { "Other" })
            .put("desc", "")
            .put("fav", false)
        stations.put(newStation)
        prefs.edit().putString(RADIO_PREFS_KEY, stations.toString()).apply()
        return Result.success("Added '${stationName.ifBlank { url }}' to TapRadio.")
    }

    // ── Helpers ──────────────────────────────────────────────────────

    private fun setNowPlaying(stationName: String, genre: String) {
        prefs.edit()
            .putBoolean("tapradio_now_playing_active", true)
            .putString("tapradio_now_playing_name", stationName)
            .putString("tapradio_now_playing_genre", genre)
            .apply()
    }

    /** Clear radio HUD state — used when playback is delegated to browser. */
    private fun clearNowPlaying() {
        prefs.edit()
            .putBoolean("tapradio_now_playing_active", false)
            .remove("tapradio_now_playing_name")
            .remove("tapradio_now_playing_genre")
            .apply()
    }

    private fun getSavedStations(): List<JSONObject> {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: return emptyList()
        return try {
            val arr = JSONArray(raw)
            (0 until arr.length()).map { arr.getJSONObject(it) }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse saved stations: ${e.message}")
            emptyList()
        }
    }

    private fun getSavedStationsMutable(): JSONArray {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: return JSONArray()
        return try { JSONArray(raw) } catch (_: Exception) { JSONArray() }
    }

    private suspend fun searchRadioBrowser(query: String, limit: Int = 5): List<JSONObject> =
        withContext(Dispatchers.IO) {
            val encoded = URLEncoder.encode(query, "UTF-8")
            for (server in RADIO_BROWSER_SERVERS) {
                try {
                    val url = "$server/json/stations/byname/$encoded?limit=$limit&order=votes&reverse=true"
                    val conn = URL(url).openConnection() as HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.setRequestProperty("User-Agent", "TapInsight/1.1.2")
                    val code = conn.responseCode
                    if (code == 200) {
                        val body = conn.inputStream.bufferedReader().readText()
                        conn.disconnect()
                        val arr = JSONArray(body)
                        return@withContext (0 until arr.length()).map { arr.getJSONObject(it) }
                    }
                    conn.disconnect()
                } catch (e: Exception) {
                    Log.d(TAG, "Radio Browser search failed on $server: ${e.message}")
                }
            }
            emptyList()
        }
}
