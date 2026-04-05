package com.rayneo.visionclaw

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
// android.graphics.Bitmap import removed – no longer needed
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.ToneGenerator
import android.os.Bundle
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.speech.SpeechRecognizer
import android.util.DisplayMetrics
import android.util.Log
import android.util.Patterns
// Choreographer removed – no longer needed for mirror frame callback
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
// PixelCopy / SurfaceView mirroring removed – BinocularSbsLayout handles SBS
import android.view.View
import android.view.WindowManager
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.viewpager2.widget.ViewPager2
import com.rayneo.visionclaw.core.assistant.AssistantIntent
import com.rayneo.visionclaw.core.assistant.AssistantIntentParser
import com.rayneo.visionclaw.core.learn.LearnLmMemoryStore
import com.rayneo.visionclaw.core.audio.GeminiAudioPlayer
import com.rayneo.visionclaw.core.audio.TtsController
import com.rayneo.visionclaw.core.camera.FrameCaptureManager
import com.rayneo.visionclaw.core.input.RayNeoArdkTrackpadBridge
import com.rayneo.visionclaw.core.input.SpeechInputController
import com.rayneo.visionclaw.core.input.TrackpadGestureEngine
import com.rayneo.visionclaw.core.location.DeviceLocationResolver
import com.rayneo.visionclaw.core.model.DeviceLocationContext
import com.rayneo.visionclaw.core.tools.ToolDispatcher
import com.rayneo.visionclaw.ui.MainPagerAdapter
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.CustomKeyboardView
import com.rayneo.visionclaw.ui.VoiceOscilloscopeView
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import com.rayneo.visionclaw.ui.panels.chat.ChatPanelFragment
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max
import java.security.Security
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/**
 * MainActivity – entry point for AITap on RayNeo X3 Pro.
 *
 * Handles: • XR session / origin null-safety checks • Trackpad gesture engine wiring (short /
 * double tap + swipe) • Edge-zone panel switching (5% left/right edges with 20px center movement) •
 * Speech input & TTS audio output integration • Frame capture for vision-based queries • HUD panel
 * anchoring for the 6 000-nit MicroLED binocular display • API-key-required notification overlay •
 * ViewPager2 hosting Chat HUD (browser launches to original TAPLINKX3 activity)
 */
