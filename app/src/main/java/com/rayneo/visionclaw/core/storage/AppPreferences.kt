package com.rayneo.visionclaw.core.storage

import android.content.Context
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.rayneo.visionclaw.core.model.DeviceLocationContext

class AppPreferences(context: Context) {

    private val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    private val gson = Gson()

    var geminiApiKey: String
        get() = prefs.getString(KEY_GEMINI_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_API_KEY, value).apply()

    var researchProvider: String
        get() = prefs.getString(KEY_RESEARCH_PROVIDER, "gemini") ?: "gemini"
        set(value) = prefs.edit().putString(KEY_RESEARCH_PROVIDER, value).apply()

    var researchApiKey: String
        get() = prefs.getString(KEY_RESEARCH_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_API_KEY, value).apply()

    var researchModel: String
        get() = prefs.getString(KEY_RESEARCH_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_RESEARCH_MODEL, value).apply()

    var learnLmModel: String
        get() = prefs.getString(KEY_LEARNLM_MODEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LEARNLM_MODEL, value).apply()

    var calendarApiKey: String
        get() = prefs.getString(KEY_CALENDAR_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_API_KEY, value).apply()

    var calendarId: String
        get() = prefs.getString(KEY_CALENDAR_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CALENDAR_ID, value).apply()

    var openClawEndpoint: String
        get() = prefs.getString(KEY_OPENCLAW_ENDPOINT, DEFAULT_OPENCLAW_ENDPOINT) ?: DEFAULT_OPENCLAW_ENDPOINT
        set(value) = prefs.edit().putString(KEY_OPENCLAW_ENDPOINT, value).apply()

    /** OpenClaw gateway bearer token for authentication. */
    var openClawToken: String
        get() = prefs.getString(KEY_OPENCLAW_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_OPENCLAW_TOKEN, value).apply()

    /** OpenClaw session ID. Defaults to "main". Use different IDs for isolated contexts. */
    var openClawSessionId: String
        get() = prefs.getString(KEY_OPENCLAW_SESSION_ID, "main") ?: "main"
        set(value) = prefs.edit().putString(KEY_OPENCLAW_SESSION_ID, value).apply()

    /** OpenClaw request timeout in seconds. 0 = default (30s). */
    var openClawTimeoutSeconds: Int
        get() = prefs.getInt(KEY_OPENCLAW_TIMEOUT_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_OPENCLAW_TIMEOUT_SECONDS, value).apply()

    /** Whether OpenClaw integration is enabled. */
    var openClawEnabled: Boolean
        get() = prefs.getBoolean(KEY_OPENCLAW_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_OPENCLAW_ENABLED, value).apply()

    /** OpenClaw heartbeat poll interval in seconds (10–120, default 20). */
    var openClawHeartbeatIntervalSeconds: Int
        get() = prefs.getInt(KEY_OPENCLAW_HEARTBEAT_INTERVAL_SECONDS, 20).coerceIn(10, 120)
        set(value) = prefs.edit().putInt(KEY_OPENCLAW_HEARTBEAT_INTERVAL_SECONDS, value.coerceIn(10, 120)).apply()

    /** Whether Gemini assistant starts with camera on by default (false = audio-only). */
    var assistantDefaultCamera: Boolean
        get() = prefs.getBoolean(KEY_ASSISTANT_DEFAULT_CAMERA, false)
        set(value) = prefs.edit().putBoolean(KEY_ASSISTANT_DEFAULT_CAMERA, value).apply()

    // ── Battery Saver ──────────────────────────────────────────────────

    /** Whether battery saver mode is currently active. */
    var batterySaverActive: Boolean
        get() = prefs.getBoolean(KEY_BATTERY_SAVER_ACTIVE, false)
        set(value) = prefs.edit().putBoolean(KEY_BATTERY_SAVER_ACTIVE, value).apply()

    /** Stash of original HUD refresh interval before battery saver overrode it. */
    var batterySaverOrigRefresh: Int
        get() = prefs.getInt(KEY_BATTERY_SAVER_ORIG_REFRESH, 0)
        set(value) = prefs.edit().putInt(KEY_BATTERY_SAVER_ORIG_REFRESH, value).apply()

