package com.rayneo.visionclaw.core.network

import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64
import java.util.concurrent.TimeUnit

/**
 * GeminiRouter – sends prompts to Google's Gemini API and returns
 * structured responses for the AITap AR assistant.
 *
 * Error-handling contract:
 *   • Missing / blank API key → [GeminiResult.ApiKeyMissing] (no crash).
 *   • Network or server errors → [GeminiResult.Error] with message.
 *   • Success → [GeminiResult.Success] with the response text.
 */
class GeminiRouter(
    private val apiKeyProvider: () -> String?,
    private val preferredModelProvider: () -> String? = { null },
    private val gatewayBaseUrlProvider: () -> String? = { null },
    private val gatewayTokenProvider: () -> String? = { null },
    private val personalityProvider: () -> String? = { null },
    private val customSystemPromptProvider: () -> String? = { null },
    private val identityProvider: () -> String? = { null },
    private val routingRulesProvider: () -> String? = { null },
    private val behaviorProvider: () -> String? = { null },
    private val urlRulesProvider: () -> String? = { null },
    private val locationContextProvider: () -> String? = { null },
    // Gemini Live voice & AI configuration
    private val liveVoiceNameProvider: () -> String? = { null },
    private val liveThinkingLevelProvider: () -> String? = { null },
    private val liveTemperatureProvider: () -> Float = { -1f },
    private val liveSessionResumptionProvider: () -> Boolean = { true },
    private val liveContextCompressionProvider: () -> Boolean = { false },
    private val liveCompressionTokensProvider: () -> Int = { 0 },
    private val liveProactiveAudioProvider: () -> Boolean = { false },
    private val liveBargeInSensitivityProvider: () -> Float = { 1.0f },
    private val liveDisableInterruptProvider: () -> Boolean = { false },
    private val liveLanguageCodeProvider: () -> String? = { null },
    // Per-model timeout (seconds); 0 or negative = use hardcoded default
    private val timeoutSecondsProvider: () -> Int = { 0 }
) {

    companion object {
        private const val TAG = "GeminiRouter"
        private const val BASE_URL =
            "https://generativelanguage.googleapis.com/v1beta/models"
        private const val LIVE_WS_URL_V1BETA =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1beta.GenerativeService.BidiGenerateContent"
        private const val LIVE_WS_URL_V1ALPHA =
            "wss://generativelanguage.googleapis.com/ws/google.ai.generativelanguage.v1alpha.GenerativeService.BidiGenerateContent"

        // Generic aliases — the OpenClaw gateway resolves these to concrete models
        // via its openclaw.json routing config ("Ship's Computer" logic).
        // When calling the Gemini API directly, buildModelFallbackList() appends
        // concrete preview model names as fallbacks.
        private const val DEFAULT_MODEL = "gemini-flash"
        private const val AUDIO_MODEL = "gemini-flash"
        // Gemini Live default upgraded to the current official Flash Live preview model.
        private const val DEFAULT_LIVE_MODEL = "gemini-3.1-flash-live-preview"
        // Proactive audio is currently supported on Gemini 2.5 Flash Live Preview, not 3.1 Flash Live.
        private const val DEFAULT_PROACTIVE_LIVE_MODEL = "gemini-2.5-flash-native-audio-preview-12-2025"
        // ── Modular system prompt sections (each editable via companion app) ──

        internal const val DEFAULT_IDENTITY =
            "You are AITap, a proactive AI assistant integrated into RayNeo X3 AR glasses.\n" +
                "You can see through the user's camera and hear their voice in real-time."

        private const val DEFAULT_CAPABILITIES =
            "CAPABILITIES:\n" +
                "- Vision: Analyze what the user sees (assembly, cooking, reading, QR codes)\n" +
                "- Calendar: Query and create events via google_calendar tool\n" +
                "- Tasks: Query, create, and complete to-do items via google_tasks tool\n" +
                "- Notes: Save observations and quick memos to Google Keep via google_keep tool\n" +
                "- Contacts: Look up contacts via google_contacts tool\n" +
                "- Navigation: Check traffic, commute times, ETAs, and get directions via google_routes tool\n" +
                "- Places: Find nearby businesses, restaurants, cafes, gas stations with ratings and open/closed status via google_places tool\n" +
                "- Air quality: Check current AQI and pollutant conditions via google_air_quality tool\n" +
                "- Daily briefing: Build a multi-source daily brief with calendar, GPS proximity, public events, traffic, parking, weather, and AQI via daily_briefing tool\n" +
                "- Ask Maps: Explore places with AI summaries, 3D photorealistic navigation, nearby landmarks, and landmark-aware directions via ask_maps tool\n" +
                "- Music: Control Spotify via spotify_player tool and Sonos via sonos_control tool\n" +
                "- Radio: Search, play, and manage internet radio stations and podcasts via tapradio tool\n" +
                "- Communication: Send messages via send_message and place calls via place_call tool\n" +
                "- Camera: Save photos via camera_action tool\n" +
                "- Web & Media: Display images, videos, web pages, and text files on the AR glasses via open_taplink tool. " +
                "Use this to show camera images, saved photos, YouTube videos, websites, or any URL the user wants to see.\n" +
                "- Internet Search: Look up current information, facts, and news using built-in Google Search grounding. Use this for 'search for', 'look up', 'what is' queries.\n" +
                "- Browser Automation: TapClaw can control Chrome tabs on the user's Mac — reuse open tabs, open apps, and install new ones with user approval. Progress shown on HUD.\n" +
                "- Research: Produce long-form research briefs via research_topic tool\n" +
                "- Memory: Recall recent conversations from context cache via get_context tool\n" +
                "- Translation: Translate speech and visible text (signs, menus, documents) to 40+ languages via translate_text tool\n" +
                "- Battery: Check battery level and toggle battery saver mode via battery_saver tool\n" +
                "- Quick Actions: Execute voice macros like 'good morning', 'leaving work', 'meeting mode' via quick_action tool"

        internal const val DEFAULT_ROUTING_RULES =
            "TOOL ROUTING RULES:\n" +
                "PRIORITY: If the user's request starts with or contains the word 'tapclaw' (e.g. 'tapclaw status', " +
                "'tapclaw check my emails', 'tapclaw play my mp3', 'tapclaw open media'), ALWAYS route to " +
                "tapclaw_agent FIRST — regardless of other keyword matches. Only rules 11, 11a, 11b, and 13 " +
                "handle tapclaw requests. Do NOT route tapclaw requests to spotify_player, sonos_control, " +
                "battery_saver, open_taplink, or any other tool.\n" +
                "1) For calendar questions (today, tomorrow, rest of day, upcoming, what's next, am I free, schedule, meetings), " +
                "always call google_calendar. Never ask for calendar provider.\n" +
                "2) For reminders, todos, or task lists, call google_tasks (query/create/complete).\n" +
                "3) For personal notes or quick memos, call google_keep.\n" +
                "4) For ANY question about directions, traffic, commute time, ETA, travel time, " +
                "how long to get somewhere, route planning, 'car to X', 'drive to X', or 'take me to X', " +
                "ALWAYS call google_routes. Never say you cannot check traffic — always call the tool. " +
                "Use origin='current' if the user doesn't specify a starting point. " +
                "For standalone traffic queries without a destination, ask the user where they're headed.\n" +
                "5) For music playback ONLY when the user explicitly asks for Spotify, Sonos, or music streaming " +
                "(e.g. 'play on Spotify', 'Sonos play', 'stream music'). Do NOT use spotify_player or sonos_control " +
                "for generic 'play' or 'open' media requests — those go to open_taplink (rule 17) or tapclaw_agent.\n" +
                "5b) For internet radio or podcast requests ('play radio', 'play jazz', 'find a news station', " +
                "'play NPR', 'turn on the radio', 'play a podcast about [topic]', 'what radio stations do I have'), " +
                "ALWAYS call tapradio. Use action='search' to find stations by name/genre, action='play' to play a station " +
                "by name or URL, action='list' to show saved stations, action='stop' to stop playback. " +
                "TapRadio can search 30,000+ public stations by name, genre, or country.\n" +
                "6) For contacts/phone numbers, call google_contacts.\n" +
                "7) For sending texts or making calls, call send_message or place_call.\n" +
                "8) For finding nearby restaurants, cafes, gas stations, pharmacies, or checking what's open nearby, " +
                "ALWAYS call google_places. Use type like 'restaurant', 'cafe', 'gas_station', etc. " +
                "If the closest place is closed, explicitly promote a DIFFERENT nearby open option instead. " +
                "Never describe the same closed place as the open fallback. Include walking ETA, driving ETA, " +
                "weather, and a Maps link when available.\n" +
                "9) ONLY call daily_briefing when the user explicitly asks for a 'daily briefing', 'daily brief', or 'ultimate daily brief'. " +
                "Never use daily_briefing for generic calendar, events-near-me, what's open, nearby places, traffic, weather, or route questions.\n" +
                "10) For air quality, AQI, smoke, pollution, or whether the air is safe right now, ALWAYS call google_air_quality.\n" +
                "11) BROWSER TASKS: When the user asks TapClaw to do something that requires a web app or desktop app " +
                "(e.g. 'tapclaw check my gmail', 'tapclaw open my google sheets', 'tapclaw send a slack message', " +
                "'tapclaw look at my figma', 'tapclaw post on twitter'), call tapclaw_agent with the full request. " +
                "TapClaw will: (a) check if the app/site is already open in a Chrome tab, (b) reuse that tab if found, " +
                "(c) open the app if not found, (d) ask the user before installing anything new. " +
                "TapClaw reports progress via heartbeat — you will see status updates in the conversation. " +
                "Relay progress to the user via HUD-friendly short responses.\n" +
                "11a) When the user says 'research [topic]', 'do research on [topic]', or 'deep dive into [topic]', " +
                "ALWAYS call research_topic with the topic. This uses the Research provider configured in the companion app " +
                "(Gemini, OpenAI, or Groq). Read the full result back to the user.\n" +
                "Do NOT call research_topic for casual uses of words like 'analyze', 'brief', 'overview', 'explain', or 'tell me about'. " +
                "Those should be answered directly or with the appropriate tool (e.g., ask_maps for places). " +
                "Do not open the browser unless the user explicitly says 'google', 'web search', 'open', or asks to display media. " +
                "For informational queries like 'search for X' or 'look up X', use Google Search grounding to answer directly.\n" +
                "12) For 'tell me about [place]', 'explore [place]', 'what is [landmark]', 'navigate 3D to', 'show me in 3D', " +
                "'what landmarks are nearby', or 'nearby landmarks', ALWAYS call ask_maps. " +
                "Use action='explore' for place info, action='navigate_3d' for 3D navigation, " +
                "action='landmark_directions' for landmark-aware directions, action='nearby_landmarks' for landmark discovery.\n" +
                "13) When the user says 'tapclaw' followed by a command (e.g. 'tapclaw check my emails', " +
                "'tapclaw turn off the lights', 'tapclaw what's on my todo list'), ALWAYS call tapclaw_agent. " +
                "TapClaw is the user's personal AI assistant for smart home, email, automation, and custom workflows.\n" +
                "14) For translation requests ('translate', 'say X in Y', 'what does that say in English'), " +
                "ALWAYS call translate_text. For camera/vision translation of visible text, use text='camera'.\n" +
                "15) For battery questions ('battery level', 'save battery', 'enable battery saver'), " +
                "ALWAYS call battery_saver with the appropriate action.\n" +
                "16) For quick action phrases ('good morning', 'leaving work', 'heading home', 'meeting mode'), " +
                "ALWAYS call quick_action. Say 'list quick actions' to see all available macros.\n" +
                "17) When the user asks to 'show me', 'display', 'open', 'play', or 'listen to' an image, video, audio, website, or file on their glasses, " +
                "ALWAYS call open_taplink with the appropriate URL. This includes showing saved camera images, " +
                "YouTube videos, web articles, audio files, or any media content. " +
                "For workspace files (audio, images, etc.), use the MEDIA RELAY URL from the system context below. " +
                "Audio files (MP3, WAV, etc.) automatically open in the built-in media player.\n" +
                "17b) When the user asks to 'read' a text file (e.g. 'read notes.txt', 'read me that file'), " +
                "do NOT open it in the browser. Instead, use tapclaw_agent to get the file contents, " +
                "then read the ENTIRE text back to the user verbatim in your voice. " +
                "The text will also appear in a chat card on the glasses display.\n" +
                "18) For 'search for', 'look up', 'find out about', 'what is', or any request for information on a topic, " +
                "use the built-in Google Search grounding to find information and answer directly in the conversation. " +
                "Do NOT open a web page or call open_taplink for informational searches. " +
                "Only open a Google web search in TapBrowser (via open_taplink) when the user explicitly says " +
                "'google [topic]', 'web search [topic]', or 'open a search for [topic]'.\n" +
                "19) If a tool fails, reply with one short sentence and a retry suggestion. Never show logs."

        internal const val DEFAULT_BEHAVIOR =
            "PROACTIVE BEHAVIOR:\n" +
                "- When you see a QR code, automatically offer to scan it\n" +
                "- When you see text (menu, sign, document), offer to read it aloud\n" +
                "- When you detect assembly/cooking context, offer step-by-step guidance\n\n" +
                "HUD OUTPUT RULES:\n" +
                "Keep responses to 1-6 lines.\n" +
                "Never output stack traces, logs, HTTP status codes, raw JSON, or diagnostics.\n" +
                "Never repeat the user's transcript.\n" +
                "For calendar answers, format each event as: TIME — TITLE (LOCATION/ONLINE).\n" +
                "For nearby places, prefer the nearest OPEN option, then include ETA, weather, and a Maps link if present.\n\n" +
                "Privacy: DO NOT transcribe or display user speech back in the chat. " +
                "Only display your own responses and valid research links."

        internal const val DEFAULT_URL_RULES =
            "URL RULES:\n" +
                "All links must use https:// format.\n" +
                "Always prefer direct, known URLs when you can confidently provide them " +
                "(e.g. https://www.youtube.com/@depechemode for an official YouTube channel).\n" +
                "Only use a Google Search URL when the user explicitly asks to 'google' or 'web search' something:\n" +
                "- For video queries: https://www.google.com/search?q=QUERY+HERE&tbm=vid\n" +
                "- For all other queries: https://www.google.com/search?q=QUERY+HERE\n" +
                "Replace spaces with + signs.\n" +
                "For informational lookups ('search for X', 'look up X', 'what is X'), answer using Google Search grounding instead of opening a URL."
        private const val CONNECT_TIMEOUT_MS = 10_000
        private const val READ_TIMEOUT_MS = 30_000
        @Suppress("unused") private const val GATEWAY_KEY_PLACEHOLDER = "gateway"
    }

    private val wsClient: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .readTimeout(0, TimeUnit.MILLISECONDS)
            // Gemini Live already speaks over an active streaming channel and OkHttp will
            // still answer any server-initiated ping frames automatically. Client-initiated
            // pings here were causing otherwise healthy long responses to die with
            // "no pong response" failures mid-turn.
            .pingInterval(0, TimeUnit.MILLISECONDS)
            .build()
    }

    private fun sanitizeApiKey(raw: String?): String? {
        val key = raw.orEmpty().trim()
        if (key.isBlank()) return null
        return if (
            key.equals("REPLACE_WITH_YOUR_GEMINI_KEY", ignoreCase = true) ||
            key.equals("YOUR_GEMINI_API_KEY", ignoreCase = true) ||
            key.equals("your_actual_key_here_abc123", ignoreCase = true)
        ) {
            null
        } else {
            key
        }
    }

    private fun resolveApiKey(): String? {
        sanitizeApiKey(apiKeyProvider())?.let { return it }
        return sanitizeApiKey(BuildConfig.GEMINI_API_KEY)
    }

    private fun resolvePreferredModel(requestedModel: String, defaultModel: String): String {
        val configured = preferredModelProvider().orEmpty().trim()
        if (configured.isBlank()) return requestedModel
        return if (requestedModel == defaultModel) configured else requestedModel
    }

    private fun resolvePreferredLiveModel(requestedModel: String): String {
        val configured = preferredModelProvider().orEmpty().trim()
        if (configured.isBlank()) return requestedModel
        val isLiveCapable =
            configured.contains("native-audio", ignoreCase = true) ||
                configured.contains("live", ignoreCase = true)
        return if (isLiveCapable) configured else requestedModel
    }

    private data class LiveSessionConfig(
        val model: String,
        val apiVersion: String,
        val proactiveAudio: Boolean
    )

    private fun resolveLiveSessionConfig(requestedModel: String): LiveSessionConfig {
        val proactiveRequested = liveProactiveAudioProvider()
        var resolvedModel = resolvePreferredLiveModel(requestedModel)
        if (proactiveRequested && resolvedModel.contains("3.1-flash-live", ignoreCase = true)) {
            resolvedModel = DEFAULT_PROACTIVE_LIVE_MODEL
        }
        val useV1Alpha = proactiveRequested
        return LiveSessionConfig(
            model = resolvedModel,
            apiVersion = if (useV1Alpha) "v1alpha" else "v1beta",
            proactiveAudio = proactiveRequested
        )
    }

    /**
     * All known Gemini prebuilt voice names (case-insensitive lookup).
     * The companion app uses dropdown selects, but we still validate
     * to guard against stale or manually-edited preference values.
     */
    private val KNOWN_VOICES = setOf(
        "Puck", "Charon", "Kore", "Fenrir", "Aoede", "Leda", "Orus", "Zephyr",
        "Achernar", "Achird", "Algenib", "Algieba", "Alnilam", "Autonoe",
        "Callirhoe", "Despina", "Enceladus", "Erinome", "Gacrux", "Iapetus",
        "Laomedeia", "Pulcherrima", "Rasalgethi", "Sadachbia", "Sadaltager",
        "Schedar", "Sulafat", "Umbriel", "Vindemiatrix", "Zubenelgenubi"
    ).map { it.lowercase() to it }.toMap()

    /**
     * Validate a voice name from the dropdown select against known voices.
     * Returns the properly-cased voice name, or null if unrecognized/blank.
     */
    private fun resolveVoiceName(rawField: String): String? {
        if (rawField.isBlank()) return null
        return KNOWN_VOICES[rawField.trim().lowercase()]
    }

    private fun resolveGatewayBaseUrl(): String? {
        val raw = gatewayBaseUrlProvider().orEmpty().trim().trimEnd('/')
        if (raw.isBlank()) return null
        return when {
            raw.startsWith("http://") || raw.startsWith("https://") -> raw
            else -> "http://$raw"
        }
    }

    private fun resolveGatewayToken(): String? {
        val token = gatewayTokenProvider().orEmpty().trim()
        return token.takeIf { it.isNotBlank() }
    }

    private fun resolveLiveWebSocketUrl(gatewayBaseUrl: String?, apiVersion: String): String {
        if (gatewayBaseUrl.isNullOrBlank()) {
            return if (apiVersion.equals("v1alpha", ignoreCase = true)) {
                LIVE_WS_URL_V1ALPHA
            } else {
                LIVE_WS_URL_V1BETA
            }
        }
        val wsBase = when {
            gatewayBaseUrl.startsWith("https://") -> "wss://${gatewayBaseUrl.removePrefix("https://")}"
            gatewayBaseUrl.startsWith("http://") -> "ws://${gatewayBaseUrl.removePrefix("http://")}"
            gatewayBaseUrl.startsWith("wss://") || gatewayBaseUrl.startsWith("ws://") -> gatewayBaseUrl
            else -> "ws://$gatewayBaseUrl"
        }.trimEnd('/')
        return "$wsBase/ws/google.ai.generativelanguage.$apiVersion.GenerativeService.BidiGenerateContent"
    }

    /** Sealed result type — callers never see raw exceptions. */
    sealed class GeminiResult {
        data class Success(val text: String, val model: String) : GeminiResult()
        data class Error(val message: String, val code: Int = -1) : GeminiResult()
        object ApiKeyMissing : GeminiResult()
    }

    interface LiveSessionListener {
        fun onSessionReady()
        fun onInputTranscription(text: String)
        fun onOutputTranscription(text: String)
        fun onModelText(text: String)
        fun onModelAudio(mimeType: String, data: ByteArray)
        fun onToolCall(callId: String, name: String, args: String)
        fun onTurnComplete(finishReason: String?)
        fun onError(message: String)
        fun onClosed(code: Int, reason: String)
    }

    class LiveSessionHandle internal constructor(
        private val socket: WebSocket
    ) {
        fun sendAudioChunkPcm16(bytes: ByteArray, size: Int, sampleRateHz: Int = 16_000): Boolean {
            if (size <= 0) return false
            val chunk = JSONObject()
                .put("mimeType", "audio/pcm;rate=$sampleRateHz")
                .put("data", Base64.getEncoder().encodeToString(bytes.copyOf(size)))
            val payload = JSONObject()
                .put("realtimeInput", JSONObject().put("audio", chunk))
            return socket.send(payload.toString())
        }

        fun sendAudioEnd(): Boolean {
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("audioStreamEnd", true)
            )
            return socket.send(payload.toString())
        }

        fun sendImageChunkBase64(imageBase64: String, mimeType: String = "image/jpeg"): Boolean {
            if (imageBase64.isBlank()) return false
            val videoChunk = JSONObject()
                .put("mimeType", mimeType)
                .put("data", imageBase64)
            val payload = JSONObject().put(
                "realtimeInput",
                JSONObject().put("video", videoChunk)
            )
            return socket.send(payload.toString())
        }

        /**
         * Inject a text message into the Live session as client context.
         * Used by ToolAssistEngine to feed tool results directly because
         * the Live model's function-calling may be unreliable.
         */
        fun sendClientText(text: String): Boolean {
            if (text.isBlank()) return false
            val payload = JSONObject().put(
                "clientContent",
                JSONObject()
                    .put("turns", JSONArray().put(
                        JSONObject()
                            .put("role", "user")
                            .put("parts", JSONArray().put(
                                JSONObject().put("text", text)
                            ))
                    ))
                    .put("turnComplete", true)
            )
            Log.d(TAG, "Injecting clientContent text: ${text.take(300)}")
            return socket.send(payload.toString())
        }

        fun sendToolResponse(callId: String, functionName: String, result: String): Boolean {
            if (callId.isBlank() || functionName.isBlank()) return false
            val functionResponse = JSONObject()
                .put("id", callId)
                .put("name", functionName)
                .put("response", JSONObject().put("result", result))
            // Gemini Live API expects exactly one top-level key: toolResponse (camelCase).
            // Sending both camelCase and snake_case in the same payload corrupts the frame
            // and prevents Gemini from generating a spoken reply.
            val payload = JSONObject().put(
                "toolResponse",
                JSONObject().put("functionResponses", JSONArray().put(functionResponse))
            )
            Log.d(
                TAG,
                "Sending toolResponse to Gemini callId=$callId function=$functionName payload=${payload.toString().take(500)}"
            )
            return socket.send(payload.toString())
        }

        fun close() {
            socket.close(1000, "client_close")
        }
    }

    fun startLiveAudioSession(
        listener: LiveSessionListener,
        model: String = DEFAULT_LIVE_MODEL,
        responseModality: String = "AUDIO",
        forceDirect: Boolean = false
    ): LiveSessionHandle? {
        val apiKey = resolveApiKey()
        // Hard lock: Gemini Live media stream is always direct-to-Google.
        val gatewayBaseUrl: String? = null
        val gatewayToken: String? = null
        val usingGatewayRoute = !gatewayBaseUrl.isNullOrBlank()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            listener.onError("Gemini API key missing for Live session.")
            return null
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER
        val liveConfig = resolveLiveSessionConfig(model)
        val liveWsUrl = resolveLiveWebSocketUrl(gatewayBaseUrl, liveConfig.apiVersion)

        val requestBuilder = Request.Builder()
            .url("$liveWsUrl?key=$effectiveApiKey")
        if (usingGatewayRoute && !gatewayToken.isNullOrBlank()) {
            requestBuilder.addHeader("Authorization", "Bearer $gatewayToken")
            requestBuilder.addHeader("X-Clawd-Token", gatewayToken)
        }
        val request = requestBuilder.build()
        val requestedLiveModel = liveConfig.model
        val route = if (gatewayBaseUrl.isNullOrBlank()) "direct-google-live" else "gateway-live"
        Log.d(
            TAG,
            "Starting Gemini Live route=$route model=$requestedLiveModel apiVersion=${liveConfig.apiVersion} proactiveAudio=${liveConfig.proactiveAudio}"
        )

        val socket = wsClient.newWebSocket(request, object : WebSocketListener() {
            private var setupReady = false
            private var setupSent = false
            private var gatewayAuthComplete = !usingGatewayRoute
            private var gatewayConnectRequestId: String? = null

            private fun notifySetupReady() {
                if (setupReady) return
                setupReady = true
                listener.onSessionReady()
            }

            private fun sendGatewayConnect(webSocket: WebSocket, nonce: String?, challengeTs: Long?): Boolean {
                if (gatewayToken.isNullOrBlank()) {
                    listener.onError("Gateway token missing for Live session.")
                    return false
                }
                if (!gatewayConnectRequestId.isNullOrBlank()) {
                    return true
                }

                val reqId = "connect-" + System.currentTimeMillis()
                val auth = JSONObject().put("token", gatewayToken)
                val params = JSONObject()
                    .put("minProtocol", 3)
                    .put("maxProtocol", 3)
                    .put(
                        "client",
                        JSONObject()
                            .put("id", "clawdbot-android")
                            .put("version", BuildConfig.VERSION_NAME)
                            .put("platform", "android")
                            .put("mode", "webchat")
                            .put("instanceId", "rayneo-x3")
                    )
                    .put("role", "operator")
                    .put(
                        "scopes",
                        JSONArray()
                            .put("operator.admin")
                            .put("operator.approvals")
                            .put("operator.pairing")
                    )
                    .put("caps", JSONArray())
                    .put("auth", auth)
                    .put("userAgent", "TapClawX3/" + BuildConfig.VERSION_NAME)
                    .put("locale", java.util.Locale.getDefault().toLanguageTag())

                val frame = JSONObject()
                    .put("type", "req")
                    .put("id", reqId)
                    .put("method", "connect")
                    .put("params", params)

                val sent = webSocket.send(frame.toString())
                if (!sent) {
                    listener.onError("Failed to send gateway connect request.")
                } else {
                    gatewayConnectRequestId = reqId
                    Log.d(TAG, "Gateway connect request sent id=" + reqId)
                }
                return sent
            }

            private fun sendSetup(webSocket: WebSocket): Boolean {
                if (setupSent) return true
                if (usingGatewayRoute && !gatewayAuthComplete) return false
                val modelId =
                    if (requestedLiveModel.startsWith("models/")) {
                        requestedLiveModel
                    } else {
                        "models/$requestedLiveModel"
                    }
                // Build effective system prompt: full override OR modular sections + personality.
                val effectivePrompt = buildString {
                    val custom = customSystemPromptProvider()?.trim().orEmpty()
                    if (custom.isNotBlank()) {
                        // Full override for power users
                        append(custom)
                    } else {
                        // Build from editable sections — blank = use default
                        append(identityProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_IDENTITY)
                        append("\n\n")
                        append(DEFAULT_CAPABILITIES)
                        append("\n\n")
                        append(routingRulesProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_ROUTING_RULES)
                        append("\n\n")
                        append(behaviorProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_BEHAVIOR)
                        append("\n\n")
                        append(urlRulesProvider()?.takeIf { it.isNotBlank() } ?: DEFAULT_URL_RULES)
                    }
                    // Inject the media relay URL so Gemini can construct URLs for workspace files
                    val gwUrl = gatewayBaseUrlProvider()?.trim().orEmpty()
                    if (gwUrl.isNotBlank()) {
                        val gwHost = Regex("""://([^:/]+)""").find(gwUrl)?.groupValues?.get(1)
                        if (gwHost != null) {
                            val isIp = gwHost.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))
                            val isLocal = gwHost == "localhost" || gwHost == "127.0.0.1" || isIp
                            val relayBase = if (isLocal) {
                                "http://$gwHost:18790"
                            } else {
                                val parts = gwHost.split(".")
                                val baseDomain = if (parts.size > 2) parts.drop(1).joinToString(".") else gwHost
                                "https://relay.$baseDomain"
                            }
                            append("\n\nMEDIA RELAY:\n")
                            append("To play or display workspace files on the glasses, use open_taplink with: ")
                            append("$relayBase/media/<filename> (e.g. $relayBase/media/song.mp3). ")
                            append("Latest camera frame: $relayBase/latest. ")
                            append("The agent can save files to the workspace directory, then tell glasses to open the relay URL.")
                        }
                    }
                    // Inject current device location so Gemini knows where the user is
                    val locationCtx = locationContextProvider()?.trim().orEmpty()
                    if (locationCtx.isNotBlank()) {
                        append("\n\nCURRENT LOCATION:\n")
                        append(locationCtx)
                    }
                    val personality = personalityProvider()?.trim().orEmpty()
                    if (personality.isNotBlank()) {
                        append("\n\nPERSONALITY:\n")
                        append(personality)
                    }
                }
                // ── Build generationConfig for the raw Live WebSocket API ──
                // Keep this payload conservative. The raw websocket endpoint currently
                // accepts responseModalities, temperature, speechConfig, and mediaResolution
                // in generationConfig. SDK docs mention thinkingConfig for Gemini 3.1 Live,
                // but the raw websocket path has been rejecting it with INVALID_ARGUMENT.
                val genConfig = JSONObject()
                    .put("responseModalities", JSONArray().put(responseModality))
                val liveTemp = liveTemperatureProvider()
                if (liveTemp in 0f..2f) {
                    genConfig.put("temperature", liveTemp.toDouble())
                }
                // Voice / speech configuration from companion app dropdown.
                val rawVoiceField = liveVoiceNameProvider()?.trim().orEmpty()
                val resolvedVoice = resolveVoiceName(rawVoiceField)
                if (resolvedVoice != null) {
                    val speechConfig = JSONObject()
                    speechConfig.put("voiceConfig", JSONObject()
                        .put("prebuiltVoiceConfig", JSONObject()
                            .put("voiceName", resolvedVoice)))
                    genConfig.put("speechConfig", speechConfig)
                }
                val setupContent = JSONObject()
                    .put("model", modelId)
                    .put(
                        "systemInstruction",
                        JSONObject().put(
                            "parts",
                            JSONArray().put(
                                JSONObject().put("text", effectivePrompt)
                            )
                        )
                    )
                    .put("generationConfig", genConfig)
                    .put("inputAudioTranscription", JSONObject())
                    .put("outputAudioTranscription", JSONObject())
                    .put("tools", JSONArray()
                        .put(JSONObject().put("functionDeclarations", buildAiTapToolDeclarations()))
                        .put(JSONObject().put("googleSearch", JSONObject()))
                    )
                // Configure server-side VAD based on barge-in sensitivity.
                val interruptDisabled = liveDisableInterruptProvider()
                if (interruptDisabled) {
                    // Keep VAD active (so Gemini still knows when you stop talking)
                    // but prevent it from interrupting ongoing speech.
                    val realtimeInputConfig = JSONObject()
                        .put("automaticActivityDetection", JSONObject()
                            .put("startOfSpeechSensitivity", "START_SENSITIVITY_LOW")
                            .put("endOfSpeechSensitivity", "END_SENSITIVITY_LOW")
                            .put("prefixPaddingMs", 500)
                            .put("silenceDurationMs", 2000))
                        .put("activityHandling", "NO_INTERRUPTION")
                    setupContent.put("realtimeInputConfig", realtimeInputConfig)
                    Log.d(TAG, "Gemini Live VAD: NO_INTERRUPTION mode (Gemini always finishes speaking)")
                } else {
                    // Higher sensitivity value = less sensitive to interruption.
                    // Map: <=1.0 → HIGH (default), 1.0–1.8 → MEDIUM, >1.8 → LOW
                    val bargeInSensitivity = liveBargeInSensitivityProvider().coerceIn(0.6f, 2.5f)
                    val startSensitivity = when {
                        bargeInSensitivity > 1.8f -> "START_SENSITIVITY_LOW"
                        bargeInSensitivity > 1.0f -> "START_SENSITIVITY_MEDIUM"
                        else -> "START_SENSITIVITY_HIGH"
                    }
                    val endSensitivity = when {
                        bargeInSensitivity > 1.8f -> "END_SENSITIVITY_LOW"
                        bargeInSensitivity > 1.0f -> "END_SENSITIVITY_MEDIUM"
                        else -> "END_SENSITIVITY_HIGH"
                    }
                    // Scale silence duration: higher sensitivity = longer silence needed to end turn
                    val silenceDurationMs = (300 * bargeInSensitivity).toInt().coerceIn(200, 800)
                    val realtimeInputConfig = JSONObject().put(
                        "automaticActivityDetection", JSONObject()
                            .put("startOfSpeechSensitivity", startSensitivity)
                            .put("endOfSpeechSensitivity", endSensitivity)
                            .put("silenceDurationMs", silenceDurationMs)
                    )
                    setupContent.put("realtimeInputConfig", realtimeInputConfig)
                    Log.d(TAG, "Gemini Live VAD: start=$startSensitivity end=$endSensitivity silenceMs=$silenceDurationMs bargeIn=$bargeInSensitivity")
                }

                if (liveConfig.proactiveAudio) {
                    setupContent.put(
                        "proactivity",
                        JSONObject().put("proactiveAudio", true)
                    )
                }
                // Thinking config is intentionally omitted for the raw websocket path.
                // Gemini 3.1 Flash Live SDK examples support thinkingLevel, but the direct
                // BidiGenerateContent websocket endpoint is currently rejecting that field.
                // Session resumption and context compression are intentionally omitted
                // on the raw websocket path for stability. They remain configurable in the UI,
                // but the current preview/live backend has been prone to closing the socket
                // with 1011 when optional session features are included.
                val setup = JSONObject().put("setup", setupContent)
                Log.d(TAG, "Gemini Live setup payload: ${setup.toString().take(400)}")
                val sent = webSocket.send(setup.toString())
                if (!sent) {
                    listener.onError("Failed to send Gemini Live setup message.")
                    return false
                }
                setupSent = true
                return true
            }

            private fun handleLiveMessage(webSocket: WebSocket, text: String) {
                runCatching {
                    val root = JSONObject(text)
                    val eventType = root.optString("type", "").trim()
                    val eventName = root.optString("event", "").trim()

                    if (usingGatewayRoute) {
                        val isChallenge =
                            eventType.equals("event", ignoreCase = true) &&
                                eventName.equals("connect.challenge", ignoreCase = true)
                        if (isChallenge) {
                            val payload = root.optJSONObject("payload")
                            val nonce = payload?.optString("nonce")
                            val challengeTs = payload?.optLong("ts")?.takeIf { it > 0L }
                            sendGatewayConnect(
                                webSocket = webSocket,
                                nonce = nonce,
                                challengeTs = challengeTs
                            )
                            return@runCatching
                        }

                        val isConnectResponse = eventType.equals("res", ignoreCase = true)
                        if (isConnectResponse) {
                            val responseId = root.optString("id", "").trim()
                            val pendingId = gatewayConnectRequestId
                            if (!pendingId.isNullOrBlank() && responseId == pendingId) {
                                gatewayConnectRequestId = null
                                val ok = root.optBoolean("ok", false)
                                if (ok) {
                                    gatewayAuthComplete = true
                                    Log.d(TAG, "Gateway connect accepted")
                                    val methods = root.optJSONObject("payload")
                                        ?.optJSONObject("features")
                                        ?.optJSONArray("methods")
                                    if (methods != null) {
                                        val list = mutableListOf<String>()
                                        for (i in 0 until methods.length()) {
                                            val name = methods.optString(i).trim()
                                            if (name.isNotBlank()) list.add(name)
                                        }
                                        Log.d(TAG, "Gateway methods: " + list.joinToString(","))
                                    }
                                    sendSetup(webSocket)
                                } else {
                                    val errMsg =
                                        root.optJSONObject("error")?.optString("message")
                                            ?.takeIf { it.isNotBlank() }
                                            ?: "Gateway connect rejected"
                                    listener.onError(errMsg)
                                }
                                return@runCatching
                            }
                        }

                        val authSucceeded =
                            eventType.equals("auth_success", ignoreCase = true) ||
                                eventName.equals("auth_success", ignoreCase = true) ||
                                root.optBoolean("auth_success", false) ||
                                root.optBoolean("authenticated", false)
                        if (authSucceeded) {
                            gatewayAuthComplete = true
                            sendSetup(webSocket)
                            return@runCatching
                        }

                        val authFailed =
                            eventType.equals("auth_failed", ignoreCase = true) ||
                                eventName.equals("auth_failed", ignoreCase = true) ||
                                eventName.equals("connect.denied", ignoreCase = true) ||
                                root.optBoolean("auth_failed", false)
                        if (authFailed) {
                            listener.onError("Gateway authentication failed.")
                            return@runCatching
                        }
                    }

                    val error = root.optJSONObject("error")
                    if (error != null) {
                        val msg = error.optString("message", "Gemini Live returned an error.")
                        listener.onError(msg)
                        return@runCatching
                    }

                    if (root.has("setupComplete") ||
                        root.has("setup_complete") ||
                        root.has("setupcomplete")
                    ) {
                        notifySetupReady()
                    }

                    val serverContent = root.optJSONObject("serverContent")
                        ?: root.optJSONObject("server_content")
                    if (serverContent != null) {
                        notifySetupReady()
                        val inputTx = (serverContent
                            .optJSONObject("inputTranscription")
                            ?: serverContent.optJSONObject("input_transcription"))
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (inputTx.isNotBlank()) {
                            listener.onInputTranscription(inputTx)
                        }

                        val outputTx = (serverContent
                            .optJSONObject("outputTranscription")
                            ?: serverContent.optJSONObject("output_transcription"))
                            ?.optString("text", "")
                            .orEmpty()
                            .trim()
                        if (outputTx.isNotBlank()) {
                            listener.onOutputTranscription(outputTx)
                        }

                        val parts = (serverContent
                            .optJSONObject("modelTurn")
                            ?: serverContent.optJSONObject("model_turn"))
                            ?.optJSONArray("parts")
                        if (parts != null) {
                            for (i in 0 until parts.length()) {
                                val part = parts.optJSONObject(i) ?: continue
                                val textPart = part.optString("text", "").trim()
                                if (textPart.isNotBlank()) {
                                    listener.onModelText(textPart)
                                }

                                val inlineData = part.optJSONObject("inlineData")
                                    ?: part.optJSONObject("inline_data")
                                if (inlineData != null) {
                                    val mime = inlineData.optString("mimeType", "")
                                    val encoded = inlineData.optString("data", "")
                                    if (mime.startsWith("audio/") && encoded.isNotBlank()) {
                                        val audioBytes = Base64.getDecoder().decode(encoded)
                                        listener.onModelAudio(mime, audioBytes)
                                    }
                                }
                            }
                        }

                        val finishReason = sequenceOf(
                            serverContent.optString("finishReason", ""),
                            serverContent.optString("finish_reason", ""),
                            root.optString("finishReason", ""),
                            root.optString("finish_reason", "")
                        ).map { it.trim() }.firstOrNull { it.isNotBlank() }

                        if (serverContent.optBoolean("turnComplete", false) ||
                            serverContent.optBoolean("turn_complete", false) ||
                            serverContent.optBoolean("generationComplete", false) ||
                            serverContent.optBoolean("generation_complete", false) ||
                            finishReason.equals("STOP", ignoreCase = true)
                        ) {
                            listener.onTurnComplete(finishReason)
                        }
                    }

                    val toolCall = root.optJSONObject("toolCall")
                        ?: root.optJSONObject("tool_call")
                    if (toolCall != null) {
                        val functionCalls = toolCall.optJSONArray("functionCalls")
                            ?: toolCall.optJSONArray("function_calls")
                        if (functionCalls != null) {
                            for (i in 0 until functionCalls.length()) {
                                val call = functionCalls.optJSONObject(i) ?: continue
                                val functionCall = call.optJSONObject("functionCall")
                                    ?: call.optJSONObject("function_call")
                                val callId = sequenceOf(
                                    call.optString("id", "").trim(),
                                    call.optString("callId", "").trim(),
                                    functionCall?.optString("id", "")?.trim().orEmpty()
                                ).firstOrNull { it.isNotBlank() }
                                    ?: "tool-call-${System.currentTimeMillis()}-$i"
                                val name = sequenceOf(
                                    functionCall?.optString("name", "")?.trim().orEmpty(),
                                    call.optString("name", "").trim()
                                ).firstOrNull { it.isNotBlank() }.orEmpty()
                                val args = sequenceOf(
                                    functionCall?.optJSONObject("args")?.toString().orEmpty(),
                                    functionCall?.optString("args", "")?.trim().orEmpty(),
                                    call.optJSONObject("args")?.toString().orEmpty(),
                                    call.optString("args", "")?.trim().orEmpty()
                                ).firstOrNull { it.isNotBlank() }.orEmpty()
                                if (name.isNotBlank()) {
                                    listener.onToolCall(callId, name, args)
                                }
                            }
                        }
                    }
                }.onFailure {
                    listener.onError("Failed to parse Live response: ${it.message}")
                }
            }

            override fun onOpen(webSocket: WebSocket, response: Response) {
                Log.d(TAG, "Gemini Live websocket opened")
                if (usingGatewayRoute) {
                    Log.d(TAG, "Gateway live connected; awaiting connect.challenge")
                    return
                }
                sendSetup(webSocket)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                Log.d(TAG, "Gemini Live inbound: ${text.take(600)}")
                handleLiveMessage(webSocket, text)
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                Log.d(
                    TAG,
                    "Gemini Live inbound binary: size=${bytes.size} preview=${bytes.hex().take(64)}"
                )
                val decoded = runCatching { bytes.utf8() }.getOrNull()
                if (!decoded.isNullOrBlank()) {
                    val isAudioChunk = decoded.contains("\"inlineData\"") && decoded.contains("\"audio/")
                    if (isAudioChunk) {
                        Log.d(TAG, "Gemini Live inbound binary-decoded: audio chunk")
                    } else {
                        val preview = decoded
                            .replace('\n', ' ')
                            .replace('\r', ' ')
                            .take(260)
                        Log.d(TAG, "Gemini Live inbound binary-decoded: $preview")
                    }
                    handleLiveMessage(webSocket, decoded)
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                val code = response?.code
                val body = runCatching { response?.peekBody(1024)?.string() }.getOrNull()
                Log.e(TAG, "Gemini Live websocket failure code=$code body=$body", t)
                listener.onError("Gemini Live connection failed: ${t.message ?: "unknown error"}")
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                listener.onClosed(code, reason)
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                Log.w(TAG, "Gemini Live websocket closing code=$code reason=$reason")
                webSocket.close(code, reason)
            }
        })
        return LiveSessionHandle(socket)
    }

    /**
     * Send a text prompt to Gemini.
     *
     * @param prompt   The user's natural-language query.
     * @param model    Gemini model identifier (default: gemini-flash, resolved by gateway or fallback list).
     * @param systemInstruction Optional system-level instruction.
     * @return [GeminiResult] — never throws.
     */
    suspend fun sendPrompt(
        prompt: String,
        model: String = DEFAULT_MODEL,
        systemInstruction: String? = null
    ): GeminiResult = withContext(Dispatchers.IO) {
        // ── 1. Guard: API key ────────────────────────────────────────
        val apiKey = resolveApiKey()
        val gatewayBaseUrl = resolveGatewayBaseUrl()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key is missing — returning ApiKeyMissing")
            return@withContext GeminiResult.ApiKeyMissing
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER

        try {
            // ── 2. Build request JSON ────────────────────────────────
            val contentsArray = JSONArray().put(
                JSONObject().apply {
                    put("role", "user")
                    put("parts", JSONArray().put(
                        JSONObject().put("text", prompt)
                    ))
                }
            )

            val requestBody = JSONObject().apply {
                put("contents", contentsArray)
                if (!systemInstruction.isNullOrBlank()) {
                    put("systemInstruction", JSONObject().apply {
                        put("parts", JSONArray().put(
                            JSONObject().put("text", systemInstruction)
                        ))
                    })
                }
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.7)
                    put("maxOutputTokens", 2048)
                    put("topP", 0.95)
                })
            }

            val requestedModel = resolvePreferredModel(model, DEFAULT_MODEL)
            val modelsToTry = buildModelFallbackList(requestedModel, audioPreferred = false)
            var lastError: GeminiResult.Error? = null
            for (candidateModel in modelsToTry) {
                val http = postGenerateContent(effectiveApiKey, candidateModel, requestBody)
                if (http.code in 200..299) {
                    val text = extractResponseText(http.body)
                    Log.d(TAG, "Gemini response OK (${text.length} chars, model=$candidateModel)")
                    return@withContext GeminiResult.Success(text = text, model = candidateModel)
                }

                Log.e(TAG, "Gemini HTTP ${http.code} model=$candidateModel: ${http.body}")
                if (isApiKeyError(http.code, http.body)) {
                    return@withContext GeminiResult.ApiKeyMissing
                }

                lastError = buildFriendlyError(
                    code = http.code,
                    errorBody = http.body,
                    defaultPrefix = "Gemini API error"
                )

                if (!shouldTryNextModel(http.code, http.body)) {
                    return@withContext lastError
                }
            }

            lastError ?: GeminiResult.Error("No compatible Gemini model available", 404)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini request failed", e)
            GeminiResult.Error(message = e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Send a multimodal prompt (text + base64 image).
     */
    suspend fun sendVisionPrompt(
        prompt: String,
        imageBase64: String,
        mimeType: String = "image/jpeg",
        model: String = DEFAULT_MODEL
    ): GeminiResult = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        val gatewayBaseUrl = resolveGatewayBaseUrl()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key missing for vision prompt")
            return@withContext GeminiResult.ApiKeyMissing
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER

        try {
            val parts = JSONArray().apply {
                put(JSONObject().put("text", prompt))
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", imageBase64)
                    })
                })
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.4)
                    put("maxOutputTokens", 2048)
                })
            }

            val requestedModel = resolvePreferredModel(model, DEFAULT_MODEL)
            val modelsToTry = buildModelFallbackList(requestedModel, audioPreferred = false)
            var lastError: GeminiResult.Error? = null
            for (candidateModel in modelsToTry) {
                val http = postGenerateContent(effectiveApiKey, candidateModel, requestBody)
                if (http.code in 200..299) {
                    val text = extractResponseText(http.body)
                    return@withContext GeminiResult.Success(text = text, model = candidateModel)
                }

                Log.e(TAG, "Gemini vision HTTP ${http.code} model=$candidateModel: ${http.body}")
                if (isApiKeyError(http.code, http.body)) {
                    return@withContext GeminiResult.ApiKeyMissing
                }

                lastError = buildFriendlyError(
                    code = http.code,
                    errorBody = http.body,
                    defaultPrefix = "Vision API error"
                )
                if (!shouldTryNextModel(http.code, http.body)) {
                    return@withContext lastError
                }
            }

            lastError ?: GeminiResult.Error("No compatible Gemini vision model available", 404)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini vision request failed", e)
            GeminiResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    /**
     * Send an audio clip to Gemini and return a plain-text transcription.
     */
    suspend fun sendAudioTranscription(
        audioBase64: String,
        mimeType: String = "audio/mp4",
        model: String = AUDIO_MODEL
    ): GeminiResult = withContext(Dispatchers.IO) {
        val apiKey = resolveApiKey()
        val gatewayBaseUrl = resolveGatewayBaseUrl()
        if (apiKey.isNullOrBlank() && gatewayBaseUrl.isNullOrBlank()) {
            Log.w(TAG, "Gemini API key missing for audio transcription")
            return@withContext GeminiResult.ApiKeyMissing
        }
        val effectiveApiKey = apiKey?.takeIf { it.isNotBlank() } ?: GATEWAY_KEY_PLACEHOLDER

        try {
            val parts = JSONArray().apply {
                put(JSONObject().put(
                    "text",
                    "Transcribe this spoken request exactly. Return only the transcript text."
                ))
                put(JSONObject().apply {
                    put("inline_data", JSONObject().apply {
                        put("mime_type", mimeType)
                        put("data", audioBase64)
                    })
                })
            }

            val requestBody = JSONObject().apply {
                put("contents", JSONArray().put(
                    JSONObject().apply {
                        put("role", "user")
                        put("parts", parts)
                    }
                ))
                put("generationConfig", JSONObject().apply {
                    put("temperature", 0.1)
                    put("maxOutputTokens", 256)
                })
            }

            val requestedModel = resolvePreferredModel(model, AUDIO_MODEL)
            val modelsToTry = buildModelFallbackList(requestedModel, audioPreferred = true)
            var lastError: GeminiResult.Error? = null
            for (candidateModel in modelsToTry) {
                val http = postGenerateContent(effectiveApiKey, candidateModel, requestBody)
                if (http.code in 200..299) {
                    val text = extractResponseText(http.body).trim()
                    return@withContext GeminiResult.Success(text = text, model = candidateModel)
                }

                Log.e(TAG, "Gemini audio HTTP ${http.code} model=$candidateModel: ${http.body}")
                if (isApiKeyError(http.code, http.body)) {
                    return@withContext GeminiResult.ApiKeyMissing
                }

                lastError = buildFriendlyError(
                    code = http.code,
                    errorBody = http.body,
                    defaultPrefix = "Audio transcription API error"
                )
                if (!shouldTryNextModel(http.code, http.body)) {
                    return@withContext lastError
                }
            }

            lastError ?: GeminiResult.Error("No compatible Gemini audio model available", 404)
        } catch (e: Exception) {
            Log.e(TAG, "Gemini audio transcription failed", e)
            GeminiResult.Error(e.localizedMessage ?: "Unknown error")
        }
    }

    private data class HttpResponse(val code: Int, val body: String)

    private fun postGenerateContent(
        apiKey: String,
        model: String,
        requestBody: JSONObject
    ): HttpResponse {
        val gatewayBase = resolveGatewayBaseUrl()?.let { "$it/v1beta/models" }
        val canFallbackToDirect = apiKey != GATEWAY_KEY_PLACEHOLDER

        if (!gatewayBase.isNullOrBlank()) {
            val gatewayUrl = "$gatewayBase/$model:generateContent?key=$apiKey"
            val gatewayAttempt = runCatching {
                postGenerateContentToUrl(gatewayUrl, requestBody)
            }
            if (gatewayAttempt.isSuccess) {
                val gatewayResponse = gatewayAttempt.getOrThrow()
                if (gatewayResponse.code in 200..299 || !canFallbackToDirect) {
                    return gatewayResponse
                }
                Log.w(TAG, "Gateway HTTP ${gatewayResponse.code}; falling back to direct Gemini")
            } else {
                val error = gatewayAttempt.exceptionOrNull()
                Log.w(
                    TAG,
                    "Gateway request failed (${error?.javaClass?.simpleName}: ${error?.message}); falling back to direct Gemini"
                )
                if (!canFallbackToDirect) {
                    throw error ?: IllegalStateException("Gateway request failed")
                }
            }
        }

        val directUrl = "$BASE_URL/$model:generateContent?key=$apiKey"
        return postGenerateContentToUrl(directUrl, requestBody)
    }

    private fun postGenerateContentToUrl(
        url: String,
        requestBody: JSONObject
    ): HttpResponse {
        val userTimeout = timeoutSecondsProvider()
        val effectiveReadTimeout = if (userTimeout > 0) userTimeout * 1000 else READ_TIMEOUT_MS
        val conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            setRequestProperty("Content-Type", "application/json")
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = effectiveReadTimeout
            doOutput = true
        }

        OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { writer ->
            writer.write(requestBody.toString())
            writer.flush()
        }

        val responseCode = conn.responseCode
        val stream = if (responseCode in 200..299) {
            conn.inputStream
        } else {
            conn.errorStream ?: conn.inputStream
        }
        val body = BufferedReader(
            InputStreamReader(stream, Charsets.UTF_8)
        ).use { it.readText() }
        return HttpResponse(responseCode, body)
    }
    private fun extractResponseText(body: String): String {
        val json = JSONObject(body)
        return json.optJSONArray("candidates")
            ?.optJSONObject(0)
            ?.optJSONObject("content")
            ?.optJSONArray("parts")
            ?.optJSONObject(0)
            ?.optString("text", "")
            ?: ""
    }

    private fun isApiKeyError(code: Int, errorBody: String): Boolean {
        if (code != 400 && code != 403) return false
        val lowerErr = errorBody.lowercase()
        return lowerErr.contains("api_key") ||
            lowerErr.contains("api key") ||
            lowerErr.contains("invalid key") ||
            lowerErr.contains("permission denied")
    }

    private fun shouldTryNextModel(code: Int, errorBody: String): Boolean {
        if (code == 429) return true
        if (code == 404) return true
        val lower = errorBody.lowercase()
        return lower.contains("is not found") ||
            lower.contains("model not found") ||
            lower.contains("not supported for generatecontent")
    }

    private fun buildFriendlyError(
        code: Int,
        errorBody: String,
        defaultPrefix: String
    ): GeminiResult.Error {
        val lower = errorBody.lowercase()
        if (code == 429 && (
                lower.contains("resource_exhausted") ||
                    lower.contains("quota exceeded") ||
                    lower.contains("limit: 0")
                )
        ) {
            return GeminiResult.Error(
                message = "Gemini quota exhausted. Free tier: 10 req/min for 2.5-flash. " +
                    "Check your key at ai.google.dev or wait a minute.",
                code = code
            )
        }
        if (code == 404 && (
                lower.contains("is not found") ||
                    lower.contains("not supported for generatecontent")
                )
        ) {
            return GeminiResult.Error(
                message = "Requested Gemini model is unavailable for this API key/project.",
                code = code
            )
        }
        return GeminiResult.Error("$defaultPrefix ($code)", code)
    }

    private fun buildModelFallbackList(model: String, audioPreferred: Boolean): List<String> {
        val usingGateway = !resolveGatewayBaseUrl().isNullOrBlank()

        if (usingGateway) {
            // When routing through the OpenClaw gateway, use only the requested model
            // (typically a generic alias like "gemini-flash"). The gateway's openclaw.json
            // handles model resolution and fallback — we don't second-guess it.
            return listOf(model)
        }

        // Direct Gemini API: try concrete model names as fallbacks.
        // Order: requested model → 3.1 pro → 3 flash → 2.5 pro (last resort).
        // Gemini 2.0 and 1.5 variants are deprecated.
        val fallbacks = listOf(
            model,
            "gemini-3.1-pro-preview",
            "gemini-3-flash-preview",
            "gemini-2.5-pro"
        )
        return fallbacks.distinct()
    }

    /** Build the AITap native tool declarations for Gemini Live setup. */
    private fun buildAiTapToolDeclarations(): JSONArray {
            val tools = JSONArray()

            // google_calendar
            tools.put(JSONObject()
                .put("name", "google_calendar")
                .put("description", "Query or create Google Calendar events across ALL user calendars. Use 'query' to check upcoming events, 'create' to add a new event. Searches all enabled calendars automatically.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'query' to list upcoming events, or 'create' to add a new event."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Natural language calendar query (e.g. 'events today'). Used with 'query' action."))
                        .put("hours", JSONObject().put("type", "STRING")
                            .put("description", "Hours ahead to look for events (default 48). For 'today' use 24, for 'tomorrow' use 48, for 'this week' use 168. Used with 'query' action."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Event title. Required for 'create' action."))
                        .put("start_time", JSONObject().put("type", "STRING")
                            .put("description", "ISO 8601 start time (e.g. '2025-06-15T14:00:00'). Required for 'create' action."))
                        .put("duration_minutes", JSONObject().put("type", "STRING")
                            .put("description", "Event duration in minutes (default 60). Used with 'create' action."))
                        .put("location", JSONObject().put("type", "STRING")
                            .put("description", "Event location. Optional, used with 'create' action."))
                        .put("description", JSONObject().put("type", "STRING")
                            .put("description", "Event description. Optional, used with 'create' action.")))
                    .put("required", JSONArray().put("action"))))

            // google_keep (local notes — Google Keep API is restricted)
            tools.put(JSONObject()
                .put("name", "google_keep")
                .put("description", "Create, append to, or list notes stored locally on the glasses. Notes persist across restarts. Use 'create' for new notes, 'append' to add to existing, 'list' to show recent notes.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'create' for new note, 'append' to add to existing note by title, 'list' to show recent notes."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Note title. Required for create/append."))
                        .put("content", JSONObject().put("type", "STRING")
                            .put("description", "Note content text. Required for create/append.")))
                    .put("required", JSONArray().put("action"))))

            // google_contacts
            tools.put(JSONObject()
                .put("name", "google_contacts")
                .put("description", "Look up contacts to get phone numbers or emails.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: search or get."))
                        .put("name", JSONObject().put("type", "STRING")
                            .put("description", "Contact name to search for.")))
                    .put("required", JSONArray().put("action").put("name"))))

            // google_routes
            tools.put(JSONObject()
                .put("name", "google_routes")
                .put("description", "Get directions, traffic conditions, commute time, ETAs, and route planning between locations. Call this for ANY question about traffic, how long a drive takes, or getting somewhere. IMPORTANT: If the user specifies a starting address (e.g. 'from 123 Main St to 456 Oak Ave'), pass it as 'origin'. If not specified, use 'current' to use GPS.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("origin", JSONObject().put("type", "STRING")
                            .put("description", "Starting location. Pass the user's spoken starting address if they provide one (e.g. 'from 123 Main St'). Use 'current' for current GPS location. Defaults to 'current' if not provided."))
                        .put("destination", JSONObject().put("type", "STRING")
                            .put("description", "Destination address or place name."))
                        .put("mode", JSONObject().put("type", "STRING")
                            .put("description", "Travel mode: driving, transit, walking, or bicycling.")))
                    .put("required", JSONArray().put("destination"))))

            // spotify_player
            tools.put(JSONObject()
                .put("name", "spotify_player")
                .put("description", "Control Spotify music playback: play, pause, skip, search, save.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: play, pause, next, previous, save, search."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Search query or song/artist/playlist name.")))
                    .put("required", JSONArray().put("action"))))

            // sonos_control
            tools.put(JSONObject()
                .put("name", "sonos_control")
                .put("description", "Control Sonos home speakers: play, pause, volume, group rooms.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: play, pause, volume, group."))
                        .put("room", JSONObject().put("type", "STRING")
                            .put("description", "Room or speaker name."))
                        .put("volume", JSONObject().put("type", "STRING")
                            .put("description", "Volume level (0-100).")))
                    .put("required", JSONArray().put("action"))))

            // send_message
            tools.put(JSONObject()
                .put("name", "send_message")
                .put("description", "Send an SMS or text message to a contact or phone number.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("recipient", JSONObject().put("type", "STRING")
                            .put("description", "Contact name or phone number."))
                        .put("message", JSONObject().put("type", "STRING")
                            .put("description", "Message text to send.")))
                    .put("required", JSONArray().put("recipient").put("message"))))

            // place_call
            tools.put(JSONObject()
                .put("name", "place_call")
                .put("description", "Initiate a phone call to a contact or phone number.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("recipient", JSONObject().put("type", "STRING")
                            .put("description", "Contact name or phone number to call.")))
                    .put("required", JSONArray().put("recipient"))))

            // camera_action
            tools.put(JSONObject()
                .put("name", "camera_action")
                .put("description", "Save a photo from the camera, trigger QR scan, or start/stop audio recording.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: save_photo, read_qr, start_recording, stop_recording."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Optional title or label for the saved item.")))
                    .put("required", JSONArray().put("action"))))

            // open_taplink
            tools.put(JSONObject()
                .put("name", "open_taplink")
                .put("description", "Display or play content on the AR glasses by opening a URL in the TapBrowser viewer. " +
                    "Supports images (JPEG, PNG), videos (YouTube, MP4), audio (MP3, WAV, OGG, M4A, FLAC), web pages, and text files. " +
                    "Use this whenever the user asks to 'show', 'display', 'view', 'open', 'play', or 'listen to' any content on their glasses. " +
                    "For workspace files, use the media relay URL from the MEDIA RELAY section in the system prompt. " +
                    "Audio files automatically open in the built-in media player with playback controls.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("url", JSONObject().put("type", "STRING")
                            .put("description", "The URL to open.")))
                    .put("required", JSONArray().put("url"))))

            tools.put(JSONObject()
                .put("name", "research_topic")
                .put("description", "Use the configured research API to generate a detailed research brief. " +
                    "ONLY call this when the user explicitly says 'research [topic]', 'do research on [topic]', " +
                    "or 'deep dive into [topic]'. Do NOT call for casual questions, analysis, or general queries.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("topic", JSONObject().put("type", "STRING")
                            .put("description", "The topic to research in depth.")))
                    .put("required", JSONArray().put("topic"))))

            tools.put(JSONObject()
                .put("name", "learn_topic")
                .put("description", "Use the LearnLM tutoring route only when the user explicitly prefixes the request with learnlm. Do not call this for ordinary teaching or how-to questions unless the user says learnlm first.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "The learning request or skill the user wants to learn.")))
                    .put("required", JSONArray().put("query"))))

            tools.put(JSONObject()
                .put("name", "daily_briefing")
                .put("description", "Generate the user's full daily brief for today using calendar, GPS proximity, Bay Area public events, traffic, parking, weather, and AQI. Only call this when the user explicitly asks for a daily briefing by name, not for ordinary calendar or nearby-event questions.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("focus", JSONObject().put("type", "STRING")
                            .put("description", "Optional focus hint such as 'today' or 'morning'.")))))

            // get_context
            tools.put(JSONObject()
                .put("name", "get_context")
                .put("description", "Recall information from the cached conversation context and recent interactions.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "What to recall from context."))
                        .put("time_range", JSONObject().put("type", "STRING")
                            .put("description", "Optional time range filter (e.g. 'last hour', 'today').")))
                    .put("required", JSONArray().put("query"))))

            // google_tasks
            tools.put(JSONObject()
                .put("name", "google_tasks")
                .put("description", "Query, create, or complete Google Tasks (todos/reminders). Use 'query' to list pending tasks, 'create' to add a new task, 'complete' to mark a task done.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'query' to list tasks, 'create' to add a task, 'complete' to mark done."))
                        .put("title", JSONObject().put("type", "STRING")
                            .put("description", "Task title. Required for 'create' action."))
                        .put("notes", JSONObject().put("type", "STRING")
                            .put("description", "Task notes/details. Optional, used with 'create'."))
                        .put("due_date", JSONObject().put("type", "STRING")
                            .put("description", "Due date in RFC 3339 format (e.g. '2025-06-15T00:00:00.000Z'). Optional."))
                        .put("task_id", JSONObject().put("type", "STRING")
                            .put("description", "Task ID. Required for 'complete' action."))
                        .put("count", JSONObject().put("type", "STRING")
                            .put("description", "Max tasks to return (default 10). Used with 'query'.")))
                    .put("required", JSONArray().put("action"))))

            // google_news
            tools.put(JSONObject()
                .put("name", "google_news")
                .put("description", "Fetch top news headlines from Google News. Use to answer questions about current events or show news on the HUD.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'query' to fetch headlines."))
                        .put("count", JSONObject().put("type", "STRING")
                            .put("description", "Number of headlines to fetch (default 5).")))
                    .put("required", JSONArray().put("action"))))

            // google_places
            tools.put(JSONObject()
                .put("name", "google_places")
                .put("description", "Find nearby businesses, restaurants, cafes, gas stations, pharmacies, and more. Returns places with ratings, open/closed status, addresses, and ETA context. When the closest result is closed, prefer the nearest open option.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("type", JSONObject().put("type", "STRING")
                            .put("description", "Place type: restaurant, cafe, gas_station, pharmacy, hospital, supermarket, bar, bakery, bank, parking, gym, lodging, etc."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Optional natural language query to help resolve type (e.g. 'tacos', 'sushi', 'urgent care')."))
                        .put("radius", JSONObject().put("type", "STRING")
                            .put("description", "Search radius in meters (default 1500, max 5000).")))
                    .put("required", JSONArray().put("type"))))

            // ask_maps — unified map intelligence
            tools.put(JSONObject()
                .put("name", "ask_maps")
                .put("description", "Explore places with AI-generated summaries, get 3D navigation with photorealistic views, " +
                    "find nearby landmarks, and get landmark-aware directions. Use this for questions like 'tell me about [place]', " +
                    "'navigate 3D to [destination]', 'what landmarks are nearby', or 'explore [location]'. " +
                    "Returns AI-generated place insights, ratings, hours, and 3D AR navigation links.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'explore' for AI-generated place summaries and details, " +
                                "'navigate_3d' to launch photorealistic 3D AR navigation, " +
                                "'landmark_directions' for turn-by-turn with landmark context, " +
                                "'nearby_landmarks' to discover notable places nearby."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Place name, address, or search query (e.g. 'Golden Gate Bridge', 'best sushi in SF')."))
                        .put("destination", JSONObject().put("type", "STRING")
                            .put("description", "Destination address for navigation actions."))
                        .put("place_id", JSONObject().put("type", "STRING")
                            .put("description", "Optional Google Place ID for direct lookup.")))
                    .put("required", JSONArray().put("action"))))

            // google_air_quality
            tools.put(JSONObject()
                .put("name", "google_air_quality")
                .put("description", "Get the current air quality index (AQI) and dominant pollutant for the user's current location.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("detail", JSONObject().put("type", "STRING")
                            .put("description", "Optional detail level, such as 'brief' or 'full'.")))))

            // translate_text — real-time translation
            tools.put(JSONObject()
                .put("name", "translate_text")
                .put("description", "Translate text or speech to another language. " +
                    "Also translates text visible in the camera feed (signs, menus, documents). " +
                    "Call this when the user asks to translate something, says 'say X in Y', " +
                    "or asks what a sign/menu says in another language.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("text", JSONObject().put("type", "STRING")
                            .put("description", "Text to translate, or 'camera' for vision-based translation."))
                        .put("target_language", JSONObject().put("type", "STRING")
                            .put("description", "Target language (e.g. 'Spanish', 'Japanese', 'fr')."))
                        .put("source_language", JSONObject().put("type", "STRING")
                            .put("description", "Optional source language hint.")))
                    .put("required", JSONArray().put("text").put("target_language"))))

            // battery_saver — power management
            tools.put(JSONObject()
                .put("name", "battery_saver")
                .put("description", "Check battery level or toggle battery saver mode. " +
                    "Call when the user asks about battery, power, or wants to save battery life.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'status', 'enable', or 'disable'.")))
                    .put("required", JSONArray().put("action"))))

            // quick_action — voice macros and shortcuts
            tools.put(JSONObject()
                .put("name", "quick_action")
                .put("description", "Execute a user-defined quick action or voice macro. " +
                    "Built-in actions include 'good morning' (daily briefing), " +
                    "'leaving work' (traffic home), 'meeting mode' (calendar summary). " +
                    "Call this when the user triggers a known quick action phrase.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Quick action name or 'list' to show available actions."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "The full user query for context.")))
                    .put("required", JSONArray().put("action"))))

            // tapradio — internet radio and podcast player
            tools.put(JSONObject()
                .put("name", "tapradio")
                .put("description", "Control TapRadio — search, play, and manage internet radio stations " +
                    "and podcasts. Can search 30,000+ public stations by name, genre, or country. " +
                    "Play saved stations or discover new ones via voice command.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("action", JSONObject().put("type", "STRING")
                            .put("description", "Action: 'play' (play a station by name/URL), " +
                                "'search' (find stations by query/genre), 'list' (show saved stations), " +
                                "'stop' (stop playback), 'add' (add a station URL with name/genre)."))
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "Station name, genre, search term, or stream URL.")))
                    .put("required", JSONArray().put("action"))))

            // tapclaw_agent — personal AI assistant (requires user to enable)
            tools.put(JSONObject()
                .put("name", "tapclaw_agent")
                .put("description", "Forward a request to the user's personal TapClaw AI agent. " +
                    "TapClaw runs on the user's own server and handles smart home control, " +
                    "email management, calendar automation, web browsing, health tracking, " +
                    "productivity apps, custom workflows, AND image/vision analysis. " +
                    "TapClaw can also control Chrome browser tabs on the user's Mac — reusing existing " +
                    "open tabs, opening new apps, and even installing apps with user permission. " +
                    "It reports task progress via heartbeat updates displayed on the HUD. " +
                    "Call this when the user says 'tapclaw' followed by a command, e.g. " +
                    "'tapclaw check my emails', 'tapclaw turn off the lights', 'tapclaw what's on my todo list'. " +
                    "Also call this for smart home commands like 'turn on the lights', 'set thermostat to 72', " +
                    "or personal automation requests when prefixed with 'tapclaw'. " +
                    "For browser/app tasks like 'check my gmail', 'open google docs', 'post on slack', " +
                    "'look at my figma' — route to tapclaw_agent as well. " +
                    "When the user says 'tapclaw' with a vision request like 'tapclaw what do you see', " +
                    "'tapclaw analyze this', 'tapclaw describe what I'm looking at', or " +
                    "'tapclaw read this sign', set include_image to true to attach the current " +
                    "camera frame for vision analysis. " +
                    "IMPORTANT: When TapClaw returns text content (e.g. file contents, email text, notes), " +
                    "read the ENTIRE text back to the user VERBATIM, word for word. " +
                    "Do NOT summarize, paraphrase, comment on, or talk about the content. " +
                    "Just read it exactly as returned, like reading a document aloud.")
                .put("parameters", JSONObject()
                    .put("type", "OBJECT")
                    .put("properties", JSONObject()
                        .put("query", JSONObject().put("type", "STRING")
                            .put("description", "The full request to send to TapClaw."))
                        .put("context", JSONObject().put("type", "STRING")
                            .put("description", "Optional context from the current conversation."))
                        .put("include_image", JSONObject().put("type", "BOOLEAN")
                            .put("description", "Set to true when the request involves seeing, " +
                                "analyzing, describing, or reading something from the camera. " +
                                "This attaches the current AR glasses camera frame to the request.")))
                    .put("required", JSONArray().put("query"))))

            return tools
        }
}
