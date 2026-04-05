package com.rayneo.visionclaw.core.config

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import com.rayneo.visionclaw.BuildConfig
import com.rayneo.visionclaw.core.network.ActiveNetworkHttp
import com.rayneo.visionclaw.core.network.GoogleAirQualityClient
import com.rayneo.visionclaw.core.network.GoogleCalendarClient
import com.rayneo.visionclaw.core.network.GoogleDirectionsClient
import com.rayneo.visionclaw.core.network.GoogleOAuthManager
import com.rayneo.visionclaw.core.network.OpenClawPairingClient
import com.rayneo.visionclaw.core.network.GooglePlacesClient
import com.rayneo.visionclaw.core.network.GoogleTasksClient
import com.rayneo.visionclaw.core.network.ResearchRouter
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.storage.AppPreferences
import fi.iki.elonen.NanoHTTPD
import kotlinx.coroutines.runBlocking
import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.Inet4Address
import java.net.NetworkInterface
import java.math.BigInteger
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.UUID
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext

/**
 * Lightweight HTTPS server that serves the AITap companion configuration pages.
 * Uses a self-signed TLS certificate generated via Android KeyStore to enable
 * secure context in browsers (required for Geolocation API / Phone GPS Bridge).
 *
 * Open from any phone/computer on the same WiFi:
 *   https://<glasses-ip>:19110
 * (Accept the self-signed certificate warning on first visit.)
 *
 * Pages:
 *   GET  /                → Setup page (API keys, model, personality)
 *   GET  /browser          → Browser settings + login sync
 *   GET  /dashboard        → Dashboard editor (categories & links)
 *   GET  /radio            → TapRadio station manager
 *
 * API:
 *   GET  /api/config      → all config as JSON
 *   POST /api/config      → save config from JSON body
 *   GET  /api/dashboard   → dashboard links/groups as JSON
 *   POST /api/dashboard   → save dashboard links/groups
 *   GET  /api/radio       → TapRadio stations as JSON array
 *   POST /api/radio       → save TapRadio stations
 */