    /** Battery level (%) at which battery saver auto-enables. 0 = manual only. */
    var batterySaverAutoThreshold: Int
        get() = prefs.getInt(KEY_BATTERY_SAVER_AUTO_THRESHOLD, 20)
        set(value) = prefs.edit().putInt(KEY_BATTERY_SAVER_AUTO_THRESHOLD, value).apply()

    // ── Quick Actions ──────────────────────────────────────────────────

    /** JSON array of user-defined quick actions. */
    var quickActionsJson: String
        get() = prefs.getString(KEY_QUICK_ACTIONS_JSON, "") ?: ""
        set(value) = prefs.edit().putString(KEY_QUICK_ACTIONS_JSON, value).apply()

    /** User's home address for "heading home" quick action. */
    var homeAddress: String
        get() = prefs.getString(KEY_HOME_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_HOME_ADDRESS, value).apply()

    /** User's work address for "leaving work" quick action. */
    var workAddress: String
        get() = prefs.getString(KEY_WORK_ADDRESS, "") ?: ""
        set(value) = prefs.edit().putString(KEY_WORK_ADDRESS, value).apply()

    // ── Translation ────────────────────────────────────────────────────

    /** Default target language for translation (e.g., "Spanish", "ja"). */
    var translateDefaultLanguage: String
        get() = prefs.getString(KEY_TRANSLATE_DEFAULT_LANGUAGE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TRANSLATE_DEFAULT_LANGUAGE, value).apply()

    /** Whether auto-translate mode is enabled (translate all visible text). */
    var translateAutoMode: Boolean
        get() = prefs.getBoolean(KEY_TRANSLATE_AUTO_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_TRANSLATE_AUTO_MODE, value).apply()

    var ttsVolume: Float
        get() = prefs.getFloat(KEY_TTS_VOLUME, 0.80f)
        set(value) = prefs.edit().putFloat(KEY_TTS_VOLUME, value.coerceIn(0f, 1f)).apply()

    var ttsMuted: Boolean
        get() = prefs.getBoolean(KEY_TTS_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_TTS_MUTED, value).apply()

    /** Preferred system TTS voice name for the media player text reader. Blank = auto. */
    var mediaTtsVoice: String
        get() = prefs.getString(KEY_MEDIA_TTS_VOICE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_MEDIA_TTS_VOICE, value).apply()

    /** Whether to underline the current word during media player TTS read-aloud. */
    var mediaTtsUnderline: Boolean
        get() = prefs.getBoolean(KEY_MEDIA_TTS_UNDERLINE, true)
        set(value) = prefs.edit().putBoolean(KEY_MEDIA_TTS_UNDERLINE, value).apply()

    var musicMuted: Boolean
        get() = prefs.getBoolean(KEY_MUSIC_MUTED, false)
        set(value) = prefs.edit().putBoolean(KEY_MUSIC_MUTED, value).apply()

    var webDesktopMode: Boolean
        get() = prefs.getBoolean(KEY_WEB_DESKTOP_MODE, false)
        set(value) = prefs.edit().putBoolean(KEY_WEB_DESKTOP_MODE, value).apply()

    var webPointerSensitivity: Float
        get() = prefs.getFloat(KEY_WEB_POINTER_SENSITIVITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_WEB_POINTER_SENSITIVITY, value.coerceIn(0.4f, 1.8f)).apply()

    var webForceDarkMode: Boolean
        get() = prefs.getBoolean(KEY_WEB_FORCE_DARK_MODE, true)
        set(value) = prefs.edit().putBoolean(KEY_WEB_FORCE_DARK_MODE, value).apply()

