package com.rayneo.visionclaw.core.tools

import android.content.Context
import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import java.net.URLEncoder
import java.nio.charset.StandardCharsets

/**
 * AiTapTool for "Ask Maps" — a unified map intelligence tool that combines:
 *
 * 1. **Place exploration** — AI-generated summaries via Places API (New) generativeSummary
 * 2. **Landmark-aware directions** — Step-by-step with nearby landmark context
 * 3. **3D map visualization** — Opens ar_nav.html with 3D mode for photorealistic rendering
 *
 * This tool is designed for the AR HUD and provides concise, voice-friendly responses
 * with optional deep-dive via the 3D AR navigation viewer.
 */
class AskMapsTool(
    private val context: Context,
    private val placesClient: GooglePlacesClient,
    private val directionsClient: GoogleDirectionsClient,
    private val locationProvider: () -> DeviceLocationContext?
) : AiTapTool {
    override val name = "ask_maps"

    companion object {
        private const val TAG = "AskMapsTool"
    }

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val action = args["action"]?.trim()?.lowercase() ?: "explore"
        val query = args["query"]?.trim().orEmpty()
        val placeId = args["place_id"]?.trim()
        val destination = args["destination"]?.trim()

        Log.d(TAG, "ask_maps: action=$action query=$query placeId=$placeId destination=$destination")

        return when (action) {
            "explore", "about", "tell_me_about" -> handleExplore(query, placeId)
            "navigate_3d", "navigate" -> handleNavigate3D(destination ?: query)
            "landmark_directions" -> handleLandmarkDirections(destination ?: query)
            "nearby_landmarks" -> handleNearbyLandmarks(query)
            else -> handleExplore(query, placeId)
        }
    }

    /**
     * Explore a place — returns AI-generated summary, rating, status, and a 3D view link.
     */
    private suspend fun handleExplore(query: String, placeId: String?): Result<String> {
        if (query.isBlank() && placeId.isNullOrBlank()) {
            return Result.failure(Exception("Please specify a place to explore."))
        }

        val location = locationProvider()

        // If we have a direct place ID, fetch details directly
        if (!placeId.isNullOrBlank()) {
            return fetchPlaceDetailsById(placeId)
        }

        // Otherwise, text search first to find the place, then get details
        val lat = location?.latitude ?: 37.7749
        val lng = location?.longitude ?: -122.4194

        val searchResult = placesClient.textSearchWithSummary(
            textQuery = query,
            latitude = lat,
            longitude = lng,
            radiusMeters = 10000.0,
            pageSize = 3
        )

        return when (searchResult) {
            is GooglePlacesClient.PlacesResult.Success -> {
                if (searchResult.places.isEmpty()) {
                    return Result.success("No places found matching '$query' near your location.")
                }

                val topPlace = searchResult.places.first()
                // Fetch full details with generativeSummary
                val plId = topPlace.id
                if (!plId.isNullOrBlank()) {
                    val detailsResult = fetchPlaceDetailsById(plId)
                    if (detailsResult.isSuccess) return detailsResult
                }

                // Fallback: return basic info
                Result.success(buildBasicPlaceResponse(topPlace, location))
            }
            is GooglePlacesClient.PlacesResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlacesResult.Error ->
                Result.failure(Exception(searchResult.message))
        }
    }

    private suspend fun fetchPlaceDetailsById(placeId: String): Result<String> {
        return when (val result = placesClient.getPlaceDetails(placeId)) {
            is GooglePlacesClient.PlaceDetailsResult.Success -> {
                val d = result.details
                val response = buildString {
                    append("📍 ${d.name}\n")
                    if (!d.address.isNullOrBlank()) append("Address: ${d.address}\n")

                    // AI-generated summary (from Gemini via Places API)
                    val summary = d.generativeSummary ?: d.editorialSummary
                    if (!summary.isNullOrBlank()) {
                        append("About: $summary\n")
                    }

                    if (d.rating != null) {
                        val stars = "★${"%.1f".format(d.rating)}"
                        val count = d.ratingCount?.let { " ($it reviews)" } ?: ""
                        append("Rating: $stars$count\n")
                    }

                    when (d.isOpen) {
                        true -> append("Status: Open Now\n")
                        false -> append("Status: Currently Closed\n")
                        null -> {}
                    }

                    if (!d.phoneNumber.isNullOrBlank()) append("Phone: ${d.phoneNumber}\n")
                    if (!d.websiteUri.isNullOrBlank()) append("Web: ${d.websiteUri}\n")
                    if (d.latitude != null && d.longitude != null) {
                        append("3D navigation available. Tap this card to open it.")
                    }
                }
                Result.success(response)
            }
            is GooglePlacesClient.PlaceDetailsResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlaceDetailsResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /**
     * Launch 3D AR navigation to a destination.
     */
    private suspend fun handleNavigate3D(destination: String): Result<String> {
        if (destination.isBlank()) {
            return Result.failure(Exception("Please specify a destination for 3D navigation."))
        }

        val location = locationProvider()
        val origin = if (location != null) "${location.latitude},${location.longitude}" else "current"

        // Get route info for summary
        val routeInfo = when (val result = directionsClient.getDirections(origin, destination, "driving")) {
            is GoogleDirectionsClient.DirectionsResult.Success -> {
                val traffic = result.durationInTraffic?.let { " ($it with traffic)" } ?: ""
                "${result.distance}, ${result.duration}$traffic via ${result.summary}"
            }
            else -> null
        }

        // Also get walking time for short distances
        val walkInfo = when (val result = directionsClient.getDirections(origin, destination, "walking")) {
            is GoogleDirectionsClient.DirectionsResult.Success -> "${result.duration} walk"
            else -> null
        }

        return Result.success(buildString {
            append("3D Navigation to: $destination\n")
            if (routeInfo != null) append("Driving: $routeInfo\n")
            if (walkInfo != null) append("Walking: $walkInfo\n")
            append("Opening 3D AR navigation view.")
        })
    }

    /**
     * Get directions with landmark context for each step.
     * Enriches standard turn-by-turn with nearby landmarks visible from AR glasses.
     */
    private suspend fun handleLandmarkDirections(destination: String): Result<String> {
        if (destination.isBlank()) {
            return Result.failure(Exception("Please specify a destination."))
        }

        val location = locationProvider()
            ?: return Result.failure(Exception("GPS not available. Enable location services."))

        val origin = "${location.latitude},${location.longitude}"

        val result = directionsClient.getDirections(origin, destination, "driving")
        if (result !is GoogleDirectionsClient.DirectionsResult.Success) {
            return Result.failure(Exception("Could not get directions to $destination"))
        }

        // Build landmark-enhanced directions
        val response = buildString {
            append("🧭 Landmark Directions to: $destination\n")
            append("${result.distance} — ${result.duration}")
            result.durationInTraffic?.let { append(" ($it in traffic)") }
            append("\n\n")

            result.steps.forEachIndexed { index, step ->
                append("${index + 1}. $step\n")
            }

            append("\nFor 3D view with landmarks, say 'navigate 3D to $destination'")
        }

        return Result.success(response)
    }

    /**
     * Find notable landmarks near the user's current location.
     */
    private suspend fun handleNearbyLandmarks(query: String): Result<String> {
        val location = locationProvider()
            ?: return Result.failure(Exception("GPS not available."))

        val searchQuery = if (query.isNotBlank()) "$query landmarks" else "landmarks points of interest"

        val result = placesClient.textSearchWithSummary(
            textQuery = searchQuery,
            latitude = location.latitude,
            longitude = location.longitude,
            radiusMeters = 2000.0,
            pageSize = 5
        )

        return when (result) {
            is GooglePlacesClient.PlacesResult.Success -> {
                if (result.places.isEmpty()) {
                    return Result.success("No notable landmarks found nearby.")
                }
                val response = buildString {
                    append("🏛️ Nearby Landmarks:\n")
                    result.places.forEachIndexed { index, place ->
                        append("${index + 1}. ${place.name}")
                        if (place.rating != null) append(" ★${"%.1f".format(place.rating)}")
                        if (!place.shortAddress.isNullOrBlank()) append(" — ${place.shortAddress}")
                        append("\n")
                    }
                }
                Result.success(response)
            }
            is GooglePlacesClient.PlacesResult.ApiKeyMissing ->
                Result.failure(Exception("Google Maps API key not configured."))
            is GooglePlacesClient.PlacesResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    private fun buildBasicPlaceResponse(
        place: GooglePlacesClient.NearbyPlace,
        location: DeviceLocationContext?
    ): String {
        return buildString {
            append("📍 ${place.name}\n")
            if (place.address.isNotBlank()) append("Address: ${place.address}\n")
            if (place.rating != null) {
                append("Rating: ★${"%.1f".format(place.rating)}")
                place.ratingCount?.let { append(" ($it reviews)") }
                append("\n")
            }
            when (place.isOpen) {
                true -> append("Status: Open Now\n")
                false -> append("Status: Currently Closed\n")
                null -> {}
            }
            append("Tap this card to open navigation.")
        }
    }

}