class CompanionServer(
    private val context: Context,
    port: Int = 19110,
    var oauthManager: GoogleOAuthManager? = null,
    /** Provides the latest device GPS location for the Location test button. */
    var locationProvider: (() -> com.rayneo.visionclaw.core.model.DeviceLocationContext?)? = null,
    var calendarSummaryProvider: (() -> String)? = null,
    var tasksSummaryProvider: (() -> String)? = null,
    var newsSummaryProvider: (() -> String)? = null,
    var airQualityTextProvider: (() -> String?)? = null,
    var airQualityValueProvider: (() -> Int?)? = null,
    var phoneLocationConsumer: ((DeviceLocationContext?) -> Unit)? = null,
    /** Provides the latest camera frame as raw JPEG bytes for the /api/camera/frame endpoint. */
    var cameraFrameProvider: (() -> ByteArray?)? = null
) : NanoHTTPD(port) {

    companion object {
        private const val TAG = "CompanionServer"
        private const val PREFS_NAME = "visionclaw_prefs"
        private const val DASHBOARD_PREFS_KEY = "dashboard_data"
        private const val SESSION_TOKEN_KEY = "companion_session_token"

        /** JS bridge for the Setup page (index.html). */
        private const val SETUP_BRIDGE_JS = """
// REST API bridge (replaces Android JavascriptInterface for phone/computer access)
const AiTapBridge = {
  _cache: {},
  _dirty: {},
  _isRestShim: true,
  _token: '__SESSION_TOKEN__',
  _headers() { return {'Content-Type': 'application/json', 'X-Session-Token': this._token}; },
  async _loadAll() {
    try {
      const r = await fetch('/api/config', {headers: {'X-Session-Token': this._token}});
      if (r.ok) this._cache = await r.json();
    } catch(e) { console.error('Load failed:', e); }
  },
  getString(key) { return this._cache[key] == null ? '' : String(this._cache[key]); },
  putString(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putFloat(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putBoolean(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putInt(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  async applyConfig() {
    try {
      const payload = {};
      for (const k of Object.keys(this._dirty)) { payload[k] = this._cache[k]; }
      const r = await fetch('/api/config', {
        method: 'POST',
        headers: this._headers(),
        body: JSON.stringify(payload)
      });
      if (r.ok) this._dirty = {};
      return r.ok;
    } catch(e) { console.error('Save failed:', e); return false; }
  }
};

"""

        /** JS bridge for the Browser page (browser.html). */
        private const val BROWSER_BRIDGE_JS = """
// REST API bridge for browser settings page
const AiTapBridge = {
  _cache: {},
  _dirty: {},
  _isRestShim: true,
  _token: '__SESSION_TOKEN__',
  _headers() { return {'Content-Type': 'application/json', 'X-Session-Token': this._token}; },
  async _loadAll() {
    try {
      const r = await fetch('/api/config', {headers: {'X-Session-Token': this._token}});
      if (r.ok) this._cache = await r.json();
    } catch(e) { console.error('Load failed:', e); }
  },
  getString(key) { return this._cache[key] == null ? '' : String(this._cache[key]); },
  putString(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putFloat(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putBoolean(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  putInt(key, v) { this._cache[key] = v; this._dirty[key] = true; },
  async applyConfig() {
    try {
      const payload = {};
      for (const k of Object.keys(this._dirty)) { payload[k] = this._cache[k]; }
      const r = await fetch('/api/config', {
        method: 'POST',
        headers: this._headers(),
        body: JSON.stringify(payload)
      });
      if (r.ok) this._dirty = {};
      return r.ok;
    } catch(e) { console.error('Save failed:', e); return false; }
  }
};

function hasBridge() { return true; }

async function loadAll() {
  await AiTapBridge._loadAll();
  for (const [id, cfg] of Object.entries(BROWSER_FIELDS)) {
    const el = document.getElementById(id);
    try { el.value = AiTapBridge.getString(cfg.key) || ''; } catch(e) {}
  }
  try {
    const raw = AiTapBridge.getString('browser_cookies');
    if (raw) {
      const parsed = JSON.parse(raw);
      cookieEntries = parsed.map((e, i) => ({ ...e, id: i }));
      nextCookieId = cookieEntries.length;
    }
  } catch(e) { console.error('Cookie load error:', e); }
  renderCookieList();
  showStatus('Settings loaded.', 'ok');
}

async function saveAll() {
  for (const [id, cfg] of Object.entries(BROWSER_FIELDS)) {
    const el = document.getElementById(id);
    const v = el.value.trim();
    if (cfg.type === 'float') AiTapBridge.putFloat(cfg.key, parseFloat(v) || 1.0);
    else if (cfg.type === 'bool') AiTapBridge.putBoolean(cfg.key, v === 'true');
    else AiTapBridge.putString(cfg.key, v);
  }
  document.querySelectorAll('.cookie-entry').forEach(el => {
    const id = parseInt(el.dataset.id);
    const entry = cookieEntries.find(e => e.id === id);
    if (entry) {
      entry.domain = el.querySelector('input[type="text"]').value.trim();
      entry.cookies = el.querySelector('textarea').value.trim();
    }
  });
  const cookieData = cookieEntries
    .filter(e => e.domain)
    .map(e => ({ domain: e.domain, cookies: e.cookies, label: e.label || '' }));
  AiTapBridge.putString('browser_cookies', JSON.stringify(cookieData));
  const ok = await AiTapBridge.applyConfig();
  showStatus(ok ? 'Settings saved! Cookies will sync on next browser launch.' : 'Failed to save.', ok ? 'ok' : 'err');
}

document.addEventListener('DOMContentLoaded', loadAll);
"""
    }

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val appPreferences = AppPreferences(context)

    /** Whether HTTPS was successfully configured. When true, the server serves
     *  HTTPS on port 19110 and the Geolocation API works (secure context). */
    var httpsEnabled: Boolean = false
        private set

    init {
        setupHttps()
    }

    /**
     * Generates a self-signed TLS certificate and configures NanoHTTPD to serve HTTPS.
     * Must be called before start().
     *
     * Uses a standard PKCS12 keystore (NOT Android KeyStore) so the private key is
     * accessible to NanoHTTPD's SSLServerSocketFactory for TLS handshakes.
     * The keystore is persisted in the app's private files dir so the certificate
     * stays stable across restarts (users only accept the cert warning once).
     *
     * On success: port 19110 serves HTTPS, `window.isSecureContext === true` in browsers,
     *   enabling the Geolocation API for the Phone GPS Bridge.
     * On failure: server falls back to plain HTTP (GPS bridge won't work but everything else does).
     */
    private fun setupHttps() {
        try {
            val ksFile = File(context.filesDir, "companion_tls.p12")
            val password = "tapinsight-tls".toCharArray()

            // Migration: delete old keystore if cert uses RSA (too slow for TLS on
            // the X3 Pro — causes audio stutters) or has validity > 398 days.
            if (ksFile.exists()) {
                try {
                    val tmpKs = KeyStore.getInstance("PKCS12")
                    ksFile.inputStream().use { tmpKs.load(it, password) }
                    val cert = tmpKs.getCertificate("companion") as? X509Certificate
                    if (cert != null) {
                        val validityDays = (cert.notAfter.time - cert.notBefore.time) / (24 * 3600 * 1000L)
                        val isRsa = cert.publicKey.algorithm == "RSA"
                        if (validityDays > 398 || isRsa) {
                            ksFile.delete()
                            Log.i(TAG, "Deleted old TLS keystore (RSA=$isRsa, validity=${validityDays}d)")
                        }
                    }
                } catch (e: Exception) {
                    ksFile.delete()
                    Log.w(TAG, "Deleted unreadable TLS keystore, will regenerate", e)
                }
            }

            val ks: KeyStore
            if (ksFile.exists()) {
                // Load existing keystore (validity already verified above)
                ks = KeyStore.getInstance("PKCS12")
                ksFile.inputStream().use { ks.load(it, password) }
                Log.d(TAG, "Loaded existing TLS keystore from ${ksFile.name}")
            } else {
                // Generate new EC key pair (P-256) — ECDSA TLS handshakes are 10-20x
                // faster than RSA 2048, critical for avoiding audio stutters when the
                // phone companion page sends frequent GPS HTTPS updates.
                val kpg = KeyPairGenerator.getInstance("EC")
                kpg.initialize(java.security.spec.ECGenParameterSpec("secp256r1"))
                val keyPair = kpg.generateKeyPair()

                // Build self-signed X.509 certificate via DER encoding
                val cert = buildSelfSignedCertificate(keyPair)

                // Store in PKCS12 keystore
                ks = KeyStore.getInstance("PKCS12")
                ks.load(null, password)
                ks.setKeyEntry("companion", keyPair.private, password, arrayOf(cert))

                // Persist to disk so cert is stable across restarts
                ksFile.outputStream().use { ks.store(it, password) }
                Log.i(TAG, "Generated new self-signed TLS certificate for companion HTTPS")
            }

            val kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
            kmf.init(ks, password)

            val sslContext = SSLContext.getInstance("TLS")
            sslContext.init(kmf.keyManagers, null, null)

            makeSecure(sslContext.serverSocketFactory, null)
            httpsEnabled = true
            Log.i(TAG, "HTTPS enabled on companion server (port 19110)")
        } catch (e: Exception) {
            Log.e(TAG, "HTTPS setup failed — falling back to HTTP. GPS bridge will not work.", e)
            httpsEnabled = false
        }
    }

    // ── Self-signed certificate generation via raw DER encoding ──────────

    /**
     * Builds a minimal self-signed X.509v3 certificate using only standard Java APIs
     * (no BouncyCastle, no Android KeyStore). The certificate is valid for 397 days
     * (under the 398-day browser maximum) with CN=TapInsight Companion, signed with SHA256withECDSA.
     * Uses ECDSA P-256 instead of RSA for ~10-20x faster TLS handshakes.
     */
    private fun buildSelfSignedCertificate(keyPair: java.security.KeyPair): X509Certificate {
        // SHA256withECDSA OID: 1.2.840.10045.4.3.2
        val sha256WithEcdsaOid = byteArrayOf(
            0x2A, 0x86.toByte(), 0x48, 0xCE.toByte(), 0x3D, 0x04, 0x03, 0x02
        )
        // ECDSA AlgorithmIdentifier has no parameters (unlike RSA which has NULL)
        val signAlgId = derSequence(derOid(sha256WithEcdsaOid))

        // Subject/Issuer: CN=TapInsight Companion
        val cnOid = byteArrayOf(0x55, 0x04, 0x03) // OID 2.5.4.3
        val cnAttr = derSequence(derOid(cnOid), derUtf8String("TapInsight Companion"))
        val rdnSet = derSet(cnAttr)
        val name = derSequence(rdnSet)

        // Validity: now → +397 days (browsers reject certs valid > 398 days)
        val now = Date()
        val expiry = Date(System.currentTimeMillis() + 397L * 24 * 3600 * 1000)
        val validity = derSequence(derUtcTime(now), derUtcTime(expiry))

        // Version: v3 (integer value 2)
        val version = derExplicit(0, derInteger(BigInteger.valueOf(2)))

        // Serial number: current timestamp
        val serial = derInteger(BigInteger.valueOf(System.currentTimeMillis()))

        // SubjectPublicKeyInfo: already DER-encoded by Java
        val spki = keyPair.public.encoded

        // Assemble TBSCertificate
        val tbsCert = derSequence(version, serial, signAlgId, name, validity, name, spki)

        // Sign the TBS certificate
        val signer = Signature.getInstance("SHA256withECDSA")
        signer.initSign(keyPair.private)
        signer.update(tbsCert)
        val signatureBytes = signer.sign()

        // Assemble full Certificate
        val certDer = derSequence(tbsCert, signAlgId, derBitString(signatureBytes))

        // Parse DER → X509Certificate
        val cf = CertificateFactory.getInstance("X.509")
        return cf.generateCertificate(ByteArrayInputStream(certDer)) as X509Certificate
    }

    // ── DER encoding primitives ──────────────────────────────────────────

    private fun derLength(len: Int): ByteArray = when {
        len < 0x80 -> byteArrayOf(len.toByte())
        len < 0x100 -> byteArrayOf(0x81.toByte(), len.toByte())
        else -> byteArrayOf(0x82.toByte(), (len shr 8).toByte(), len.toByte())
    }

    private fun derTag(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf(tag.toByte()) + derLength(content.size) + content

    private fun derSequence(vararg elements: ByteArray): ByteArray {
        val body = elements.fold(ByteArray(0)) { acc, e -> acc + e }
        return derTag(0x30, body)
    }

    private fun derSet(vararg elements: ByteArray): ByteArray {
        val body = elements.fold(ByteArray(0)) { acc, e -> acc + e }
        return derTag(0x31, body)
    }

    private fun derInteger(value: BigInteger): ByteArray =
        derTag(0x02, value.toByteArray())

    private fun derBitString(bytes: ByteArray): ByteArray =
        derTag(0x03, byteArrayOf(0x00) + bytes) // 0 unused bits

    private fun derOid(oid: ByteArray): ByteArray =
        derTag(0x06, oid)

    private fun derNull(): ByteArray = byteArrayOf(0x05, 0x00)

    private fun derUtf8String(s: String): ByteArray =
        derTag(0x0C, s.toByteArray(Charsets.UTF_8))

    private fun derExplicit(tag: Int, content: ByteArray): ByteArray =
        byteArrayOf((0xA0 or tag).toByte()) + derLength(content.size) + content

    private fun derUtcTime(date: Date): ByteArray {
        val sdf = SimpleDateFormat("yyMMddHHmmss'Z'", Locale.US)
        sdf.timeZone = TimeZone.getTimeZone("UTC")
        return derTag(0x17, sdf.format(date).toByteArray(Charsets.US_ASCII))
    }

    /** Session token for authenticating companion page API requests. */
    val sessionToken: String
        get() {
            val existing = prefs.getString(SESSION_TOKEN_KEY, null)
            if (!existing.isNullOrBlank()) return existing
            val token = UUID.randomUUID().toString().replace("-", "").take(16)
            prefs.edit().putString(SESSION_TOKEN_KEY, token).commit()
            return token
        }

    /** Validates the session token on API requests. HTML pages are served without auth
     *  (they embed the token as a cookie/header for subsequent API calls). */
    private fun isAuthorizedApiRequest(session: IHTTPSession): Boolean {
        // Check Authorization header first: "Bearer <token>"
        val authHeader = session.headers?.get("authorization") ?: ""
        if (authHeader.equals("Bearer $sessionToken", ignoreCase = true)) return true
        // Check X-Session-Token header
        val tokenHeader = session.headers?.get("x-session-token") ?: ""
        if (tokenHeader == sessionToken) return true
        // Check cookie for same-origin companion requests after page reloads
        val cookieHeader = session.headers?.get("cookie") ?: ""
        val cookieToken = cookieHeader
            .split(';')
            .map { it.trim() }
            .firstOrNull { it.startsWith("companion_session_token=") }
            ?.substringAfter('=', "")
            ?.trim()
            .orEmpty()
        if (cookieToken == sessionToken) return true
        // Check query parameter for simple GET requests
        val queryToken = session.parms?.get("token") ?: ""
        if (queryToken == sessionToken) return true
        return false
    }

    /** Add CORS and security headers to a response. */
    private fun addSecurityHeaders(response: Response): Response {
        response.addHeader("Access-Control-Allow-Origin", "*")
        response.addHeader("Access-Control-Allow-Methods", "GET, POST, OPTIONS")
        response.addHeader("Access-Control-Allow-Headers", "Content-Type, Authorization, X-Session-Token")
        response.addHeader("X-Content-Type-Options", "nosniff")
        response.addHeader("Cache-Control", "no-store")
        response.addHeader("Set-Cookie", "companion_session_token=$sessionToken; Path=/; SameSite=Lax")
        return response
    }

    /** Known boolean config keys and their defaults (prevents returning "" for unset booleans). */
    private val booleanKeyDefaults = mapOf(
        "tts_muted" to false,
        "web_desktop_mode" to false,
        "web_force_dark_mode" to true,
        "browser_show_system_info" to true,
        "hud_show_calendar" to true,
        "hud_show_traffic" to true,
        "hud_show_notifications" to true,
        "hud_show_event_time" to true,
        "hud_show_tasks" to true,
        "hud_show_news" to true,
        "phone_location_bridge_enabled" to false,
        // Live AI
        "live_session_resumption" to true,
        "live_context_compression" to false,
        "live_proactive_audio" to false,
        "live_disable_interrupt" to false,
        // Accessibility
        "hud_high_contrast" to false,
        "tts_auto_read" to true,
        // OpenClaw
        "openclaw_enabled" to false,
        // Assistant
        "assistant_default_camera" to false,
        // Translation
        "translate_auto_mode" to false
    )

    /** Known integer config keys and their defaults. */
    private val intKeyDefaults = mapOf(
        "hud_refresh_interval_seconds" to 60,
        "tasks_item_count" to 5,
        "news_item_count" to 3,
        "news_refresh_interval_seconds" to 600,
        // Timeouts
        "timeout_live_idle_seconds" to 0,
        "timeout_gemini_seconds" to 0,
        "timeout_research_seconds" to 0,
        "timeout_learnlm_seconds" to 0,
        // Live AI
        "live_compression_tokens" to 0,
        "live_silence_threshold" to 600,
        // OpenClaw
        "openclaw_timeout_seconds" to 0,
        "openclaw_heartbeat_interval_seconds" to 20,
        // Battery Saver
        "battery_saver_auto_threshold" to 20
    )

    /** Known float config keys and their defaults. */
    private val floatKeyDefaults = mapOf(
        "tts_volume" to 0.8f,
        "web_pointer_sensitivity" to 1.0f,
        // Live AI
        "live_temperature" to -1f,
        "live_barge_in_sensitivity" to 1.0f,
        // Accessibility
        "hud_font_scale" to 0f,
        "tts_speech_rate" to 0f
    )

    /** Config keys the companion pages can read/write. */
    private val allowedKeys = setOf(
        // Setup page keys
        "gemini_api_key",
        "gemini_model_override",
        "research_provider",
        "research_api_key",
        "research_model",
        "learnlm_model",
        "calendar_api_key",
        "calendar_id",
        "google_maps_api_key",
        "phone_location_bridge_enabled",
        "google_oauth_client_id",
        "google_oauth_client_secret",
        "spotify_client_id",
        "spotify_client_secret",
        "personality",
        "custom_system_prompt",
        "tts_volume",
        "tts_muted",
        // Browser page keys
        "web_desktop_mode",
        "web_force_dark_mode",
        "web_pointer_sensitivity",
        "browser_show_system_info",
        "browser_cookies",
        // HUD display keys
        "hud_show_calendar",
        "hud_show_traffic",
        "hud_show_notifications",
        "hud_refresh_interval_seconds",
        "hud_show_event_time",
        "enabled_calendar_ids",
        "hud_show_tasks",
        "hud_show_news",
        "tasks_item_count",
        "news_item_count",
        "news_refresh_interval_seconds",
        "calendar_item_counts",
        "hud_display_order",
        "prompt_identity",
        "prompt_routing_rules",
        "prompt_behavior",
        "prompt_url_rules",
        // Gemini Live voice & AI settings
        "live_voice_name",
        "tts_voice_name",
        "cloud_tts_api_key",
        "cloud_tts_voice_name",
        "cloud_tts_language",
        "live_thinking_level",
        "live_temperature",
        "live_session_resumption",
        "live_context_compression",
        "live_compression_tokens",
        "live_proactive_audio",
        "live_language_code",
        "live_barge_in_sensitivity",
        "live_silence_threshold",
        "live_disable_interrupt",
        // Timeout settings
        "timeout_live_idle_seconds",
        "timeout_research_seconds",
        "timeout_learnlm_seconds",
        "timeout_gemini_seconds",
        // Accessibility settings
        "hud_font_scale",
        "hud_high_contrast",
        "tts_speech_rate",
        "tts_auto_read",
        // Translation
        "translate_default_language",
        "translate_auto_mode",
        // Battery Saver
        "battery_saver_auto_threshold",
        // Quick Actions
        "home_address",
        "work_address",
        "quick_actions_json",
        // OpenClaw integration
        "openclaw_endpoint",
        "openclaw_token",
        "openclaw_session_id",
        "openclaw_timeout_seconds",
        "openclaw_enabled",
        "openclaw_heartbeat_interval_seconds",
        // Assistant
        "assistant_default_camera"
    )

    /** Keys readable via /api/oauth/status (no secrets exposed). */
    private val oauthStatusKeys = setOf(
        "google_oauth_client_id",
        "google_oauth_token_expiry_ms"
    )

    override fun serve(session: IHTTPSession): Response {
        val uri = session.uri ?: "/"
        val method = session.method

        // Handle CORS preflight
        if (method == Method.OPTIONS) {
            return addSecurityHeaders(
                newFixedLengthResponse(Response.Status.OK, "text/plain", "")
            )
        }

        // API endpoints require authentication (HTML pages do not — they embed the token)
        val isApiRequest = uri.startsWith("/api/")
        if (isApiRequest && !isAuthorizedApiRequest(session)) {
            Log.w(TAG, "Unauthorized API request to $uri from ${session.remoteIpAddress}")
            return addSecurityHeaders(
                newFixedLengthResponse(
                    Response.Status.UNAUTHORIZED,
                    "application/json",
                    """{"error":"Unauthorized. Include header 'X-Session-Token: <token>' or query param '?token=<token>'."}"""
                )
            )
        }

        return try {
            val response = when {
                uri == "/" || uri == "/index.html" -> serveAssetPage("companion/index.html", SETUP_BRIDGE_JS)
                uri == "/browser" || uri == "/browser.html" -> serveAssetPage("companion/browser.html", BROWSER_BRIDGE_JS)
                uri == "/dashboard" || uri == "/dashboard.html" -> serveRawAsset("companion/dashboard.html")
                uri == "/radio" || uri == "/radio.html" -> serveRawAsset("companion/radio.html")
                uri == "/openclaw" || uri == "/openclaw.html" -> serveRawAsset("companion/openclaw.html")
                uri == "/api/radio" && method == Method.GET -> serveRadioStations()
                uri == "/api/radio" && method == Method.POST -> saveRadioStations(session)
                uri == "/api/config" && method == Method.GET -> serveConfig()
                uri == "/api/config" && method == Method.POST -> saveConfig(session)
                uri == "/api/dashboard" && method == Method.GET -> serveDashboard()
                uri == "/api/dashboard" && method == Method.POST -> saveDashboard(session)
                uri == "/api/phone-location/status" && method == Method.GET -> servePhoneLocationBridgeStatus()
                uri == "/api/phone-location" && method == Method.POST -> savePhoneLocationBridge(session)
                uri == "/oauth/callback" && method == Method.GET -> handleOAuthCallback(session)
                uri == "/api/oauth/exchange" && method == Method.POST -> handleOAuthExchange(session)
                uri == "/api/oauth/status" && method == Method.GET -> serveOAuthStatus()
                uri == "/api/calendars" && method == Method.GET -> fetchCalendarList()
                uri == "/api/verify/calendar" && method == Method.GET -> verifyCalendar()
                uri == "/api/verify/directions" && method == Method.GET -> verifyDirections()
                uri == "/api/verify/tasks" && method == Method.GET -> verifyTasks()
                uri == "/api/verify/places" && method == Method.GET -> verifyPlaces()
                uri == "/api/verify/location" && method == Method.GET -> verifyLocation()
                uri == "/api/verify/traffic" && method == Method.GET -> verifyTraffic()
                uri == "/api/verify/air_quality" && method == Method.GET -> verifyAirQuality()
                uri == "/api/verify/research" && method == Method.GET -> verifyResearch()
                uri == "/api/verify/openclaw" && method == Method.POST -> verifyOpenClaw(session)
                uri == "/api/openclaw/pair" && method == Method.POST -> pairOpenClaw(session)
                uri == "/api/camera/frame" && method == Method.GET -> serveCameraFrame()
                uri == "/api/relay/status" && method == Method.GET -> proxyRelayRequest("/status", "application/json")
                uri == "/api/relay/latest" && method == Method.GET -> proxyRelayRequest("/latest", "image/jpeg")
                uri.startsWith("/api/relay/media/") && method == Method.GET -> proxyRelayMedia(uri)
                uri == "/api/hud_state" && method == Method.GET -> serveHudState()
                uri == "/api/server-info" && method == Method.GET -> serveServerInfo(session)
                else -> newFixedLengthResponse(Response.Status.NOT_FOUND, "text/plain", "Not found")
            }
            addSecurityHeaders(response)
        } catch (t: Throwable) {
            Log.e(TAG, "Server error: ${t.message}", t)
            addSecurityHeaders(
                newFixedLengthResponse(Response.Status.INTERNAL_ERROR, "text/plain", "Error: ${t.message ?: t.javaClass.simpleName}")
            )
        }
    }

    private fun serveAssetPage(assetPath: String, bridgeJs: String): Response {
        val html = context.assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }

        // Inject session token into bridge JS
        val tokenizedBridgeJs = bridgeJs.replace("__SESSION_TOKEN__", sessionToken)

        // The setup page contains its own verified save/load UX. Only append the
        // REST shim there so we do not overwrite page-local handlers again.
        val patchedHtml = when {
            assetPath == "companion/index.html" && html.contains("</body>") ->
                html.replace("</body>", "<script>\n$tokenizedBridgeJs\n</script>\n</body>")
            assetPath == "companion/index.html" ->
                html + "\n<script>\n$tokenizedBridgeJs\n</script>\n"
            else -> {
                val browserMarker =
                    "// Auto-load on page ready\n" +
                        "document.addEventListener('DOMContentLoaded', loadAll);"
                when {
                    html.contains(browserMarker) -> html.replace(browserMarker, tokenizedBridgeJs)
                    html.contains("</body>") -> html.replace("</body>", "<script>\n$tokenizedBridgeJs\n</script>\n</body>")
                    else -> html + "\n<script>\n$tokenizedBridgeJs\n</script>\n"
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", patchedHtml)
    }

    private fun serveConfig(): Response {
        val json = JSONObject()
        for (key in allowedKeys) {
            when (val value = prefs.all[key]) {
                is String -> json.put(key, value)
                is Float -> json.put(key, value.toDouble())
                is Boolean -> json.put(key, value)
                is Int -> json.put(key, value)
                is Long -> json.put(key, value)
                else -> {
                    // Type-aware defaults for unset keys
                    when {
                        booleanKeyDefaults.containsKey(key) -> json.put(key, booleanKeyDefaults[key]!!)
                        intKeyDefaults.containsKey(key) -> json.put(key, intKeyDefaults[key]!!)
                        floatKeyDefaults.containsKey(key) -> json.put(key, floatKeyDefaults[key]!!.toDouble())
                        else -> json.put(key, prefs.getString(key, "") ?: "")
                    }
                }
            }
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    private fun saveConfig(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{"error":"Empty body"}"""
            )
        }

        val beforeSnapshot = HashMap(prefs.all)
        val json = JSONObject(postData)
        val requested = JSONObject()
        val editor = prefs.edit()
        for (key in allowedKeys) {
            if (!json.has(key)) continue
            when {
                floatKeyDefaults.containsKey(key) -> {
                    val value = json.optDouble(key, floatKeyDefaults[key]!!.toDouble()).toFloat()
                    editor.putFloat(key, value)
                    requested.put(key, value.toDouble())
                }
                booleanKeyDefaults.containsKey(key) -> {
                    val value = json.optBoolean(key, booleanKeyDefaults[key]!!)
                    editor.putBoolean(key, value)
                    requested.put(key, value)
                }
                key == "hud_refresh_interval_seconds" -> {
                    val value = json.optInt(key, 60).coerceIn(5, 300)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                key == "tasks_item_count" -> {
                    val value = json.optInt(key, 5).coerceIn(1, 10)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                key == "news_item_count" -> {
                    val value = json.optInt(key, 3).coerceIn(1, 10)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                key == "news_refresh_interval_seconds" -> {
                    val value = json.optInt(key, 600).coerceIn(60, 3600)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                intKeyDefaults.containsKey(key) -> {
                    val value = json.optInt(key, intKeyDefaults[key]!!)
                    editor.putInt(key, value)
                    requested.put(key, value)
                }
                else -> {
                    val value = json.optString(key, "")
                    editor.putString(key, value)
                    requested.put(key, value)
                }
            }
        }

        val committed = editor.commit()
        if (!committed) {
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Settings could not be persisted"}"""
            )
        }

        val saved = JSONObject()
        val mismatches = JSONArray()
        var changedCount = 0
        for (key in allowedKeys) {
            if (!requested.has(key)) continue
            val storedValue: Any? = when (val value = prefs.all[key]) {
                is String -> value
                is Float -> value.toDouble()
                is Boolean -> value
                is Int -> value
                is Long -> value
                else -> when {
                    booleanKeyDefaults.containsKey(key) -> booleanKeyDefaults[key]
                    intKeyDefaults.containsKey(key) -> intKeyDefaults[key]
                    floatKeyDefaults.containsKey(key) -> floatKeyDefaults[key]?.toDouble()
                    else -> prefs.getString(key, "") ?: ""
                }
            }
            if (storedValue == null) saved.put(key, JSONObject.NULL) else saved.put(key, storedValue)

            val requestedValue = requested.get(key)
            val matches = when {
                requestedValue is Number && storedValue is Number ->
                    kotlin.math.abs(requestedValue.toDouble() - storedValue.toDouble()) < 0.0001
                requestedValue == JSONObject.NULL && storedValue == null -> true
                else -> requestedValue.toString() == (storedValue?.toString() ?: "")
            }
            if (!matches) {
                mismatches.put(
                    JSONObject()
                        .put("key", key)
                        .put("requested", requestedValue)
                        .put("saved", storedValue ?: JSONObject.NULL)
                )
            }

            val beforeValue = beforeSnapshot[key]
            val changed = when {
                beforeValue is Number && storedValue is Number ->
                    kotlin.math.abs(beforeValue.toDouble() - storedValue.toDouble()) >= 0.0001
                else -> (beforeValue?.toString() ?: "") != (storedValue?.toString() ?: "")
            }
            if (changed) changedCount++
        }

        val response = JSONObject()
            .put("status", if (mismatches.length() == 0) "saved" else "verification_failed")
            .put("verified", mismatches.length() == 0)
            .put("restartRecommended", true)
            .put("changedCount", changedCount)
            .put("saved", saved)
            .put("mismatches", mismatches)
        Log.d(TAG, "Config saved from companion app; verified=${mismatches.length() == 0}; changed=$changedCount")
        return newFixedLengthResponse(Response.Status.OK, "application/json", response.toString())
    }

    /** Serve an asset page with minimal auth token injection (for dashboard, radio, etc.).
     *  These pages handle their own bridge logic but need the session token for API calls. */
    private fun serveRawAsset(assetPath: String): Response {
        var html = context.assets.open(assetPath)
            .bufferedReader(Charsets.UTF_8)
            .use { it.readText() }
        // Inject a minimal script that patches fetch to include the session token
        val tokenScript = """<script>
(function(){
  var _token = '${sessionToken}';
  var _origFetch = window.fetch;
  window.fetch = function(url, opts) {
    opts = opts || {};
    if (typeof url === 'string' && url.indexOf('/api/') !== -1) {
      opts.headers = opts.headers || {};
      if (opts.headers instanceof Headers) {
        opts.headers.set('X-Session-Token', _token);
      } else {
        opts.headers['X-Session-Token'] = _token;
      }
    }
    return _origFetch.call(this, url, opts);
  };
})();
</script>"""
        html = if (html.contains("<head>")) {
            html.replace("<head>", "<head>\n$tokenScript")
        } else if (html.contains("<body>")) {
            html.replace("<body>", "$tokenScript\n<body>")
        } else {
            tokenScript + "\n" + html
        }
        return newFixedLengthResponse(Response.Status.OK, "text/html", html)
    }

    /** Return the saved dashboard JSON (apps + groups). */
    private fun serveDashboard(): Response {
        val raw = prefs.getString(DASHBOARD_PREFS_KEY, null) ?: "{}"
        return newFixedLengthResponse(Response.Status.OK, "application/json", raw)
    }

    /** Save the full dashboard JSON (apps + groups). */
    private fun saveDashboard(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Empty body\"}"""
            )
        }
        // Validate it's valid JSON
        try {
            JSONObject(postData)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Invalid JSON\"}"""
            )
        }
        prefs.edit().putString(DASHBOARD_PREFS_KEY, postData).apply()
        Log.d(TAG, "Dashboard saved from companion app (${postData.length} chars)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    // ── TapRadio ───────────────────────────────────────────────────────

    private val RADIO_PREFS_KEY = "tapradio_stations"

    /** Return saved TapRadio stations JSON array. */
    private fun serveRadioStations(): Response {
        val raw = prefs.getString(RADIO_PREFS_KEY, null) ?: "[]"
        return newFixedLengthResponse(Response.Status.OK, "application/json", raw)
    }

    /** Save TapRadio stations JSON array from the companion editor. */
    private fun saveRadioStations(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Empty body\"}"""
            )
        }
        prefs.edit().putString(RADIO_PREFS_KEY, postData).apply()
        Log.d(TAG, "TapRadio stations saved (${postData.length} chars)")
        return newFixedLengthResponse(Response.Status.OK, "application/json", """{"status":"saved"}""")
    }

    private fun servePhoneLocationBridgeStatus(): Response {
        val loc = appPreferences.getPhoneLocationBridgeContext()
        val ageSeconds = loc?.let { ((System.currentTimeMillis() - it.timestampMs).coerceAtLeast(0L) / 1000L) }
        return jsonResponse(JSONObject().apply {
            put("enabled", appPreferences.phoneLocationBridgeEnabled)
            put("has_location", loc != null)
            put("provider", loc?.provider ?: JSONObject.NULL)
            put("latitude", loc?.latitude ?: JSONObject.NULL)
            put("longitude", loc?.longitude ?: JSONObject.NULL)
            put("accuracy_meters", loc?.accuracyMeters ?: JSONObject.NULL)
            put("timestamp_ms", loc?.timestampMs ?: JSONObject.NULL)
            put("age_seconds", ageSeconds ?: JSONObject.NULL)
        })
    }

    private fun savePhoneLocationBridge(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"Empty body\"}"
            )
        }
        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"Invalid JSON\"}"
            )
        }

        if (json.optBoolean("clear", false)) {
            appPreferences.setPhoneLocationBridgeContext(null)
            phoneLocationConsumer?.invoke(null)
            return jsonResponse(JSONObject().apply {
                put("status", "cleared")
                put("enabled", appPreferences.phoneLocationBridgeEnabled)
            })
        }

        if (!appPreferences.phoneLocationBridgeEnabled) {
            return jsonResponse(JSONObject().apply {
                put("status", "disabled")
                put("message", "Phone GPS bridge is off in companion settings.")
            })
        }

        if (!json.has("latitude") || !json.has("longitude")) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"latitude and longitude are required\"}"
            )
        }

        val latitude = json.optDouble("latitude", Double.NaN)
        val longitude = json.optDouble("longitude", Double.NaN)
        if (!latitude.isFinite() || !longitude.isFinite() ||
            latitude !in -90.0..90.0 || longitude !in -180.0..180.0
        ) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST,
                "application/json",
                "{\"error\":\"invalid latitude or longitude\"}"
            )
        }

        val accuracyMeters =
            if (json.has("accuracy_meters") && !json.isNull("accuracy_meters")) {
                json.optDouble("accuracy_meters", Double.NaN)
                    .takeIf { it.isFinite() && it >= 0.0 && it <= 100_000.0 }
                    ?.toFloat()
            } else {
                null
            }
        val altitudeMeters =
            if (json.has("altitude_meters") && !json.isNull("altitude_meters")) {
                json.optDouble("altitude_meters", Double.NaN)
                    .takeIf { it.isFinite() && it in -20_000.0..100_000.0 }
            } else {
                null
            }
        val speedMps =
            if (json.has("speed_mps") && !json.isNull("speed_mps")) {
                json.optDouble("speed_mps", Double.NaN)
                    .takeIf { it.isFinite() && it >= 0.0 && it <= 500.0 }
                    ?.toFloat()
            } else {
                null
            }
        val bearingDeg =
            if (json.has("bearing_deg") && !json.isNull("bearing_deg")) {
                json.optDouble("bearing_deg", Double.NaN)
                    .takeIf { it.isFinite() }
                    ?.let { (((it % 360.0) + 360.0) % 360.0).toFloat() }
            } else {
                null
            }
        val rawTimestampMs = json.optLong("timestamp_ms", System.currentTimeMillis())
        val nowMs = System.currentTimeMillis()
        val timestampMs =
            rawTimestampMs.takeIf { it in (nowMs - 24L * 60L * 60L * 1000L)..(nowMs + 5L * 60L * 1000L) }
                ?: nowMs

        val context = DeviceLocationContext(
            latitude = latitude,
            longitude = longitude,
            accuracyMeters = accuracyMeters,
            altitudeMeters = altitudeMeters,
            speedMps = speedMps,
            bearingDeg = bearingDeg,
            provider = "companion_phone",
            timestampMs = timestampMs
        )
        appPreferences.setPhoneLocationBridgeContext(context)
        Log.d(
            TAG,
            "Stored phone bridge fix lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters} ts=${context.timestampMs}"
        )
        return jsonResponse(JSONObject().apply {
            put("status", "ok")
            put("enabled", true)
            put("stored_only", true)
            put("provider", context.provider)
            put("accuracy_meters", context.accuracyMeters ?: JSONObject.NULL)
            put("timestamp_ms", context.timestampMs)
        })
    }

    // ── OAuth ─────────────────────────────────────────────────────────

    /**
     * Handle Google OAuth redirect: /oauth/callback?code=...
     * Exchanges the authorization code for tokens and stores them.
     */
    private fun handleOAuthCallback(session: IHTTPSession): Response {
        val params = session.parms ?: emptyMap()
        val code = params["code"]
        val error = params["error"]

        if (error != null) {
            Log.w(TAG, "OAuth error: $error")
            return newFixedLengthResponse(
                Response.Status.OK, "text/html",
                oauthResultPage(false, "Authorization denied: $error")
            )
        }

        if (code.isNullOrBlank()) {
            return newFixedLengthResponse(
                Response.Status.BAD_REQUEST, "text/html",
                oauthResultPage(false, "No authorization code received.")
            )
        }

        val mgr = oauthManager
        if (mgr == null) {
            Log.e(TAG, "OAuth manager not initialized")
            return newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "text/html",
                oauthResultPage(false, "OAuth manager not ready. Restart AITap and try again.")
            )
        }

        // Reconstruct the redirect URI from the incoming request
        val host = session.headers["host"] ?: "localhost:19110"
        val scheme = if (httpsEnabled) "https" else "http"
        val redirectUri = "$scheme://$host/oauth/callback"

        // Exchange code for tokens (blocking in NanoHTTPD thread)
        val result = runBlocking { mgr.exchangeCodeForTokensDetailed(code, redirectUri) }

        return newFixedLengthResponse(
            Response.Status.OK, "text/html",
            oauthResultPage(
                result.success,
                if (result.success) "Google account authorized! You can close this tab."
                else "Token exchange failed: ${result.errorDetail}<br><br><small>Redirect URI used: $redirectUri</small>"
            )
        )
    }

    /**
     * Handle manual OAuth code submission: POST /api/oauth/exchange
     * Body: {"code": "...", "redirect_uri": "http://<glasses-ip>:19110/oauth/callback"}
     */
    private fun handleOAuthExchange(session: IHTTPSession): Response {
        return try {
            // Read POST body
            val body = HashMap<String, String>()
            session.parseBody(body)
            val postData = body["postData"] ?: ""

            if (postData.isBlank()) {
                Log.e(TAG, "OAuth exchange: empty body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Empty body\"}"""
                )
            }

            Log.d(TAG, "OAuth exchange: received body (${postData.length} chars)")

            val json = try { JSONObject(postData) } catch (e: Exception) {
                Log.e(TAG, "OAuth exchange: invalid JSON", e)
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{\"error\":\"Invalid JSON\"}"""
                )
            }

            val code = json.optString("code", "").trim()
            val host = session.headers["host"] ?: "localhost:19110"
            val scheme = if (httpsEnabled) "https" else "http"
            val defaultRedirectUri = "$scheme://$host/oauth/callback"
            val redirectUri = json.optString("redirect_uri", defaultRedirectUri).trim()

            if (code.isBlank()) {
                Log.e(TAG, "OAuth exchange: no code in body")
                return newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json", """{"error":"No code provided"}"""
                )
            }

            Log.d(TAG, "OAuth exchange: code=${code.take(10)}..., redirect=$redirectUri")

            val mgr = oauthManager
            if (mgr == null) {
                Log.e(TAG, "OAuth manager not initialized")
                return newFixedLengthResponse(
                    Response.Status.INTERNAL_ERROR, "application/json",
                    """{"error":"OAuth manager not ready. Restart AITap and try again."}"""
                )
            }

            val success = runBlocking { mgr.exchangeCodeForTokens(code, redirectUri) }

            if (success) {
                Log.i(TAG, "OAuth exchange: success!")
                newFixedLengthResponse(
                    Response.Status.OK, "application/json", """{"status":"authorized"}"""
                )
            } else {
                Log.e(TAG, "OAuth exchange: token exchange failed")
                newFixedLengthResponse(
                    Response.Status.BAD_REQUEST, "application/json",
                    """{"error":"Token exchange failed. Check Client ID and Secret."}"""
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "OAuth exchange: unexpected error", e)
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR, "application/json",
                """{"error":"Server error: ${e.message?.replace("\"", "'")}"}"""
            )
        }
    }

    /** Returns OAuth authorization status as JSON. */
    private fun serveOAuthStatus(): Response {
        val hasToken = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        val expiryMs = prefs.getLong("google_oauth_token_expiry_ms", 0L)
        val isValid = hasToken && System.currentTimeMillis() < expiryMs
        val json = JSONObject().apply {
            put("authorized", hasToken)
            put("token_valid", isValid)
            put("expiry_ms", expiryMs)
        }
        return newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())
    }

    /**
     * GET /api/camera/frame — serves the latest camera frame as a JPEG image.
     * OpenClaw's agent fetches this URL to analyze what the AR glasses camera sees.
     * Returns 503 if no frame is available (camera not active).
     */
    private fun serveCameraFrame(): Response {
        val frameBytes = cameraFrameProvider?.invoke()
        if (frameBytes == null || frameBytes.isEmpty()) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"No camera frame available","hint":"Camera may not be active"}"""
            )
        }

        val inputStream = java.io.ByteArrayInputStream(frameBytes)
        val response = newFixedLengthResponse(
            Response.Status.OK,
            "image/jpeg",
            inputStream,
            frameBytes.size.toLong()
        )
        response.addHeader("Cache-Control", "no-cache, no-store")
        response.addHeader("Access-Control-Allow-Origin", "*")
        return response
    }

    /**
     * Proxy a request to the image relay, avoiding mixed-content browser blocks.
     * The companion app is served over HTTPS, so direct fetch to http://<relay>:18790
     * fails silently in browsers. This endpoint proxies the request server-side.
     *
     * @param relayPath the relay path to fetch, e.g. "/status" or "/latest"
     * @param expectedMime the expected MIME type of the response
     */
    private fun proxyRelayRequest(relayPath: String, expectedMime: String): Response {
        val relayBase = buildRelayBaseUrl()
        if (relayBase == null) {
            return newFixedLengthResponse(
                Response.Status.SERVICE_UNAVAILABLE,
                "application/json",
                """{"error":"No OpenClaw gateway URL configured — cannot determine relay address"}"""
            )
        }
        return try {
            val url = java.net.URL(relayBase + relayPath)
            val conn = url.openConnection() as java.net.HttpURLConnection
            conn.connectTimeout = 3000
            conn.readTimeout = 5000
            conn.requestMethod = "GET"
            val code = conn.responseCode
            if (code != 200) {
                conn.disconnect()
                return newFixedLengthResponse(
                    Response.Status.lookup(code) ?: Response.Status.INTERNAL_ERROR,
                    "application/json",
                    """{"error":"Relay returned HTTP $code"}"""
                )
            }
            val bytes = conn.inputStream.use { it.readBytes() }
            conn.disconnect()
            val resp = newFixedLengthResponse(
                Response.Status.OK,
                expectedMime,
                ByteArrayInputStream(bytes),
                bytes.size.toLong()
            )
            resp.addHeader("Cache-Control", "no-cache, no-store")
            resp
        } catch (e: Exception) {
            Log.w(TAG, "Relay proxy failed for $relayBase$relayPath: ${e.message}")
            newFixedLengthResponse(
                Response.Status.INTERNAL_ERROR,
                "application/json",
                """{"error":"Cannot reach image relay at $relayBase","detail":"${e.message?.replace("\"", "'")}"}"""
            )
        }
    }

    /**
     * Proxy media file requests to the relay's /media/ endpoint.
     * Detects MIME type from the file extension so TapBrowser can identify audio/video.
     */
    private fun proxyRelayMedia(uri: String): Response {
        val filename = uri.removePrefix("/api/relay/media/")
        if (filename.isBlank() || filename.contains("..")) {
            return newFixedLengthResponse(Response.Status.BAD_REQUEST, "text/plain", "Invalid filename")
        }
        val ext = filename.substringAfterLast('.', "").lowercase()
        val mime = when (ext) {
            "mp3" -> "audio/mpeg"
            "wav" -> "audio/wav"
            "ogg" -> "audio/ogg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "flac" -> "audio/flac"
            "opus" -> "audio/opus"
            "mp4" -> "video/mp4"
            "webm" -> "video/webm"
            "jpg", "jpeg" -> "image/jpeg"
            "png" -> "image/png"
            "gif" -> "image/gif"
            else -> "application/octet-stream"
        }
        return proxyRelayRequest("/media/$filename", mime)
    }

    /** Build the relay base URL from the configured OpenClaw gateway endpoint.
     *  Mirrors the logic in OpenClawClient.buildRelayUrl(). */
    private fun buildRelayBaseUrl(): String? {
        val endpoint = appPreferences.openClawEndpoint.trim()
        if (endpoint.isBlank()) return null
        val host = Regex("""://([^:/]+)""").find(endpoint)?.groupValues?.get(1) ?: return null
        val isIp = host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))
        val isLocal = host == "localhost" || host == "127.0.0.1" || isIp
        return if (isLocal) {
            "http://$host:18790"
        } else {
            val parts = host.split(".")
            val baseDomain = if (parts.size > 2) parts.drop(1).joinToString(".") else host
            "https://relay.$baseDomain"
        }
    }

    private fun serveHudState(): Response {
        return jsonResponse(JSONObject().apply {
            put("hud_show_calendar", prefs.getBoolean("hud_show_calendar", true))
            put("hud_show_tasks", prefs.getBoolean("hud_show_tasks", true))
            put("hud_show_news", prefs.getBoolean("hud_show_news", true))
            put("calendar_summary", calendarSummaryProvider?.invoke().orEmpty())
            put("tasks_summary", tasksSummaryProvider?.invoke().orEmpty())
            put("news_summary", newsSummaryProvider?.invoke().orEmpty())
            put("aqi_text", airQualityTextProvider?.invoke() ?: JSONObject.NULL)
            put("aqi_value", airQualityValueProvider?.invoke() ?: JSONObject.NULL)
            put("location_provider", locationProvider?.invoke()?.provider ?: JSONObject.NULL)
        })
    }

    /** Returns server connection info (protocol, HTTPS status, URL hint). */
    private fun serveServerInfo(session: IHTTPSession): Response {
        val host = session.headers["host"] ?: "localhost:19110"
        val hostName = host.substringBefore(':').ifBlank { "localhost" }
        val serverPort = runCatching { listeningPort }.getOrDefault(19110)
        val lanIps = detectLanIpv4Addresses()
        val primaryLanIp = lanIps.firstOrNull() ?: hostName.takeIf { it != "localhost" && it != "127.0.0.1" }
        val scheme = if (httpsEnabled) "https" else "http"
        return jsonResponse(JSONObject().apply {
            put("https_enabled", httpsEnabled)
            put("scheme", scheme)
            put("url", "$scheme://$host")
            put("secure_context", httpsEnabled)
            put("gps_bridge_supported", httpsEnabled)
            put("host_header", host)
            put("host_name", hostName)
            put("primary_lan_ip", primaryLanIp ?: JSONObject.NULL)
            put("recommended_phone_url", if (primaryLanIp != null && httpsEnabled) "https://$primaryLanIp:${serverPort}" else JSONObject.NULL)
            put("recommended_wifi_url", if (primaryLanIp != null) "$scheme://$primaryLanIp:${serverPort}" else JSONObject.NULL)
            put("recommended_usb_url", "http://localhost:${serverPort}")
            put("lan_ips", org.json.JSONArray().apply {
                lanIps.forEach { put(it) }
            })
        })
    }

    private fun detectLanIpv4Addresses(): List<String> {
        return runCatching {
            NetworkInterface.getNetworkInterfaces()
                ?.toList()
                .orEmpty()
                .asSequence()
                .filter { !it.isLoopback && it.isUp }
                .flatMap { iface ->
                    iface.inetAddresses.toList().asSequence()
                }
                .filterIsInstance<Inet4Address>()
                .map { it.hostAddress ?: "" }
                .filter { ip ->
                    ip.isNotBlank() &&
                        ip != "127.0.0.1" &&
                        !ip.startsWith("169.254.")
                }
                .distinct()
                .sorted()
                .toList()
        }.getOrDefault(emptyList())
    }

    // ── Calendar List ────────────────────────────────────────────────────

    /** Fetch all calendars visible to the OAuth-authenticated user. */
    private fun fetchCalendarList(): Response {
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        if (!hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("status", "auth_required")
                put("message", "OAuth authorization required to list calendars")
                put("calendars", org.json.JSONArray())
            })
        }

        return try {
            val mgr = oauthManager ?: return jsonResponse(JSONObject().apply {
                put("status", "error")
                put("message", "OAuth manager not initialized")
                put("calendars", org.json.JSONArray())
            })

            val calendarApiKey = prefs.getString("calendar_api_key", "") ?: ""
            val client = GoogleCalendarClient(
                apiKeyProvider = { calendarApiKey.takeIf { it.isNotBlank() } },
                accessTokenProvider = { runBlocking { mgr.getValidAccessToken() } },
                context = context
            )
            val result = runBlocking { client.fetchCalendarList() }

            when (result) {
                is GoogleCalendarClient.CalendarListResult.Success -> {
                    val arr = org.json.JSONArray()
                    result.calendars.forEach { cal ->
                        arr.put(JSONObject().apply {
                            put("id", cal.id)
                            put("summary", cal.summary)
                            put("primary", cal.primary)
                            if (cal.backgroundColor != null) put("backgroundColor", cal.backgroundColor)
                            if (cal.accessRole != null) put("accessRole", cal.accessRole)
                        })
                    }
                    jsonResponse(JSONObject().apply {
                        put("status", "success")
                        put("calendars", arr)
                    })
                }
                is GoogleCalendarClient.CalendarListResult.AuthRequired ->
                    jsonResponse(JSONObject().apply {
                        put("status", "auth_required")
                        put("message", "OAuth authorization required")
                        put("calendars", org.json.JSONArray())
                    })
                is GoogleCalendarClient.CalendarListResult.Error ->
                    jsonResponse(JSONObject().apply {
                        put("status", "error")
                        put("message", result.message)
                        put("calendars", org.json.JSONArray())
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar list error", e)
            jsonResponse(JSONObject().apply {
                put("status", "error")
                put("message", "Exception: ${e.message}")
                put("calendars", org.json.JSONArray())
            })
        }
    }

    // ── API Verification ──────────────────────────────────────────────────

    /** Test Google Calendar API with current credentials. */
    private fun verifyCalendar(): Response {
        val calendarApiKey = prefs.getString("calendar_api_key", "") ?: ""
        val calendarId = (prefs.getString("calendar_id", "") ?: "").ifBlank { "primary" }
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true
        Log.d(TAG, "verifyCalendar start hasOAuth=$hasOAuth hasApiKey=${calendarApiKey.isNotBlank()} calendarId=$calendarId")

        if (calendarApiKey.isBlank() && !hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("service", "calendar")
                put("status", "not_configured")
                put("message", "No OAuth token or Calendar API key configured")
                put("has_oauth", false)
                put("has_api_key", false)
                put("calendar_id", calendarId)
            })
        }

        return try {
            val mgr = oauthManager
            val client = GoogleCalendarClient(
                apiKeyProvider = { calendarApiKey.takeIf { it.isNotBlank() } },
                accessTokenProvider = {
                    if (mgr != null && hasOAuth) runBlocking { mgr.getValidAccessToken() } else null
                },
                context = context
            )
            val result = runBlocking { client.fetchUpcomingEvents(calendarId, maxResults = 5) }

            when (result) {
                is GoogleCalendarClient.CalendarResult.Success -> {
                    Log.d(TAG, "verifyCalendar success events=${result.events.size}")
                    val eventSummary = if (result.events.isEmpty()) {
                        "No events in next 24h"
                    } else {
                        result.events.take(3).joinToString(", ") { it.summary }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "calendar")
                        put("status", "success")
                        put("message", "Connected! ${result.events.size} event(s): $eventSummary")
                        put("has_oauth", hasOAuth)
                        put("has_api_key", calendarApiKey.isNotBlank())
                        put("calendar_id", calendarId)
                        put("event_count", result.events.size)
                    })
                }
                is GoogleCalendarClient.CalendarResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    Log.w(TAG, "verifyCalendar api key missing/invalid")
                    put("service", "calendar")
                    put("status", "failed")
                    put("message", "API key is missing or invalid. Enable Calendar API in GCP and check your key.")
                    put("has_oauth", hasOAuth)
                    put("has_api_key", calendarApiKey.isNotBlank())
                    put("calendar_id", calendarId)
                })
                is GoogleCalendarClient.CalendarResult.Error -> jsonResponse(JSONObject().apply {
                    Log.e(TAG, "verifyCalendar error message=${result.message} code=${result.code}")
                    put("service", "calendar")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_oauth", hasOAuth)
                    put("has_api_key", calendarApiKey.isNotBlank())
                    put("calendar_id", calendarId)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Calendar verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "calendar")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_oauth", hasOAuth)
                put("has_api_key", calendarApiKey.isNotBlank())
                put("calendar_id", calendarId)
            })
        }
    }

    /** Test Google Directions API with current credentials. */
    private fun verifyDirections(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "directions")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured")
                put("has_api_key", false)
            })
        }

        return try {
            val client = GoogleDirectionsClient(apiKeyProvider = { mapsApiKey }, context = context)
            val result = runBlocking {
                client.getDirections("Times Square, NYC", "Central Park, NYC", "driving")
            }

            when (result) {
                is GoogleDirectionsClient.DirectionsResult.Success -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "success")
                    put("message", "Connected! Test route: ${result.distance}, ${result.duration}")
                    put("has_api_key", true)
                })
                is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "failed")
                    put("message", "API key is invalid. Enable Directions API in GCP.")
                    put("has_api_key", true)
                })
                is GoogleDirectionsClient.DirectionsResult.Error -> jsonResponse(JSONObject().apply {
                    put("service", "directions")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_api_key", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Directions verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "directions")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
            })
        }
    }

    /** Test Google Tasks API with current OAuth credentials. */
    private fun verifyTasks(): Response {
        val hasOAuth = prefs.getString("google_oauth_refresh_token", "")?.isNotBlank() == true

        if (!hasOAuth) {
            return jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "not_configured")
                put("message", "OAuth authorization required for Tasks API. Complete Step 6 and re-authorize with Tasks scope.")
                put("has_oauth", false)
            })
        }

        return try {
            val mgr = oauthManager ?: return jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "failed")
                put("message", "OAuth manager not initialized")
                put("has_oauth", true)
            })

            val client = GoogleTasksClient(
                accessTokenProvider = { runBlocking { mgr.getValidAccessToken() } },
                context = context
            )
            val result = runBlocking { client.fetchTasks(maxResults = 3) }

            when (result) {
                is GoogleTasksClient.TasksResult.Success -> {
                    val taskSummary = if (result.tasks.isEmpty()) {
                        "No pending tasks"
                    } else {
                        result.tasks.take(3).joinToString(", ") { it.title }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "success")
                        put("message", "Connected! ${result.tasks.size} task(s): $taskSummary")
                        put("has_oauth", true)
                        put("task_count", result.tasks.size)
                    })
                }
                is GoogleTasksClient.TasksResult.AuthRequired ->
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "failed")
                        put("message", "OAuth token missing or expired. Re-authorize with Tasks scope.")
                        put("has_oauth", true)
                    })
                is GoogleTasksClient.TasksResult.Error ->
                    jsonResponse(JSONObject().apply {
                        put("service", "tasks")
                        put("status", "failed")
                        put("message", result.message)
                        put("has_oauth", true)
                    })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tasks verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "tasks")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_oauth", true)
            })
        }
    }

    /** Test Google Places API (Nearby Search) with current Maps API key. */
    private fun verifyPlaces(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "places")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Places API uses the same key as Directions.")
                put("has_api_key", false)
            })
        }

        return try {
            val client = GooglePlacesClient(apiKeyProvider = { mapsApiKey }, context = context)
            // Test with a search for restaurants near a known location (Houston, TX)
            val result = runBlocking {
                client.searchNearby(
                    latitude = 29.7604,
                    longitude = -95.3698,
                    types = listOf("restaurant"),
                    radiusMeters = 1000.0,
                    maxResults = 3
                )
            }

            when (result) {
                is GooglePlacesClient.PlacesResult.Success -> {
                    val placeSummary = if (result.places.isEmpty()) {
                        "No restaurants found (API works but no results at test location)"
                    } else {
                        result.places.take(3).joinToString(", ") { place ->
                            val open = when (place.isOpen) {
                                true -> "Open"
                                false -> "Closed"
                                null -> ""
                            }
                            "${place.name}${if (open.isNotBlank()) " ($open)" else ""}"
                        }
                    }
                    jsonResponse(JSONObject().apply {
                        put("service", "places")
                        put("status", "success")
                        put("message", "Connected! ${result.places.size} place(s): $placeSummary")
                        put("has_api_key", true)
                        put("place_count", result.places.size)
                    })
                }
                is GooglePlacesClient.PlacesResult.ApiKeyMissing -> jsonResponse(JSONObject().apply {
                    put("service", "places")
                    put("status", "failed")
                    put("message", "API key is missing or invalid.")
                    put("has_api_key", mapsApiKey.isNotBlank())
                })
                is GooglePlacesClient.PlacesResult.Error -> jsonResponse(JSONObject().apply {
                    put("service", "places")
                    put("status", "failed")
                    put("message", result.message)
                    put("has_api_key", true)
                })
            }
        } catch (e: Exception) {
            Log.e(TAG, "Places verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "places")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
            })
        }
    }

    /** Test GPS location availability on the device. */
    private fun verifyLocation(): Response {
        val loc = locationProvider?.invoke()

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "location")
                put("status", "failed")
                put("message", "Device location not available. Make sure Location Services are enabled " +
                    "on the glasses (Settings → Location → On) and the app has location permission.")
                put("has_gps", false)
            })
        }

        val ageSeconds = (System.currentTimeMillis() - loc.timestampMs) / 1000
        val fresh = if (ageSeconds < 300) "current" else "${ageSeconds / 60}min old"
        val sourceLabel =
            when (loc.provider) {
                "ip_geolocation" -> "Approximate network location"
                else -> "Location active"
            }
        return jsonResponse(JSONObject().apply {
            put("service", "location")
            put("status", "success")
            put("message", "$sourceLabel: Lat: ${"%.6f".format(loc.latitude)}, " +
                "Lng: ${"%.6f".format(loc.longitude)} " +
                "(accuracy: ${loc.accuracyMeters?.toInt() ?: "?"}m, $fresh)" +
                (loc.altitudeMeters?.let { alt: Double -> ", alt: ${alt.toInt()}m" } ?: "") +
                (loc.speedMps?.let { spd: Float -> if (spd > 0.5f) ", speed: ${"%.1f".format(spd * 2.237)}mph" else "" } ?: ""))
            put("has_gps", loc.provider != "ip_geolocation")
            put("provider", loc.provider ?: JSONObject.NULL)
            put("latitude", loc.latitude)
            put("longitude", loc.longitude)
            put("accuracy_meters", loc.accuracyMeters ?: -1)
            put("age_seconds", ageSeconds)
        })
    }

    /** Test Google Directions API with live GPS (origin = device) to a known destination. */
    private fun verifyTraffic(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""
        val loc = locationProvider?.invoke()

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Set it above and save first.")
                put("has_api_key", false)
                put("has_gps", loc != null)
            })
        }

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "failed")
                put("message", "GPS not available — cannot test traffic from your current location. " +
                    "Enable Location Services on the glasses.")
                put("has_api_key", true)
                put("has_gps", false)
            })
        }

        return try {
            val origin = "${loc.latitude},${loc.longitude}"
            // Use a well-known nearby city as the destination test
            val destination = "Houston, TX"
            val client = GoogleDirectionsClient(apiKeyProvider = { mapsApiKey }, context = context)
            val result = runBlocking {
                client.getDirections(origin = origin, destination = destination)
            }

            when (result) {
                is GoogleDirectionsClient.DirectionsResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "success")
                        put("message", "Routes API working! From your location to $destination: " +
                            "${result.duration} (${result.distance})" +
                            (result.durationInTraffic?.let { " — with traffic: $it" } ?: ""))
                        put("has_api_key", true)
                        put("has_gps", true)
                        put("origin", origin)
                        put("destination", destination)
                    })
                }
                is GoogleDirectionsClient.DirectionsResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "failed")
                        put("message", "Routes API error: ${result.message}")
                        put("has_api_key", true)
                        put("has_gps", true)
                    })
                }
                is GoogleDirectionsClient.DirectionsResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "traffic")
                        put("status", "failed")
                        put("message", "API key missing or invalid.")
                        put("has_api_key", false)
                        put("has_gps", true)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Traffic verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "traffic")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
                put("has_gps", true)
            })
        }
    }

    /** Test Google Air Quality API with live GPS location. */
    private fun verifyAirQuality(): Response {
        val mapsApiKey = prefs.getString("google_maps_api_key", "") ?: ""
        val loc = locationProvider?.invoke()

        if (mapsApiKey.isBlank()) {
            return jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "not_configured")
                put("message", "Google Maps API key not configured. Air Quality uses the same key.")
                put("has_api_key", false)
                put("has_gps", loc != null)
            })
        }

        if (loc == null) {
            return jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "failed")
                put("message", "GPS not available — cannot test air quality from the glasses.")
                put("has_api_key", true)
                put("has_gps", false)
            })
        }

        return try {
            val client = GoogleAirQualityClient(apiKeyProvider = { mapsApiKey }, context = context)
            when (
                val result = runBlocking {
                    client.fetchCurrentConditions(loc.latitude, loc.longitude)
                }
            ) {
                is GoogleAirQualityClient.AirQualityResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "success")
                        put(
                            "message",
                            "Connected! ${result.index.label}" +
                                (result.index.dominantPollutant?.let { " — dominant pollutant: $it" } ?: "")
                        )
                        put("has_api_key", true)
                        put("has_gps", true)
                        put("aqi", result.index.aqi ?: JSONObject.NULL)
                    })
                }
                is GoogleAirQualityClient.AirQualityResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "failed")
                        put("message", "Air Quality API key missing or invalid.")
                        put("has_api_key", false)
                        put("has_gps", true)
                    })
                }
                is GoogleAirQualityClient.AirQualityResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "air_quality")
                        put("status", "failed")
                        put("message", result.message)
                        put("has_api_key", true)
                        put("has_gps", true)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Air quality verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "air_quality")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("has_api_key", mapsApiKey.isNotBlank())
                put("has_gps", loc != null)
            })
        }
    }

    /** Test the configured research provider with a short sample prompt. */
    private fun verifyResearch(): Response {
        val provider = (prefs.getString("research_provider", "") ?: "").trim().ifBlank { "gemini" }
        val router = ResearchRouter(
            providerProvider = { provider },
            apiKeyProvider = { prefs.getString("research_api_key", "")?.trim() },
            modelProvider = { prefs.getString("research_model", "")?.trim() },
            geminiFallbackApiKeyProvider = {
                (prefs.getString("gemini_api_key", "") ?: "").trim().ifBlank {
                    BuildConfig.GEMINI_API_KEY.takeIf { it.isNotBlank() }
                }
            },
            context = context
        )

        return try {
            when (val result = runBlocking { router.research("current capabilities of TapInsight") }) {
                is ResearchRouter.ResearchResult.Success -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "success")
                        put("message", "Connected! ${result.provider} / ${result.model}")
                        put("provider", result.provider)
                        put("model", result.model)
                        put("preview", result.text.take(240))
                    })
                }
                is ResearchRouter.ResearchResult.ApiKeyMissing -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "not_configured")
                        put("message", "Research provider API key missing. Configure it in the companion app.")
                        put("provider", provider)
                    })
                }
                is ResearchRouter.ResearchResult.Error -> {
                    jsonResponse(JSONObject().apply {
                        put("service", "research")
                        put("status", "failed")
                        put("message", result.message)
                        put("provider", provider)
                    })
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Research verify error", e)
            jsonResponse(JSONObject().apply {
                put("service", "research")
                put("status", "failed")
                put("message", "Exception: ${e.message}")
                put("provider", provider)
            })
        }
    }

    private fun verifyOpenClaw(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("message", "Empty request body.")
            )
        }

        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("message", "Invalid JSON: ${e.message}")
            )
        }

        val endpoint = json.optString("endpoint", "").trim()
        val token = json.optString("token", "").trim()
        val sessionId = json.optString("session_id", "main").ifBlank { "main" }
        val timeoutSeconds = json.optInt("timeout_seconds", 0).coerceIn(0, 120)
        val timeoutMs = (if (timeoutSeconds > 0) timeoutSeconds else 30) * 1000L

        if (endpoint.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("message", "OpenClaw gateway URL is missing or invalid.")
            )
        }

        // Normalize to WebSocket URL — gateway is WebSocket-only
        var wsUrl = endpoint.trim().trimEnd('/')
        wsUrl = when {
            wsUrl.startsWith("ws://") || wsUrl.startsWith("wss://") -> wsUrl
            wsUrl.startsWith("https://") -> wsUrl.replace("https://", "wss://")
            wsUrl.startsWith("http://") -> wsUrl.replace("http://", "ws://")
            else -> "ws://$wsUrl"
        }
        val schemeEnd = wsUrl.indexOf("://") + 3
        val hostPart = wsUrl.substring(schemeEnd)
        if (!hostPart.contains(':')) {
            wsUrl = wsUrl.substring(0, schemeEnd) + hostPart + ":18789"
        }

        // Use WebSocket to call the health method
        val client = okhttp3.OkHttpClient.Builder()
            .connectTimeout(10, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS)
            .build()

        val latch = java.util.concurrent.CountDownLatch(1)
        var resultJson: JSONObject? = null
        var errorMsg: String? = null

        val connectId = "verify-" + System.currentTimeMillis()
        val callId = "health-" + System.currentTimeMillis()
        var connected = false

        val request = okhttp3.Request.Builder().url(wsUrl).build()
        val ws = client.newWebSocket(request, object : okhttp3.WebSocketListener() {
            override fun onOpen(webSocket: okhttp3.WebSocket, response: okhttp3.Response) {
                Log.d(TAG, "Verify WS opened, waiting for challenge...")
            }

            override fun onMessage(webSocket: okhttp3.WebSocket, text: String) {
                try {
                    val msg = JSONObject(text)
                    val type = msg.optString("type", "")
                    val event = msg.optString("event", "")

                    when {
                        // Challenge — respond with signed device identity auth
                        type == "event" && event == "connect.challenge" -> {
                            if (token.isNotBlank()) {
                                val nonce = msg.optJSONObject("payload")?.optString("nonce", "") ?: ""
                                val deviceId = prefs.getString("openclaw_pair_device_id", null)?.takeIf { it.isNotBlank() }
                                val publicKey = prefs.getString("openclaw_pair_public_key", null)?.takeIf { it.isNotBlank() }
                                val privateKey = prefs.getString("openclaw_pair_private_key", null)?.takeIf { it.isNotBlank() }
                                val signedAtMs = System.currentTimeMillis()

                                val connectFrame = JSONObject().apply {
                                    put("type", "req")
                                    put("id", connectId)
                                    put("method", "connect")
                                    put("params", JSONObject().apply {
                                        put("minProtocol", 3)
                                        put("maxProtocol", 3)
                                        put("client", JSONObject().apply {
                                            put("id", "openclaw-android")
                                            put("version", "1.1.2")
                                            put("platform", "android")
                                            put("mode", "node")
                                        })
                                        put("role", "operator")
                                        put("scopes", org.json.JSONArray().apply {
                                            put("operator.read")
                                            put("operator.write")
                                        })
                                        put("auth", JSONObject().apply { put("token", token) })
                                        put("caps", org.json.JSONArray())
                                        // Include device identity if available (required by gateway)
                                        if (deviceId != null && publicKey != null && privateKey != null && nonce.isNotBlank()) {
                                            val authPayload = listOf(
                                                "v3", deviceId, "openclaw-android", "node", "operator",
                                                "operator.read,operator.write", signedAtMs.toString(), token, nonce, "android", "glasses"
                                            ).joinToString("|")
                                            try {
                                                val pkBytes = android.util.Base64.decode(
                                                    privateKey.replace('-', '+').replace('_', '/'),
                                                    android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                                )
                                                val signer = org.bouncycastle.crypto.signers.Ed25519Signer()
                                                signer.init(true, org.bouncycastle.crypto.params.Ed25519PrivateKeyParameters(pkBytes, 0))
                                                val payloadBytes = authPayload.toByteArray(Charsets.UTF_8)
                                                signer.update(payloadBytes, 0, payloadBytes.size)
                                                val sigBytes = signer.generateSignature()
                                                val signature = android.util.Base64.encodeToString(
                                                    sigBytes,
                                                    android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP
                                                )
                                                put("device", JSONObject().apply {
                                                    put("id", deviceId)
                                                    put("publicKey", publicKey)
                                                    put("signature", signature)
                                                    put("signedAt", signedAtMs)
                                                    put("nonce", nonce)
                                                })
                                            } catch (e: Exception) {
                                                Log.w(TAG, "Device identity signing failed: ${e.message}")
                                            }
                                        }
                                    })
                                }
                                webSocket.send(connectFrame.toString())
                            }
                        }

                        // Connect response — send health call
                        type == "res" && msg.optString("id", "") == connectId -> {
                            if (msg.optBoolean("ok", false)) {
                                connected = true
                                val callMsg = JSONObject().apply {
                                    put("type", "req")
                                    put("id", callId)
                                    put("method", "health")
                                    put("params", JSONObject())
                                }
                                webSocket.send(callMsg.toString())
                            } else {
                                errorMsg = "Authentication failed"
                                webSocket.close(1000, "auth failed")
                                latch.countDown()
                            }
                        }

                        // Health response
                        type == "res" && msg.optString("id", "") == callId -> {
                            resultJson = msg
                            webSocket.close(1000, "done")
                            latch.countDown()
                        }
                    }
                } catch (_: Exception) {}
            }

            override fun onFailure(webSocket: okhttp3.WebSocket, t: Throwable, response: okhttp3.Response?) {
                errorMsg = t.localizedMessage ?: "Connection failed"
                latch.countDown()
            }

            override fun onClosing(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                if (code == 1008) {
                    errorMsg = "Authentication failed: $reason"
                }
                webSocket.close(1000, null)
                latch.countDown()
            }

            override fun onClosed(webSocket: okhttp3.WebSocket, code: Int, reason: String) {
                latch.countDown()
            }
        })

        val completed = latch.await(timeoutMs + 2000, java.util.concurrent.TimeUnit.MILLISECONDS)

        return when {
            !completed -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", "TapClaw connection timed out.")
            )
            errorMsg != null -> {
                val authFailed = errorMsg!!.contains("unauthorized", ignoreCase = true) ||
                        errorMsg!!.contains("auth", ignoreCase = true)
                jsonResponse(
                    JSONObject()
                        .put("service", "openclaw")
                        .put("status", if (authFailed) "auth_failed" else "failed")
                        .put("endpoint", wsUrl)
                        .put("session_id", sessionId)
                        .put("message", errorMsg)
                )
            }
            resultJson?.has("error") == true -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", resultJson!!.optJSONObject("error")?.optString("message", "Gateway error") ?: "Gateway error")
            )
            resultJson != null -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "ok")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", "TapClaw is reachable from the glasses.")
            )
            else -> jsonResponse(
                JSONObject()
                    .put("service", "openclaw")
                    .put("status", "failed")
                    .put("endpoint", wsUrl)
                    .put("session_id", sessionId)
                    .put("message", "No response from TapClaw gateway.")
            )
        }
    }


    private fun openClawDebugJson(debug: OpenClawPairingClient.DebugInfo?): JSONObject? {
        if (debug == null) return null
        return JSONObject()
            .put("endpoint", debug.endpoint)
            .put("gatewayWsUrl", debug.gatewayWsUrl)
            .put("deviceId", debug.deviceId)
            .put("clientId", debug.clientId)
            .put("clientMode", debug.clientMode)
            .put("platform", debug.platform)
            .put("deviceFamily", debug.deviceFamily)
            .put("role", debug.role)
            .put("scopes", JSONArray(debug.scopes))
            .put("bootstrapTokenSuffix", debug.bootstrapTokenSuffix)
            .put("payloadVersion", debug.payloadVersion)
            .put("gatewayErrorCode", debug.gatewayErrorCode ?: JSONObject.NULL)
            .put("gatewayErrorDetails", debug.gatewayErrorDetails ?: JSONObject.NULL)
            .put("closeCode", debug.closeCode ?: JSONObject.NULL)
            .put("closeReason", debug.closeReason ?: JSONObject.NULL)
            .put("lastServerFrame", debug.lastServerFrame ?: JSONObject.NULL)
    }

    private fun pairOpenClaw(session: IHTTPSession): Response {
        val body = HashMap<String, String>()
        session.parseBody(body)
        val postData = body["postData"] ?: ""
        if (postData.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", "Empty request body.")
            )
        }

        val json = try {
            JSONObject(postData)
        } catch (e: Exception) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", "Invalid JSON: ${e.message}")
            )
        }

        val setupCode = json.optString("setup_code", "").trim()
        val endpoint = json.optString("endpoint", "").trim()
        if (setupCode.isBlank()) {
            return jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", "No OpenClaw setup code was provided.")
            )
        }

        return try {
            when (val result = runBlocking { OpenClawPairingClient(context, prefs).startOrCheckPairing(setupCode, endpoint) }) {
                is OpenClawPairingClient.PairingResult.PendingApproval -> {
                    // Pre-save the endpoint so it's ready when pairing is approved
                    if (result.endpoint.isNotBlank()) {
                        appPreferences.openClawEndpoint = result.endpoint
                    }
                    jsonResponse(
                        JSONObject()
                            .put("service", "openclaw_pairing")
                            .put("status", "pending_approval")
                            .put("endpoint", result.endpoint)
                            .put("requestId", result.requestId)
                            .put("deviceId", result.deviceId)
                            .put("debug", openClawDebugJson(result.debug) ?: JSONObject.NULL)
                            .put("message", "Pairing request created. Approve it on your OpenClaw server, then check again.")
                    )
                }
                is OpenClawPairingClient.PairingResult.Approved -> {
                    // Auto-configure: save the endpoint and enable TapClaw so the
                    // Gemini tool registers on next app restart without manual setup.
                    if (result.endpoint.isNotBlank()) {
                        appPreferences.openClawEndpoint = result.endpoint
                        Log.d(TAG, "TapClaw auto-configured endpoint: ${result.endpoint}")
                    }
                    appPreferences.openClawEnabled = true
                    Log.d(TAG, "TapClaw auto-enabled after successful pairing")

                    jsonResponse(
                        JSONObject()
                            .put("service", "openclaw_pairing")
                            .put("status", "approved")
                            .put("endpoint", result.endpoint)
                            .put("deviceId", result.deviceId)
                            .put("hasDeviceToken", result.hasDeviceToken)
                            .put("debug", openClawDebugJson(result.debug) ?: JSONObject.NULL)
                            .put("message", "TapClaw pairing completed. Integration has been enabled automatically.")
                    )
                }
                is OpenClawPairingClient.PairingResult.Failed -> jsonResponse(
                    JSONObject()
                        .put("service", "openclaw_pairing")
                        .put("status", "failed")
                        .put("endpoint", result.endpoint ?: JSONObject.NULL)
                        .put("debug", openClawDebugJson(result.debug) ?: JSONObject.NULL)
                        .put("message", result.message)
                )
            }
        } catch (t: Throwable) {
            Log.e(TAG, "OpenClaw pairing error", t)
            jsonResponse(
                JSONObject()
                    .put("service", "openclaw_pairing")
                    .put("status", "failed")
                    .put("message", t.localizedMessage ?: t.javaClass.simpleName ?: "OpenClaw pairing failed.")
            )
        }
    }

    private fun normalizeOpenClawBaseUrl(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty().trimEnd('/')
        if (trimmed.isBlank()) return null

        var normalized = trimmed
            .replace(Regex("^wss://"), "https://")
            .replace(Regex("^ws://"), "http://")
            .replace(Regex("/ws/?$"), "")

        if (!normalized.startsWith("http://") && !normalized.startsWith("https://")) {
            normalized = "http://$normalized"
        }
        if (!normalized.matches(Regex("https?://[^/]+:\\d+(?:/.*)?"))) {
            val schemeEnd = normalized.indexOf("://") + 3
            val hostAndPath = normalized.substring(schemeEnd)
            val host = hostAndPath.substringBefore('/')
            if (!host.contains(':')) {
                normalized = normalized.substring(0, schemeEnd) + host + ":18789" + hostAndPath.removePrefix(host)
            }
        }
        return normalized
    }

    private fun jsonResponse(json: JSONObject): Response =
        newFixedLengthResponse(Response.Status.OK, "application/json", json.toString())

    /** Simple HTML result page shown after OAuth redirect. */
    private fun oauthResultPage(success: Boolean, message: String): String {
        val color = if (success) "#4caf50" else "#f44336"
        val icon = if (success) "&#10003;" else "&#10007;"
        return """
            <!DOCTYPE html>
            <html><head><meta charset="utf-8"><meta name="viewport" content="width=device-width,initial-scale=1">
            <title>TapInsight OAuth</title>
            <style>
              body { font-family: -apple-system, sans-serif; display: flex; justify-content: center;
                     align-items: center; height: 100vh; margin: 0; background: #111; color: #eee; }
              .card { text-align: center; padding: 40px; border-radius: 12px; background: #1a1a1a;
                      border: 1px solid #333; max-width: 400px; }
              .icon { font-size: 48px; color: $color; margin-bottom: 16px; }
              .msg { font-size: 16px; line-height: 1.5; }
              a { color: #6ea8fe; }
            </style></head>
            <body><div class="card">
              <div class="icon">$icon</div>
              <div class="msg">$message</div>
              ${if (success) "" else "<p><a href='/'>Back to Setup</a></p>"}
            </div></body></html>
        """.trimIndent()
    }

    fun startServer() {
        try {
            start(SOCKET_READ_TIMEOUT, false)
            Log.d(TAG, "Companion server started on port $listeningPort")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start companion server: ${e.message}", e)
        }
    }

    fun stopServer() {
        stop()
        Log.d(TAG, "Companion server stopped")
    }
}