    var browserShowSystemInfo: Boolean
        get() = prefs.getBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, true)
        set(value) = prefs.edit().putBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, value).apply()

    /** Custom system prompt override. If blank, the built-in prompt is used. */
    var customSystemPrompt: String
        get() = prefs.getString(KEY_CUSTOM_SYSTEM_PROMPT, "") ?: ""
        set(value) = prefs.edit().putString(KEY_CUSTOM_SYSTEM_PROMPT, value).apply()

    /** Personality description injected after the system prompt. */
    var personality: String
        get() = prefs.getString(KEY_PERSONALITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PERSONALITY, value).apply()

    /** Editable prompt section: Identity (who is AITap). Blank = use default. */
    var promptIdentity: String
        get() = prefs.getString(KEY_PROMPT_IDENTITY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_IDENTITY, value).apply()

    /** Editable prompt section: Tool routing rules. Blank = use default. */
    var promptRoutingRules: String
        get() = prefs.getString(KEY_PROMPT_ROUTING_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_ROUTING_RULES, value).apply()

    /** Editable prompt section: Proactive behavior + HUD output + privacy. Blank = use default. */
    var promptBehavior: String
        get() = prefs.getString(KEY_PROMPT_BEHAVIOR, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_BEHAVIOR, value).apply()

    /** Editable prompt section: URL generation rules. Blank = use default. */
    var promptUrlRules: String
        get() = prefs.getString(KEY_PROMPT_URL_RULES, "") ?: ""
        set(value) = prefs.edit().putString(KEY_PROMPT_URL_RULES, value).apply()

    /** Spotify OAuth client ID. */
    var spotifyClientId: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_ID, value).apply()

    /** Spotify OAuth client secret. */
    var spotifyClientSecret: String
        get() = prefs.getString(KEY_SPOTIFY_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_SPOTIFY_CLIENT_SECRET, value).apply()

    /** Google OAuth client ID (for Calendar, Keep, Contacts). */
    var googleOAuthClientId: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_CLIENT_ID, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_CLIENT_ID, value).apply()

    /** Google OAuth client secret (for Web application type). */
    var googleOAuthClientSecret: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_CLIENT_SECRET, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_CLIENT_SECRET, value).apply()

    /** Google OAuth access token (short-lived). */
    var googleOAuthAccessToken: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_ACCESS_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_ACCESS_TOKEN, value).apply()

    /** Google OAuth refresh token (long-lived). */
    var googleOAuthRefreshToken: String
        get() = prefs.getString(KEY_GOOGLE_OAUTH_REFRESH_TOKEN, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_OAUTH_REFRESH_TOKEN, value).apply()

    /** Expiry timestamp (epoch ms) for the current access token. */
    var googleOAuthTokenExpiryMs: Long
        get() = prefs.getLong(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS, 0L)
        set(value) = prefs.edit().putLong(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS, value).apply()

    /** Returns true if a non-expired OAuth access token exists. */
    fun isGoogleOAuthTokenValid(): Boolean =
        googleOAuthAccessToken.isNotBlank() &&
                System.currentTimeMillis() < googleOAuthTokenExpiryMs

    /** Returns true if any Google OAuth tokens are stored (even if expired). */
    fun hasGoogleOAuthTokens(): Boolean =
        googleOAuthRefreshToken.isNotBlank()

    /** Clears all Google OAuth tokens (forces re-authorization). */
    fun clearGoogleOAuthTokens() {
        prefs.edit()
            .remove(KEY_GOOGLE_OAUTH_ACCESS_TOKEN)
            .remove(KEY_GOOGLE_OAUTH_REFRESH_TOKEN)
            .remove(KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS)
            .apply()
    }

    // ── HUD Display Settings ────────────────────────────────────────────

    /** Show calendar widget on HUD. */
    var hudShowCalendar: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_CALENDAR, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_CALENDAR, value).apply()

    /** Show traffic & commute info on HUD. */
    var hudShowTraffic: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_TRAFFIC, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_TRAFFIC, value).apply()

    /** Show floating notifications on HUD. */
    var hudShowNotifications: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_NOTIFICATIONS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_NOTIFICATIONS, value).apply()

    /** HUD widget refresh interval in seconds (5–300). */
    var hudRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_HUD_REFRESH_INTERVAL_SECONDS, 60).coerceIn(5, 300)
        set(value) = prefs.edit().putInt(KEY_HUD_REFRESH_INTERVAL_SECONDS, value.coerceIn(5, 300)).apply()

    /** Show event time/date on the HUD calendar widget. */
    var hudShowEventTime: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_EVENT_TIME, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_EVENT_TIME, value).apply()

    /** Comma-separated list of enabled Google Calendar IDs. Empty = primary only. */
    var enabledCalendarIds: Set<String>
        get() {
            val raw = prefs.getString(KEY_ENABLED_CALENDAR_IDS, "") ?: ""
            return if (raw.isBlank()) emptySet()
            else raw.split(",").map { it.trim() }.filter { it.isNotBlank() }.toSet()
        }
        set(value) = prefs.edit().putString(KEY_ENABLED_CALENDAR_IDS, value.joinToString(",")).apply()

    /** Show tasks widget on HUD. */
    var hudShowTasks: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_TASKS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_TASKS, value).apply()

    /** Show news headlines on HUD. */
    var hudShowNews: Boolean
        get() = prefs.getBoolean(KEY_HUD_SHOW_NEWS, true)
        set(value) = prefs.edit().putBoolean(KEY_HUD_SHOW_NEWS, value).apply()

    /** Number of tasks to show on HUD (1-10). */
    var tasksItemCount: Int
        get() = prefs.getInt(KEY_TASKS_ITEM_COUNT, 5).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_TASKS_ITEM_COUNT, value.coerceIn(1, 10)).apply()

    /** Number of news headlines to show on HUD (1-10). */
    var newsItemCount: Int
        get() = prefs.getInt(KEY_NEWS_ITEM_COUNT, 3).coerceIn(1, 10)
        set(value) = prefs.edit().putInt(KEY_NEWS_ITEM_COUNT, value.coerceIn(1, 10)).apply()

    /** News headline refresh interval in seconds (60-3600). */
    var newsRefreshIntervalSeconds: Int
        get() = prefs.getInt(KEY_NEWS_REFRESH_INTERVAL_SECONDS, 600).coerceIn(60, 3600)
        set(value) = prefs.edit().putInt(KEY_NEWS_REFRESH_INTERVAL_SECONDS, value.coerceIn(60, 3600)).apply()

    /** Comma-separated display order for HUD cards (e.g. "calendar,tasks,news"). */
    var hudDisplayOrder: String
        get() = prefs.getString(KEY_HUD_DISPLAY_ORDER, "calendar,tasks,news") ?: "calendar,tasks,news"
        set(value) = prefs.edit().putString(KEY_HUD_DISPLAY_ORDER, value).apply()

    /** JSON map of calendar ID → item count. E.g. {"primary":3,"other@gmail.com":5} */
    var calendarItemCounts: String
        get() = prefs.getString(KEY_CALENDAR_ITEM_COUNTS, "{}") ?: "{}"
        set(value) = prefs.edit().putString(KEY_CALENDAR_ITEM_COUNTS, value).apply()

    /** Get item count for a specific calendar. Default 3. */
    fun getCalendarItemCount(calendarId: String): Int {
        return try {
            val map: Map<String, Double> = gson.fromJson(
                calendarItemCounts,
                object : TypeToken<Map<String, Double>>() {}.type
            )
            map[calendarId]?.toInt()?.coerceIn(1, 10) ?: 3
        } catch (_: Exception) { 3 }
    }

    /** Google Maps / Routes API key. */
    var googleMapsApiKey: String
        get() = prefs.getString(KEY_GOOGLE_MAPS_API_KEY, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GOOGLE_MAPS_API_KEY, value).apply()

    /** Gemini model override (e.g. "gemini-2.5-pro"). Blank = use default. */
    var geminiModelOverride: String
        get() = prefs.getString(KEY_GEMINI_MODEL_OVERRIDE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_GEMINI_MODEL_OVERRIDE, value).apply()

    // ── Gemini Live Voice & AI Settings ─────────────────────────────────

    /** Gemini Live voice name (e.g. "Puck", "Kore"). Blank = default (Puck). */
    var liveVoiceName: String
        get() = prefs.getString(KEY_LIVE_VOICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_VOICE_NAME, value).apply()

    /** Gemini TTS voice name for non-live text-to-speech (all 30 voices). Blank = default. */
    var ttsVoiceName: String
        get() = prefs.getString(KEY_TTS_VOICE_NAME, "") ?: ""
        set(value) = prefs.edit().putString(KEY_TTS_VOICE_NAME, value).apply()

    /** Gemini Live thinking level: minimal, low, medium, high. Blank = minimal. */
    var liveThinkingLevel: String
        get() = prefs.getString(KEY_LIVE_THINKING_LEVEL, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_THINKING_LEVEL, value).apply()

    /** Gemini Live temperature (0.0–2.0). -1 = use model default. */
    var liveTemperature: Float
        get() = prefs.getFloat(KEY_LIVE_TEMPERATURE, -1f)
        set(value) = prefs.edit().putFloat(KEY_LIVE_TEMPERATURE, value).apply()

    /** Enable session resumption for Gemini Live. */
    var liveSessionResumption: Boolean
        get() = prefs.getBoolean(KEY_LIVE_SESSION_RESUMPTION, true)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_SESSION_RESUMPTION, value).apply()

    /** Enable context window compression for longer Live sessions. */
    var liveContextCompression: Boolean
        get() = prefs.getBoolean(KEY_LIVE_CONTEXT_COMPRESSION, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_CONTEXT_COMPRESSION, value).apply()

    /** Target token count for context window compression trigger. */
    var liveCompressionTokens: Int
        get() = prefs.getInt(KEY_LIVE_COMPRESSION_TOKENS, 0)
        set(value) = prefs.edit().putInt(KEY_LIVE_COMPRESSION_TOKENS, value).apply()

    /** Enable proactive audio (model speaks when relevant without being asked). */
    var liveProactiveAudio: Boolean
        get() = prefs.getBoolean(KEY_LIVE_PROACTIVE_AUDIO, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_PROACTIVE_AUDIO, value).apply()

    /** Gemini Live language code (e.g. "en-US", "es-ES"). Blank = auto. */
    var liveLanguageCode: String
        get() = prefs.getString(KEY_LIVE_LANGUAGE_CODE, "") ?: ""
        set(value) = prefs.edit().putString(KEY_LIVE_LANGUAGE_CODE, value).apply()

    /** Barge-in sensitivity multiplier for interrupting Gemini speech. Higher = less sensitive. */
    var liveBargeInSensitivity: Float
        get() = prefs.getFloat(KEY_LIVE_BARGE_IN_SENSITIVITY, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_LIVE_BARGE_IN_SENSITIVITY, value.coerceIn(0.6f, 2.5f)).apply()

    /** Microphone silence threshold (PCM16 peak). Audio below this level is treated as silence.
     *  Higher values filter out more background noise. Default 600 (~1.8% of max). Range: 100–3000. */
    var liveSilenceThreshold: Int
        get() = prefs.getInt(KEY_LIVE_SILENCE_THRESHOLD, 600)
        set(value) = prefs.edit().putInt(KEY_LIVE_SILENCE_THRESHOLD, value.coerceIn(100, 3000)).apply()

    /** When true, Gemini is never interrupted — it always finishes speaking before listening.
     *  Disables both server-side VAD interruption and client-side barge-in. */
    var liveDisableInterrupt: Boolean
        get() = prefs.getBoolean(KEY_LIVE_DISABLE_INTERRUPT, false)
        set(value) = prefs.edit().putBoolean(KEY_LIVE_DISABLE_INTERRUPT, value).apply()

    // ── Timeout Settings ─────────────────────────────────────────────────

    /** Gemini Live idle timeout in seconds before auto-disconnect. 0 = use default. */
    var timeoutLiveIdleSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_LIVE_IDLE_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_LIVE_IDLE_SECONDS, value).apply()

    /** Research model HTTP timeout in seconds. 0 = use default (45s). */
    var timeoutResearchSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_RESEARCH_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_RESEARCH_SECONDS, value).apply()

    /** LearnLM model HTTP timeout in seconds. 0 = use default (45s). */
    var timeoutLearnLmSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_LEARNLM_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_LEARNLM_SECONDS, value).apply()

    /** Standard Gemini text/audio model HTTP timeout in seconds. 0 = use default. */
    var timeoutGeminiSeconds: Int
        get() = prefs.getInt(KEY_TIMEOUT_GEMINI_SECONDS, 0)
        set(value) = prefs.edit().putInt(KEY_TIMEOUT_GEMINI_SECONDS, value).apply()

    // ── Accessibility Settings ───────────────────────────────────────────

    /** HUD font scale multiplier (0.5–3.0). 1.0 = normal. */
    var hudFontScale: Float
        get() = prefs.getFloat(KEY_HUD_FONT_SCALE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_HUD_FONT_SCALE, value.coerceIn(0.5f, 3.0f)).apply()

    /** High-contrast mode for HUD text. */
    var hudHighContrast: Boolean
        get() = prefs.getBoolean(KEY_HUD_HIGH_CONTRAST, false)
        set(value) = prefs.edit().putBoolean(KEY_HUD_HIGH_CONTRAST, value).apply()

    /** TTS speech rate multiplier (0.25–4.0). 1.0 = normal. */
    var ttsSpeechRate: Float
        get() = prefs.getFloat(KEY_TTS_SPEECH_RATE, 1.0f)
        set(value) = prefs.edit().putFloat(KEY_TTS_SPEECH_RATE, value.coerceIn(0.25f, 4.0f)).apply()

    /** Auto-read all assistant responses via TTS. */
    var ttsAutoRead: Boolean
        get() = prefs.getBoolean(KEY_TTS_AUTO_READ, true)
        set(value) = prefs.edit().putBoolean(KEY_TTS_AUTO_READ, value).apply()

    /** Enable optional phone-to-glasses GPS bridge from the companion app. */
    var phoneLocationBridgeEnabled: Boolean
        get() = prefs.getBoolean(KEY_PHONE_LOCATION_BRIDGE_ENABLED, false)
        set(value) = prefs.edit().putBoolean(KEY_PHONE_LOCATION_BRIDGE_ENABLED, value).apply()

    /** Latest phone-provided location fix pushed from the companion app. */
    fun getPhoneLocationBridgeContext(): DeviceLocationContext? {
        val raw = prefs.getString(KEY_PHONE_LOCATION_BRIDGE_CONTEXT, "") ?: ""
        if (raw.isBlank()) return null
        return runCatching { gson.fromJson(raw, DeviceLocationContext::class.java) }.getOrNull()
    }

    fun setPhoneLocationBridgeContext(context: DeviceLocationContext?) {
        val editor = prefs.edit()
        if (context == null) editor.remove(KEY_PHONE_LOCATION_BRIDGE_CONTEXT)
        else editor.putString(KEY_PHONE_LOCATION_BRIDGE_CONTEXT, gson.toJson(context))
        editor.apply()
    }

    fun getBookmarks(): List<Bookmark> {
        val raw = prefs.getString(KEY_BOOKMARKS, "[]") ?: "[]"
        return runCatching {
            gson.fromJson<List<Bookmark>>(raw, object : TypeToken<List<Bookmark>>() {}.type)
        }.getOrDefault(emptyList())
    }

    fun addBookmark(bookmark: Bookmark) {
        val updated = getBookmarks().toMutableList().apply { add(bookmark) }
        prefs.edit().putString(KEY_BOOKMARKS, gson.toJson(updated)).apply()
    }

    fun removeBookmark(url: String) {
        val updated = getBookmarks().filterNot { it.url == url }
        prefs.edit().putString(KEY_BOOKMARKS, gson.toJson(updated)).apply()
    }

    fun getAssistantCardHistory(): List<PersistedAssistantCard> {
        val raw = prefs.getString(KEY_ASSISTANT_CARD_HISTORY, "[]") ?: "[]"
        val structured = runCatching {
            gson.fromJson<List<PersistedAssistantCard>>(
                raw,
                object : TypeToken<List<PersistedAssistantCard>>() {}.type
            )
        }.getOrNull()
            ?.mapNotNull { card ->
                val text = card.text.trim()
                if (text.isBlank()) null else card.copy(text = text)
            }
            .orEmpty()
        if (structured.isNotEmpty()) return structured

        // Backward compatibility: migrate legacy string-only history.
        val legacy = runCatching {
            gson.fromJson<List<String>>(raw, object : TypeToken<List<String>>() {}.type)
        }.getOrDefault(emptyList())
            .map { it.trim() }
            .filter { it.isNotBlank() }
        if (legacy.isEmpty()) return emptyList()

        val base = System.currentTimeMillis()
        return legacy.mapIndexed { index, text ->
            PersistedAssistantCard(
                text = text,
                url = null,
                timestampMs = base + index
            )
        }
    }

    fun setAssistantCardHistory(cards: List<PersistedAssistantCard>) {
        prefs.edit().putString(KEY_ASSISTANT_CARD_HISTORY, gson.toJson(cards)).apply()
    }

    companion object {
        private const val KEY_GEMINI_API_KEY = "gemini_api_key"
        private const val KEY_RESEARCH_PROVIDER = "research_provider"
        private const val KEY_RESEARCH_API_KEY = "research_api_key"
        private const val KEY_RESEARCH_MODEL = "research_model"
        private const val KEY_LEARNLM_MODEL = "learnlm_model"
        private const val KEY_CALENDAR_API_KEY = "calendar_api_key"
        private const val KEY_CALENDAR_ID = "calendar_id"
        private const val KEY_OPENCLAW_ENDPOINT = "openclaw_endpoint"
        private const val KEY_BOOKMARKS = "bookmarks"
        private const val KEY_ASSISTANT_CARD_HISTORY = "assistant_card_history"
        private const val KEY_TTS_VOLUME = "tts_volume"
        private const val KEY_TTS_MUTED = "tts_muted"
        private const val KEY_MEDIA_TTS_VOICE = "media_tts_voice"
        private const val KEY_MEDIA_TTS_UNDERLINE = "media_tts_underline"
        private const val KEY_MUSIC_MUTED = "music_muted"
        private const val KEY_WEB_DESKTOP_MODE = "web_desktop_mode"
        private const val KEY_WEB_POINTER_SENSITIVITY = "web_pointer_sensitivity"
        private const val KEY_WEB_FORCE_DARK_MODE = "web_force_dark_mode"
        private const val KEY_BROWSER_SHOW_SYSTEM_INFO = "browser_show_system_info"
        private const val KEY_CUSTOM_SYSTEM_PROMPT = "custom_system_prompt"
        private const val KEY_PERSONALITY = "personality"
        private const val KEY_SPOTIFY_CLIENT_ID = "spotify_client_id"
        private const val KEY_SPOTIFY_CLIENT_SECRET = "spotify_client_secret"
        private const val KEY_GOOGLE_OAUTH_CLIENT_ID = "google_oauth_client_id"
        private const val KEY_GOOGLE_OAUTH_CLIENT_SECRET = "google_oauth_client_secret"
        private const val KEY_GOOGLE_OAUTH_ACCESS_TOKEN = "google_oauth_access_token"
        private const val KEY_GOOGLE_OAUTH_REFRESH_TOKEN = "google_oauth_refresh_token"
        private const val KEY_GOOGLE_OAUTH_TOKEN_EXPIRY_MS = "google_oauth_token_expiry_ms"
        private const val KEY_GOOGLE_MAPS_API_KEY = "google_maps_api_key"
        private const val KEY_GEMINI_MODEL_OVERRIDE = "gemini_model_override"
        private const val KEY_PHONE_LOCATION_BRIDGE_ENABLED = "phone_location_bridge_enabled"
        private const val KEY_PHONE_LOCATION_BRIDGE_CONTEXT = "phone_location_bridge_context"
        private const val KEY_HUD_SHOW_CALENDAR = "hud_show_calendar"
        private const val KEY_HUD_SHOW_TRAFFIC = "hud_show_traffic"
        private const val KEY_HUD_SHOW_NOTIFICATIONS = "hud_show_notifications"
        private const val KEY_HUD_REFRESH_INTERVAL_SECONDS = "hud_refresh_interval_seconds"
        private const val KEY_HUD_SHOW_EVENT_TIME = "hud_show_event_time"
        private const val KEY_ENABLED_CALENDAR_IDS = "enabled_calendar_ids"
        private const val KEY_HUD_SHOW_TASKS = "hud_show_tasks"
        private const val KEY_HUD_SHOW_NEWS = "hud_show_news"
        private const val KEY_TASKS_ITEM_COUNT = "tasks_item_count"
        private const val KEY_NEWS_ITEM_COUNT = "news_item_count"
        private const val KEY_NEWS_REFRESH_INTERVAL_SECONDS = "news_refresh_interval_seconds"
        private const val KEY_CALENDAR_ITEM_COUNTS = "calendar_item_counts"
        private const val KEY_HUD_DISPLAY_ORDER = "hud_display_order"
        private const val KEY_PROMPT_IDENTITY = "prompt_identity"
        private const val KEY_PROMPT_ROUTING_RULES = "prompt_routing_rules"
        private const val KEY_PROMPT_BEHAVIOR = "prompt_behavior"
        private const val KEY_PROMPT_URL_RULES = "prompt_url_rules"
        // Gemini Live Voice & AI
        private const val KEY_LIVE_VOICE_NAME = "live_voice_name"
        private const val KEY_TTS_VOICE_NAME = "tts_voice_name"
        private const val KEY_LIVE_THINKING_LEVEL = "live_thinking_level"
        private const val KEY_LIVE_TEMPERATURE = "live_temperature"
        private const val KEY_LIVE_SESSION_RESUMPTION = "live_session_resumption"
        private const val KEY_LIVE_CONTEXT_COMPRESSION = "live_context_compression"
        private const val KEY_LIVE_COMPRESSION_TOKENS = "live_compression_tokens"
        private const val KEY_LIVE_PROACTIVE_AUDIO = "live_proactive_audio"
        private const val KEY_LIVE_LANGUAGE_CODE = "live_language_code"
        private const val KEY_LIVE_BARGE_IN_SENSITIVITY = "live_barge_in_sensitivity"
        private const val KEY_LIVE_SILENCE_THRESHOLD = "live_silence_threshold"
        private const val KEY_LIVE_DISABLE_INTERRUPT = "live_disable_interrupt"
        // Timeout settings
        private const val KEY_TIMEOUT_LIVE_IDLE_SECONDS = "timeout_live_idle_seconds"
        private const val KEY_TIMEOUT_RESEARCH_SECONDS = "timeout_research_seconds"
        private const val KEY_TIMEOUT_LEARNLM_SECONDS = "timeout_learnlm_seconds"
        private const val KEY_TIMEOUT_GEMINI_SECONDS = "timeout_gemini_seconds"
        // Accessibility
        private const val KEY_HUD_FONT_SCALE = "hud_font_scale"
        private const val KEY_HUD_HIGH_CONTRAST = "hud_high_contrast"
        private const val KEY_TTS_SPEECH_RATE = "tts_speech_rate"
        private const val KEY_TTS_AUTO_READ = "tts_auto_read"
        // OpenClaw
        private const val KEY_OPENCLAW_TOKEN = "openclaw_token"
        private const val KEY_OPENCLAW_SESSION_ID = "openclaw_session_id"
        private const val KEY_OPENCLAW_TIMEOUT_SECONDS = "openclaw_timeout_seconds"
        private const val KEY_OPENCLAW_ENABLED = "openclaw_enabled"
        private const val DEFAULT_OPENCLAW_ENDPOINT = ""

        private const val KEY_OPENCLAW_HEARTBEAT_INTERVAL_SECONDS = "openclaw_heartbeat_interval_seconds"
        private const val KEY_ASSISTANT_DEFAULT_CAMERA = "assistant_default_camera"

        // Battery Saver
        private const val KEY_BATTERY_SAVER_ACTIVE = "battery_saver_active"
        private const val KEY_BATTERY_SAVER_AUTO_THRESHOLD = "battery_saver_auto_threshold"
        private const val KEY_BATTERY_SAVER_ORIG_REFRESH = "battery_saver_orig_refresh"

        // Quick Actions
        private const val KEY_QUICK_ACTIONS_JSON = "quick_actions_json"
        private const val KEY_HOME_ADDRESS = "home_address"
        private const val KEY_WORK_ADDRESS = "work_address"

        // Translation
        private const val KEY_TRANSLATE_DEFAULT_LANGUAGE = "translate_default_language"
        private const val KEY_TRANSLATE_AUTO_MODE = "translate_auto_mode"
    }
}

data class Bookmark(
    val title: String,
    val url: String
)

data class PersistedAssistantCard(
    val text: String,
    val url: String?,
    val timestampMs: Long
)