class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "AITap"
        private const val HUD_NOTIFICATION_DURATION_MS = 3_000L
        private const val HEARTBEAT_UI_INTERVAL_MS = 20_000L
        private const val CHAT_HUD_IDLE_TIMEOUT_MS = 60_000L
        /** Minimum center-movement distance (px) to trigger edge-zone panel switch. */
        private const val EDGE_CENTER_MOVEMENT_THRESHOLD_PX = 48f
        /** Screen edge zone percentage (5% from left or right edge). */
        private const val EDGE_ZONE_PERCENTAGE = 0.05f
        private const val TRACKPAD_EDGE_DEADZONE_PX = 32f
        private const val VOICE_ACTIVATE_BEEP_MS = 90
        private const val VOICE_TIMEOUT_BEEP_MS = 180
        private const val VOICE_LISTEN_START_DELAY_MS = 220L
        private const val VOICE_ACTIVATION_DEBOUNCE_MS = 400L
        private const val GEMINI_LIVE_IDLE_TIMEOUT_MS = 120_000L
        private const val LEARNLM_IDLE_TIMEOUT_MS = 30_000L
        private const val GEMINI_LIVE_ACTIVITY_HEARTBEAT_MS = 250L
        private const val GEMINI_LIVE_CONNECT_TIMEOUT_MS = 15_000L
        private const val GEMINI_AUDIO_SAMPLE_RATE = 16_000
        private const val GEMINI_AUDIO_NON_SILENT_THRESHOLD = 600
        private const val GEMINI_BARGE_IN_MIN_MIC_LEVEL = 0.15f
        private const val GEMINI_BARGE_IN_OUTPUT_MARGIN = 0.13f
        private const val GEMINI_BARGE_IN_OUTPUT_RATIO = 2.10f
        private const val GEMINI_BARGE_IN_HOLD_MS = 420L
        private const val GEMINI_BARGE_IN_COOLDOWN_MS = 1_200L
        private const val GEMINI_BARGE_IN_SUPPRESS_MS = 1_500L
        private const val MULTIMODAL_FRAME_INTERVAL_MS = 2_000L
        private const val CAMERA_IDLE_TIMEOUT_MS = 5_000L
        // AITap: always use Gemini Live directly for continuous camera + voice.
        // Native STT would kill the camera after each utterance.
        private const val USE_NATIVE_STT = false
        private const val OSCILLOSCOPE_USER_COLOR = 0xFFFF4B52.toInt()
        private const val OSCILLOSCOPE_MODEL_COLOR = 0xFF4AA6FF.toInt()
        private const val OSCILLOSCOPE_UI_THROTTLE_MS = 45L
        // MIRROR_FRAME_INTERVAL_NS removed – BinocularSbsLayout handles SBS
        private const val TAP_BROWSER_ACTIVITY_CLASS = "com.TapLinkX3.app.MainActivity"
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"
        private const val EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP = "tapclaw_return_to_chat_double_tap"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUERY = "tapclaw_youtube_autoplay_query"
        private const val EXTRA_YOUTUBE_AUTOPLAY_MODE = "tapclaw_youtube_autoplay_mode"
        private const val GENERIC_SCROLL_SCALE = 22f
        private const val LOCATION_MIN_TIME_MS = 2_000L
        private const val LOCATION_MIN_DISTANCE_METERS = 2f
        private const val LOCATION_SNAPSHOT_MAX_AGE_MS = 15 * 60 * 1000L
        private const val LOCATION_SNAPSHOT_TIMEOUT_MS = 5_000L
        private const val LOCATION_SNAPSHOT_REFRESH_DEBOUNCE_MS = 15_000L
        private const val LOCATION_PRECISE_MAX_AGE_MS = 2 * 60 * 1000L
        private const val LOCATION_PRECISE_MAX_ACCURACY_METERS = 250f
        private const val LOCATION_REJECT_LOW_CONFIDENCE_JUMP_METERS = 10_000f
        private const val LIVE_INPUT_SETTLE_MS = 900L
        private const val LOCAL_DIRECT_OUTPUT_SUPPRESS_MS = 4_000L
    }

    private enum class GeminiLiveState {
        IDLE,
        LISTENING,
        THINKING,
        FOLLOW_UP
    }

    // ── ViewModel & gesture engine ───────────────────────────────────────
    private val viewModel: MainViewModel by viewModels()
    private val gestureEngine = TrackpadGestureEngine()

    // ── Fragment instances (retained across config changes via ViewPager2) ─
    private val chatFragment = ChatPanelFragment()

    // ── Views ────────────────────────────────────────────────────────────
    private var viewPager: ViewPager2? = null
    private var hudNotification: TextView? = null
    private var holdProgressBar: ProgressBar? = null
    private var listeningOverlay: FrameLayout? = null
    private var listeningTranscript: TextView? = null
    private var voiceOscilloscope: VoiceOscilloscopeView? = null
    private var customKeyboardView: CustomKeyboardView? = null
    private var activeTextInput: EditText? = null

    // Screen mirroring removed – BinocularSbsLayout handles SBS rendering

    private val uiHandler = Handler(Looper.getMainLooper())
    private val delayedVoiceStartRunnable = Runnable { startVoiceInputSession() }
    private val stopGeminiCaptureRunnable = Runnable { handleGeminiLiveIdleTimeout() }
    private val liveSetupTimeoutRunnable = Runnable {
        if (geminiLiveSession != null && !liveSessionReady) {
            handleGeminiVoiceFailure("Gemini Live connection timed out. Try again.")
        }
    }
    private val settledLiveInputRunnable = Runnable {
        val safe = pendingLiveInputTranscript.trim()
        if (safe.isBlank()) return@Runnable
        if (safe == lastHandledLiveInputTranscript) return@Runnable

        lastHandledLiveInputTranscript = safe
        lastToolAssistTranscript = safe
        toolAssistRecoveryFired = false

        // ── LearnLM continuation fast-path: stay in Gemini Live voice ──
        // Intercept before maybeAssist so we never do the slow HTTP call
        if (geminiLiveSession != null && AssistantIntentParser.isExplicitLearnRequest(safe)) {
            val isContinuation = safe.lowercase().let { l ->
                l.contains("continue") || l.contains("last problem") || l.contains("pick up") ||
                    l.contains("where we left off") || l.contains("previous") || l.contains("resume")
            }
            if (isContinuation) {
                Log.d(TAG, "LearnLM continuation fast-path — building context from disk")
                learnLmToolCallActive = true
                keepLearnLmSessionAliveUntilManualClose = true
                armSilenceWatchdog()
                lifecycleScope.launch(Dispatchers.IO) {
                    try {
                        val recentCards = viewModel.getAssistantCardsSnapshot().map { it.text }
                        val ctx = learnLmMemoryStore.buildContext(safe, recentCards)
                        val lesson = ctx.priorLessons.firstOrNull()
                        val contextText = if (lesson != null) {
                            buildString {
                                append("[LEARNLM CONTINUATION — The user wants to continue their previous tutoring session]\n")
                                append("Previous topic: ${lesson.topic}\n")
                                append("Previous question: ${lesson.query}\n")
                                append("Previous lesson summary: ${lesson.summary}\n")
                                lesson.lessonExcerpt?.takeIf { it.isNotBlank() }?.let {
                                    append("Lesson excerpt: ${it.take(500)}\n")
                                }
                                append("\nStart by giving a brief verbal summary of where we left off on this problem, ")
                                append("then ask the user what they'd like to focus on next. Keep the voice conversation going.")
                            }
                        } else {
                            "[LEARNLM CONTINUATION — The user wants to continue a previous tutoring session but no saved lesson was found. " +
                                "Ask them what problem they'd like to work on. Keep the voice conversation going.]"
                        }
                        val sent = geminiLiveSession?.sendClientText(contextText) == true
                        Log.d(TAG, "LearnLM continuation context injected=$sent, topic=${lesson?.topic}")
                    } catch (e: Exception) {
                        Log.e(TAG, "LearnLM continuation fast-path error", e)
                    }
                }
                return@Runnable
            }
        }

        if (maybeRouteLocalIntentDirectly(safe)) {
            return@Runnable
        }

        val engine = toolAssistEngine ?: return@Runnable
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assist = engine.maybeAssist(safe) ?: return@launch
                Log.d(TAG, "ToolAssist matched [${assist.toolName}]: ${assist.resultText.take(200)}")

                // LearnLM continuation prefers staying in Gemini Live voice
                if (assist.preferLiveVoice && geminiLiveSession != null) {
                    Log.d(TAG, "ToolAssist routing learn continuation through Gemini Live voice")
                    // Set learnlm flags so 30s timeout applies
                    learnLmToolCallActive = true
                    keepLearnLmSessionAliveUntilManualClose = true
                    armSilenceWatchdog()
                    val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                    Log.d(TAG, "ToolAssist learn continuation injected=$sent")
                    if (!sent) {
                        // Fallback to local if injection failed
                        runOnUiThread { presentToolAssistLocally(assist.toolName, assist.resultText) }
                    }
                    return@launch
                }

                if (shouldOwnToolAssistLocally(assist.toolName)) {
                    runOnUiThread {
                        presentToolAssistLocally(assist.toolName, assist.resultText)
                    }
                    return@launch
                }
                val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                Log.d(TAG, "ToolAssist injected clientContent sent=$sent")
                runOnUiThread {
                    viewModel.appendLiveAssistantStreamChunk(assist.resultText)
                    viewModel.commitLiveAssistantStreamIfNeeded()
                    showHudNotification(assist.resultText.take(120))
                }
            } catch (e: Exception) {
                Log.e(TAG, "ToolAssist error", e)
            }
        }
    }
    private val cameraIdleTimeoutRunnable = Runnable {
        if (!cameraCaptureActive) return@Runnable
        val recentVoiceActivity =
                (SystemClock.uptimeMillis() - lastUserSpeechActivityMs) < CAMERA_IDLE_TIMEOUT_MS
        if (isGeminiListeningOrThinking() || recentVoiceActivity) {
            scheduleCameraIdleTimeout()
        } else {
            stopCameraCapture()
            showHudNotification("Camera auto-off")
        }
    }
    private val hideHudNotificationRunnable = Runnable {
        hudNotification
                ?.animate()
                ?.alpha(0f)
                ?.setDuration(220)
                ?.withEndAction { hudNotification?.visibility = View.GONE }
                ?.start()
    }
    private val chatHudIdleRunnable = Runnable {
        val currentPanel = viewPager?.currentItem ?: return@Runnable
        if (currentPanel != MainViewModel.PANEL_CHAT) return@Runnable
        if (chatFragment.isHudModeEnabled()) return@Runnable
        if (isGeminiListeningOrThinking()) {
            scheduleChatHudIdleTimer()
            return@Runnable
        }
        chatFragment.setHudModeEnabled(true)
    }
    private val hudStatePushRunnable = object : Runnable {
        override fun run() {
            pushHudStateToChatFragment(force = false)
            uiHandler.postDelayed(this, 2000L)
        }
    }

    // ── Speech & Audio ───────────────────────────────────────────────────
    private var speechController: SpeechInputController? = null
    private var geminiLiveSession:
            com.rayneo.visionclaw.core.network.GeminiRouter.LiveSessionHandle? =
            null
    private var geminiAudioRecord: AudioRecord? = null
    private var geminiAudioThread: Thread? = null
    @Volatile private var geminiCaptureActive = false
    @Volatile private var liveSessionReady = false
    @Volatile private var liveSessionClosingByApp = false
    @Volatile private var liveState = GeminiLiveState.IDLE
    @Volatile private var awaitingServerTurnComplete = false
    private var latestLiveTranscript = ""
    private var latestLiveOutputTranscript = ""
    @Volatile private var pendingLiveInputTranscript = ""
    @Volatile private var lastHandledLiveInputTranscript = ""
    @Volatile private var lastLiveActivityHeartbeatMs = 0L
    @Volatile private var lastMultimodalFrameSentMs = 0L
    @Volatile private var lastUserSpeechActivityMs = 0L
    @Volatile private var forceDirectGeminiLive = true
    @Volatile private var lastVoiceActivationMs = 0L
    /** Monotonically increasing counter to detect stale WebSocket callbacks from old sessions. */
    @Volatile private var geminiSessionEpoch = 0L
    @Volatile private var suppressGeminiOutputUntilMs = 0L
    @Volatile private var keepLearnLmSessionAliveUntilManualClose = false
    @Volatile private var learnLmToolCallActive = false
    @Volatile private var nativeSttFallbackTriggered = false
    private lateinit var toolDispatcher: ToolDispatcher
    private var toolAssistEngine: com.rayneo.visionclaw.core.tools.ToolAssistEngine? = null
    private lateinit var learnLmMemoryStore: LearnLmMemoryStore
    private var companionServer: com.rayneo.visionclaw.core.config.CompanionServer? = null
    private lateinit var oauthManager: com.rayneo.visionclaw.core.network.GoogleOAuthManager

    private var lastKnownAiConnectionStatus = ChatPanelFragment.ConnectionStatus.IDLE
    private var sawNonSilentGeminiAudio = false
    private var loggedGeminiAudioProbe = false
    private var rayNeoMicRouteActive = false
    private var geminiAcousticEchoCanceler: AcousticEchoCanceler? = null
    private var geminiNoiseSuppressor: NoiseSuppressor? = null
    private var geminiAutomaticGainControl: AutomaticGainControl? = null
    @Volatile private var geminiBargeInCandidateSinceMs = 0L
    @Volatile private var geminiBargeInLastTriggerMs = 0L
    private var frameCapture: FrameCaptureManager? = null
    private var geminiAudioPlayer: GeminiAudioPlayer? = null
    private var ttsController: TtsController? = null
    private var toneGenerator: ToneGenerator? = null
    private var latestFrame: String? = null
    private var cameraCaptureActive = false
    private var coreEyeSurfaceReady = false
    private var pendingCameraStart = false
    @Volatile private var lastOscilloscopeUiUpdateMs = 0L
    private var lastPushedCalendarSummary = ""
    private var lastPushedTasksSummary = ""
    private var lastPushedNewsSummary = ""
    private var lastPushedAqiText: String? = null
    private var lastPushedAqiValue: Int? = null
    private var lastPushedRadioName: String? = null
    private var lastPushedRadioPlaying = false
    private var pendingFocusNewChatOnResume = false

    // ── Edge-zone tracking ───────────────────────────────────────────────
    private var swipeStartX = 0f
    private var swipeStartY = 0f
    private var isTrackingSwipe = false
    private var edgeZoneLeft = 0f
    private var edgeZoneRight = 0f
    private var initialPageSnapDone = false

    // ── Runtime permissions ───────────────────────────────────────────────
    private var micPermissionGranted = false
    private var cameraPermissionGranted = false
    private var locationPermissionGranted = false
    private var locationManager: LocationManager? = null
    private var locationTrackingActive = false
    private lateinit var deviceLocationResolver: DeviceLocationResolver
    @Volatile private var lastLocationSnapshotRefreshElapsedMs = 0L
    private val locationListener =
            LocationListener { location -> publishDeviceLocationContext(location) }

    private val permissionLauncher =
            registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { grants
                ->
                micPermissionGranted =
                        grants[Manifest.permission.RECORD_AUDIO]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.RECORD_AUDIO
                                ) == PackageManager.PERMISSION_GRANTED)
                cameraPermissionGranted =
                        grants[Manifest.permission.CAMERA]
                                ?: (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                                        PackageManager.PERMISSION_GRANTED)
                val fineGranted =
                        grants[Manifest.permission.ACCESS_FINE_LOCATION]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_FINE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                val coarseGranted =
                        grants[Manifest.permission.ACCESS_COARSE_LOCATION]
                                ?: (ContextCompat.checkSelfPermission(
                                        this,
                                        Manifest.permission.ACCESS_COARSE_LOCATION
                                ) == PackageManager.PERMISSION_GRANTED)
                locationPermissionGranted = fineGranted || coarseGranted
                Log.i(
                        TAG,
                        "Permissions — mic=$micPermissionGranted camera=$cameraPermissionGranted location=$locationPermissionGranted"
                )
                syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
                if (locationPermissionGranted) {
                    startLocationTracking()
                    refreshLocationSnapshot(force = true)
                } else {
                    stopLocationTracking()
                    viewModel.clearDeviceLocationContext()
                }
            }

    override fun attachBaseContext(newBase: Context) {
        val config = Configuration(newBase.resources.configuration).apply {
            densityDpi = DisplayMetrics.DENSITY_MEDIUM
        }
        super.attachBaseContext(newBase.createConfigurationContext(config))
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    private fun configureDnsCaching() {
        runCatching {
            Security.setProperty("networkaddress.cache.ttl", "60")
            Security.setProperty("networkaddress.cache.negative.ttl", "0")
            System.setProperty("networkaddress.cache.ttl", "60")
            System.setProperty("networkaddress.cache.negative.ttl", "0")
            Log.d(TAG, "Configured DNS cache policy: ttl=60 negativeTtl=0")
        }.onFailure {
            Log.w(TAG, "Failed configuring DNS cache policy: ${it.message}")
        }
    }

    private fun bindProcessToValidatedWifi() {
        runCatching {
            val connectivityManager = getSystemService(ConnectivityManager::class.java) ?: return
            val activeNetwork = connectivityManager.activeNetwork
            val capabilities = activeNetwork?.let { connectivityManager.getNetworkCapabilities(it) }
            val unbound = connectivityManager.bindProcessToNetwork(null)
            Log.d(
                TAG,
                "Cleared process network binding unbound=$unbound activeNetwork=$activeNetwork validated=${
                    capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true
                } wifi=${capabilities?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true}"
            )
        }.onFailure {
            Log.w(TAG, "Failed clearing process network binding: ${it.message}")
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        // Initialize Mercury SDK for binocular (both lenses) display — must be before super.
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        super.onCreate(savedInstanceState)
        configureDnsCaching()
        bindProcessToValidatedWifi()

        // ── Immersive full-screen for AR HUD ─────────────────────────
        configureImmersiveDisplay()
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )

        setContentView(R.layout.activity_main)

        // ── Bind views (null-safe) ───────────────────────────────────
        viewPager = findViewById(R.id.view_pager)
        hudNotification = findViewById(R.id.hud_notification)
        holdProgressBar = findViewById(R.id.hold_progress)
        listeningOverlay = findViewById(R.id.listening_overlay)
        listeningTranscript = findViewById(R.id.listening_transcript)
        voiceOscilloscope = findViewById(R.id.voice_oscilloscope)
        customKeyboardView = findViewById(R.id.custom_keyboard_view)

        setupCustomKeyboard()

        // ── Initialize OAuth manager and API clients ─────────────────────
        val prefs = viewModel.preferences
        // Clear stale TapRadio "now playing" state from previous session on cold start
        // The radio isn't actually playing when the app restarts
        getSharedPreferences("visionclaw_prefs", MODE_PRIVATE).edit()
            .putBoolean("tapradio_now_playing_active", false)
            .remove("tapradio_now_playing_name")
            .remove("tapradio_now_playing_genre")
            .apply()

        oauthManager = com.rayneo.visionclaw.core.network.GoogleOAuthManager(prefs, this)
        deviceLocationResolver = DeviceLocationResolver(this)

        val calendarClient = com.rayneo.visionclaw.core.network.GoogleCalendarClient(
            apiKeyProvider = { prefs.calendarApiKey },
            accessTokenProvider = {
                kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
            },
            context = this
        )
        viewModel.setCalendarClient(calendarClient)

        val directionsClient = com.rayneo.visionclaw.core.network.GoogleDirectionsClient(
            apiKeyProvider = { prefs.googleMapsApiKey },
            context = this
        )

        val tasksClient = com.rayneo.visionclaw.core.network.GoogleTasksClient(
            accessTokenProvider = {
                kotlinx.coroutines.runBlocking { oauthManager.getValidAccessToken() }
            },
            context = this
        )
        viewModel.setTasksClient(tasksClient)

        val placesClient = com.rayneo.visionclaw.core.network.GooglePlacesClient(
            apiKeyProvider = { prefs.googleMapsApiKey },
            context = this
        )

        val airQualityClient = com.rayneo.visionclaw.core.network.GoogleAirQualityClient(
            apiKeyProvider = { prefs.googleMapsApiKey },
            context = this
        )
        viewModel.setAirQualityClient(airQualityClient)
        val weatherClient = com.rayneo.visionclaw.core.network.OpenMeteoWeatherClient(
            context = this
        )

        val deviceLocationLambda: () -> DeviceLocationContext? = {
            getToolReadyLocationContext()
        }

        // OpenClaw/TapClaw: use companion UI settings first, fall back to
        // device token + endpoint stored during QR/node pairing.
        val pairingPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val openClawClient = com.rayneo.visionclaw.core.network.OpenClawClient(
            gatewayUrlProvider = {
                prefs.openClawEndpoint.takeIf { it.isNotBlank() }
                    ?: pairingPrefs.getString("openclaw_pair_device_token_gateway", null)
                        ?.takeIf { it.isNotBlank() }
            },
            gatewayTokenProvider = {
                prefs.openClawToken.takeIf { it.isNotBlank() }
                    ?: pairingPrefs.getString("openclaw_pair_device_token", null)
                        ?.takeIf { it.isNotBlank() }
            },
            deviceIdProvider = {
                pairingPrefs.getString("openclaw_pair_device_id", null)
                    ?.takeIf { it.isNotBlank() }
            },
            publicKeyProvider = {
                pairingPrefs.getString("openclaw_pair_public_key", null)
                    ?.takeIf { it.isNotBlank() }
            },
            privateKeyProvider = {
                pairingPrefs.getString("openclaw_pair_private_key", null)
                    ?.takeIf { it.isNotBlank() }
            },
            sessionIdProvider = { prefs.openClawSessionId.ifBlank { "main" } },
            timeoutMsProvider = {
                val t = prefs.openClawTimeoutSeconds
                if (t > 0) t * 1000 else 30_000
            }
        )
        // Store client reference for periodic ping and start idle status ticker.
        openClawClientField = openClawClient
        startOpenClawPing()

        // Show OpenClaw streaming progress as scrolling heartbeat text under
        // the clock — throttled to once every 5 s during active tasks.
        openClawClient.onProgressUpdate = { deltaText ->
            // Always capture the latest text (cheap, no UI work).
            lastTapClawHeartbeat = deltaText.take(200).replace('\n', ' ')

            val now = android.os.SystemClock.uptimeMillis()
            // Active task updates use a fixed 5 s throttle (preference controls idle ping)
            if (now - lastHeartbeatUiUpdateMs >= 5_000L) {
                lastHeartbeatUiUpdateMs = now
                runOnUiThread {
                    val lower = deltaText.lowercase()
                    val hudLabel = when {
                        lower.contains("installing") -> "Installing app..."
                        lower.contains("install permission") || lower.contains("approval needed") ->
                            "Needs permission to install an app"
                        lower.contains("opening tab") || lower.contains("switching to tab") ->
                            "Opening tab..."
                        lower.contains("tab found") || lower.contains("reusing tab") ->
                            "Found existing tab"
                        lower.contains("app not found") -> "App not found — checking options..."
                        lower.contains("searching tabs") || lower.contains("scanning tabs") ->
                            "Scanning Chrome tabs..."
                        lower.contains("task complete") -> "Task complete"
                        else -> deltaText.take(120).replace('\n', ' ')
                    }
                    chatFragment.showHeartbeat(hudLabel, 25_000L)
                    chatFragment.setStreamActiveIndicator(true)
                }
            }
        }
        toolDispatcher = ToolDispatcher(
            this, calendarClient, directionsClient, tasksClient,
            placesClient = placesClient,
            airQualityClient = airQualityClient,
            weatherClient = weatherClient,
            learnLmRouter = viewModel.learnLmRouter,
            recentCardsProvider = { viewModel.getAssistantCardsSnapshot().map { it.text } },
            locationProvider = deviceLocationLambda,
            openClawClient = openClawClient,
            cameraFrameProvider = { latestFrame },
            batteryLevelProvider = { getBatteryLevel() },
            isChargingProvider = { isBatteryCharging() },
            toggleBatterySaver = { enabled -> onBatterySaverToggled(enabled) }
        )

        // ToolAssistEngine: client-side tool execution for Live model
        // which may have unreliable function calling.
        toolAssistEngine = com.rayneo.visionclaw.core.tools.ToolAssistEngine(
            toolDispatcher = toolDispatcher,
            locationProvider = deviceLocationLambda
        )

        learnLmMemoryStore = LearnLmMemoryStore(this)

        viewModel.setMultimodalCameraEnabled(false)
        viewModel.setMultimodalTextureReady(false)

        // Start companion config server so phone can configure AITap via WiFi
        val serverPort = viewModel.appConfig.debugServerSettings.port
        companionServer = com.rayneo.visionclaw.core.config.CompanionServer(
            this, serverPort, oauthManager,
            locationProvider = deviceLocationLambda,
            calendarSummaryProvider = { viewModel.calendarSummary.value },
            tasksSummaryProvider = { viewModel.tasksSummary.value },
            newsSummaryProvider = { viewModel.newsSummary.value },
            airQualityTextProvider = { viewModel.airQualitySummary.value?.text },
            airQualityValueProvider = { viewModel.airQualitySummary.value?.aqi },
            phoneLocationConsumer = { context ->
                runOnUiThread {
                    if (context != null) {
                        publishDeviceLocationContext(context)
                    } else if (viewModel.getDeviceLocationContext()?.provider == "companion_phone") {
                        viewModel.clearDeviceLocationContext()
                        pushHudStateToChatFragment(force = true)
                    }
                }
            },
            cameraFrameProvider = {
                // Convert the base64 latestFrame to raw JPEG bytes for the HTTP endpoint.
                // Returns null if no frame is available (camera not active).
                latestFrame?.let { base64 ->
                    try {
                        android.util.Base64.decode(base64, android.util.Base64.NO_WRAP)
                    } catch (e: Exception) {
                        null
                    }
                }
            }
        )
        companionServer?.startServer()
        Log.d(TAG, "Companion config server available at http://<glasses-ip>:$serverPort")

        // ── Calculate edge zones + gesture side awareness ─────────────────
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        edgeZoneLeft = screenWidth * EDGE_ZONE_PERCENTAGE
        edgeZoneRight = screenWidth * (1f - EDGE_ZONE_PERCENTAGE)
        gestureEngine.setScreenSize(screenWidth, screenHeight)

        // ── XR Origin / session safety checks ────────────────────────
        initXrOriginSafe()

        // ── ViewPager setup ──────────────────────────────────────────
        setupViewPager()
        chatFragment.setCoreEyeSurfaceListener(
                object : ChatPanelFragment.CoreEyeSurfaceListener {
                    override fun onSurfaceAvailable() {
                        coreEyeSurfaceReady = true
                        viewModel.setMultimodalTextureReady(true)
                        syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
                    }

                    override fun onSurfaceDestroyed() {
                        coreEyeSurfaceReady = false
                        viewModel.setMultimodalTextureReady(false)
                        if (cameraCaptureActive) {
                            stopCameraCapture()
                        }
                        pendingCameraStart =
                                cameraPermissionGranted && viewModel.voiceAssistantActive.value == true
                    }
                }
        )
        chatFragment.setCardActionListener(
                object : ChatPanelFragment.CardActionListener {
                    override fun onAssistantRequested() {
                        runOnUiThread { activateChatVoiceAssistant() }
                    }
                }
        )

        // ── Trackpad gesture engine ──────────────────────────────────
        setupGestureEngine()

        // ── Speech input controller ──────────────────────────────────
        if (USE_NATIVE_STT) {
            speechController =
                    SpeechInputController(
                            this,
                            object : SpeechInputController.Listener {
                                override fun onSpeechResult(text: String) {
                                    handleSpeechResult(text)
                                }

                                override fun onSpeechPartial(text: String) {
                                    handleSpeechPartial(text)
                                }

                                override fun onSpeechStatus(status: String) {
                                    runOnUiThread { updateListeningTranscript(status) }
                                }

                                override fun onSpeechError(errorCode: Int) {
                                    handleSpeechError(errorCode)
                                }
                            }
                    )
        }

        // ── Frame capture manager ────────────────────────────────────
        frameCapture = FrameCaptureManager(this)

        // ── TTS controller (triggered via voice command, not double-tap) ─
        geminiAudioPlayer = GeminiAudioPlayer(this).also { player ->
            player.onDrainComplete = {
                // Called on a background thread once all buffered audio has
                // been played.  NOW arm the silence watchdog so the idle
                // timeout only begins after the user has heard the full reply.
                Log.d(TAG, "GeminiAudioPlayer drain complete — arming silence watchdog")
                runOnUiThread { armSilenceWatchdog() }
            }
        }
        if (viewModel.preferences.ttsVolume <= 0f) {
            viewModel.preferences.ttsVolume = 0.80f
        }
        ttsController = TtsController(this, viewModel.preferences)
        toneGenerator =
                runCatching { ToneGenerator(AudioManager.STREAM_NOTIFICATION, 85) }.getOrNull()

        // ── Wire trackpad scroll → active panel onTrackpadScroll ─────
        gestureEngine.onScroll = { deltaX, deltaY ->
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    customKeyboardView?.handleTrackpadSwipe(deltaX, deltaY)
                } else {
                    currentTrackpadPanel()?.onTrackpadPan(deltaX, deltaY)
                }
            }
        }

        // ── Observe ViewModel events ─────────────────────────────────
        observeViewModel()
        viewModel.refreshHudUpcomingCalendar(force = true)
        pushHudStateToChatFragment(force = true)
        uiHandler.postDelayed({ pushHudStateToChatFragment(force = true) }, 1500L)
        uiHandler.postDelayed({ pushHudStateToChatFragment(force = true) }, 5000L)
        uiHandler.postDelayed({ pushHudStateToChatFragment(force = true) }, 9000L)
        applyInitialPageSelection()
        viewPager?.post { syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true) }

        // ── Request runtime permissions for mic + camera ─────────────
        requestRequiredPermissions()

        Log.i(TAG, "AITap MainActivity created successfully")
    }

    override fun onResume() {
        super.onResume()
        bindProcessToValidatedWifi()
        if (!initialPageSnapDone) {
            initialPageSnapDone = true
            applyInitialPageSelection()
        }
        viewModel.setMultimodalTextureReady(
                coreEyeSurfaceReady && chatFragment.isCoreEyeSurfaceReady()
        )
        syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
        handlePanelChanged(viewPager?.currentItem ?: MainViewModel.PANEL_CHAT)
        uiHandler.removeCallbacks(hudStatePushRunnable)
        uiHandler.post(hudStatePushRunnable)
        if (locationPermissionGranted) {
            startLocationTracking()
            refreshLocationSnapshot(force = false)
        }
        viewModel.refreshHudUpcomingCalendar(force = false)
        exitTextInputMode()
        if (pendingFocusNewChatOnResume) {
            chatFragment.view?.post {
                chatFragment.focusNewChatCard(animate = false)
                pendingFocusNewChatOnResume = false
            }
        }
    }

    override fun onPause() {
        uiHandler.removeCallbacks(delayedVoiceStartRunnable)
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        uiHandler.removeCallbacks(chatHudIdleRunnable)
        uiHandler.removeCallbacks(hudStatePushRunnable)
        pendingCameraStart = false
        releaseGeminiAudioCapture(cancelOnly = true)
        geminiAudioPlayer?.stopAndFlush()
        ttsController?.stop()
        hideCustomKeyboard(clearFocus = true)
        stopCameraCapture()
        stopLocationTracking()
        super.onPause()
    }

    override fun onDestroy() {
        companionServer?.stopServer()
        companionServer = null
        gestureEngine.release()
        chatFragment.setCoreEyeSurfaceListener(null)
        chatFragment.setCardActionListener(null)
        speechController?.destroy()
        releaseGeminiAudioCapture(cancelOnly = true)
        viewModel.setMultimodalTextureReady(false)
        viewModel.setMultimodalCameraEnabled(false)
        frameCapture?.shutdown()
        geminiAudioPlayer?.release()
        geminiAudioPlayer = null
        ttsController?.shutdown()
        toneGenerator?.release()
        toneGenerator = null
        viewPager = null
        hudNotification = null
        holdProgressBar = null
        listeningOverlay = null
        listeningTranscript = null
        voiceOscilloscope = null
        customKeyboardView = null
        activeTextInput = null
        uiHandler.removeCallbacksAndMessages(null)
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        stopLocationTracking()
        super.onDestroy()
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        configureImmersiveDisplay()
        // Recalculate edge zones + gesture side awareness on config change
        val screenWidth = resources.displayMetrics.widthPixels
        val screenHeight = resources.displayMetrics.heightPixels
        edgeZoneLeft = screenWidth * EDGE_ZONE_PERCENTAGE
        edgeZoneRight = screenWidth * (1f - EDGE_ZONE_PERCENTAGE)
        gestureEngine.setScreenSize(screenWidth, screenHeight)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Runtime Permissions
    // ══════════════════════════════════════════════════════════════════════

    private fun requestRequiredPermissions() {
        val needed = mutableListOf<String>()

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionGranted = true
        } else {
            needed.add(Manifest.permission.RECORD_AUDIO)
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionGranted = true
        } else {
            needed.add(Manifest.permission.CAMERA)
        }

        val fineGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val coarseGranted =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        locationPermissionGranted = fineGranted || coarseGranted
        if (!locationPermissionGranted) {
            needed.add(Manifest.permission.ACCESS_FINE_LOCATION)
            needed.add(Manifest.permission.ACCESS_COARSE_LOCATION)
        }

        if (needed.isNotEmpty()) {
            permissionLauncher.launch(needed.toTypedArray())
        } else {
            syncCameraToGeminiState(viewModel.voiceAssistantActive.value == true)
            startLocationTracking()
            refreshLocationSnapshot(force = true)
        }
    }

    private fun startLocationTracking() {
        if (locationTrackingActive || !locationPermissionGranted) return

        val manager =
                locationManager
                        ?: getSystemService(LocationManager::class.java)?.also {
                            locationManager = it
                        }
                        ?: return

        publishBestLastKnownLocation(manager)

        var requested = false
        val hasFine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED

        if (hasFine && manager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.GPS_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request GPS updates: ${it.message}") }
        }

        if ((hasFine || hasCoarse) && manager.isProviderEnabled(LocationManager.FUSED_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.FUSED_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request fused location updates: ${it.message}") }
        }

        if (hasCoarse && manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.NETWORK_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request network location updates: ${it.message}") }
        }

        if (hasCoarse && manager.isProviderEnabled(LocationManager.PASSIVE_PROVIDER)) {
            runCatching {
                        manager.requestLocationUpdates(
                                LocationManager.PASSIVE_PROVIDER,
                                LOCATION_MIN_TIME_MS,
                                LOCATION_MIN_DISTANCE_METERS,
                                locationListener,
                                Looper.getMainLooper()
                        )
                    }
                    .onSuccess { requested = true }
                    .onFailure { Log.w(TAG, "Failed to request passive location updates: ${it.message}") }
        }

        locationTrackingActive = requested
        if (requested) {
            Log.i(TAG, "Location tracking enabled")
            refreshLocationSnapshot(force = false)
        } else {
            Log.w(TAG, "Location tracking unavailable; no providers registered")
            refreshLocationSnapshot(force = true)
        }
    }

    private fun stopLocationTracking() {
        if (!locationTrackingActive) return
        runCatching { locationManager?.removeUpdates(locationListener) }
                .onFailure { Log.w(TAG, "Failed to remove location updates: ${it.message}") }
        locationTrackingActive = false
    }

    private fun publishBestLastKnownLocation(manager: LocationManager) {
        val hasFine =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        val hasCoarse =
                ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                        PackageManager.PERMISSION_GRANTED
        if (!hasFine && !hasCoarse) return

        val providers = mutableListOf<String>()
        if (hasFine) {
            providers += LocationManager.GPS_PROVIDER
        }
        if (hasFine || hasCoarse) {
            providers += LocationManager.FUSED_PROVIDER
        }
        if (hasCoarse) {
            providers += LocationManager.NETWORK_PROVIDER
            providers += LocationManager.PASSIVE_PROVIDER
        }

        var best: Location? = null
        providers.forEach { provider ->
            val candidate = runCatching { manager.getLastKnownLocation(provider) }.getOrNull() ?: return@forEach
            best = selectBetterLocation(current = best, candidate = candidate)
        }
        best?.takeIf {
            val ageMs = System.currentTimeMillis() - (it.time.takeIf { ts -> ts > 0L } ?: System.currentTimeMillis())
            val accuracy = if (it.hasAccuracy()) it.accuracy else Float.MAX_VALUE
            ageMs <= LOCATION_SNAPSHOT_MAX_AGE_MS && accuracy <= 500f
        }?.let { publishDeviceLocationContext(it) }
    }

    private fun selectBetterLocation(current: Location?, candidate: Location): Location {
        if (current == null) return candidate
        val candidateTime = candidate.time
        val currentTime = current.time
        val candidateAccuracy = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
        val currentAccuracy = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
        val timeDelta = candidateTime - currentTime

        return when {
            timeDelta > 120_000L -> candidate
            timeDelta < -120_000L -> current
            candidateAccuracy + 10f < currentAccuracy -> candidate
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> candidate
            else -> current
        }
    }

    private fun publishDeviceLocationContext(location: Location) {
        publishDeviceLocationContext(
            DeviceLocationContext(
                latitude = location.latitude,
                longitude = location.longitude,
                accuracyMeters = if (location.hasAccuracy()) location.accuracy else null,
                altitudeMeters = if (location.hasAltitude()) location.altitude else null,
                speedMps = if (location.hasSpeed()) location.speed else null,
                bearingDeg = if (location.hasBearing()) location.bearing else null,
                provider = location.provider,
                timestampMs = location.time.takeIf { it > 0L } ?: System.currentTimeMillis()
            )
        )
    }

    private fun publishDeviceLocationContext(context: DeviceLocationContext) {
        val current = viewModel.getDeviceLocationContext()
        if (!shouldAcceptLocationUpdate(current, context)) {
            Log.d(
                TAG,
                "Ignoring lower-quality location update provider=${context.provider} lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters}"
            )
            return
        }
        viewModel.updateDeviceLocationContext(context)
        Log.d(
                TAG,
                "Location update provider=${context.provider} lat=${context.latitude} lon=${context.longitude} acc=${context.accuracyMeters}"
        )
        viewModel.refreshHudUpcomingCalendar(force = false)
        runOnUiThread {
            pushHudStateToChatFragment(force = true)
        }
    }

    private fun getToolReadyLocationContext(): DeviceLocationContext? {
        viewModel.getDeviceLocationContext()?.takeIf(::isPreciseLocationContext)?.let { return it }
        deviceLocationResolver.peekCached(
            maxAgeMs = LOCATION_PRECISE_MAX_AGE_MS,
            maxAccuracyMeters = LOCATION_PRECISE_MAX_ACCURACY_METERS,
            allowApproximate = false
        )?.let { cached ->
            publishDeviceLocationContext(cached)
            return viewModel.getDeviceLocationContext()?.takeIf(::isPreciseLocationContext) ?: cached
        }
        val resolved = deviceLocationResolver.resolveNavigationBlocking()
        if (resolved != null) {
            publishDeviceLocationContext(resolved)
        }
        return viewModel.getDeviceLocationContext()?.takeIf(::isPreciseLocationContext) ?: resolved
    }

    private fun isPreciseLocationContext(context: DeviceLocationContext): Boolean {
        val ageMs = System.currentTimeMillis() - context.timestampMs
        val accuracy = context.accuracyMeters ?: Float.MAX_VALUE
        return ageMs <= LOCATION_PRECISE_MAX_AGE_MS &&
            accuracy <= LOCATION_PRECISE_MAX_ACCURACY_METERS &&
            context.provider != "ip_geolocation"
    }

    private fun isLowConfidenceLocationContext(context: DeviceLocationContext): Boolean {
        val accuracy = context.accuracyMeters ?: Float.MAX_VALUE
        return context.provider == "ip_geolocation" || accuracy > 1_000f
    }

    private fun distanceMeters(a: DeviceLocationContext, b: DeviceLocationContext): Float {
        val results = FloatArray(1)
        Location.distanceBetween(a.latitude, a.longitude, b.latitude, b.longitude, results)
        return results.firstOrNull() ?: Float.MAX_VALUE
    }

    private fun shouldAcceptLocationUpdate(
        current: DeviceLocationContext?,
        candidate: DeviceLocationContext
    ): Boolean {
        if (current == null) return candidate.provider != "ip_geolocation" || candidate.accuracyMeters != null
        val candidateAgeMs = System.currentTimeMillis() - candidate.timestampMs
        if (candidateAgeMs > LOCATION_SNAPSHOT_MAX_AGE_MS) return false

        val timeDelta = candidate.timestampMs - current.timestampMs
        val candidateAccuracy = candidate.accuracyMeters ?: Float.MAX_VALUE
        val currentAccuracy = current.accuracyMeters ?: Float.MAX_VALUE
        val currentApproximate = current.provider == "ip_geolocation"
        val candidateApproximate = candidate.provider == "ip_geolocation"
        val candidateTriangulated =
            candidate.provider == "wifi_geolocation" || candidate.provider == "network_geolocation"
        val currentIsPhoneBridge = current.provider == "companion_phone"
        val candidateIsPhoneBridge = candidate.provider == "companion_phone"
        val phoneBridgeEnabled = viewModel.preferences.phoneLocationBridgeEnabled
        val currentDistanceToCandidate = distanceMeters(current, candidate)

        if (phoneBridgeEnabled) {
            if (candidateIsPhoneBridge) return true
            if (currentIsPhoneBridge) {
                val currentPhoneAgeMs = System.currentTimeMillis() - current.timestampMs
                if (currentPhoneAgeMs <= 60_000L) return false
            }
        }

        if (candidateApproximate && !currentApproximate) return false
        if (isLowConfidenceLocationContext(candidate) &&
            !isLowConfidenceLocationContext(current) &&
            currentDistanceToCandidate > LOCATION_REJECT_LOW_CONFIDENCE_JUMP_METERS
        ) {
            Log.w(
                TAG,
                "Rejecting low-confidence far jump provider=${candidate.provider} distance=${currentDistanceToCandidate.toInt()}m acc=${candidate.accuracyMeters}"
            )
            return false
        }

        return when {
            currentApproximate && candidate.provider != "ip_geolocation" -> true
            timeDelta > 120_000L -> true
            timeDelta < -120_000L -> false
            candidate.provider == LocationManager.GPS_PROVIDER && current.provider != LocationManager.GPS_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 25f -> true
            candidate.provider == LocationManager.FUSED_PROVIDER && current.provider == LocationManager.NETWORK_PROVIDER &&
                candidateAccuracy <= currentAccuracy + 25f -> true
            candidateAccuracy + 25f < currentAccuracy -> true
            candidateTriangulated && currentAccuracy > 1_000f && candidateAccuracy <= 250f -> true
            timeDelta > 0L && candidateAccuracy <= currentAccuracy + 50f -> true
            else -> false
        }
    }

    private fun pushHudStateToChatFragment(force: Boolean) {
        syncTapRadioHudStateFromPrefs()
        val calendarSummary = viewModel.calendarSummary.value
        val tasksSummary = viewModel.tasksSummary.value
        val newsSummary = viewModel.newsSummary.value
        val airQualityState = viewModel.airQualitySummary.value
        val radioState = viewModel.radioSummary.value
        val changed = force ||
            calendarSummary != lastPushedCalendarSummary ||
            tasksSummary != lastPushedTasksSummary ||
            newsSummary != lastPushedNewsSummary ||
            airQualityState?.text != lastPushedAqiText ||
            airQualityState?.aqi != lastPushedAqiValue ||
            radioState?.stationName != lastPushedRadioName ||
            (radioState?.playing == true) != lastPushedRadioPlaying
        if (!changed) return
        lastPushedCalendarSummary = calendarSummary
        lastPushedTasksSummary = tasksSummary
        lastPushedNewsSummary = newsSummary
        lastPushedAqiText = airQualityState?.text
        lastPushedAqiValue = airQualityState?.aqi
        lastPushedRadioName = radioState?.stationName
        lastPushedRadioPlaying = radioState?.playing == true
        chatFragment.syncHudSnapshot(
            calendarSummary = calendarSummary,
            tasksSummary = tasksSummary,
            newsSummary = newsSummary,
            airQualityState = airQualityState,
            radioState = radioState
        )
    }

    private fun syncTapRadioHudStateFromPrefs() {
        val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val playing = prefs.getBoolean("tapradio_now_playing_active", false)
        val stationName = prefs.getString("tapradio_now_playing_name", null)
        val genre = prefs.getString("tapradio_now_playing_genre", null)
        viewModel.updateRadioHudState(stationName = stationName, genre = genre, playing = playing)
    }

    private fun refreshLocationSnapshot(force: Boolean) {
        if (!locationPermissionGranted) return
        val now = SystemClock.elapsedRealtime()
        if (!force && now - lastLocationSnapshotRefreshElapsedMs < LOCATION_SNAPSHOT_REFRESH_DEBOUNCE_MS) {
            return
        }
        lastLocationSnapshotRefreshElapsedMs = now
        lifecycleScope.launch(Dispatchers.IO) {
            val snapshot =
                deviceLocationResolver.resolve(
                    maxAgeMs = LOCATION_SNAPSHOT_MAX_AGE_MS,
                    timeoutMs = LOCATION_SNAPSHOT_TIMEOUT_MS
                ) ?: return@launch
            publishDeviceLocationContext(snapshot)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // XR Origin Safety
    // ══════════════════════════════════════════════════════════════════════

    private fun initXrOriginSafe() {
        try {
            val xrDisplayManager = getSystemService("xr_display")
            if (xrDisplayManager == null) {
                Log.w(TAG, "XR display subsystem is null — running in fallback 2D mode")
                return
            }
            Log.d(TAG, "XR display subsystem initialised: $xrDisplayManager")

            // Attach vendor trackpad bridge if ARDK is available on-device
            val bridge = RayNeoArdkTrackpadBridge()
            val attached = bridge.attachIfAvailable(this)
            Log.d(TAG, "Vendor trackpad bridge attached: $attached")

            try {
                val cameraManager = getSystemService(CAMERA_SERVICE)
                if (cameraManager == null) {
                    Log.w(TAG, "Camera service is null — photo features disabled")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Camera subsystem init failed", e)
            }
        } catch (e: Exception) {
            Log.e(TAG, "XR Origin init failed — running in 2D fallback mode", e)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Immersive Display (6 000-nit MicroLED optimised)
    // ══════════════════════════════════════════════════════════════════════

    @SuppressLint("WrongConstant")
    private fun configureImmersiveDisplay() {
        WindowCompat.setDecorFitsSystemWindows(window, false)

        window.apply {
            addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            applyTransparentSystemBarColors()

            val lp = attributes
            lp.screenBrightness = WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_FULL
            attributes = lp
        }

        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        window.decorView.setBackgroundColor(Color.BLACK)
    }

    @Suppress("DEPRECATION")
    private fun applyTransparentSystemBarColors() {
        window.statusBarColor = Color.TRANSPARENT
        window.navigationBarColor = Color.TRANSPARENT
    }

    fun enterTextInputMode() {
        WindowCompat.setDecorFitsSystemWindows(window, true)
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
        hideSystemIme()
    }

    fun exitTextInputMode() {
        hideCustomKeyboard(clearFocus = true)
        configureImmersiveDisplay()
        window.setSoftInputMode(
                WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING or
                        WindowManager.LayoutParams.SOFT_INPUT_STATE_HIDDEN
        )
    }

    fun showCustomKeyboardFor(target: EditText) {
        activeTextInput = target
        target.showSoftInputOnFocus = false
        target.requestFocus()
        target.requestFocusFromTouch()
        target.isCursorVisible = true
        target.setSelection(target.text?.length ?: 0)
        enterTextInputMode()
        customKeyboardView?.visibility = View.VISIBLE
        customKeyboardView?.bringToFront()
        customKeyboardView?.post { customKeyboardView?.focusHideButton() }
    }

    fun hideCustomKeyboard(clearFocus: Boolean = false) {
        hideSystemIme()
        customKeyboardView?.visibility = View.GONE
        if (clearFocus) {
            activeTextInput?.clearFocus()
            currentFocus?.clearFocus()
            activeTextInput = null
        }
    }

    private fun hideSystemIme() {
        WindowInsetsControllerCompat(window, window.decorView).hide(WindowInsetsCompat.Type.ime())
        val imm = getSystemService(InputMethodManager::class.java)
        val token = currentFocus?.windowToken ?: window.decorView.windowToken
        imm?.hideSoftInputFromWindow(token, 0)
    }

    private fun setupCustomKeyboard() {
        customKeyboardView?.setOnKeyboardActionListener(
                object : CustomKeyboardView.OnKeyboardActionListener {
                    override fun onKeyPressed(key: String) {
                        withActiveInput { target ->
                            val start = target.selectionStart.coerceAtLeast(0)
                            val end = target.selectionEnd.coerceAtLeast(0)
                            val min = minOf(start, end)
                            val max = maxOf(start, end)
                            target.text?.replace(min, max, key)
                            target.setSelection(min + key.length)
                        }
                    }

                    override fun onBackspacePressed() {
                        withActiveInput { target ->
                            val start = target.selectionStart.coerceAtLeast(0)
                            val end = target.selectionEnd.coerceAtLeast(0)
                            val min = minOf(start, end)
                            val max = maxOf(start, end)
                            when {
                                min != max -> {
                                    target.text?.delete(min, max)
                                    target.setSelection(min)
                                }
                                min > 0 -> {
                                    target.text?.delete(min - 1, min)
                                    target.setSelection(min - 1)
                                }
                            }
                        }
                    }

                    override fun onEnterPressed() {
                        withActiveInput { target ->
                            val handled =
                                    target.dispatchKeyEvent(
                                            KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER)
                                    ) ||
                                            target.dispatchKeyEvent(
                                                    KeyEvent(
                                                            KeyEvent.ACTION_UP,
                                                            KeyEvent.KEYCODE_ENTER
                                                    )
                                            )
                            if (!handled) {
                                target.text?.append("\n")
                                target.setSelection(target.text?.length ?: 0)
                            }
                        }
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard(clearFocus = true)
                    }

                    override fun onClearPressed() {
                        withActiveInput { target ->
                            target.text?.clear()
                            target.setSelection(0)
                        }
                    }

                    override fun onMoveCursorLeft() {
                        withActiveInput { target ->
                            val pos = target.selectionStart.coerceAtLeast(0)
                            target.setSelection((pos - 1).coerceAtLeast(0))
                        }
                    }

                    override fun onMoveCursorRight() {
                        withActiveInput { target ->
                            val pos = target.selectionStart.coerceAtLeast(0)
                            val max = target.text?.length ?: 0
                            target.setSelection((pos + 1).coerceAtMost(max))
                        }
                    }

                }
        )
        customKeyboardView?.visibility = View.GONE
    }

    private inline fun withActiveInput(action: (EditText) -> Unit) {
        val cached = activeTextInput?.takeIf { it.isAttachedToWindow }
        val target = cached ?: (currentFocus as? EditText) ?: return
        activeTextInput = target
        target.showSoftInputOnFocus = false
        hideSystemIme()
        action(target)
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewPager (Chat HUD host)
    // ══════════════════════════════════════════════════════════════════════

    private fun setupViewPager() {
        val pager =
                viewPager
                        ?: run {
                            Log.e(TAG, "ViewPager is null — cannot set up panels")
                            return
                        }

        pager.adapter = MainPagerAdapter(this, chatFragment)
        pager.offscreenPageLimit = 1
        pager.isUserInputEnabled = false
        pager.isSaveEnabled = false

        pager.registerOnPageChangeCallback(
                object : ViewPager2.OnPageChangeCallback() {
                    override fun onPageSelected(position: Int) {
                        viewModel.setActivePanel(position)
                        handlePanelChanged(position)
                    }
                }
        )
    }

    private fun applyInitialPageSelection() {
        val pager = viewPager ?: return
        pager.setCurrentItem(MainViewModel.PANEL_CHAT, false)
        viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
        pager.post {
            pager.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
            handlePanelChanged(MainViewModel.PANEL_CHAT)
        }
    }

    private fun handlePanelChanged(position: Int) {
        if (position != MainViewModel.PANEL_CHAT) return
        chatFragment.setHudModeEnabled(false)
        if (pendingFocusNewChatOnResume) {
            chatFragment.view?.post {
                chatFragment.focusNewChatCard(animate = false)
                pendingFocusNewChatOnResume = false
            }
        }
        scheduleChatHudIdleTimer()
    }

    private fun scheduleChatHudIdleTimer() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)
        if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) return
        if (chatFragment.isHudModeEnabled()) return
        uiHandler.postDelayed(chatHudIdleRunnable, CHAT_HUD_IDLE_TIMEOUT_MS)
    }

    private fun markTrackpadActivity() {
        if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) return
        if (chatFragment.isHudModeEnabled()) return
        scheduleChatHudIdleTimer()
    }

    /**
     * Double-tap behaviour — linear progression:
     *   1. Gemini active + camera OFF → switch to camera/video mode
     *   2. Gemini active + camera ON  → end Gemini session
     *   3. Gemini NOT active          → launch TapBrowser (original behaviour)
     *
     * New Chat always starts in audio-only mode. First double-tap enables
     * camera, second double-tap exits the assistant entirely.
     */
    private fun cyclePanelViaDoubleTap() {
        uiHandler.removeCallbacks(chatHudIdleRunnable)

        // ── If the chat panel is showing a reader-expanded card, double-tap
        //    closes it back to the normal chat carousel. ──
        val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
        if (currentPanel == MainViewModel.PANEL_CHAT && chatFragment.isReaderModeActive()) {
            chatFragment.exitReaderModeFromOutside()
            return
        }

        val geminiActive = viewModel.voiceAssistantActive.value == true

        if (geminiActive) {
            if (!cameraCaptureActive) {
                // Audio mode → switch to video/camera mode
                if (cameraPermissionGranted) {
                    startCameraCapture()
                    showHudNotification("Camera mode")
                } else {
                    // No permission — close session instead
                    shutdownMultimodalSession("Session ended.")
                }
            } else {
                // Camera already on → exit Gemini session
                shutdownMultimodalSession("Session ended.")
            }
            return
        }

        // Not active → launch TapBrowser
        launchTapBrowser()
    }

    private fun launchTapBrowser(
        initialUrl: String? = null,
        youtubeAutoplayQuery: String? = null,
        youtubeAutoplayMode: String? = null
    ) {
        // Inject saved cookies from companion app into WebView CookieManager
        // before launching TapBrowser (same APK = shared CookieManager).
        injectSavedBrowserCookies()
        pendingFocusNewChatOnResume = true

        if (!youtubeAutoplayQuery.isNullOrBlank()) {
            runCatching {
                val browserClass = Class.forName(TAP_BROWSER_ACTIVITY_CLASS)
                val method = browserClass.getMethod("prepareForIncomingYouTubeAutoplay")
                method.invoke(null)
                Log.d("VisionClaw", "Prepared TapBrowser for incoming YouTube autoplay handoff")
            }
        }

        val intent =
                Intent().setClassName(this, TAP_BROWSER_ACTIVITY_CLASS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    putExtra(EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP, true)
                    initialUrl
                            ?.trim()
                            ?.takeIf { it.isNotBlank() }
                            ?.let { putExtra(EXTRA_BROWSER_INITIAL_URL, it) }
                    youtubeAutoplayQuery
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY, it) }
                    youtubeAutoplayMode
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?.let { putExtra(EXTRA_YOUTUBE_AUTOPLAY_MODE, it) }
                }

        try {
            startActivity(intent)
        } catch (_: ActivityNotFoundException) {
            showHudNotification("TapBrowser module is unavailable")
        } finally {
            viewModel.setActivePanel(MainViewModel.PANEL_CHAT)
            viewPager?.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            chatFragment.setHudModeEnabled(false)
            scheduleChatHudIdleTimer()
        }
    }

    /**
     * Reads browser_cookies from SharedPreferences (set by companion app)
     * and injects them into Android's CookieManager so TapBrowser can use them.
     * Format: JSON array of { domain, cookies, label } objects.
     */
    private fun injectSavedBrowserCookies() {
        try {
            val raw = viewModel.preferences.let {
                val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("browser_cookies", null)
            }
            if (raw.isNullOrBlank()) return

            val cookieManager = android.webkit.CookieManager.getInstance()
            cookieManager.setAcceptCookie(true)

            val arr = org.json.JSONArray(raw)
            for (i in 0 until arr.length()) {
                val entry = arr.getJSONObject(i)
                val domain = entry.optString("domain", "").trim()
                val cookieStr = entry.optString("cookies", "").trim()
                if (domain.isBlank() || cookieStr.isBlank()) continue

                // The cookie string from document.cookie is semicolon-separated.
                // CookieManager.setCookie expects one cookie at a time.
                val url = if (domain.startsWith(".")) "https://${domain.substring(1)}" else "https://$domain"
                for (cookie in cookieStr.split(";")) {
                    val trimmed = cookie.trim()
                    if (trimmed.isNotBlank()) {
                        cookieManager.setCookie(url, trimmed)
                    }
                }
            }
            cookieManager.flush()
            Log.d(TAG, "Injected saved browser cookies for ${arr.length()} domain(s)")
        } catch (e: Exception) {
            Log.w(TAG, "Failed to inject browser cookies: ${e.message}")
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad Gesture Engine
    // ══════════════════════════════════════════════════════════════════════

    private fun setupGestureEngine() {
        gestureEngine.onShortTap = {
            Log.d(TAG, "Short tap")
            runOnUiThread {
                if (customKeyboardView?.visibility == View.VISIBLE) {
                    customKeyboardView?.performFocusedTap()
                    return@runOnUiThread
                }
                val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT
                if (currentPanel == MainViewModel.PANEL_CHAT) {
                    if (chatFragment.isHudModeEnabled()) {
                        chatFragment.setHudModeEnabled(false)
                        scheduleChatHudIdleTimer()
                        return@runOnUiThread
                    }
                    when (chatFragment.handleFocusedCardTap()) {
                        ChatPanelFragment.FocusedTapResult.OPENED_URL -> return@runOnUiThread
                        ChatPanelFragment.FocusedTapResult.IGNORED -> return@runOnUiThread
                        ChatPanelFragment.FocusedTapResult.ACTIVATE_ASSISTANT ->
                                activateChatVoiceAssistant()
                    }
                    return@runOnUiThread
                }
                // Let the active panel handle explicit trackpad selection first.
                val consumed = currentTrackpadPanel()?.onTrackpadSelect() ?: false
                if (!consumed) {
                    // Fallback click if panel does not implement trackpad-select.
                    viewPager?.focusedChild?.performClick()
                }
            }
        }

        gestureEngine.onDoubleTap = {
            Log.d(TAG, "Double tap → cycle panel/session")
            runOnUiThread {
                cyclePanelViaDoubleTap()
            }
        }
    }

    private fun activateChatVoiceAssistant() {
        // ── Debounce rapid taps ──────────────────────────────────────
        val now = SystemClock.elapsedRealtime()
        if (now - lastVoiceActivationMs < VOICE_ACTIVATION_DEBOUNCE_MS) {
            Log.d(TAG, "activateChatVoiceAssistant: debounced (${now - lastVoiceActivationMs}ms)")
            return
        }
        lastVoiceActivationMs = now

        if (!micPermissionGranted) {
            showHudNotification("Microphone permission required")
            playVoiceTimeoutBeep()
            return
        }

        // ── Tear down any existing / stale session first ─────────────
        if (geminiLiveSession != null || geminiCaptureActive || liveState != GeminiLiveState.IDLE) {
            Log.d(TAG, "activateChatVoiceAssistant: cleaning stale session " +
                    "(session=${geminiLiveSession != null}, capture=$geminiCaptureActive, state=$liveState)")
            releaseGeminiAudioCapture(cancelOnly = true)
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            // Brief pause to let WebSocket close, then start fresh session
            // automatically — no second tap required.
            uiHandler.postDelayed({
                lastVoiceActivationMs = 0L  // reset debounce so activation proceeds
                activateChatVoiceAssistant()
            }, 300L)
            return
        }

        // ── Start fresh session ──────────────────────────────────────
        nativeSttFallbackTriggered = false
        viewModel.activateVoiceAssistant()
        showListeningOverlay(true)
        updateListeningTranscript("Listening…")
        pushOscilloscopeLevel(0.08f, OSCILLOSCOPE_USER_COLOR, force = true)
        playVoiceActivateBeep()
        uiHandler.removeCallbacks(delayedVoiceStartRunnable)
        uiHandler.postDelayed(delayedVoiceStartRunnable, VOICE_LISTEN_START_DELAY_MS)
    }

    private fun isChatUiReady(): Boolean {
        return chatFragment.isAdded && chatFragment.view != null
    }

    private fun setHudConnectionStatus(status: ChatPanelFragment.ConnectionStatus) {
        when (status) {
            ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED,
            ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED -> {
                lastKnownAiConnectionStatus = status
            }

            else -> Unit
        }

        val renderStatus =
            if (status == ChatPanelFragment.ConnectionStatus.IDLE &&
                lastKnownAiConnectionStatus != ChatPanelFragment.ConnectionStatus.IDLE
            ) {
                lastKnownAiConnectionStatus
            } else {
                status
            }

        runOnUiThread {
            if (isChatUiReady()) {
                chatFragment.setConnectionStatus(renderStatus)
            }
        }
    }

    // AITap: OpenClaw bridge infrastructure removed.
    // All tool routing now handled by ToolDispatcher via Gemini native tool calls.

    /** Last user transcript from the Live session, used by ToolAssist recovery. */
    @Volatile private var lastToolAssistTranscript = ""
    /** Prevents double-firing recovery for the same turn. */
    @Volatile private var toolAssistRecoveryFired = false

    /**
     * Detect when Gemini says "I can't access the tool" or similar failure
     * responses, and re-inject the tool result via ToolAssist.  This handles
     * the race condition where Gemini's Live model starts responding
     * before our proactive ToolAssist injection arrives.
     */
    private fun maybeRecoverFromGeminiFallback(modelText: String): Boolean {
        // If local turn owner is active, don't attempt any recovery — suppress entirely
        if (isGeminiOutputSuppressed()) return true
        val lower = modelText.lowercase()
        val isToolFailure = lower.contains("unable to access") ||
            lower.contains("tool") && (lower.contains("not available") || lower.contains("can't") || lower.contains("cannot")) ||
            lower.contains("don't have access to") ||
            lower.contains("i don't have the ability") ||
            lower.contains("i'm not able to") && (lower.contains("location") || lower.contains("place") || lower.contains("traffic")) ||
            lower.contains("enable location") ||
            lower.contains("i don't know where you are") ||
            lower.contains("don't have your location")

        if (!isToolFailure) return false
        if (toolAssistRecoveryFired) return false

        val transcript = lastToolAssistTranscript
        if (transcript.isBlank()) return false

        val engine = toolAssistEngine ?: return false
        toolAssistRecoveryFired = true
        val localMapTurn = looksLikeMapInfoIntent(transcript)

        Log.d(TAG, "ToolAssist RECOVERY triggered for: $transcript")
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val assist = engine.maybeAssist(transcript) ?: return@launch
                Log.d(TAG, "ToolAssist recovery result [${assist.toolName}]: ${assist.resultText.take(200)}")
                // LearnLM continuation prefers voice in recovery path too
                if (assist.preferLiveVoice && geminiLiveSession != null) {
                    learnLmToolCallActive = true
                    keepLearnLmSessionAliveUntilManualClose = true
                    armSilenceWatchdog()
                    geminiLiveSession?.sendClientText(assist.contextPrompt)
                    return@launch
                }
                if (localMapTurn || shouldOwnToolAssistLocally(assist.toolName)) {
                    runOnUiThread {
                        presentToolAssistLocally(assist.toolName, assist.resultText)
                    }
                    return@launch
                }
                val sent = geminiLiveSession?.sendClientText(assist.contextPrompt) == true
                Log.d(TAG, "ToolAssist recovery injected=$sent")
                if (sent) {
                    runOnUiThread {
                        viewModel.appendLiveAssistantStreamChunk(assist.resultText)
                        viewModel.commitLiveAssistantStreamIfNeeded()
                        showHudNotification(assist.resultText.take(120))
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "ToolAssist recovery error", e)
            }
        }
        return localMapTurn
    }

    private fun refreshToolBridgeStatus() {
        // AITap: No external bridge. Tools are always local.
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.TOOLS_READY)
    }

    private fun dispatchLiveToolCall(callId: String, name: String, args: String) {
        // Defense-in-depth: reject tool calls that arrive after local handoff claimed the turn
        if (isGeminiOutputSuppressed()) {
            Log.d(TAG, "dispatchLiveToolCall SUPPRESSED: $name (local turn owner active)")
            return
        }
        val functionName = name.trim()
        if (functionName.isBlank()) return
        if (!toolDispatcher.isSupported(functionName)) {
            Log.w(TAG, "Unsupported tool call: $functionName — sending error back to Gemini")
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(responseId, functionName, "Unknown tool: $functionName")
            }
            return
        }

        if (functionName == "learn_topic") {
            val learnCandidates = listOf(
                lastToolAssistTranscript,
                pendingLiveInputTranscript,
                latestLiveTranscript
            )
            val explicitLearnRequest = learnCandidates.any {
                AssistantIntentParser.isExplicitLearnRequest(it.trim())
            }
            if (!explicitLearnRequest) {
                Log.w(
                    TAG,
                    "Rejected learn_topic tool call for non-explicit transcript: ${lastToolAssistTranscript.take(160)}"
                )
                lifecycleScope.launch(Dispatchers.IO) {
                    val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                    geminiLiveSession?.sendToolResponse(
                        responseId,
                        functionName,
                        "LearnLM is only available when the user explicitly prefixes the request with learnlm. Ask the user to say learnlm first if they want tutor mode."
                    )
                }
                return
            }
            learnLmToolCallActive = true
            keepLearnLmSessionAliveUntilManualClose = true
            Log.d(TAG, "learn_topic tool call — learnLmToolCallActive=true, 30s timeout set")
            pinLearnLmLiveSessionIfNeeded(pendingLiveInputTranscript.trim())
        }

        if (functionName == "daily_briefing" && !looksLikeDailyBriefingIntent(lastToolAssistTranscript)) {
            Log.w(
                TAG,
                "Rejected daily_briefing tool call for non-explicit transcript: ${lastToolAssistTranscript.take(160)}"
            )
            lifecycleScope.launch(Dispatchers.IO) {
                val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
                geminiLiveSession?.sendToolResponse(
                    responseId,
                    functionName,
                    "Daily briefing is only available when the user explicitly asks for a daily briefing by name. Use calendar, routes, places, weather, or research tools for this request instead."
                )
            }
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            val result = toolDispatcher.dispatch(functionName, args)
            val resultText = result.getOrElse { err ->
                Log.e(TAG, "Tool dispatch error for $functionName", err)
                err.message?.trim().takeUnless { it.isNullOrBlank() }
                    ?: "Tool $functionName is unavailable right now."
            }
            // When a TapClaw research task returns, start background polling
            // for the report file instead of keeping the Gemini session open.
            if (functionName == "tapclaw_agent") {
                runOnUiThread {
                    chatFragment.setStreamActiveIndicator(false)
                    chatFragment.hideHeartbeat()
                }
                val queryLower = try {
                    JSONObject(args).optString("query", "").lowercase()
                } catch (_: Exception) { "" }
                if (queryLower.contains("deep research") || queryLower.contains("background research")) {
                    val topic = queryLower
                        .substringAfter("research")
                        .replace(Regex("""[.!,;:'"]+"""), "")
                        .substringBefore("use the google")
                        .substringBefore("save the")
                        .substringBefore("this is a background")
                        .trim()
                        .ifBlank { "unknown topic" }
                    startResearchPoll(topic)
                }
            }
            val autoOpenUrl = when {
                functionName == "open_taplink" ->
                    AssistantIntentParser.extractTapLinkUrl(resultText)
                functionName == "tapradio" && resultText.startsWith("open_taplink:") ->
                    resultText.substringAfter("open_taplink:").substringBefore("\n").trim()
                else -> null
            }
            val responseId = callId.trim().ifBlank { "tool-${System.currentTimeMillis()}" }
            Log.d(
                TAG,
                "Tool result ready callId=$responseId function=$functionName text=${resultText.take(220)}"
            )

            // If this tool call will open a URL (open_taplink), suppress Gemini
            // audio output BEFORE sending the tool response.  This prevents
            // Gemini from generating audio that overlaps with the local action.
            // NOTE: Only set the suppression timestamp and flush audio here
            // (both are thread-safe).  Do NOT call armLocalDirectResponseHandoff()
            // from the IO thread — it calls shutdownMultimodalSession() which
            // touches UI elements and corrupts session state.  The full session
            // shutdown happens on the UI thread below via shutdownMultimodalSession().
            if (!autoOpenUrl.isNullOrBlank()) {
                Log.d(TAG, "open_taplink URL detected — suppressing Gemini output before tool response")
                suppressGeminiOutputUntilMs = maxOf(
                    suppressGeminiOutputUntilMs,
                    SystemClock.uptimeMillis() + LOCAL_DIRECT_OUTPUT_SUPPRESS_MS
                )
                ttsController?.stop()
                geminiAudioPlayer?.stopAndFlush()
            }

            // For tapclaw_agent, append the latest heartbeat context to the tool
            // result so Gemini knows what happened during the task — without
            // injecting a disruptive client turn into the conversation.
            val finalResultText = if (functionName == "tapclaw_agent") {
                val hb = lastTapClawHeartbeat
                lastTapClawHeartbeat = null
                if (!hb.isNullOrBlank()) "$resultText\n[TapClaw last status: $hb]" else resultText
            } else resultText
            val sent = geminiLiveSession?.sendToolResponse(responseId, functionName, finalResultText) == true
            Log.d(TAG, "sendToolResponse sent=$sent callId=$responseId")
            val hudText = if (!autoOpenUrl.isNullOrBlank()) {
                "Opening ${AssistantIntentParser.displayLabelForUrl(autoOpenUrl)}"
            } else {
                hudSafeCalendarResult(resultText)
            }
            runOnUiThread {
                if (!autoOpenUrl.isNullOrBlank()) {
                    shutdownMultimodalSession()
                    // Detect YouTube/video intent from open_taplink URLs:
                    //  1. Direct youtube.com / youtu.be links
                    //  2. Any URL containing "youtube" in path or query
                    //  3. Google Video search (tbm=vid) — Gemini often uses this
                    //     even when the user asked for YouTube specifically
                    val urlLower = autoOpenUrl.lowercase()
                    val isYouTubeIntent = urlLower.contains("youtube.com") ||
                        urlLower.contains("youtu.be") ||
                        urlLower.contains("youtube") ||
                        urlLower.contains("tbm=vid")  // Google Video tab search

                    if (isYouTubeIntent) {
                        // Cancel the settle timer to prevent double-launch
                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                        lastHandledLiveInputTranscript = pendingLiveInputTranscript.trim()

                        val uri = android.net.Uri.parse(autoOpenUrl)
                        // Extract the real search topic from URL query params;
                        // strip "youtube" if Gemini appended it to the search query.
                        val rawQuery = (uri.getQueryParameter("search_query")
                            ?: uri.getQueryParameter("q")
                            ?: "")
                            .replace(Regex("(?i)\\byoutube\\b"), "")
                            .replace(Regex("\\s+"), " ")
                            .trim()
                        // Fallback: extract from the voice transcript
                        val query = rawQuery.takeIf { it.isNotBlank() }
                            ?: pendingLiveInputTranscript
                                .replace(Regex("(?i)^\\s*(?:play|open|be)?\\s*(?:youtube)?\\s*(?:music|videos?|songs?)?\\s*(?:by|from|about|on)?\\s*"), "")
                                .trimEnd('.', '!', '?')
                                .trim()
                                .takeIf { it.isNotBlank() }
                            ?: lastHandledLiveInputTranscript
                                .replace(Regex("(?i)^\\s*(?:play|open|be)?\\s*(?:youtube)?\\s*(?:music|videos?|songs?)?\\s*(?:by|from|about|on)?\\s*"), "")
                                .trimEnd('.', '!', '?')
                                .trim()
                                .takeIf { it.isNotBlank() }
                            ?: lastToolAssistTranscript
                                .replace(Regex("(?i)^\\s*(?:play|open|be)?\\s*(?:youtube)?\\s*(?:music|videos?|songs?)?\\s*(?:by|from|about|on)?\\s*"), "")
                                .trimEnd('.', '!', '?')
                                .trim()
                                .takeIf { it.isNotBlank() }
                            ?: "trending"
                        val transcript = (pendingLiveInputTranscript + " " + lastToolAssistTranscript).lowercase()
                        val mode = if (transcript.contains("music") ||
                            transcript.contains("song")) "music" else "video"
                        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
                        val searchUrl = "https://www.youtube.com/results?search_query=$encoded&sp=CAI%253D&taplink_autoplay=$mode"
                        Log.d(TAG, "YouTube open_taplink intercepted → TapBrowser query='$query' mode='$mode' originalUrl=$autoOpenUrl transcript='${pendingLiveInputTranscript.take(80)}'")
                        launchTapBrowser(
                            initialUrl = searchUrl,
                            youtubeAutoplayQuery = query,
                            youtubeAutoplayMode = mode
                        )
                    } else {
                        viewModel.openUrl(autoOpenUrl)
                    }
                }
                viewModel.appendLiveAssistantStreamChunk(hudText)
                viewModel.commitLiveAssistantStreamIfNeeded()
                showHudNotification(hudText)
                if (sent) {
                    setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.TOOLS_READY)
                } else {
                    Log.w(TAG, "sendToolResponse failed — Gemini session may have closed")
                    setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
                }
            }
        }
    }

    private fun maybeRouteLocalIntentDirectly(
        transcript: String,
        forcedSkill: String? = null,
        forcedIntent: String? = null
    ): Boolean {
        parseYouTubePlaybackIntent(transcript)?.let { youtubeRequest ->
            armLocalDirectResponseHandoff()
            showHudNotification(youtubeRequest.hudLabel)
            runOnUiThread {
                viewModel.appendDirectAssistantResponse(youtubeRequest.responseText)
                ttsController?.stop()
                ttsController?.speak(youtubeRequest.hudLabel)
                launchTapBrowser(
                    initialUrl = youtubeRequest.searchUrl,
                    youtubeAutoplayQuery = youtubeRequest.query,
                    youtubeAutoplayMode = youtubeRequest.mode
                )
            }
            return true
        }

        if (looksLikeDailyBriefingIntent(transcript)) {
            armLocalDirectResponseHandoff()
            showHudNotification("Generating daily briefing")
            lifecycleScope.launch(Dispatchers.IO) {
                val result = toolDispatcher.dispatch(
                    "daily_briefing",
                    JSONObject().put("focus", "today").toString()
                )
                val resultText = result.getOrElse { error ->
                    Log.e(TAG, "Daily briefing dispatch failed", error)
                    "Daily briefing unavailable right now."
                }
                val speech = dailyBriefSpeechSummary(resultText)
                runOnUiThread {
                    viewModel.appendDirectAssistantResponse(resultText)
                    if (speech.isNotBlank()) {
                        ttsController?.stop()
                        ttsController?.speak(speech)
                        showHudNotification(speech.take(120))
                    } else {
                        showHudNotification(resultText.take(120))
                    }
                }
            }
            return true
        }

        if (looksLikeNearbyPlacesIntent(transcript)) {
            val engine = toolAssistEngine ?: return false
            armLocalDirectResponseHandoff()
            showHudNotification("Checking nearby places")
            lifecycleScope.launch(Dispatchers.IO) {
                val assist = runCatching { engine.maybeAssist(transcript) }.getOrNull()
                if (assist == null || assist.toolName != "google_places") {
                    runOnUiThread { showHudNotification("Nearby places unavailable.") }
                    return@launch
                }
                val resultText = assist.resultText
                val spokenSummary = placesSpeechSummary(resultText)
                runOnUiThread {
                    viewModel.appendDirectAssistantResponse(resultText)
                    if (spokenSummary.isNotBlank()) {
                        ttsController?.stop()
                        ttsController?.speak(spokenSummary)
                        showHudNotification(spokenSummary.take(120))
                    } else {
                        showHudNotification(resultText.take(120))
                    }
                }
            }
            return true
        }

        val intent = AssistantIntentParser.parse(transcript) ?: return false
        val preserveLearnLmSession =
            intent is AssistantIntent.Learn &&
                geminiLiveSession != null &&
                (
                    keepLearnLmSessionAliveUntilManualClose ||
                        AssistantIntentParser.isExplicitLearnRequest(transcript)
                )
        if (preserveLearnLmSession) {
            armPinnedLearnLmResponseHandoff()
        } else {
            armLocalDirectResponseHandoff()
        }
        when (intent) {
            is AssistantIntent.OpenWeb -> {
                showHudNotification("Opening ${intent.displayLabel}")
            }
            is AssistantIntent.Research -> {
                showHudNotification("Researching ${intent.topic}")
            }
            is AssistantIntent.Learn -> {
                showHudNotification("Teaching ${intent.topicHint.ifBlank { "that topic" }}")
            }
        }
        viewModel.handleDirectAssistantIntent(intent)
        return true
    }

    private fun armLocalDirectResponseHandoff() {
        keepLearnLmSessionAliveUntilManualClose = false
        suppressGeminiOutputUntilMs =
            maxOf(
                suppressGeminiOutputUntilMs,
                SystemClock.uptimeMillis() + LOCAL_DIRECT_OUTPUT_SUPPRESS_MS
            )
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        // Stop ALL audio output — both Gemini streaming audio AND local TTS
        ttsController?.stop()
        geminiAudioPlayer?.stopAndFlush()
        if (geminiLiveSession != null || liveState != GeminiLiveState.IDLE || viewModel.voiceAssistantActive.value == true) {
            shutdownMultimodalSession()
        }
    }

    private fun armPinnedLearnLmResponseHandoff() {
        keepLearnLmSessionAliveUntilManualClose = true
        suppressGeminiOutputUntilMs = Long.MAX_VALUE
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        ttsController?.stop()
        geminiAudioPlayer?.stopAndFlush()
        awaitingServerTurnComplete = false
        liveState = GeminiLiveState.FOLLOW_UP
        if (!geminiCaptureActive && geminiLiveSession != null) {
            startGeminiAudioStreaming()
        }
        armSilenceWatchdog()   // 30s timeout while learnlm flag is set
        runOnUiThread {
            showListeningOverlay(true)
            clearLiveSpeechPreview()
            updateListeningTranscript("LearnLM active (30s timeout). Ask a follow-up.")
            chatFragment.setStreamActiveIndicator(false)
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
            showHudNotification("LearnLM session active — 30s idle timeout.")
        }
    }

    private fun pinLearnLmLiveSessionIfNeeded(transcript: String) {
        if (keepLearnLmSessionAliveUntilManualClose) return
        val candidates = listOf(
            transcript,
            pendingLiveInputTranscript,
            lastHandledLiveInputTranscript,
            lastToolAssistTranscript
        )
        if (candidates.none { AssistantIntentParser.isExplicitLearnRequest(it) }) return
        keepLearnLmSessionAliveUntilManualClose = true
        if (!geminiCaptureActive && geminiLiveSession != null) {
            startGeminiAudioStreaming()
        }
        armSilenceWatchdog()   // 30s timeout while learnlm flag is set
        runOnUiThread {
            updateListeningTranscript("LearnLM active (30s timeout). Ask a follow-up.")
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED)
            showHudNotification("LearnLM session active — 30s idle timeout.")
        }
    }

    private fun isGeminiOutputSuppressed(): Boolean =
        SystemClock.uptimeMillis() < suppressGeminiOutputUntilMs

    private fun looksLikeDailyBriefingIntent(transcript: String): Boolean {
        val normalized = transcript
            .trim()
            .lowercase(Locale.US)
            .replace(Regex("[^a-z0-9\\s]"), " ")
            .replace(Regex("\\s+"), " ")
            .trim()
        if (normalized.isBlank()) return false
        return normalized in setOf(
            "daily briefing",
            "daily brief",
            "ultimate daily brief",
            "morning briefing",
            "brief me on today",
            "give me my briefing",
            "give me a daily briefing"
        )
    }

    private fun parseYouTubePlaybackIntent(transcript: String): YouTubePlaybackRequest? {
        val trimmed = transcript.trim().trimEnd('.', '!', '?')
        if (trimmed.isBlank()) return null

        // "play/open" is optional — Gemini Live often transcribes without it
        // e.g. " YouTube Drake." instead of "play YouTube Drake"

        val subscriptionsPatterns = listOf(
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?(?:youtube\s+)?subscribed\s+channels\s*$"""),
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?youtube\s+subscriptions?\s*$"""),
            Regex("""(?i)^\s*(?:play|open|start)?\s*(?:my\s+)?subscriptions?\s*$""")
        )
        if (subscriptionsPatterns.any { it.matches(trimmed) }) {
            return YouTubePlaybackRequest(
                query = "subscriptions",
                mode = "subscriptions",
                searchUrl = buildYouTubeSubscriptionsUrl(),
                hudLabel = "Playing your newest subscribed channel videos",
                responseText = "Playing the newest videos from your subscribed channels with captions enabled."
            )
        }

        // --- YouTube History patterns (flexible contains-based matching) ---
        val lower = trimmed.lowercase()
        val isHistoryCommand = (lower.contains("youtube") && lower.contains("history")) ||
            (lower.contains("play") && lower.contains("history")) ||
            (lower.contains("open") && lower.contains("history")) ||
            lower.contains("watch history") ||
            lower.contains("viewing history")
        if (isHistoryCommand) {
            return YouTubePlaybackRequest(
                query = "history",
                mode = "history",
                searchUrl = buildYouTubeHistoryUrl(),
                hudLabel = "Playing videos from your YouTube history",
                responseText = "Playing videos from your YouTube watch history with captions enabled."
            )
        }

        // --- Music-specific patterns (highest priority) ---
        val musicPatterns = listOf(
            Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+music\\s+(?:by|from|about)\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+songs?\\s+(?:by|from|about)\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+music\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+songs?\\s+(.+?)\\s*$")
        )

        val musicTopic = musicPatterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (!musicTopic.isNullOrBlank()) {
            val searchQuery = "$musicTopic music"
            return YouTubePlaybackRequest(
                query = musicTopic,
                mode = "music",
                searchUrl = buildYouTubeSearchUrl(searchQuery, "music"),
                hudLabel = "Playing latest YouTube music for $musicTopic",
                responseText = "Playing the newest YouTube music results for $musicTopic with captions enabled."
            )
        }

        // --- Video-specific patterns ---
        val videoPatterns = listOf(
            Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+videos?\\s+(?:by|from|about|on)\\s+(.+?)\\s*$"),
            Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+videos?\\s+(.+?)\\s*$")
        )

        val videoTopic = videoPatterns.firstNotNullOfOrNull { it.find(trimmed)?.groupValues?.getOrNull(1) }
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (!videoTopic.isNullOrBlank()) {
            return YouTubePlaybackRequest(
                query = videoTopic,
                mode = "video",
                searchUrl = buildYouTubeSearchUrl(videoTopic),
                hudLabel = "Playing latest YouTube videos for $videoTopic",
                responseText = "Playing the newest YouTube videos for $videoTopic with captions enabled."
            )
        }

        // --- Catch-all: "[play] youtube <anything>" defaults to video mode ---
        val catchAllPattern = Regex("(?i)^\\s*(?:play|open)?\\s*youtube\\s+(.+?)\\s*$")
        val catchAllTopic = catchAllPattern.find(trimmed)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
        if (!catchAllTopic.isNullOrBlank()) {
            return YouTubePlaybackRequest(
                query = catchAllTopic,
                mode = "video",
                searchUrl = buildYouTubeSearchUrl(catchAllTopic),
                hudLabel = "Playing latest YouTube videos for $catchAllTopic",
                responseText = "Playing the newest YouTube videos for $catchAllTopic with captions enabled."
            )
        }

        val genericPlayPattern =
            Regex("(?i)^\\s*(?:play|put on|listen to|start)\\s+(.+?)\\s*$")
        val genericTopic = genericPlayPattern.find(trimmed)?.groupValues?.getOrNull(1)
            ?.trim()
            ?.trimEnd('.', '!', '?')
            ?.takeIf { topic ->
                topic.isNotBlank() &&
                    !topic.equals("music", ignoreCase = true) &&
                    !topic.contains("radio", ignoreCase = true) &&
                    !topic.contains("tapradio", ignoreCase = true) &&
                    !topic.contains("station", ignoreCase = true) &&
                    !topic.contains("scan", ignoreCase = true) &&
                    !topic.contains("volume", ignoreCase = true)
            }
        if (!genericTopic.isNullOrBlank()) {
            val looksVideoLike = listOf(
                "video",
                "videos",
                "documentary",
                "history of",
                "interview",
                "lecture",
                "trailer",
                "episode"
            ).any { genericTopic.contains(it, ignoreCase = true) }
            val mode = if (looksVideoLike) "video" else "music"
            val searchTopic = if (mode == "music") "$genericTopic music" else genericTopic
            return YouTubePlaybackRequest(
                query = genericTopic,
                mode = mode,
                searchUrl = buildYouTubeSearchUrl(searchTopic, mode),
                hudLabel = "Playing latest YouTube $mode for $genericTopic",
                responseText = "Playing the newest YouTube $mode results for $genericTopic with captions enabled."
            )
        }

        return null
    }

    private fun buildYouTubeSearchUrl(query: String, mode: String = "video"): String {
        val encoded = java.net.URLEncoder.encode(query, "UTF-8")
        // sp=CAI%253D = YouTube sort-by-upload-date (newest first)
        return "https://www.youtube.com/results?search_query=$encoded&sp=CAI%253D&taplink_autoplay=$mode"
    }

    private fun buildYouTubeSubscriptionsUrl(): String {
        return "https://www.youtube.com/feed/subscriptions?taplink_autoplay=subscriptions"
    }

    private fun buildYouTubeHistoryUrl(): String {
        return "https://www.youtube.com/feed/history?taplink_autoplay=history"
    }

    private data class YouTubePlaybackRequest(
        val query: String,
        val mode: String,
        val searchUrl: String,
        val hudLabel: String,
        val responseText: String
    )

    private fun looksLikeNearbyPlacesIntent(transcript: String): Boolean {
        val lower = transcript.trim().lowercase(Locale.US)
        if (lower.isBlank()) return false
        val mentionsPlaceType = listOf(
            "coffee", "coffee shop", "cafe", "restaurant", "food", "gas station",
            "fuel", "pharmacy", "grocery", "supermarket", "bar", "bakery", "parking"
        ).any { lower.contains(it) }
        if (!mentionsPlaceType) return false
        return listOf(
            "nearest", "closest", "nearby", "near me", "open", "around here", "around me", "where can i get"
        ).any { lower.contains(it) }
    }

    private fun placesSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true)
            }
            .take(4)
            .joinToString(". ")
    }

    private fun routesSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("Maps:", ignoreCase = true) }
            .take(3)
            .joinToString(". ")
    }

    // ── Battery helpers ───────────────────────────────────────────────

    private fun getBatteryLevel(): Int {
        val batteryIntent = registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val level = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1) ?: -1
        val scale = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100) ?: 100
        return if (scale > 0) (level * 100) / scale else -1
    }

    private fun isBatteryCharging(): Boolean {
        val batteryIntent = registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        val status = batteryIntent?.getIntExtra(android.os.BatteryManager.EXTRA_STATUS, -1) ?: -1
        return status == android.os.BatteryManager.BATTERY_STATUS_CHARGING ||
               status == android.os.BatteryManager.BATTERY_STATUS_FULL
    }

    private fun onBatterySaverToggled(enabled: Boolean) {
        Log.d(TAG, "Battery saver toggled: $enabled")
        val appPrefs = viewModel.preferences
        if (enabled) {
            // Store original HUD refresh interval for later restoration
            appPrefs.batterySaverOrigRefresh = appPrefs.hudRefreshIntervalSeconds
            // Disable camera-based multimodal analysis to save power
            viewModel.setMultimodalCameraEnabled(false)
        } else {
            // Restore original HUD refresh interval
            val orig = appPrefs.batterySaverOrigRefresh
            if (orig > 0) {
                appPrefs.hudRefreshIntervalSeconds = orig
                appPrefs.batterySaverOrigRefresh = 0
            }
        }
    }

    private fun clawSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[OpenClaw", ignoreCase = true) }
            .take(4)
            .joinToString(". ")
            .ifBlank { "TapClaw completed the request." }
    }

    private fun translateSpeechSummary(resultText: String): String {
        // Extract just the translation result, skip metadata lines
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[TRANSLATE_", ignoreCase = true) }
            .filterNot { it.startsWith("Mode:", ignoreCase = true) }
            .filterNot { it.startsWith("Instruction:", ignoreCase = true) }
            .take(3)
            .joinToString(". ")
            .ifBlank { "Translation ready." }
    }

    private fun batterySpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("Available commands", ignoreCase = true) }
            .filterNot { it.startsWith("•") }
            .take(3)
            .joinToString(". ")
            .ifBlank { "Battery status checked." }
    }

    private fun quickActionSpeechSummary(resultText: String): String {
        // For quick actions, summarize the combined tool results
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("—") }
            .filterNot { it.startsWith("⚡") }
            .take(4)
            .joinToString(". ")
            .ifBlank { "Quick action completed." }
    }

    private fun locationSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .take(2)
            .joinToString(". ")
            .ifBlank { "Location ready." }
    }

    // ── Deep Research background polling ──────────────────────────────
    // When a research task is fired via TapClaw, we poll the relay to check
    // if the report file has appeared in the workspace. Once found, we notify
    // the user on the HUD so they can ask Gemini to read it.

    private var researchPollJob: kotlinx.coroutines.Job? = null
    private var lastResearchTopic: String? = null
    /** Latest heartbeat snippet from OpenClaw — appended to tool results so Gemini has context. */
    @Volatile private var lastTapClawHeartbeat: String? = null
    /** Throttle heartbeat HUD updates to once every 20 s to reduce thermal load. */
    private var lastHeartbeatUiUpdateMs = 0L

    /** OpenClaw client instance — promoted from onCreate for periodic ping access. */
    private var openClawClientField: com.rayneo.visionclaw.core.network.OpenClawClient? = null
    /** Periodic OpenClaw connection-status ping. */
    private val openClawPingRunnable = object : Runnable {
        override fun run() {
            val client = openClawClientField
            if (client == null || !viewModel.preferences.openClawEnabled) {
                runOnUiThread { chatFragment.showHeartbeat(null) }
                scheduleNextPing()
                return
            }
            lifecycleScope.launch(Dispatchers.IO) {
                val result = client.ping()
                val label = when (result) {
                    is com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.Success ->
                        "OpenClaw connected"
                    is com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.Error ->
                        "OpenClaw offline"
                    is com.rayneo.visionclaw.core.network.OpenClawClient.ClawResult.NotConfigured ->
                        "OpenClaw not configured"
                    else -> null
                }
                if (label != null) {
                    runOnUiThread {
                        // Only update idle status — don't overwrite active task heartbeats
                        if (lastTapClawHeartbeat == null) {
                            chatFragment.showHeartbeat(label, 0L)  // 0 = persistent, no auto-hide
                        }
                    }
                }
            }
            scheduleNextPing()
        }

        private fun scheduleNextPing() {
            val intervalMs = viewModel.preferences.openClawHeartbeatIntervalSeconds * 1000L
            uiHandler.postDelayed(this, intervalMs)
        }
    }

    private fun startOpenClawPing() {
        uiHandler.removeCallbacks(openClawPingRunnable)
        // First ping after a short delay to let the UI settle
        uiHandler.postDelayed(openClawPingRunnable, 3_000L)
    }

    private fun stopOpenClawPing() {
        uiHandler.removeCallbacks(openClawPingRunnable)
    }

    /** Start polling the relay for a research report file. Called when
     *  a "research [topic]" tapclaw_agent call returns (fire-and-forget). */
    fun startResearchPoll(topic: String) {
        lastResearchTopic = topic
        researchPollJob?.cancel()
        researchPollJob = lifecycleScope.launch(Dispatchers.IO) {
            val relayBase = buildRelayBaseUrlFromPrefs() ?: return@launch
            // Build expected filename pattern: Gemini-ResearchTopic-YYYY-MM-DD
            val dateStr = java.time.LocalDate.now().toString()
            val checkUrl = "$relayBase/media/Gemini-Research/Gemini-ResearchTopic-$dateStr.txt"
            Log.d(TAG, "Research poll started: checking $checkUrl every 30s")
            runOnUiThread {
                chatFragment.showHeartbeat("Research started: $topic", 10_000L)
                chatFragment.setStreamActiveIndicator(true)
            }
            var found = false
            while (!found && isActive) {
                kotlinx.coroutines.delay(30_000) // Check every 30 seconds
                try {
                    val conn = java.net.URL(checkUrl).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 5000
                    conn.readTimeout = 5000
                    conn.requestMethod = "HEAD"
                    val code = conn.responseCode
                    conn.disconnect()
                    if (code == 200) {
                        found = true
                        Log.d(TAG, "Research report ready: $checkUrl")
                        runOnUiThread {
                            chatFragment.setStreamActiveIndicator(false)
                            chatFragment.showHeartbeat("Research report ready! Say 'read the research report'.", 15_000L)
                            viewModel.appendDirectAssistantResponse(
                                "Research report on \"$topic\" is ready.\nSay \"read the research report\" to hear it."
                            )
                        }
                    } else {
                        Log.d(TAG, "Research poll: not ready yet (HTTP $code)")
                        runOnUiThread {
                            chatFragment.showHeartbeat("Researching: $topic...", 35_000L)
                        }
                    }
                } catch (e: Exception) {
                    Log.d(TAG, "Research poll error: ${e.message}")
                }
            }
        }
    }

    /** Build relay base URL from preferences (same logic as OpenClawClient/CompanionServer). */
    private fun buildRelayBaseUrlFromPrefs(): String? {
        val endpoint = viewModel.preferences.openClawEndpoint.trim()
        if (endpoint.isBlank()) return null
        val host = Regex("""://([^:/]+)""").find(endpoint)?.groupValues?.get(1) ?: return null
        val isIp = host.matches(Regex("""\d+\.\d+\.\d+\.\d+"""))
        val isLocal = host == "localhost" || host == "127.0.0.1" || isIp
        return if (isLocal) "http://$host:18790" else {
            val parts = host.split(".")
            val baseDomain = if (parts.size > 2) parts.drop(1).joinToString(".") else host
            "https://relay.$baseDomain"
        }
    }

    private fun shouldOwnToolAssistLocally(toolName: String): Boolean {
        return toolName in setOf("google_places", "google_routes", "google_air_quality", "location", "learn_topic", "translate_text", "battery_saver", "quick_action")
    }

    private fun looksLikeMapInfoIntent(transcript: String): Boolean {
        val lower = transcript.trim().lowercase(Locale.US)
        if (lower.isBlank()) return false
        if (looksLikeNearbyPlacesIntent(lower)) return true
        return listOf(
            "address", "directions", "route", "traffic", "eta", "how far", "how long",
            "where is", "located", "near me", "nearby", "closest", "nearest", "map",
            "parking", "air quality", "aqi", "walk time", "drive time", "transit"
        ).any { lower.contains(it) }
    }

    private fun presentToolAssistLocally(toolName: String, resultText: String) {
        // Any learn_topic tool call means we should use the 30s timeout
        val preservePinnedLearnLmSession = toolName == "learn_topic"
        if (preservePinnedLearnLmSession) {
            learnLmToolCallActive = true
            keepLearnLmSessionAliveUntilManualClose = true
            Log.d(TAG, "presentToolAssistLocally learn_topic — 30s timeout set")
            armPinnedLearnLmResponseHandoff()
        } else {
            armLocalDirectResponseHandoff()
        }
        val speech = when (toolName) {
            "google_places" -> placesSpeechSummary(resultText)
            "google_routes" -> routesSpeechSummary(resultText)
            "location", "google_air_quality" -> locationSpeechSummary(resultText)
            "learn_topic" -> learnSpeechSummary(resultText)
            "tapclaw_agent" -> clawSpeechSummary(resultText)
            "translate_text" -> translateSpeechSummary(resultText)
            "battery_saver" -> batterySpeechSummary(resultText)
            "quick_action" -> quickActionSpeechSummary(resultText)
            else -> resultText.lineSequence().map { it.trim() }.firstOrNull { it.isNotBlank() }.orEmpty()
        }
        viewModel.appendDirectAssistantResponse(resultText)
        if (speech.isNotBlank()) {
            ttsController?.stop()
            ttsController?.speak(speech)
            showHudNotification(speech.take(120))
        } else {
            showHudNotification(resultText.take(120))
        }
    }

    private fun learnSpeechSummary(resultText: String): String {
        return resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.startsWith("[LearnLM model:", ignoreCase = true) }
            .take(3)
            .joinToString(". ")
            .ifBlank { "Tutor response ready." }
    }

    private fun dailyBriefSpeechSummary(resultText: String): String {
        val summary = resultText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot { it.endsWith(":") || it.startsWith("-") || it.startsWith("Ultimate daily brief") }
            .take(3)
            .joinToString(". ")
            .trim()
        return if (summary.isBlank()) {
            "Daily briefing ready."
        } else {
            "Daily briefing ready. $summary"
        }
    }

    private fun hudSafeCalendarResult(raw: String): String {
        val cleaned = raw.replace('\r', '\n').trim()
        if (cleaned.isBlank()) return "No upcoming events."
        // If the cleaned text looks like a direct calendar answer, return it
        // before running line-level noise filtering.
        val looksLikeCalendarAnswer = cleaned.contains("—") ||
            cleaned.contains(" AM") || cleaned.contains(" PM") ||
            cleaned.lowercase(Locale.US).let {
                it.startsWith("no upcoming events") ||
                it.startsWith("no events") ||
                it.startsWith("you have no") ||
                it.startsWith("your next event") ||
                it.startsWith("here are your") ||
                it.startsWith("today's events") ||
                it.startsWith("tomorrow's events")
            }
        if (looksLikeCalendarAnswer) {
            val firstLines = cleaned.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .take(6)
                .toList()
            return if (firstLines.isEmpty()) "No upcoming events." else firstLines.joinToString("\n")
        }
        val lines =
            cleaned
                .lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() }
                .map { line ->
                    when {
                        line.startsWith("•") -> line.removePrefix("•").trim()
                        line.startsWith("-") -> line.removePrefix("-").trim()
                        line.matches(Regex("^\\d+[.)].*")) ->
                            line.replaceFirst(Regex("^\\d+[.)]\\s*"), "").trim()
                        else -> line
                    }
                }
                .filterNot { looksLikeInternalHudNoise(it) }
                .take(6)
                .toList()
        if (lines.isEmpty()) return "No upcoming events."
        return lines.joinToString("\n")
    }

    private fun looksLikeInternalHudNoise(line: String): Boolean {
        val value = line.trim().lowercase(Locale.US)
        if (value.isBlank()) return false
        // Only filter genuinely internal/diagnostic output.
        // Do NOT filter lines starting with '[' generically — calendar
        // answers like "[10:00] Meeting" are valid.
        return value.startsWith("[tools]") ||
            value.startsWith("ps ") ||
            value.startsWith("http/") ||
            (value.startsWith("{") && value.contains("\"")) ||
            value.startsWith("error:") ||
            value.startsWith("tool dispatch error") ||
            (value.contains("exception") && value.contains(" at ")) ||
            value.contains("stack trace") ||
            value.contains("restart gateway") ||
            value.contains(" at com.")
    }

    private fun syncCameraToGeminiState(active: Boolean) {
        if (!active) {
            stopCameraCapture()
            return
        }

        // Respect user preference: if default mode is audio-only and camera
        // isn't already running, skip camera start — UNLESS a manual toggle
        // (double-tap) set pendingCameraStart, in which case honour it.
        if (!viewModel.preferences.assistantDefaultCamera && !cameraCaptureActive && !pendingCameraStart) {
            return
        }

        if (!cameraPermissionGranted) {
            pendingCameraStart = true
            return
        }

        if (!isChatUiReady()) {
            pendingCameraStart = true
            return
        }

        // Ensure PiP is visible before checking TextureView readiness.
        chatFragment.setCoreEyeCaptureEnabled(true)

        if (!coreEyeSurfaceReady || !chatFragment.isCoreEyeSurfaceReady()) {
            pendingCameraStart = true
            return
        }

        if (!cameraCaptureActive) {
            pendingCameraStart = false
            startCameraCapture()
        } else {
            pendingCameraStart = false
            viewModel.setMultimodalCameraEnabled(true)
            chatFragment.setCoreEyeCaptureEnabled(true)
        }
    }

    private fun refreshCameraForGeminiSession() {
        if (viewModel.voiceAssistantActive.value != true) {
            showHudNotification("Activate Gemini first")
            return
        }
        if (!cameraPermissionGranted) {
            showHudNotification("Camera permission required")
            return
        }

        if (!isChatUiReady() || !coreEyeSurfaceReady || !chatFragment.isCoreEyeSurfaceReady()) {
            pendingCameraStart = true
            showHudNotification("Preparing camera…")
            return
        }

        if (cameraCaptureActive) {
            stopCameraCapture()
        }
        syncCameraToGeminiState(active = true)
        showHudNotification("Camera refreshed")
    }

    private fun startCameraCapture() {
        if (viewModel.voiceAssistantActive.value != true) {
            stopCameraCapture()
            return
        }
        if (cameraCaptureActive) return
        val surfaceProvider = chatFragment.getCoreEyeSurfaceProvider()
        if (surfaceProvider == null) {
            pendingCameraStart = true
            // Ensure the camera surface is being created so pending start resolves
            chatFragment.setCoreEyeCaptureEnabled(true)
            showHudNotification("Waiting for camera surface…")
            return
        }
        pendingCameraStart = false
        chatFragment.setCoreEyeCaptureEnabled(true)
        frameCapture?.start(this, surfaceProvider) { base64 ->
            latestFrame = base64
            viewModel.updateLatestLearnFrame(base64)
            maybeSendMultimodalImageFrame(base64)
            runOnUiThread { chatFragment.onCoreEyeFrameStreamed() }
        }
        cameraCaptureActive = true
        viewModel.setMultimodalCameraEnabled(true)
    }

    private fun stopCameraCapture() {
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        if (cameraCaptureActive) {
            frameCapture?.stop()
        }
        cameraCaptureActive = false
        pendingCameraStart = false
        latestFrame = null
        viewModel.updateLatestLearnFrame(null)
        lastMultimodalFrameSentMs = 0L
        viewModel.setMultimodalCameraEnabled(false)
        if (isChatUiReady()) {
            chatFragment.setCoreEyeCaptureEnabled(false)
        }
    }

    private fun isGeminiListeningOrThinking(): Boolean {
        return viewModel.voiceAssistantActive.value == true ||
                geminiCaptureActive ||
                geminiLiveSession != null ||
                liveSessionReady
    }

    private fun scheduleCameraIdleTimeout() {
        if (!cameraCaptureActive) return
        uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
        uiHandler.postDelayed(cameraIdleTimeoutRunnable, CAMERA_IDLE_TIMEOUT_MS)
    }

    /** Forward trackpad touch events and update chat HUD idle timers. */
    override fun dispatchTouchEvent(ev: MotionEvent?): Boolean {
        if (ev == null) return super.dispatchTouchEvent(ev)
        when (ev.actionMasked) {
            MotionEvent.ACTION_DOWN,
            MotionEvent.ACTION_MOVE,
            MotionEvent.ACTION_UP -> markTrackpadActivity()
        }
        if (customKeyboardView?.visibility == View.VISIBLE) {
            if (gestureEngine.onTouchEvent(ev)) {
                return true
            }
            return super.dispatchTouchEvent(ev)
        }

        if (gestureEngine.onTouchEvent(ev)) {
            return true
        }
        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        when (ev.actionMasked) {
            MotionEvent.ACTION_SCROLL -> {
                val isPointerLike =
                        ev.isFromSource(InputDevice.SOURCE_CLASS_POINTER) ||
                                ev.isFromSource(InputDevice.SOURCE_MOUSE) ||
                                ev.isFromSource(InputDevice.SOURCE_TOUCHPAD)
                if (isPointerLike) {
                    markTrackpadActivity()
                    var deltaX = ev.getAxisValue(MotionEvent.AXIS_HSCROLL) * GENERIC_SCROLL_SCALE
                    var deltaY = -ev.getAxisValue(MotionEvent.AXIS_VSCROLL) * GENERIC_SCROLL_SCALE

                    if (deltaX == 0f && deltaY == 0f) {
                        deltaX = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_X)
                        deltaY = ev.getAxisValue(MotionEvent.AXIS_RELATIVE_Y)
                    }

                    if (gestureEngine.onGenericScroll(deltaX, deltaY)) {
                        return true
                    }
                    if (currentTrackpadPanel()?.onTrackpadPan(deltaX, deltaY) == true) {
                        return true
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_PRESS -> {
                if ((ev.buttonState and MotionEvent.BUTTON_PRIMARY) != 0) {
                    markTrackpadActivity()
                    if (dispatchSyntheticTrackpadTap(MotionEvent.ACTION_DOWN, ev)) {
                        return true
                    }
                }
            }

            MotionEvent.ACTION_BUTTON_RELEASE -> {
                markTrackpadActivity()
                if (dispatchSyntheticTrackpadTap(MotionEvent.ACTION_UP, ev)) {
                    return true
                }
            }
        }
        return super.dispatchGenericMotionEvent(ev)
    }

    private fun dispatchSyntheticTrackpadTap(action: Int, sourceEvent: MotionEvent): Boolean {
        val synthetic =
                MotionEvent.obtain(
                        sourceEvent.downTime.takeIf { it > 0L } ?: SystemClock.uptimeMillis(),
                        SystemClock.uptimeMillis(),
                        action,
                        sourceEvent.x,
                        sourceEvent.y,
                        0
                )
        synthetic.source = sourceEvent.source
        return try {
            gestureEngine.onTouchEvent(synthetic)
        } finally {
            synthetic.recycle()
        }
    }

    /**
     * Forward hardware key events from the temple trackpad. Signature uses NON-nullable KeyEvent to
     * match AppCompatActivity.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            markTrackpadActivity()
        }
        if (gestureEngine.onKeyEvent(event)) {
            return true
        }
        return super.dispatchKeyEvent(event)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Speech & Audio Handlers
    // ══════════════════════════════════════════════════════════════════════

    private fun touchGeminiLiveActivity(force: Boolean = false) {
        if (geminiLiveSession == null) return
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastLiveActivityHeartbeatMs < GEMINI_LIVE_ACTIVITY_HEARTBEAT_MS) {
            return
        }
        lastLiveActivityHeartbeatMs = now
        // Reset the silence watchdog while the model is actively streaming audio/text.
        // This prevents the idle timeout from firing mid-response when the model
        // takes a brief pause between audio chunks or tool-call rounds.
        // Also cancel any pending audio-drain watcher from a previous turn.
        geminiAudioPlayer?.cancelDrain()
        disarmSilenceWatchdog()
    }

    private fun armSilenceWatchdog() {
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        if (geminiLiveSession == null) return
        if (chatFragment.isReaderModeActive()) return
        val userLiveIdleSeconds = viewModel.preferences.timeoutLiveIdleSeconds
        val defaultTimeout = if (keepLearnLmSessionAliveUntilManualClose) LEARNLM_IDLE_TIMEOUT_MS else GEMINI_LIVE_IDLE_TIMEOUT_MS
        val timeout = if (userLiveIdleSeconds > 0) userLiveIdleSeconds * 1000L else defaultTimeout
        uiHandler.postDelayed(stopGeminiCaptureRunnable, timeout)
    }

    private fun disarmSilenceWatchdog() {
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
    }

    private fun handleGeminiLiveIdleTimeout() {
        if (geminiLiveSession == null || liveState == GeminiLiveState.IDLE) {
            hideOscilloscope()
            return
        }
        if (chatFragment.isReaderModeActive()) {
            return
        }

        // ── Last-chance learnLM detection ──
        // If ANY signal indicates this is a learnLM session but the flag
        // wasn't set in time (race between onTurnComplete and onToolCall),
        // promote to 30s instead of killing.
        if (!keepLearnLmSessionAliveUntilManualClose) {
            val isLearnLm = learnLmToolCallActive ||
                listOf(pendingLiveInputTranscript, lastHandledLiveInputTranscript, lastToolAssistTranscript)
                    .any { AssistantIntentParser.isExplicitLearnRequest(it.trim()) ||
                           AssistantIntentParser.isLooseLearnLmPrefix(it.trim()) }
            if (isLearnLm) {
                keepLearnLmSessionAliveUntilManualClose = true
                Log.d(TAG, "LearnLM detected at timeout — upgrading to 30s instead of killing")
                armSilenceWatchdog()   // re-arm with 30s
                return
            }
        }

        val userLiveIdleSeconds = viewModel.preferences.timeoutLiveIdleSeconds
        val effectiveTimeoutSec = if (userLiveIdleSeconds > 0) userLiveIdleSeconds
            else if (keepLearnLmSessionAliveUntilManualClose) (LEARNLM_IDLE_TIMEOUT_MS / 1000).toInt()
            else (GEMINI_LIVE_IDLE_TIMEOUT_MS / 1000).toInt()
        val msg = "Session ended after ${effectiveTimeoutSec}s of silence."
        shutdownMultimodalSession(msg)
    }

    private fun shutdownMultimodalSession(message: String? = null) {
        keepLearnLmSessionAliveUntilManualClose = false
        learnLmToolCallActive = false
        if (suppressGeminiOutputUntilMs == Long.MAX_VALUE) {
            suppressGeminiOutputUntilMs = 0L
        }
        disarmSilenceWatchdog()
        awaitingServerTurnComplete = false
        releaseGeminiAudioCapture(cancelOnly = true)
        viewModel.deactivateVoiceAssistant()
        showListeningOverlay(false)
        clearLiveSpeechPreview()
        clearListeningTranscript()
        hideOscilloscope()
        chatFragment.setStreamActiveIndicator(false)
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.IDLE)
        stopCameraCapture()
        runOnUiThread { chatFragment.focusNewChatCard(animate = true) }
        message?.trim()?.takeIf { it.isNotBlank() }?.let { showHudNotification(it) }
    }

    private fun markUserSpeechActivity() {
        lastUserSpeechActivityMs = SystemClock.uptimeMillis()
        awaitingServerTurnComplete = true
        disarmSilenceWatchdog()
        if (liveState == GeminiLiveState.FOLLOW_UP || liveState == GeminiLiveState.THINKING) {
            liveState = GeminiLiveState.LISTENING
            runOnUiThread { updateListeningTranscript("Listening… Speak now") }
        }
        touchGeminiLiveActivity(force = true)
    }

    private fun maybeSendMultimodalImageFrame(imageBase64: String) {
        if (!viewModel.canSendMultimodalFrame()) return
        if (!liveSessionReady || geminiLiveSession == null) return
        if (liveState == GeminiLiveState.IDLE) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - lastMultimodalFrameSentMs < MULTIMODAL_FRAME_INTERVAL_MS) return
        val sent = geminiLiveSession?.sendImageChunkBase64(imageBase64, "image/jpeg") == true
        if (sent) {
            lastMultimodalFrameSentMs = now
            Log.d(TAG, "Sent multimodal frame to Gemini Live")
        }
    }

    private fun startVoiceInputSession() {
        if (USE_NATIVE_STT) {
            val controller = speechController
            if (controller != null) {
                controller.startListening()
            } else {
                fallbackToGeminiLiveFromNativeStt("speech_controller_unavailable")
            }
            return
        }
        startGeminiAudioCapture()
    }

    private fun fallbackToGeminiLiveFromNativeStt(reason: String): Boolean {
        if (!USE_NATIVE_STT) return false
        if (nativeSttFallbackTriggered) return false
        if (geminiLiveSession != null || geminiCaptureActive) return false

        nativeSttFallbackTriggered = true
        Log.w(TAG, "Native STT fallback -> Gemini Live ($reason)")
        showHudNotification("Voice fallback: Gemini Live")
        startGeminiAudioCapture()
        return true
    }

    private fun startGeminiAudioCapture() {
        if (geminiCaptureActive || geminiLiveSession != null) return

        viewModel.resetLiveAssistantStream()
        geminiAudioPlayer?.stopAndFlush()
        liveSessionReady = false
        liveSessionClosingByApp = false
        liveState = GeminiLiveState.LISTENING
        awaitingServerTurnComplete = false
        lastLiveActivityHeartbeatMs = 0L
        lastMultimodalFrameSentMs = 0L
        lastUserSpeechActivityMs = 0L
        latestLiveTranscript = ""
        latestLiveOutputTranscript = ""
        pendingLiveInputTranscript = ""
        lastHandledLiveInputTranscript = ""
        sawNonSilentGeminiAudio = false
        loggedGeminiAudioProbe = false
        updateListeningTranscript("Connecting to Gemini Live…")
        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.CONNECTING)
        pushOscilloscopeLevel(0.06f, OSCILLOSCOPE_USER_COLOR, force = true)
        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
        uiHandler.removeCallbacks(stopGeminiCaptureRunnable)
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        uiHandler.postDelayed(liveSetupTimeoutRunnable, GEMINI_LIVE_CONNECT_TIMEOUT_MS)

        // Capture the epoch so callbacks from THIS session can detect staleness.
        val sessionEpoch = geminiSessionEpoch

        val session =
                viewModel.geminiRouter.startLiveAudioSession(
                        listener =
                                object :
                                        com.rayneo.visionclaw.core.network.GeminiRouter.LiveSessionListener {
                                    /** True only while this session is still the active one. */
                                    private fun isCurrentSession(): Boolean =
                                            sessionEpoch == geminiSessionEpoch

                                    override fun onSessionReady() {
                                        if (!isCurrentSession()) return
                                        runOnUiThread {
                                            if (!isCurrentSession()) return@runOnUiThread
                                            uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                            liveSessionReady = true
                                            forceDirectGeminiLive = true
                                            liveState = GeminiLiveState.LISTENING
                                            awaitingServerTurnComplete = false
                                            // Minimal UI updates first — get the mic streaming ASAP.
                                            setHudConnectionStatus(
                                                    ChatPanelFragment.ConnectionStatus.GEMINI_CONNECTED
                                            )
                                            updateListeningTranscript("Listening… Speak now")
                                            chatFragment.setStreamActiveIndicator(false)
                                            startGeminiAudioStreaming()
                                            touchGeminiLiveActivity(force = true)
                                            // Deferred: bridge-reachability ping is cosmetic —
                                            // run it after the session is fully streaming.
                                            uiHandler.postDelayed({ refreshToolBridgeStatus() }, 800L)
                                        }
                                    }

                                    override fun onInputTranscription(text: String) {
                                        if (!isCurrentSession()) return
                                        val safe = text.trim()
                                        if (safe.isBlank()) return
                                        liveState = GeminiLiveState.LISTENING
                                        awaitingServerTurnComplete = true
                                        markUserSpeechActivity()
                                        latestLiveTranscript =
                                                mergeLiveTranscript(latestLiveTranscript, safe)
                                        pendingLiveInputTranscript = latestLiveTranscript

                                        // Early learnlm detection: set the flag NOW so that
                                        // onTurnComplete() uses the 30s timeout instead of 5s.
                                        // Without this, onTurnComplete fires before onToolCall
                                        // and arms the 5s watchdog before the flag is set.
                                        if (!keepLearnLmSessionAliveUntilManualClose &&
                                            AssistantIntentParser.isExplicitLearnRequest(latestLiveTranscript)) {
                                            keepLearnLmSessionAliveUntilManualClose = true
                                            Log.d(TAG, "LearnLM prefix detected early — flag set, 30s timeout will apply")
                                        }

                                        // Immediately check YouTube patterns before Gemini can respond.
                                        // These patterns require complete keywords ("subscriptions",
                                        // "history") so partial transcripts won't false-match.
                                        val youtubeReq = parseYouTubePlaybackIntent(safe)
                                        if (youtubeReq != null) {
                                            uiHandler.removeCallbacks(settledLiveInputRunnable)
                                            lastHandledLiveInputTranscript = safe
                                            runOnUiThread {
                                                armLocalDirectResponseHandoff()
                                                showHudNotification(youtubeReq.hudLabel)
                                                viewModel.appendDirectAssistantResponse(youtubeReq.responseText)
                                                ttsController?.stop()
                                                ttsController?.speak(youtubeReq.hudLabel)
                                                launchTapBrowser(
                                                    initialUrl = youtubeReq.searchUrl,
                                                    youtubeAutoplayQuery = youtubeReq.query,
                                                    youtubeAutoplayMode = youtubeReq.mode
                                                )
                                            }
                                            return
                                        }

                                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                                        uiHandler.postDelayed(
                                                settledLiveInputRunnable,
                                                LIVE_INPUT_SETTLE_MS
                                        )
                                        runOnUiThread {
                                            updateListeningTranscript(safe)
                                        }
                                    }

                                    override fun onOutputTranscription(text: String) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        val safe = text.trim()
                                        if (safe.isBlank()) return
                                        if (maybeRecoverFromGeminiFallback(safe)) return

                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        latestLiveOutputTranscript =
                                                mergeLiveTranscript(latestLiveOutputTranscript, safe)
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(true)
                                            updateListeningTranscript(safe)
                                            viewModel.appendLiveAssistantStreamChunk(
                                                    latestLiveOutputTranscript
                                            )
                                            if (Patterns.WEB_URL.matcher(latestLiveOutputTranscript).find()) {
                                                chatFragment.autoFocusLatestAssistantUrl()
                                            }
                                        }
                                    }
                                    override fun onModelText(text: String) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        if (maybeRecoverFromGeminiFallback(text)) return

                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        // Verbatim dialog mode: ignore free-form model text/metadata payloads.
                                        // Chat persistence is driven only by outputTranscription.
                                    }
                                    override fun onModelAudio(mimeType: String, data: ByteArray) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        liveState = GeminiLiveState.THINKING
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        val outputPeak = calculatePcm16Peak(data, data.size)
                                        val normalised = (outputPeak / 32767f).coerceIn(0f, 1f)
                                        pushOscilloscopeLevel(normalised, OSCILLOSCOPE_MODEL_COLOR)
                                        runOnUiThread { chatFragment.setStreamActiveIndicator(true) }
                                        val prefs = viewModel.preferences
                                        geminiAudioPlayer?.playChunk(
                                                mimeType = mimeType,
                                                data = data,
                                                muted = prefs.ttsMuted,
                                                volume = prefs.ttsVolume
                                        )
                                    }

                                    override fun onToolCall(callId: String, name: String, args: String) {
                                        if (!isCurrentSession() || isGeminiOutputSuppressed()) return
                                        awaitingServerTurnComplete = true
                                        touchGeminiLiveActivity()
                                        // Cancel the ToolAssist settled-input timer so we don't
                                        // inject a duplicate client-text response alongside the
                                        // Gemini tool-call response.  Mark transcript as handled
                                        // so the runnable is a no-op even if it fires anyway.
                                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                                        val transcript = pendingLiveInputTranscript.trim()
                                        if (transcript.isNotBlank()) {
                                            lastHandledLiveInputTranscript = transcript
                                        }
                                        dispatchLiveToolCall(callId = callId, name = name, args = args)
                                    }

                                    override fun onTurnComplete(finishReason: String?) {
                                        if (!isCurrentSession()) return
                                        viewModel.commitLiveAssistantStreamIfNeeded()
                                        awaitingServerTurnComplete = false
                                        liveState = GeminiLiveState.FOLLOW_UP
                                        uiHandler.removeCallbacks(settledLiveInputRunnable)

                                        // Safety net: check learnlm prefix on the transcript
                                        // in case onInputTranscription had only partial text
                                        if (!keepLearnLmSessionAliveUntilManualClose) {
                                            val candidates = listOf(
                                                pendingLiveInputTranscript,
                                                lastHandledLiveInputTranscript,
                                                lastToolAssistTranscript
                                            )
                                            if (candidates.any { AssistantIntentParser.isExplicitLearnRequest(it.trim()) }) {
                                                keepLearnLmSessionAliveUntilManualClose = true
                                                Log.d(TAG, "LearnLM prefix detected in onTurnComplete — flag set, 30s timeout")
                                            }
                                        }

                                        val shouldStartCleanupTimer =
                                                finishReason.isNullOrBlank() ||
                                                        finishReason.equals("STOP", ignoreCase = true)
                                        if (shouldStartCleanupTimer || keepLearnLmSessionAliveUntilManualClose) {
                                            // Don't arm the watchdog immediately — the AudioTrack
                                            // may still have buffered audio playing.  Notify the
                                            // audio player that the turn is done; it will invoke
                                            // onDrainComplete once all audio has been heard, which
                                            // is where we arm the watchdog.
                                            geminiAudioPlayer?.notifyTurnComplete()
                                                ?: armSilenceWatchdog() // fallback if no player
                                        } else {
                                            disarmSilenceWatchdog()
                                        }
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(false)
                                            updateListeningTranscript("Listening for follow-up…")
                                            chatFragment.autoFocusLatestAssistantUrl()
                                        }
                                        Log.d(TAG, "Gemini turn complete finishReason=${finishReason ?: "unknown"}")
                                    }

                                    override fun onError(message: String) {
                                        if (!isCurrentSession()) return
                                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                        awaitingServerTurnComplete = false

                                        val gatewayProtocolMismatch =
                                                !forceDirectGeminiLive &&
                                                        (message.contains("invalid request frame", ignoreCase = true) ||
                                                                (message.contains("unexpected property", ignoreCase = true) &&
                                                                        message.contains("setup", ignoreCase = true)) ||
                                                                (message.contains("required property", ignoreCase = true) &&
                                                                        message.contains("method", ignoreCase = true)))
                                        if (gatewayProtocolMismatch) {
                                            forceDirectGeminiLive = true
                                            runOnUiThread {
                                                setHudConnectionStatus(
                                                        ChatPanelFragment.ConnectionStatus.CONNECTING
                                                )
                                                showHudNotification(
                                                        "Gateway RPC mode detected. Retrying direct Gemini…"
                                                )
                                                releaseGeminiAudioCapture(cancelOnly = true)
                                                uiHandler.postDelayed(
                                                        { startGeminiAudioCapture() },
                                                        150L
                                                )
                                            }
                                            return
                                        }

                                        setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.ERROR)
                                        handleGeminiVoiceFailure(message)
                                    }

                                    override fun onClosed(code: Int, reason: String) {
                                        Log.d(
                                                TAG,
                                                "Gemini Live session closed code=$code reason=$reason epoch=$sessionEpoch current=$geminiSessionEpoch"
                                        )
                                        if (!isCurrentSession()) {
                                            // Stale callback from a previous session — ignore.
                                            Log.d(TAG, "Ignoring onClosed from stale session (epoch $sessionEpoch)")
                                            return
                                        }
                                        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
                                        uiHandler.removeCallbacks(settledLiveInputRunnable)
                                        val closedByApp = liveSessionClosingByApp
                                        val wasLiveSessionReady = liveSessionReady
                                        liveSessionClosingByApp = false
                                        liveSessionReady = false
                                        liveState = GeminiLiveState.IDLE
                                        awaitingServerTurnComplete = false
                                        if (closedByApp) {
                                            runOnUiThread {
                                                chatFragment.setStreamActiveIndicator(false)
                                                hideOscilloscope()
                                                setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.IDLE)
                                            }
                                            return
                                        }
                                        val invalidGatewayFrame =
                                                code == 1008 &&
                                                        reason.contains(
                                                                "invalid request frame",
                                                                ignoreCase = true
                                                        ) &&
                                                        !forceDirectGeminiLive
                                        if (invalidGatewayFrame) {
                                            forceDirectGeminiLive = true
                                            runOnUiThread {
                                                setHudConnectionStatus(
                                                        ChatPanelFragment.ConnectionStatus.CONNECTING
                                                )
                                                showHudNotification(
                                                        "Gateway live rejected. Retrying direct Gemini…"
                                                )
                                                releaseGeminiAudioCapture(cancelOnly = true)
                                                uiHandler.postDelayed(
                                                        { startGeminiAudioCapture() },
                                                        150L
                                                )
                                            }
                                            return
                                        }
                                        if (!wasLiveSessionReady) {
                                            val suffix =
                                                    reason.takeIf { it.isNotBlank() }?.let {
                                                        ": $it"
                                                    }
                                                            ?: ""
                                            handleGeminiVoiceFailure(
                                                    "Gemini Live closed before ready (code $code)$suffix"
                                            )
                                            return
                                        }
                                        runOnUiThread {
                                            chatFragment.setStreamActiveIndicator(false)
                                            shutdownMultimodalSession("Gemini Live session closed.")
                                        }
                                    }
                                },
                        forceDirect = true
                )

        if (session == null) {
            uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
            // startLiveAudioSession already reports the concrete error via listener.onError.
            return
        }
        geminiLiveSession = session
        touchGeminiLiveActivity(force = true)
    }

    private fun handleGeminiVoiceFailure(message: String) {
        keepLearnLmSessionAliveUntilManualClose = false
        learnLmToolCallActive = false
        if (suppressGeminiOutputUntilMs == Long.MAX_VALUE) {
            suppressGeminiOutputUntilMs = 0L
        }
        val display = message.trim().ifBlank { "Voice request failed. Please try again." }
        Log.w(TAG, "Gemini voice failure: $display")
        runOnUiThread {
            awaitingServerTurnComplete = false
            viewModel.resetLiveAssistantStream()
            releaseGeminiAudioCapture(cancelOnly = true)
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()
            chatFragment.setStreamActiveIndicator(false)
            setHudConnectionStatus(ChatPanelFragment.ConnectionStatus.ERROR)
            playVoiceTimeoutBeep()
            showHudNotification(display)
        }
    }

    private fun releaseGeminiAudioEffects() {
        runCatching { geminiAcousticEchoCanceler?.enabled = false }
        runCatching { geminiAcousticEchoCanceler?.release() }
        geminiAcousticEchoCanceler = null
        runCatching { geminiNoiseSuppressor?.enabled = false }
        runCatching { geminiNoiseSuppressor?.release() }
        geminiNoiseSuppressor = null
        runCatching { geminiAutomaticGainControl?.enabled = false }
        runCatching { geminiAutomaticGainControl?.release() }
        geminiAutomaticGainControl = null
    }

    private fun configureGeminiAudioEffects(audioSessionId: Int) {
        releaseGeminiAudioEffects()
        if (audioSessionId <= 0) return
        if (AcousticEchoCanceler.isAvailable()) {
            geminiAcousticEchoCanceler = AcousticEchoCanceler.create(audioSessionId)?.also {
                runCatching { it.enabled = true }
                Log.d(TAG, "Gemini AEC enabled session=$audioSessionId")
            }
        } else {
            Log.d(TAG, "Gemini AEC unavailable on this device")
        }
        if (NoiseSuppressor.isAvailable()) {
            geminiNoiseSuppressor = NoiseSuppressor.create(audioSessionId)?.also {
                runCatching { it.enabled = true }
                Log.d(TAG, "Gemini noise suppressor enabled session=$audioSessionId")
            }
        }
        if (AutomaticGainControl.isAvailable()) {
            geminiAutomaticGainControl = AutomaticGainControl.create(audioSessionId)?.also {
                runCatching { it.enabled = true }
                Log.d(TAG, "Gemini AGC enabled session=$audioSessionId")
            }
        }
    }

    private fun createGeminiAudioRecord(bufferSize: Int): AudioRecord? {
        val sources = intArrayOf(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            MediaRecorder.AudioSource.MIC
        )
        for (source in sources) {
            val recorder = runCatching {
                AudioRecord(
                    source,
                    GEMINI_AUDIO_SAMPLE_RATE,
                    AudioFormat.CHANNEL_IN_MONO,
                    AudioFormat.ENCODING_PCM_16BIT,
                    bufferSize
                )
            }.getOrNull() ?: continue
            if (recorder.state == AudioRecord.STATE_INITIALIZED) {
                Log.d(TAG, "Gemini microphone source=${if (source == MediaRecorder.AudioSource.VOICE_COMMUNICATION) "VOICE_COMMUNICATION" else "MIC"}")
                return recorder
            }
            runCatching { recorder.release() }
        }
        return null
    }

    private fun maybeHandleGeminiBargeIn(micPeak: Int) {
        if (viewModel.preferences.liveDisableInterrupt) return  // Never-interrupt mode
        val session = geminiLiveSession ?: return
        if (!liveSessionReady) return
        if (isGeminiOutputSuppressed()) return
        val player = geminiAudioPlayer ?: return
        if (!player.isActivelySpeaking()) {
            geminiBargeInCandidateSinceMs = 0L
            return
        }
        val now = SystemClock.uptimeMillis()
        if (now - geminiBargeInLastTriggerMs < GEMINI_BARGE_IN_COOLDOWN_MS) {
            return
        }
        val micLevel = (micPeak / 32767f).coerceIn(0f, 1f)
        val outputLevel = player.currentOutputLevel().coerceIn(0f, 1f)
        val sensitivity = viewModel.preferences.liveBargeInSensitivity.coerceIn(0.6f, 2.5f)
        val requiredLevel = max(
            GEMINI_BARGE_IN_MIN_MIC_LEVEL * sensitivity,
            max(
                outputLevel + (GEMINI_BARGE_IN_OUTPUT_MARGIN * sensitivity),
                outputLevel * (1f + ((GEMINI_BARGE_IN_OUTPUT_RATIO - 1f) * sensitivity))
            )
        ).coerceAtMost(0.98f)
        if (micLevel < requiredLevel) {
            geminiBargeInCandidateSinceMs = 0L
            return
        }
        if (geminiBargeInCandidateSinceMs == 0L) {
            geminiBargeInCandidateSinceMs = now
            return
        }
        val holdMs = (GEMINI_BARGE_IN_HOLD_MS * sensitivity).toLong().coerceIn(220L, 900L)
        if (now - geminiBargeInCandidateSinceMs < holdMs) {
            return
        }
        geminiBargeInCandidateSinceMs = 0L
        geminiBargeInLastTriggerMs = now
        suppressGeminiOutputUntilMs = maxOf(
            suppressGeminiOutputUntilMs,
            now + GEMINI_BARGE_IN_SUPPRESS_MS
        )
        ttsController?.stop()
        player.stopAndFlush()
        awaitingServerTurnComplete = true
        liveState = GeminiLiveState.LISTENING
        touchGeminiLiveActivity(force = true)
        Log.d(TAG, "Gemini barge-in triggered micLevel=$micLevel outputLevel=$outputLevel requiredLevel=$requiredLevel sensitivity=$sensitivity")
        runOnUiThread {
            chatFragment.setStreamActiveIndicator(false)
            updateListeningTranscript("Listening… Speak now")
        }
    }

    private fun startGeminiAudioStreaming() {
        if (geminiCaptureActive) return
        if (!liveSessionReady || geminiLiveSession == null) return

        enableRayNeoVoiceAssistantMicRoute()

        val minBuffer =
                AudioRecord.getMinBufferSize(
                        GEMINI_AUDIO_SAMPLE_RATE,
                        AudioFormat.CHANNEL_IN_MONO,
                        AudioFormat.ENCODING_PCM_16BIT
                )
        if (minBuffer <= 0) {
            handleGeminiVoiceFailure("Microphone buffer could not be created.")
            return
        }

        val bufferSize = maxOf(minBuffer * 2, 4096)
        val recorder = createGeminiAudioRecord(bufferSize)
        if (recorder == null) {
            handleGeminiVoiceFailure("Unable to start microphone capture.")
            return
        }

        geminiAudioRecord = recorder
        geminiCaptureActive = true
        geminiBargeInCandidateSinceMs = 0L
        configureGeminiAudioEffects(recorder.audioSessionId)
        recorder.startRecording()

        geminiAudioThread =
                Thread {
                    android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_AUDIO)
                    val chunk = ByteArray(2048)
                    var chunkCount = 0
                    while (geminiCaptureActive) {
                        val read = recorder.read(chunk, 0, chunk.size)
                        if (read > 0) {
                            if (!loggedGeminiAudioProbe) {
                                loggedGeminiAudioProbe = true
                                val preview =
                                        chunk.take(read.coerceAtMost(10)).joinToString(" ") { b ->
                                            "%02x".format(b.toInt() and 0xFF)
                                        }
                                Log.d(TAG, "Gemini mic probe bytes: $preview")
                            }
                            val peak = calculatePcm16Peak(chunk, read)
                            val silenceThreshold = viewModel.preferences.liveSilenceThreshold
                            if (peak >= silenceThreshold) {
                                sawNonSilentGeminiAudio = true
                                awaitingServerTurnComplete = true
                                touchGeminiLiveActivity(force = true)
                                maybeHandleGeminiBargeIn(peak)
                            } else {
                                geminiBargeInCandidateSinceMs = 0L
                            }
                            val normalisedPeak = (peak / 32767f).coerceIn(0f, 1f)
                            pushOscilloscopeLevel(normalisedPeak, OSCILLOSCOPE_USER_COLOR)
                            if (chunkCount % 12 == 0) {
                                Log.d(
                                        TAG,
                                        "Gemini mic chunk=$chunkCount bytes=$read peak=$peak nonSilent=$sawNonSilentGeminiAudio"
                                )
                            }
                            chunkCount += 1
                            geminiLiveSession?.sendAudioChunkPcm16(
                                    chunk,
                                    read,
                                    GEMINI_AUDIO_SAMPLE_RATE
                            )
                        } else if (read < 0) {
                            Log.w(TAG, "Gemini mic read error code=$read")
                        }
                    }
                }
                        .apply {
                            name = "GeminiLiveAudioThread"
                            start()
                        }
        touchGeminiLiveActivity(force = true)
    }

    private fun stopGeminiAudioStreaming() {
        if (!geminiCaptureActive && geminiAudioRecord == null) return

        geminiCaptureActive = false
        geminiBargeInCandidateSinceMs = 0L
        runCatching { geminiAudioRecord?.stop() }
        releaseGeminiAudioEffects()
        runCatching { geminiAudioRecord?.release() }
        geminiAudioRecord = null

        // Never join() on the UI thread — it blocked for up to 250ms per tap.
        // The audio thread exits on its own once geminiCaptureActive == false.
        val thread = geminiAudioThread
        geminiAudioThread = null
        if (thread != null) {
            // Fire-and-forget cleanup on a background thread.
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { thread.join(300) }
            }
        }
        disableRayNeoVoiceAssistantMicRouteAsync()
    }

    private fun releaseGeminiAudioCapture(cancelOnly: Boolean) {
        disarmSilenceWatchdog()
        uiHandler.removeCallbacks(liveSetupTimeoutRunnable)
        stopGeminiAudioStreaming()
        geminiAudioPlayer?.stopAndFlush()
        liveSessionReady = false
        liveState = GeminiLiveState.IDLE
        awaitingServerTurnComplete = false
        lastLiveActivityHeartbeatMs = 0L
        lastMultimodalFrameSentMs = 0L
        lastUserSpeechActivityMs = 0L

        // Bump the epoch so stale callbacks from the dying session are ignored.
        geminiSessionEpoch++
        liveSessionClosingByApp = true
        // Close the WebSocket on a background thread to avoid blocking the UI.
        val session = geminiLiveSession
        geminiLiveSession = null
        if (session != null) {
            lifecycleScope.launch(Dispatchers.IO) {
                runCatching { session.close() }
            }
        }
        latestLiveTranscript = ""
        latestLiveOutputTranscript = ""
        chatFragment.setStreamActiveIndicator(false)
        hideOscilloscope()
        // stopGeminiAudioStreaming already released the mic route asynchronously;
        // belt-and-suspenders async call here is harmless if the flag was already cleared.
        disableRayNeoVoiceAssistantMicRouteAsync()
        if (cancelOnly) {
            viewModel.resetLiveAssistantStream()
        }
    }

    /**
     * MUST be synchronous — AudioRecord is created immediately after this call
     * in startGeminiAudioStreaming(), and the RayNeo hardware requires the
     * audio_source_record parameter to be set BEFORE the recorder opens.
     * The call typically completes in <5 ms on RayNeo X3 hardware.
     */
    private fun enableRayNeoVoiceAssistantMicRoute() {
        if (rayNeoMicRouteActive) return
        rayNeoMicRouteActive = true
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("audio_source_record=voiceassistant")
            Log.i(TAG, "RayNeo mic route enabled (voiceassistant)")
        }.onFailure { Log.w(TAG, "Unable to enable RayNeo mic route: ${it.message}") }
    }

    /** Synchronous variant — only called from stopGeminiAudioStreaming which already defers
     *  to a background thread for cleanup.  Kept for the legacy call-site in releaseGeminiAudioCapture. */
    private fun disableRayNeoVoiceAssistantMicRoute() {
        if (!rayNeoMicRouteActive) return
        rayNeoMicRouteActive = false
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.setParameters("audio_source_record=off")
            Log.i(TAG, "RayNeo mic route released")
        }.onFailure { Log.w(TAG, "Unable to release RayNeo mic route: ${it.message}") }
    }

    /** Async variant — safe to call from the UI thread. */
    private fun disableRayNeoVoiceAssistantMicRouteAsync() {
        if (!rayNeoMicRouteActive) return
        rayNeoMicRouteActive = false
        val audioManager = getSystemService(AUDIO_SERVICE) as? AudioManager ?: return
        lifecycleScope.launch(Dispatchers.IO) {
            runCatching {
                audioManager.setParameters("audio_source_record=off")
                Log.i(TAG, "RayNeo mic route released (async)")
            }.onFailure { Log.w(TAG, "Unable to release RayNeo mic route: ${it.message}") }
        }
    }

    private fun mergeLiveTranscript(existing: String, incoming: String): String {
        val prev = existing.trim()
        val next = incoming.trim()
        if (prev.isBlank()) return next
        if (next.isBlank()) return prev
        if (next.startsWith(prev)) return next
        if (prev.startsWith(next)) return prev
        if (next.contains(prev)) return next
        if (prev.contains(next)) return prev
        val maxOverlap = minOf(prev.length, next.length)
        for (n in maxOverlap downTo 1) {
            if (prev.endsWith(next.substring(0, n))) {
                return prev + next.substring(n)
            }
        }
        return "$prev $next"
    }

    private fun calculatePcm16Peak(data: ByteArray, size: Int): Int {
        if (size < 2) return 0
        var peak = 0
        var i = 0
        while (i + 1 < size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF))
            val signed = if ((sample and 0x8000) != 0) sample - 0x10000 else sample
            val magnitude = abs(signed)
            if (magnitude > peak) peak = magnitude
            i += 2
        }
        return peak
    }

    private fun pushOscilloscopeLevel(level: Float, color: Int, force: Boolean = false) {
        if (!force && !isGeminiListeningOrThinking()) {
            return
        }
        val now = SystemClock.uptimeMillis()
        if (!force && now - lastOscilloscopeUiUpdateMs < OSCILLOSCOPE_UI_THROTTLE_MS) {
            return
        }
        lastOscilloscopeUiUpdateMs = now
        runOnUiThread {
            if (!force && !isGeminiListeningOrThinking()) {
                chatFragment.hideVoiceOscilloscope()
                voiceOscilloscope?.stop()
                voiceOscilloscope?.visibility = View.GONE
                return@runOnUiThread
            }
            chatFragment.pushVoiceOscilloscope(level, color)
            // Legacy overlay view is intentionally disabled in favor of inline chat rendering.
            voiceOscilloscope?.stop()
            voiceOscilloscope?.visibility = View.GONE
        }
    }

    private fun hideOscilloscope() {
        lastOscilloscopeUiUpdateMs = 0L
        runOnUiThread {
            chatFragment.hideVoiceOscilloscope()
            voiceOscilloscope?.stop()
            voiceOscilloscope?.visibility = View.GONE
        }
    }

    /**
     * Handle speech recognition result.
     *
     * Flow:
     * 1. If the active panel implements TrackpadPanel, try injecting
     * ```
     *      text into its focused field via onTextInputFromHold().
     * ```
     * 2. If no panel consumed the text, route through Gemini for
     * ```
     *      intent routing / tool calls.
     * ```
     * Note: STT may run through Android SpeechRecognizer or Gemini audio transcription fallback
     * (device-dependent). Gemini still handles intent routing and tool-call dispatch after
     * transcript extraction.
     */
    private fun handleSpeechResult(text: String) {
        Log.d(TAG, "Speech result: $text")
        runOnUiThread {
            nativeSttFallbackTriggered = false
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()

            if (maybeRouteLocalIntentDirectly(text)) {
                Log.d(TAG, "Voice input — local intent routed directly")
                return@runOnUiThread
            }

            val currentPanel = viewPager?.currentItem ?: MainViewModel.PANEL_CHAT

            // Chat panel: voice input always routes to Gemini directly —
            // the user expects a conversational response, not text sitting
            // in the EditText waiting for a manual Send tap.
            if (currentPanel == MainViewModel.PANEL_CHAT) {
                Log.d(TAG, "Chat panel — routing voice input to Gemini")
                viewModel.routeWithToolCalls(text, latestFrame)
                return@runOnUiThread
            }

            // Other panels (Settings, Web): try injecting into the focused
            // text field first (e.g. dictating into an API key field).
            val panel = currentTrackpadPanel()
            val consumed = panel?.onTextInputFromHold(text) ?: false

            if (!consumed) {
                // No active text field — fall back to Gemini routing
                Log.d(TAG, "No active text field, routing to Gemini")
                viewModel.routeWithToolCalls(text, latestFrame)
            } else {
                Log.d(TAG, "Text injected into active panel field")
            }
        }
    }

    private fun handleSpeechPartial(text: String) {
        runOnUiThread {
            updateListeningTranscript(text)
        }
    }

    /** Handle speech recognition error. Deactivates voice assistant and shows HUD notification. */
    private fun handleSpeechError(errorCode: Int) {
        Log.e(TAG, "Speech error: $errorCode")
        runOnUiThread {
            viewModel.deactivateVoiceAssistant()
            showListeningOverlay(false)
            clearLiveSpeechPreview()
            clearListeningTranscript()

            val message =
                    when (errorCode) {
                        SpeechRecognizer.ERROR_NO_MATCH ->
                                "No speech detected. Hold trackpad ~1s, then speak clearly."
                        SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                "Speech timed out. Try holding trackpad and speaking sooner."
                        SpeechRecognizer.ERROR_AUDIO ->
                                "Mic error — check microphone permission in Settings."
                        SpeechRecognizer.ERROR_NETWORK, SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                "Network error — speech service needs internet."
                        SpeechRecognizer.ERROR_SERVER ->
                                "Speech server error. Try again in a moment."
                        SpeechRecognizer.ERROR_SERVER_DISCONNECTED ->
                                "Speech service disconnected — reconnecting..."
                        SpeechRecognizer.ERROR_CLIENT ->
                                "Speech client error. Restarting recognizer."
                        SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS ->
                                "Mic permission denied. Grant in system Settings."
                        SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                "Recognizer busy. Wait a moment and try again."
                        else -> "Voice error (code $errorCode)"
                    }

            val isTimeout =
                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                            errorCode == SpeechRecognizer.ERROR_NO_MATCH
            if (isTimeout) playVoiceTimeoutBeep()

            val shouldFallbackToGeminiLive =
                    USE_NATIVE_STT &&
                            (errorCode == SpeechRecognizer.ERROR_NO_MATCH ||
                                    errorCode == SpeechRecognizer.ERROR_SPEECH_TIMEOUT ||
                                    errorCode == SpeechRecognizer.ERROR_CLIENT ||
                                    errorCode == SpeechRecognizer.ERROR_SERVER ||
                                    errorCode == SpeechRecognizer.ERROR_SERVER_DISCONNECTED ||
                                    errorCode == SpeechRecognizer.ERROR_RECOGNIZER_BUSY)
            if (shouldFallbackToGeminiLive &&
                    fallbackToGeminiLiveFromNativeStt("speech_error_$errorCode")) {
                return@runOnUiThread
            }

            showHudNotification(message)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad scroll/input forwarding to active panel
    // ══════════════════════════════════════════════════════════════════════

    /** Returns the current panel fragment cast to TrackpadPanel if applicable. */
    private fun currentTrackpadPanel(): TrackpadPanel? {
        return chatFragment as? TrackpadPanel
    }

    // ══════════════════════════════════════════════════════════════════════
    // ViewModel Observers
    // ══════════════════════════════════════════════════════════════════════

    private fun observeViewModel() {
        viewModel.apiKeyRequired.observe(this) { message ->
            if (message != null) {
                showHudNotification(message)
                viewModel.clearApiKeyRequired()
            }
        }

        viewModel.activePanelIndex.observe(this) { index ->
            if (index == MainViewModel.PANEL_WEB) {
                launchTapBrowser(viewModel.webNavigationUrl.value)
                viewModel.clearWebNavigation()
                return@observe
            }

            if (viewPager?.currentItem != MainViewModel.PANEL_CHAT) {
                viewPager?.setCurrentItem(MainViewModel.PANEL_CHAT, false)
            }
            handlePanelChanged(MainViewModel.PANEL_CHAT)
        }

        viewModel.voiceAssistantActive.observe(this) { active ->
            showListeningOverlay(active)
            syncCameraToGeminiState(active)
        }

        viewModel.youtubePlaybackEvent.observe(this) { event ->
            if (event == null) return@observe
            viewModel.clearYoutubePlaybackEvent()
            showHudNotification("Playing latest YouTube ${event.mode} for ${event.query}")
            launchTapBrowser(
                initialUrl = event.searchUrl,
                youtubeAutoplayQuery = event.query,
                youtubeAutoplayMode = event.mode
            )
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(
                        viewModel.calendarSummary,
                        viewModel.tasksSummary,
                        viewModel.newsSummary,
                        viewModel.airQualitySummary
                    ) { calendar, tasks, news, airQuality ->
                        arrayOf(calendar, tasks, news, airQuality)
                    }.collect { values ->
                        chatFragment.syncHudSnapshot(
                            calendarSummary = values[0] as String,
                            tasksSummary = values[1] as String,
                            newsSummary = values[2] as String,
                            airQualityState = values[3] as? MainViewModel.AirQualityHudState
                        )
                    }
                }
            }
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD Notification (non-intrusive overlay)
    // ══════════════════════════════════════════════════════════════════════

    private fun showHudNotification(message: String) {
        val formatted =
                message.trim().ifBlank {
                    return
                }
        hudNotification?.apply {
            uiHandler.removeCallbacks(hideHudNotificationRunnable)
            animate().cancel()
            text = formatted

            // Apply accessibility: font scale
            val fontScale = viewModel.preferences.hudFontScale
            if (fontScale > 0f) {
                textSize = 14f * fontScale  // base size 14sp scaled by user preference
            }
            // Apply accessibility: high contrast
            if (viewModel.preferences.hudHighContrast) {
                setTextColor(0xFFFFFFFF.toInt())
                setShadowLayer(4f, 0f, 0f, 0xFF000000.toInt())
            }

            visibility = View.VISIBLE
            alpha = 0f
            animate().alpha(1f).setDuration(160).start()
            uiHandler.postDelayed(hideHudNotificationRunnable, HUD_NOTIFICATION_DURATION_MS)
        }
    }

    fun showMirroredNotice(message: String) {
        runOnUiThread { showHudNotification(message) }
    }

    // Intentionally no-op for privacy: user speech transcripts are not shown in chat/HUD.
    private fun showLiveSpeechPreview(text: String) = Unit

    private fun clearLiveSpeechPreview() {
        hudNotification?.apply {
            uiHandler.removeCallbacks(hideHudNotificationRunnable)
            animate().cancel()
            visibility = View.GONE
        }
    }

    private fun updateListeningTranscript(text: String) {
        val value = text.trim()
        if (value.isBlank()) return
        listeningTranscript?.apply {
            this.text = value
            visibility = View.VISIBLE
            alpha = 1f
            isSelected = true
        }
    }

    private fun clearListeningTranscript() {
        pendingLiveInputTranscript = ""
        lastHandledLiveInputTranscript = ""
        uiHandler.removeCallbacks(settledLiveInputRunnable)
        listeningTranscript?.apply {
            text = ""
            visibility = View.GONE
            isSelected = false
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // Listening Overlay (Siri-style visual)
    // ══════════════════════════════════════════════════════════════════════

    private fun showListeningOverlay(show: Boolean) {
        listeningOverlay?.apply {
            if (show) {
                uiHandler.removeCallbacks(cameraIdleTimeoutRunnable)
                visibility = View.VISIBLE
                alpha = 0f
                animate().alpha(1f).setDuration(200).start()
                listeningTranscript?.visibility = View.VISIBLE
                listeningTranscript?.text = "Listening…"
                listeningTranscript?.isSelected = true
            } else {
                uiHandler.removeCallbacks(settledLiveInputRunnable)
                animate()
                        .alpha(0f)
                        .setDuration(300)
                        .withEndAction { visibility = View.GONE }
                        .start()
                listeningTranscript?.visibility = View.GONE
                listeningTranscript?.isSelected = false
            }
        }
    }

    private fun playVoiceActivateBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_ACK, VOICE_ACTIVATE_BEEP_MS)
    }

    private fun playVoiceTimeoutBeep() {
        toneGenerator?.startTone(ToneGenerator.TONE_PROP_NACK, VOICE_TIMEOUT_BEEP_MS)
    }

    // Screen mirroring functions removed – BinocularSbsLayout handles SBS rendering

}
