package com.rayneo.visionclaw.core.tools

import android.util.Log
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.network.OpenClawClient

/**
 * OpenClawTool — bridges Gemini Live tool calls to a user's personal
 * OpenClaw AI assistant running on their own server/device.
 *
 * Triggered via the "tapclaw" prefix in voice commands:
 *   "tapclaw check my emails"
 *   "tapclaw turn off the lights"
 *   "tapclaw what do you see" (sends camera frame as attachment)
 *
 * OpenClaw handles smart home, email, calendars, web automation,
 * health data, productivity apps, custom agent skills, and
 * image/vision analysis (when a camera frame is attached).
 */
class OpenClawTool(
    private val openClawClient: OpenClawClient,
    private val locationProvider: () -> DeviceLocationContext?,
    private val frameProvider: () -> String? = { null }
) : AiTapTool {

    companion object {
        private const val TAG = "OpenClawTool"

        /** Keywords that suggest the user wants vision/image analysis */
        private val VISION_KEYWORDS = setOf(
            "see", "look", "image", "photo", "picture", "camera",
            "analyze", "describe", "identify", "recognize", "read",
            "what is this", "what's this", "show", "visual", "scan",
            "ocr", "text in", "sign", "label", "screen", "view"
        )
    }

    override val name = "tapclaw_agent"

    override suspend fun execute(args: Map<String, String>): Result<String> {
        val query = args["query"]?.trim().orEmpty()
        if (query.isBlank()) {
            return Result.failure(Exception("No query provided for OpenClaw."))
        }

        // Check if query implies vision — attach camera frame if available
        val includeImage = args["include_image"]?.toBooleanStrictOrNull() == true
            || isVisionQuery(query)
        val imageBase64 = if (includeImage) frameProvider() else null
        val hasImage = !imageBase64.isNullOrBlank()

        val frameSizeKb = imageBase64?.length?.div(1024) ?: 0
        Log.d(TAG, "Sending to OpenClaw: query=${query.take(100)} vision=$includeImage hasFrame=$hasImage frameSizeKb=$frameSizeKb")
        if (hasImage) {
            Log.d(TAG, "Frame preview (first 80 chars): ${imageBase64!!.take(80)}")
        }

        // Skip location context entirely — the blocking GPS resolver adds ~13s
        // of delay before the WebSocket call even starts. OpenClaw has its own
        // context and doesn't need glasses GPS for most queries.
        // Only include Gemini-provided context if present.
        val context = args["context"]?.takeIf { it.isNotBlank() }

        return when (val result = openClawClient.sendMessage(query, context, imageBase64)) {
            is OpenClawClient.ClawResult.Success -> {
                val response = buildString {
                    append(result.text)
                    if (!result.sessionId.isNullOrBlank() && result.sessionId != "main") {
                        append("\n[OpenClaw session: ${result.sessionId}]")
                    }
                }
                Result.success(response)
            }
            is OpenClawClient.ClawResult.NotConfigured ->
                Result.failure(Exception(
                    "TapClaw is not configured. Set the gateway URL and token in TapInsight setup."
                ))
            is OpenClawClient.ClawResult.Error ->
                Result.failure(Exception(result.message))
        }
    }

    /**
     * Heuristic check: does the query imply the user wants OpenClaw to
     * look at something through the camera?
     */
    private fun isVisionQuery(query: String): Boolean {
        val lower = query.lowercase()
        return VISION_KEYWORDS.any { keyword -> lower.contains(keyword) }
    }
}
