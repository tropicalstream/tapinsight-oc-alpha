package com.TapLinkX3.app

import android.Manifest
import android.annotation.SuppressLint
import android.content.ActivityNotFoundException
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.hardware.Camera
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.media.AudioAttributes
import android.media.AudioManager
import android.media.ImageReader
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Base64
import android.view.Choreographer
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.BaseInputConnection
import android.webkit.ConsoleMessage
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.JavascriptInterface
import android.webkit.PermissionRequest
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ImageView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.content.edit
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.CameraPreview
import com.journeyapps.barcodescanner.DecoratedBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import com.journeyapps.barcodescanner.camera.CameraConfigurationUtils
import com.ffalconxr.mercury.ipc.Launcher
import com.ffalconxr.mercury.ipc.helpers.GPSIPCHelper
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.lang.ref.WeakReference
import java.util.Locale
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import org.json.JSONObject

interface NavigationListener {
    fun onNavigationBackPressed()
    fun onNavigationForwardPressed()
    fun onQuitPressed()
    fun onSettingsPressed()
    fun onRefreshPressed()
    fun onHomePressed()
    fun onHyperlinkPressed()
}

interface LinkEditingListener {
    fun onShowLinkEditing()
    fun onHideLinkEditing()
    fun onSendCharacterToLink(character: String)
    fun onSendBackspaceInLink()
    fun onSendEnterInLink()
    fun onSendClearInLink()
    fun isLinkEditing(): Boolean
}

class MainActivity :
        AppCompatActivity(),
        DualWebViewGroup.DualWebViewGroupListener,
        NavigationListener,
        CustomKeyboardView.OnKeyboardActionListener,
        BookmarkListener,
        BookmarkKeyboardListener,
        LinkEditingListener,
        DualWebViewGroup.MaskToggleListener,
        DualWebViewGroup.TtsToggleListener,
        DualWebViewGroup.WindowCallback {

    companion object {
        private const val EXTRA_BROWSER_INITIAL_URL = "tapclaw_initial_url"
        private const val EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP =
                "tapclaw_return_to_chat_double_tap"
        private const val EXTRA_YOUTUBE_AUTOPLAY_QUERY = "tapclaw_youtube_autoplay_query"
        private const val EXTRA_YOUTUBE_AUTOPLAY_MODE = "tapclaw_youtube_autoplay_mode"
        private const val TAPCLAW_MAIN_ACTIVITY = "com.rayneo.visionclaw.MainActivity"
        private var activeInstanceRef: WeakReference<MainActivity>? = null

        @JvmStatic
        fun prepareForIncomingYouTubeAutoplay() {
            activeInstanceRef?.get()?.let { activity ->
                activity.runOnUiThread {
                    activity.prepareForIncomingYouTubeAutoplayInternal()
                }
            }
        }
    }

    fun updateCursorSensitivity(progress: Int) {
        cursorSensitivity = progress
        // Map 0-100 to 0.0f - 0.9f gain. 50 -> 0.45f
        cursorGain = 0.9f * (progress / 100f)
    }

    private val H2V_GAIN = 1.0f // how strongly horizontal motion affects vertical scroll
    private val X_INVERT = -1.0f // 1 = left -> up (what you want). Use -1 to flip.
    private val Y_INVERT = -1.0f // 1 = drag up -> up. Use -1 to flip if needed.
    lateinit var dualWebViewGroup: DualWebViewGroup
    private lateinit var webView: WebView
    private lateinit var mainContainer: FrameLayout
    private lateinit var gestureDetector: GestureDetector
    private lateinit var templeDoubleTapDetector: GestureDetector
    private var isSimulatingTouchEvent = false
    private var isCursorVisible = true
    private var isMouseTapMode = false
    private var lastMouseRawX = Float.NaN
    private var lastMouseRawY = Float.NaN
    private var lastMouseMappedX = Float.NaN
    private var lastMouseMappedY = Float.NaN
    private var mouseGestureDownTime = 0L
    private var mouseGestureActive = false
    private var mouseSwipeTracking = false
    private var mouseSwipeStartedOnCustomUi = false
    private var mouseSwipeDownDispatched = false
    private var mouseSwipeStartX = 0f
    private var mouseSwipeStartY = 0f
    private var mouseSwipeLastX = 0f
    private var mouseSwipeLastY = 0f
    private var mouseSwipeDownTime = 0L

    private fun refreshCursor() {
        dualWebViewGroup.updateCursorPosition(lastCursorX, lastCursorY, isCursorVisible)
    }

    private fun refreshCursor(visible: Boolean) {
        isCursorVisible = visible
        refreshCursor()
    }

    private fun centerCursor(visible: Boolean = isCursorVisible) {
        lastCursorX = 320f
        lastCursorY = 240f
        isCursorVisible = visible
        refreshCursor()
    }

    private fun isMousePointerEvent(event: MotionEvent): Boolean {
        if (event.isFromSource(InputDevice.SOURCE_MOUSE)) return true
        if (event.pointerCount <= 0) return false
        return event.getToolType(0) == MotionEvent.TOOL_TYPE_MOUSE
    }

    private fun toggleMouseTapMode() {
        val enableMouseTapMode = !isMouseTapMode
        isMouseTapMode = enableMouseTapMode

        if (enableMouseTapMode) {
            cancelActiveTouchScrollGesture()
            if (::dualWebViewGroup.isInitialized && dualWebViewGroup.isInScrollMode()) {
                dualWebViewGroup.setScrollMode(false)
            }
            isCursorVisible = false
            cursorJustAppeared = false
            if (::cursorLeftView.isInitialized) {
                cursorLeftView.visibility = View.GONE
            }
            if (::cursorRightView.isInitialized) {
                cursorRightView.visibility = View.GONE
            }
            refreshCursor(false)
            dualWebViewGroup.showToast("Mouse tap mode enabled")
        } else {
            isCursorVisible = true
            refreshCursor(true)
            dualWebViewGroup.showToast("Cursor mode enabled")
        }
    }

    private fun ensureMouseTapModeEnabled() {
        if (isMouseTapMode) return
        toggleMouseTapMode()
    }

    private fun ensureMouseTapModeDisabled() {
        if (!isMouseTapMode) return
        toggleMouseTapMode()
    }

    private fun autoEnterMouseModeForMudraInput(event: MotionEvent) {
        val deviceName = event.device?.name ?: InputDevice.getDevice(event.deviceId)?.name ?: return
        if (!deviceName.contains("Mudra", ignoreCase = true)) return
        ensureMouseTapModeEnabled()
    }
    private var isToggling = false
    private var lastCursorX = 320f
    private var lastCursorY = 240f
    private var isDispatchingTouchEvent = false
    private var isGestureHandled = false
    private var wasTouchOnBookmarks = false

    private val CAMERA_REQUEST_CODE = 1001
    private val CAMERA_PERMISSION_CODE = 100
    private val LOCATION_PERMISSION_REQUEST_CODE = 1002
    private var cameraPermissionGranted = false
    private var filePathCallback: ValueCallback<Array<Uri>>? = null
    private var pendingGeolocationCallback: GeolocationPermissions.Callback? = null
    private var pendingGeolocationOrigin: String? = null
    private val FILE_CHOOSER_REQUEST_CODE = 999 // Any unique code
    private var cameraImageUri: Uri? = null
    private var isCapturing = false // Add this flag to prevent multiple captures

    private var lastClickTime = 0L
    private val MIN_CLICK_INTERVAL = 500L // Minimum time between clicks

    // In MainActivity, add these properties to track cursor state and position
    private var lastKnownCursorX = 320f // Default center position
    private var lastKnownCursorY = 240f // Default center position
    private var lastKnownWebViewX = 0f
    private var lastKnownWebViewY = 0f
    private var cursorJustAppeared = false // Track if cursor just appeared

    private var lastGpsLat: Double? = null
    private var lastGpsLon: Double? = null

    private var currentVelocityX = 0f
    private var currentVelocityY = 0f
    private val movementDecay = 0.9f // Decay factor to slow down gradually
    private val updateInterval = 16L // Update interval in ms for smooth motion
    private val handler = Handler(Looper.getMainLooper())

    private val longPressTimeout = 200L // Milliseconds threshold for tap vs long press

    private lateinit var cursorLeftView: ImageView
    private lateinit var cursorRightView: ImageView

    private var keyboardView: CustomKeyboardView? = null
    private var isKeyboardVisible = false
    private var wasKeyboardVisibleAtDown = false
    private var wasTouchOnKeyboard = false

    private val prefsName = Constants.BROWSER_PREFS_NAME
    private val keyLastUrl = Constants.KEY_LAST_URL
    private var lastUrl: String? = null
    private var isUrlEditing = false
    private var returnToChatOnDoubleTap = false
    private var startupUrlOverride: String? = null
    private var youtubeAutoplayQuery: String? = null
    private var youtubeAutoplayMode: String? = null
    /** Ordered list of video IDs scraped from YouTube search results */
    private var youtubePlaylist: List<String> = emptyList()
    /** Index into youtubePlaylist of the currently-playing video */
    private var youtubePlaylistIndex: Int = 0
    /** Last URL we injected the bootstrap script for (prevents double-injection) */
    private var lastYouTubeInjectionUrl: String? = null
    /** Set true during nuclear WebView clearing so onPageStarted's about:blank
     *  recovery doesn't reload the old YouTube page. */
    @Volatile private var nuclearCleanupInProgress = false

    // User Agent management
    private var defaultUserAgent: String? = null
    private var customUserAgent: String? = null

    private fun shouldUseDesktopUaForYouTube(url: String?, autoplayMode: String?): Boolean {
        if (url.isNullOrBlank()) return false
        val lowerUrl = url.lowercase(Locale.US)
        if (!lowerUrl.contains("youtube.com") && !lowerUrl.contains("youtu.be")) return false
        val mode = autoplayMode?.trim()?.lowercase(Locale.US).orEmpty()
        val isFeedScrapeMode =
            (mode == "history" && lowerUrl.contains("/feed/history") && !lowerUrl.contains("/watch")) ||
            (mode == "subscriptions" && lowerUrl.contains("/feed/subscriptions") && !lowerUrl.contains("/watch"))
        return !isFeedScrapeMode
    }

    private var keyboardListener: DualWebViewGroup.KeyboardListener? = null

    private val PERMISSIONS_REQUEST_CODE = 123
    private var pendingPermissionRequest: PermissionRequest? = null
    private var qrScanCallbackWebView: WebView? = null
    private var isQrScanInProgress = false
    private var pendingNativeQrStart = false
    private var nativeQrScannerView: DecoratedBarcodeView? = null
    private val defaultQrZoomRatio = 3.0
    private var audioManager: AudioManager? = null
    private var nativeRadioPlayer: ExoPlayer? = null
    private var nativeRadioUrl: String? = null
    private var nativeRadioStationName: String? = null
    private var nativeRadioGenre: String? = null
    private var nativeRadioPreparing = false
    private var nativeRadioBuffering = false
    private var nativeRadioError: String? = null
    private val nativeRadioFocusListener = AudioManager.OnAudioFocusChangeListener { change ->
        when {
            change <= AudioManager.AUDIOFOCUS_LOSS_TRANSIENT -> {
                runOnUiThread { pauseNativeRadioStreamInternal(abandonFocus = false) }
            }
        }
    }
    private var speechRecognizer: SpeechRecognizer? = null
    private lateinit var cameraManager: CameraManager
    private var cameraDevice: CameraDevice? = null
    private var imageReader: ImageReader? = null
    private var cameraThread: HandlerThread? = null
    private var cameraHandler: Handler? = null

    private var fullScreenCustomView: View? = null
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null
    private var originalSystemUiVisibility: Int = 0
    private var originalOrientation: Int = 0
    private var wasKeyboardDismissedByEnter = false
    private var suppressWebClickUntil = 0L

    private var preMaskCursorState = false
    private var preMaskCursorX = 0f
    private var preMaskCursorY = 0f
    private var closeChatOnNextPageStart = false
    private var closeChatOnNextPageStartDeadlineMs = 0L

    private val uiHandler = Handler(Looper.getMainLooper())
    private var pendingCursorUpdate = false

    private val onBackPressedCallback =
            object : OnBackPressedCallback(true) {
                override fun handleOnBackPressed() {
                    when {
                        fullScreenCustomView != null -> {
                            hideFullScreenCustomView()
                        }
                        isKeyboardVisible || dualWebViewGroup.isUrlEditing() -> {
                            // Hide keyboard and exit URL editing
                            hideCustomKeyboard()
                        }
                        isCursorVisible -> {
                            // Hide cursor
                            toggleCursorVisibility(forceHide = true)
                        }
                        else -> {
                            // Remove the callback and let the system handle back
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                            // Re-enable for next time
                            isEnabled = true
                        }
                    }
                }
            }

    private lateinit var sensorManager: SensorManager
    private var rotationSensor: Sensor? = null
    private var isAnchored = false // Will be loaded from preferences in onCreate
        set(value) {
            field = value
            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.isAnchored = value
            }
        }

    // Smoothing and performance parameters for anchored mode
    private val TRANSLATION_SCALE =
            2000f // Adjusted for better visual stability (approx 36 deg FOV)

    // Dynamic smoothing factors (controlled by user preference)
    // Range: 0 (fastest/least smooth) to 100 (slowest/most smooth)
    private var smoothnessLevel = 40 // Default: fairly smooth
    private var anchorSmoothingFactor = 0.08f // Calculated from smoothnessLevel
    private var velocitySmoothing = 0.15f // Calculated from smoothnessLevel

    // Cursor sensitivity for non-anchored mode
    private var cursorSensitivity = 50 // Default 50 corresponds to 0.45f gain
    private var cursorGain = 0.45f

    // Velocity tracking for double exponential smoothing
    private var smoothedDeltaX = 0f
    private var smoothedDeltaY = 0f
    private var smoothedRollDeg = 0f

    // Frame timing for vsync
    private var lastFrameTime = 0L
    private val MIN_FRAME_INTERVAL_MS = 8L // ~120 FPS max (displays may be 90-120Hz)

    private var sensorEventListener = createSensorEventListener()
    private var shouldResetInitialQuaternion = false
    private var pendingDoubleTapAction = false

    private var ipcLauncher: Launcher? = null
    private var gpsUpdatesRegistered = false
    private val gpsHandler = Handler(Looper.getMainLooper())
    private var gpsStopRunnable: Runnable? = null
    private var lastGpsRequestAt = 0L
    private val GPS_IDLE_TIMEOUT_MS = 60000L

    private val doubleTapLock = Any()
    private var isProcessingDoubleTap = false
    private var lastDoubleTapStartTime = 0L
    private val DOUBLE_TAP_CONFIRMATION_DELAY = 200L

    // Triple tap detection for re-centering in anchored mode
    private var lastTapTime = 0L
    private var firstTapTime = 0L
    private var tapCount = 0
    private val TAP_INTERVAL = 400L // Max time between consecutive taps
    private val TRIPLE_TAP_DURATION = 800L // Max time for entire 3-tap sequence
    private var isTripleTapInProgress = false

    private var settingsMenu: View? = null

    private val gpsResponseListener =
            Launcher.OnResponseListener { response ->
                if (response?.data == null) return@OnResponseListener

                try {
                    val jo = JSONObject(response.data)
                    if (jo.has("mLatitude") && jo.has("mLongitude")) {
                        val mLatitude = jo.getDouble("mLatitude")
                        val mLongitude = jo.getDouble("mLongitude")

                        // Save for page reloads
                        lastGpsLat = mLatitude
                        lastGpsLon = mLongitude

                        // Inject location into WebView on UI thread
                        runOnUiThread { dualWebViewGroup.injectLocation(mLatitude, mLongitude) }
                    }
                } catch (e: Exception) {
                    DebugLog.e("GpsData", "Error processing GPS data: ${e.message}")
                }
            }

    private val cursorToggleLock = Any()
    private var potentialTapEvent: MotionEvent? = null

    private var pendingTouchHandler: Handler? = null
    private var pendingTouchRunnable: Runnable? = null

    // Touch scroll simulation state for mobile mode
    private var isTouchScrollActive = false
    private var touchScrollDownTime = 0L
    private var touchScrollCurrentY = 240f // Start at center
    private var accumulatedScrollY = 0f // Accumulate small scroll deltas

    private fun cancelActiveTouchScrollGesture() {
        pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
        pendingTouchRunnable = null
        accumulatedScrollY = 0f

        if (!isTouchScrollActive || !::webView.isInitialized) return

        val now = SystemClock.uptimeMillis()
        val cancelEvent =
                MotionEvent.obtain(
                        touchScrollDownTime,
                        now,
                        MotionEvent.ACTION_CANCEL,
                        lastCursorX,
                        touchScrollCurrentY,
                        0
                )
        cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
        isSimulatingTouchEvent = true
        try {
            webView.dispatchTouchEvent(cancelEvent)
        } finally {
            isSimulatingTouchEvent = false
        }
        cancelEvent.recycle()
        isTouchScrollActive = false
    }

    private val notificationReceiver =
            object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    if (intent?.action == NotificationService.ACTION_NOTIFICATION_POSTED) {
                        val packageName = intent.getStringExtra(NotificationService.EXTRA_PACKAGE)
                        val title = intent.getStringExtra(NotificationService.EXTRA_TITLE)
                        val text = intent.getStringExtra(NotificationService.EXTRA_TEXT)

                        DebugLog.d(
                                "MainActivity",
                                "Received notification from $packageName: $title - $text"
                        )

                        // Show a custom toast with the notification
                        dualWebViewGroup.showToast("Notification: $title - $text", 3000L)
                    }
                }
            }

    init {
        DebugLog.d("LinkEditing", "MainActivity initialized, isUrlEditing=$isUrlEditing")
    }

    @SuppressLint("SetJavaScriptEnabled", "ClickableViewAccessibility", "DEPRECATION")
    override fun onCreate(savedInstanceState: Bundle?) {
        runCatching { com.ffalcon.mercury.android.sdk.MercurySDK.init(application) }
        parseTapClawLaunchIntent(intent)
        super.onCreate(savedInstanceState)
        activeInstanceRef = WeakReference(this)
        // Set window background to black immediately
        window.setBackgroundDrawableResource(android.R.color.black)

        // Set initial brightness to 10% to reduce power consumption
        window.attributes = window.attributes.apply { screenBrightness = 0.1f }

        // Force hardware acceleration but with black background
        window.setFlags(
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
        )

        // Prevent any drawing until we're ready
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
        }

        // Set content view with black background
        setContentView(R.layout.tapbrowser_activity_main)

        findViewById<View>(android.R.id.content).setBackgroundColor(Color.BLACK)

        mainContainer = findViewById(R.id.mainContainer)

        onBackPressedDispatcher.addCallback(this, onBackPressedCallback)

        // Add this to disable default keyboard
        window.setFlags(
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM,
                WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM
        )

        // After basic window setup but before using any settings

        supportActionBar?.hide()

        keyboardListener =
                object : DualWebViewGroup.KeyboardListener {
                    override fun onShowKeyboard() {
                        showCustomKeyboard()
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard()
                    }
                }

        // Initialize DualWebViewGroup first
        dualWebViewGroup = findViewById(R.id.dualWebViewGroup)
        dualWebViewGroup.listener = this
        dualWebViewGroup.navigationListener = this
        dualWebViewGroup.maskToggleListener = this
        dualWebViewGroup.windowCallback = this
        dualWebViewGroup.restoreState()

        // Load saved anchored mode state
        isAnchored =
                getSharedPreferences(prefsName, MODE_PRIVATE)
                        .getBoolean("isAnchored", false) // Default to false on first run

        dualWebViewGroup.isAnchored = isAnchored

        if (isAnchored) {
            dualWebViewGroup.startAnchoring()
        } else {
            dualWebViewGroup.stopAnchoring()
        }

        // Load cursor sensitivity
        cursorSensitivity =
                getSharedPreferences(prefsName, MODE_PRIVATE).getInt("cursorSensitivity", 50)
        updateCursorSensitivity(cursorSensitivity)

        // Initialize GestureDetector
        gestureDetector =
                GestureDetector(
                        this,
                        object : SimpleOnGestureListener() {
                            private var isProcessingTap = false
                            private var totalScrollDistance = 0f
                            private var doubleTapRunnable: Runnable? = null

                            override fun onDown(e: MotionEvent): Boolean {
                                totalScrollDistance = 0f
                                DebugLog.d(
                                        "GestureInput",
                                        """
            Gesture Down:
            Source: ${e.source}
            Device: ${e.device?.name}
            ButtonState: ${e.buttonState}
            Pressure: ${e.pressure}
            Size: ${e.size}
            EventTime: ${e.eventTime}
            DownTime: ${e.downTime}
            Duration: ${e.eventTime - e.downTime}ms
        """.trimIndent()
                                )

                                // Store the down event for potential tap
                                potentialTapEvent = MotionEvent.obtain(e)

                                // Triple tap detection for screen re-centering in anchored mode
                                val currentTime = e.eventTime
                                if (currentTime - lastTapTime > TAP_INTERVAL) {
                                    DebugLog.d("TripleTapDebug", "Starting new tap sequence")
                                    tapCount = 1
                                    firstTapTime = currentTime
                                    isTripleTapInProgress = false
                                } else {
                                    tapCount++
                                    DebugLog.d(
                                            "TripleTapDebug",
                                            "Tap count increased to: $tapCount"
                                    )
                                }
                                lastTapTime = currentTime

                                // Check for triple tap
                                if (tapCount == 3 &&
                                                (currentTime - firstTapTime) <= TRIPLE_TAP_DURATION
                                ) {
                                    DebugLog.d(
                                            "TripleTapDebug",
                                            "Triple tap detected! Time from first tap: ${currentTime - firstTapTime}ms"
                                    )
                                    // Explicitly cancel the specific double tap runnable
                                    doubleTapRunnable?.let { handler.removeCallbacks(it) }
                                    doubleTapRunnable = null

                                    handler.removeCallbacksAndMessages(null)
                                    synchronized(doubleTapLock) { pendingDoubleTapAction = false }
                                    isTripleTapInProgress = true
                                    tapCount = 0

                                    if (isAnchored) {
                                        // Reset translations to center the view
                                        shouldResetInitialQuaternion = true
                                        dualWebViewGroup.updateLeftEyePosition(
                                                0f,
                                                0f,
                                                0f
                                        ) // Reset translations and rotation
                                        dualWebViewGroup.showToast("Screen Re-centered")
                                    } else {
                                        // Non-anchored triple tap: Toggle Scroll Mode
                                        if (dualWebViewGroup.isInScrollMode()) {
                                            toggleCursorVisibility(forceShow = true)
                                            dualWebViewGroup.showToast("Cursor mode activated")
                                        } else {
                                            toggleCursorVisibility(forceHide = true)
                                            dualWebViewGroup.showToast(
                                                    "Scroll mode activated, triple tap again to leave"
                                            )
                                        }
                                    }
                                    return true
                                }

                                return true
                            }

                            override fun onLongPress(e: MotionEvent) {
                                tapCount = 0
                                DebugLog.d(
                                        "RingInput",
                                        """
            Long Press:
            Source: ${e.source}
            Device: ${e.device?.name}
            ButtonState: ${e.buttonState}
            Pressure: ${e.pressure}
            Duration: ${e.eventTime - e.downTime}ms
        """.trimIndent()
                                )
                            }

                            override fun onScroll(
                                    e1: MotionEvent?,
                                    e2: MotionEvent,
                                    distanceX: Float,
                                    distanceY: Float
                            ): Boolean {
                                tapCount = 0 // Reset tap count on scroll to prevent accidental
                                // triple-tap detection
                                totalScrollDistance +=
                                        kotlin.math.sqrt(
                                                distanceX * distanceX + distanceY * distanceY
                                        )

                                if (isAnchored && isCursorVisible) {
                                    // In anchored cursor mode, cursor movement should not become
                                    // a synthetic touch swipe on the page.
                                    cancelActiveTouchScrollGesture()
                                    return true
                                }

                                // When ANCHORED or SCROLL MODE: both X and Y move the page
                                // vertically
                                if ((!isCursorVisible &&
                                                (isAnchored || dualWebViewGroup.isInScrollMode())) &&
                                                !isKeyboardVisible &&
                                                !dualWebViewGroup.isScreenMasked()
                                ) {
                                    // Map horizontal to vertical: LEFT -> UP, RIGHT -> DOWN
                                    // GestureDetector gives incremental deltas since last callback
                                    val horizontalAsVertical = (-distanceX) * X_INVERT * H2V_GAIN
                                    val verticalFromDrag = distanceY * Y_INVERT

                                    val scale = dualWebViewGroup.uiScale
                                    val verticalDelta =
                                            (horizontalAsVertical + verticalFromDrag) / scale

                                    if (kotlin.math.abs(verticalDelta) >= 1f) {
                                        if (dualWebViewGroup.isDesktopMode()) {
                                            // Desktop mode: Use mouse scroll wheel simulation
                                            val pointerCoords = MotionEvent.PointerCoords()
                                            pointerCoords.x = 320f
                                            pointerCoords.y = 240f
                                            pointerCoords.setAxisValue(
                                                    MotionEvent.AXIS_VSCROLL,
                                                    verticalDelta / 30f
                                            )

                                            val pointerProperties = MotionEvent.PointerProperties()
                                            pointerProperties.id = 0
                                            pointerProperties.toolType = MotionEvent.TOOL_TYPE_MOUSE

                                            val event =
                                                    MotionEvent.obtain(
                                                            SystemClock.uptimeMillis(),
                                                            SystemClock.uptimeMillis(),
                                                            MotionEvent.ACTION_SCROLL,
                                                            1,
                                                            arrayOf(pointerProperties),
                                                            arrayOf(pointerCoords),
                                                            0,
                                                            0,
                                                            1.0f,
                                                            1.0f,
                                                            0,
                                                            0,
                                                            InputDevice.SOURCE_MOUSE,
                                                            0
                                                    )

                                            webView.dispatchGenericMotionEvent(event)
                                            event.recycle()
                                        } else {
                                            // Mobile mode: Use touch swipe simulation
                                            val now = SystemClock.uptimeMillis()

                                            // Accumulate scroll delta for smoother swiping
                                            accumulatedScrollY += verticalDelta

                                            if (!isTouchScrollActive) {
                                                // Prevent accidental clicks by requiring a minimum
                                                // movement threshold
                                                // before starting a swipe gesture. 15px is
                                                // typically safe for touch slop.
                                                if (kotlin.math.abs(accumulatedScrollY) < 15f) {
                                                    return true
                                                }

                                                // Start a new touch gesture at the last known
                                                // cursor position
                                                isTouchScrollActive = true
                                                touchScrollDownTime = now
                                                // Use last known cursor X/Y to ensure we scroll the
                                                // correct element
                                                touchScrollCurrentY = lastCursorY
                                                // accumulatedScrollY is valid and > threshold,
                                                // proceed using it

                                                val downEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_DOWN,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(downEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                downEvent.recycle()
                                            }

                                            // Apply scroll delta (Positive delta = Move DOWN = Drag
                                            // DOWN)
                                            // User wants: Swipe Forward (Up/Neg) -> Go Down (Scroll
                                            // Down)
                                            // Scroll Down -> Drag UP (Decrease Y)
                                            // So if Delta < 0, we want CurrentY to DECREASE.
                                            // So we ADD delta.
                                            var candidateY =
                                                    touchScrollCurrentY + accumulatedScrollY

                                            // Check bounds and loop gesture if needed to allow
                                            // continuous scrolling
                                            if (candidateY < 10f || candidateY > 470f) {
                                                // End current gesture with CANCEL to prevent acting
                                                // as a click at the edge
                                                val cancelEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_CANCEL,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                cancelEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(cancelEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                cancelEvent.recycle()

                                                // Reset to center to regain runway
                                                touchScrollCurrentY = 240f
                                                touchScrollDownTime = now

                                                // Start new gesture
                                                val downEvent =
                                                        MotionEvent.obtain(
                                                                touchScrollDownTime,
                                                                now,
                                                                MotionEvent.ACTION_DOWN,
                                                                lastCursorX,
                                                                touchScrollCurrentY,
                                                                0
                                                        )
                                                downEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                                isSimulatingTouchEvent = true
                                                try {
                                                    webView.dispatchTouchEvent(downEvent)
                                                } finally {
                                                    isSimulatingTouchEvent = false
                                                }
                                                downEvent.recycle()

                                                // Re-calculate candidate
                                                candidateY =
                                                        touchScrollCurrentY + accumulatedScrollY
                                            }

                                            touchScrollCurrentY = candidateY.coerceIn(0f, 480f)
                                            accumulatedScrollY = 0f

                                            val moveEvent =
                                                    MotionEvent.obtain(
                                                            touchScrollDownTime,
                                                            now,
                                                            MotionEvent.ACTION_MOVE,
                                                            lastCursorX, // Keep X locked to gaze or
                                                            // original? Using gaze for
                                                            // now as per previous
                                                            touchScrollCurrentY,
                                                            0
                                                    )
                                            moveEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                                            isSimulatingTouchEvent = true
                                            try {
                                                webView.dispatchTouchEvent(moveEvent)
                                            } finally {
                                                isSimulatingTouchEvent = false
                                            }
                                            moveEvent.recycle()

                                            // Schedule ACTION_CANCEL to complete the gesture if
                                            // scrolling stops. Using CANCEL prevents "clicks" on
                                            // lift.
                                            if (pendingTouchHandler == null) {
                                                pendingTouchHandler =
                                                        Handler(Looper.getMainLooper())
                                            }
                                            pendingTouchRunnable?.let {
                                                pendingTouchHandler?.removeCallbacks(it)
                                            }

                                            pendingTouchRunnable = Runnable {
                                                if (isTouchScrollActive) {
                                                    val upTime = SystemClock.uptimeMillis()
                                                    val cancelEvent =
                                                            MotionEvent.obtain(
                                                                    touchScrollDownTime,
                                                                    upTime,
                                                                    MotionEvent.ACTION_CANCEL,
                                                                    lastCursorX,
                                                                    touchScrollCurrentY,
                                                                    0
                                                            )
                                                    cancelEvent.source =
                                                            InputDevice.SOURCE_TOUCHSCREEN
                                                    isSimulatingTouchEvent = true
                                                    try {
                                                        webView.dispatchTouchEvent(cancelEvent)
                                                    } finally {
                                                        isSimulatingTouchEvent = false
                                                    }
                                                    cancelEvent.recycle()
                                                    isTouchScrollActive = false
                                                    DebugLog.d(
                                                            "ScrollMode",
                                                            "Touch scroll gesture cancelled via timeout"
                                                    )
                                                }
                                            }
                                            pendingTouchHandler?.postDelayed(
                                                    pendingTouchRunnable!!,
                                                    150
                                            )
                                        }
                                    }
                                    return true
                                }

                                // Not anchored: keep your existing cursor-follow logic
                                // val cursorGain = 0.45f // using class member cursorGain instead
                                val dx = -distanceX * cursorGain
                                val dy = -distanceY * cursorGain
                                if (!isAnchored && !dualWebViewGroup.isInScrollMode()) {
                                    // Clamp to single eye dimensions (640x480), not full dual
                                    // display width
                                    val maxW = 640f
                                    val maxH = 480f
                                    lastCursorX = (lastCursorX + dx).coerceIn(0f, maxW)
                                    lastCursorY = (lastCursorY + dy).coerceIn(0f, maxH)

                                    val loc = IntArray(2)
                                    webView.getLocationOnScreen(loc)
                                    lastKnownWebViewX = lastCursorX - loc[0]
                                    lastKnownWebViewY = lastCursorY - loc[1]
                                    refreshCursor(true)
                                    DebugLog.d("GestureInput", "Trapped!")
                                    return true
                                }

                                return false
                            }
                            override fun onSingleTapConfirmed(e: MotionEvent): Boolean {
                                DebugLog.d("RingInput", "Single Tap from device: ${e.device?.name}")

                                if (totalScrollDistance > 10f) {
                                    DebugLog.d(
                                            "GestureInput",
                                            "Tap ignored due to swipe distance: $totalScrollDistance"
                                    )
                                    return false
                                }

                                handleUserInteraction()

                                // If the touch interaction started on the bookmarks view, consume
                                // the tap here
                                // to prevent it from propagating to the WebView (even if bookmarks
                                // closed in the meantime)
                                if (wasTouchOnBookmarks) {
                                    return true
                                }

                                // Don't handle single taps that are part of a triple tap sequence
                                if (isTripleTapInProgress) {
                                    isTripleTapInProgress = false
                                    return true
                                }

                                // When masked, don't consume the tap - let it reach the unmask
                                // button and media controls
                                // The mask overlay itself will block touches to web content
                                if (dualWebViewGroup.isScreenMasked()) {
                                    dispatchTouchEventAtCursor()
                                    return true
                                }

                                if (isProcessingTap) return true

                                isProcessingTap = true
                                Handler(Looper.getMainLooper())
                                        .postDelayed({ isProcessingTap = false }, 300)

                                // Bookmark taps are now handled exclusively by
                                // DualWebViewGroup.onTouchEvent
                                // to prevent double-dispatch issues with actions like "Set Home"

                                when {
                                    isToggling && cursorJustAppeared -> {
                                        DebugLog.d(
                                                "TouchDebug",
                                                "Ignoring tap during cursor appearance"
                                        )
                                        return true
                                    }
                                    isCursorVisible -> {
                                        // Check if this is a long press
                                        if (e.eventTime - e.downTime > longPressTimeout) {
                                            // ignoring input interaction")
                                            return true
                                        }

                                        // Handle regular clicks when cursor is visible
                                        if (!cursorJustAppeared && !isSimulatingTouchEvent) {
                                            val UILocation = IntArray(2)
                                            dualWebViewGroup.leftEyeUIContainer.getLocationOnScreen(
                                                    UILocation
                                            )

                                            // Dispatch the touch event at the current cursor
                                            // position
                                            dispatchTouchEventAtCursor()
                                        }
                                    }
                                    else -> {
                                        // In scroll mode (cursor hidden), let taps pass through to
                                        // the WebView
                                        // User can exit scroll mode via the dedicated unhide button
                                        if (dualWebViewGroup.isInScrollMode()) {
                                            return false // Don't consume - let tap go to WebView
                                        }
                                        isSimulatingTouchEvent = true
                                        toggleCursorVisibility()
                                    }
                                }
                                return true
                            }

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                // Prevent double tap back navigation if keyboard is visible
                                if (isKeyboardVisible) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Double tap ignored because keyboard is visible"
                                    )
                                    return true // Consume the event so it doesn't propagate
                                }

                                // If this is part of a triple tap sequence (which just toggled
                                // mode), ignore double tap
                                if (isTripleTapInProgress) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Double tap ignored - part of triple tap sequence"
                                    )
                                    return true
                                }

                                val isInScrollMode = dualWebViewGroup.isInScrollMode()
                                DebugLog.d(
                                        "DoubleTapDebug",
                                        """onDoubleTap called. isProcessingDoubleTap: $isProcessingDoubleTap, isInScrollMode: $isInScrollMode"""
                                )

                                if (isInScrollMode) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Double tap ignored because in scroll mode"
                                    )
                                    return true
                                }

                                synchronized(doubleTapLock) {
                                    // Safety check: Reset if flag has been stuck for too long
                                    // (>500ms)
                                    val currentTime = SystemClock.uptimeMillis()
                                    if (isProcessingDoubleTap &&
                                                    lastDoubleTapStartTime > 0 &&
                                                    currentTime - lastDoubleTapStartTime > 500
                                    ) {
                                        DebugLog.d(
                                                "DoubleTapDebug",
                                                "Resetting stuck isProcessingDoubleTap flag"
                                        )
                                        isProcessingDoubleTap = false
                                    }

                                    if (isProcessingDoubleTap) {
                                        DebugLog.d(
                                                "DoubleTapDebug",
                                                "Skipping - already processing double tap"
                                        )
                                        return true
                                    }
                                    isProcessingDoubleTap = true
                                    lastDoubleTapStartTime = currentTime
                                    pendingDoubleTapAction = true

                                    // Calculate dynamic delay to ensure we wait until AFTER the
                                    // triple tap window closes
                                    val timeSinceFirstTap = SystemClock.uptimeMillis() - firstTapTime
                                    val remainingTripleTapWindow =
                                            TRIPLE_TAP_DURATION - timeSinceFirstTap

                                    // Make sure we wait at least a small buffer after the window
                                    // closes
                                    // But cap the delay to avoid excessive waiting if the window is
                                    // huge (though 800ms is reasonable)
                                    val delay =
                                            if (remainingTripleTapWindow > 0)
                                                    remainingTripleTapWindow + 30
                                            else DOUBLE_TAP_CONFIRMATION_DELAY

                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "Scheduling double tap action. Delay: ${delay}ms (Window remaining: $remainingTripleTapWindow)"
                                    )

                                    doubleTapRunnable = Runnable {
                                        synchronized(doubleTapLock) {
                                            try {
                                                // Final check for triple tap
                                                if (isTripleTapInProgress) {
                                                    DebugLog.d(
                                                            "DoubleTapDebug",
                                                            "Aborting double tap action - triple tap in progress"
                                                    )
                                                    return@Runnable
                                                }

                                                if (pendingDoubleTapAction) {
                                                    DebugLog.d(
                                                            "DoubleTapDebug",
                                                            "Executing pending double tap action"
                                                    )
                                                    performDoubleTapBackNavigation()
                                                }
                                            } finally {
                                                // Note: Do NOT reset tapCount/lastTapTime here
                                                pendingDoubleTapAction = false
                                                isProcessingDoubleTap = false
                                                lastDoubleTapStartTime = 0L
                                                doubleTapRunnable = null
                                            }
                                        }
                                    }

                                    handler.postDelayed(doubleTapRunnable!!, delay)
                                }

                                return true
                            }

                            private fun performDoubleTapBackNavigation() {
                                if (returnToChatOnDoubleTap) {
                                    try {
                                        startActivity(
                                            Intent().setClassName(this@MainActivity, TAPCLAW_MAIN_ACTIVITY)
                                                .addFlags(
                                                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or
                                                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                                                )
                                        )
                                    } catch (e: Exception) {
                                        DebugLog.e("DoubleTapDebug", "Failed to return to TapClaw", e)
                                        finish()
                                    }
                                    return
                                }

                                val isScreenMasked = dualWebViewGroup.isScreenMasked()
                                val hasHistory = webView.canGoBack()

                                DebugLog.d(
                                        "DoubleTapDebug",
                                        """Double tap confirmed. isScreenMasked=$isScreenMasked, isKeyboardVisible=$isKeyboardVisible, canGoBack=$hasHistory"""
                                )

                                if (!hasHistory) {
                                    DebugLog.d(
                                            "DoubleTapDebug",
                                            "No history entry available for goBack()"
                                    )
                                    return
                                }

                                onNavigationBackPressed()
                            }
                        }
                )

        templeDoubleTapDetector =
                GestureDetector(
                        this,
                        object : SimpleOnGestureListener() {
                            override fun onDown(e: MotionEvent): Boolean = true

                            override fun onDoubleTap(e: MotionEvent): Boolean {
                                toggleMouseTapMode()
                                return true
                            }
                        }
                )

        // Create and set up bookmarks view
        val bookmarksView =
                BookmarksView(this).apply {
                    setKeyboardListener(this@MainActivity) // Set keyboard listener directly
                    setBookmarkListener(
                            this@MainActivity
                    ) // Add this line to set the bookmark listener
                }

        // Set up bookmarks view in DualWebViewGroup
        dualWebViewGroup.setBookmarksView(bookmarksView)

        // Ensure settings and bookmarks are closed on app startup
        dualWebViewGroup.resetUiState()

        bookmarksView.setKeyboardListener(this)
        DebugLog.d("BookmarksDebug", "BookmarksView set in onCreate")

        // Set up the keyboard listener
        dualWebViewGroup.keyboardListener =
                object : DualWebViewGroup.KeyboardListener {
                    override fun onShowKeyboard() {
                        showCustomKeyboard()
                    }

                    override fun onHideKeyboard() {
                        hideCustomKeyboard()
                    }
                }

        // Set up the mic listener for the chat view
        dualWebViewGroup.micListener =
                object : ChatView.MicListener {
                    override fun onMicrophonePressed() {
                        this@MainActivity.onMicrophonePressed()
                    }
                }

        // Cursor views setup
        // Set up the cursor views directly in the main container
        cursorLeftView =
                ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(24, 24) // Adjust size as needed
                    setImageResource(R.drawable.cursor_arrow_image)
                    scaleType =
                            ImageView.ScaleType
                                    .FIT_START // Anchor to top-left for accurate click alignment
                    x = 320f
                    y = 240f
                    visibility = View.GONE
                }
        cursorRightView =
                ImageView(this).apply {
                    layoutParams = ViewGroup.LayoutParams(24, 24)
                    setImageResource(R.drawable.cursor_arrow_image)
                    scaleType =
                            ImageView.ScaleType
                                    .FIT_START // Anchor to top-left for accurate click alignment
                    x = 960f
                    y = 240f
                    visibility = View.GONE
                }

        // Add cursor views to the main container
        mainContainer.apply {
            addView(cursorLeftView)
            addView(cursorRightView)
        }

        webView = dualWebViewGroup.getWebView()

        webView.setOnTouchListener { _, event ->
            val isMouseEvent = isMousePointerEvent(event)

            // Clear any pending touch events when a new touch starts
            // or when touch ends/cancels
            if (event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_UP ||
                            event.action == MotionEvent.ACTION_CANCEL
            ) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }

            if (isAnchored && isKeyboardVisible) {
                return@setOnTouchListener false
            }

            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isMouseTapMode && isMouseEvent) {
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                return@setOnTouchListener true
            }

            // Use the cached result from dispatchTouchEvent instead of calling gestureDetector
            // again
            // This prevents double-processing which can corrupt gesture state
            val handled = isGestureHandled

            // Add check for settings menu visibility
            if (dualWebViewGroup.isSettingsVisible()) {
                return@setOnTouchListener isCursorVisible // Let the event propagate to the settings
                // menu
            }

            // In scroll mode, let taps pass through to WebView
            // The gesture detector still sees all events via dispatchTouchEvent,
            // so double-tap detection still works independently
            // In scroll mode, let taps pass through to WebView ONLY if anchored
            // If not anchored, we want the cursor to handle the event (move/wake)
            if (dualWebViewGroup.isInScrollMode() && isAnchored) {
                // Always return false to let taps reach the WebView (for clicking links, etc.)
                // The gesture detector has already processed the event in dispatchTouchEvent
                return@setOnTouchListener false
            }

            if (isCursorVisible && !isMouseEvent) {
                return@setOnTouchListener true
            }

            if (event.action == MotionEvent.ACTION_UP && !handled) {}

            handled
        }

        // Enable storage + JS features required by modern web apps (auth/session state, etc.).
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        @Suppress("DEPRECATION") run { webView.settings.databaseEnabled = true }

        webView.webViewClient =
                object : WebViewClient() {
                    override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                        super.onPageStarted(view, url, favicon)
                        DebugLog.d("YouTubeAuto", "onPageStarted[1]: url=$url")
                        // If cursor was visible, store its position
                        if (isCursorVisible) {
                            lastKnownCursorX = lastCursorX
                            lastKnownCursorY = lastCursorY
                        }
                        // Force desktop UA for YouTube when autoplay is active so we
                        // get predictable desktop DOM with standard <a href="/watch?v=..."> links.
                        if (!youtubeAutoplayQuery.isNullOrBlank() &&
                            !youtubeAutoplayMode.isNullOrBlank() &&
                            url != null &&
                            (url.contains("youtube.com") || url.contains("youtu.be"))
                        ) {
                            val desktopUA = if (::dualWebViewGroup.isInitialized) {
                                dualWebViewGroup.getDesktopUserAgent()
                            } else null
                            if (!desktopUA.isNullOrBlank() && view?.settings?.userAgentString != desktopUA) {
                                view?.settings?.userAgentString = desktopUA
                            }
                        }
                    }

                    override fun onPageFinished(view: WebView?, url: String?) {
                        super.onPageFinished(view, url)

                        if (::dualWebViewGroup.isInitialized) {
                            dualWebViewGroup.saveWindowMetadataState()
                        }

                        // Force enable input on all potential input fields
                        webView.evaluateJavascript(
                                """
                (function() {
                    function enableInput(element) {
                        element.style.webkitUserSelect = 'text';
                        element.style.userSelect = 'text';
                        element.setAttribute('inputmode', 'text');
                    }
                    
                    document.querySelectorAll('input,textarea,[contenteditable="true"]')
                        .forEach(enableInput);
                        
                    // Create observer for dynamically added elements
                    new MutationObserver((mutations) => {
                        mutations.forEach((mutation) => {
                            mutation.addedNodes.forEach((node) => {
                                if (node.nodeType === 1) {  // ELEMENT_NODE
                                    if (node.matches('input,textarea,[contenteditable="true"]')) {
                                        enableInput(node);
                                    }
                                    node.querySelectorAll('input,textarea,[contenteditable="true"]')
                                        .forEach(enableInput);
                                }
                            });
                        });
                    }).observe(document.body, {
                        childList: true,
                        subtree: true
                    });
                })();
            """,
                                null
                        )

                        wasKeyboardDismissedByEnter = false

                        // Log focus state

                        // Update scrollbar visibility based on new content
                        dualWebViewGroup.updateScrollBarsVisibility()

                        // Lock viewport scale to avoid zoom-loop behavior on X3 trackpad.
                        val viewportContent =
                                if (dualWebViewGroup.isDesktopMode()) {
                                    "width=1280, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                } else {
                                    "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                }
                        webView.evaluateJavascript(
                                """
                        (function() {
                            var viewport = document.querySelector('meta[name="viewport"]');
                            if (!viewport) {
                                viewport = document.createElement('meta');
                                viewport.name = 'viewport';
                                document.head.appendChild(viewport);
                            }
                            viewport.content = '$viewportContent';
                        })();
                        """,
                                null
                        )

                        // Auto-unmute YouTube videos that start muted due to autoplay policy
                        DebugLog.d("YouTubeAuto", "onPageFinished: url=$url")
                        if (url != null && (url.contains("youtube.com") || url.contains("youtu.be"))) {
                            webView.evaluateJavascript(
                                    """
                            (function() {
                                var attempts = 0;
                                function tryUnmute() {
                                    var videos = document.querySelectorAll('video');
                                    var unmuted = false;
                                    videos.forEach(function(v) {
                                        if (v.muted) { v.muted = false; unmuted = true; }
                                    });
                                    if (!unmuted || videos.length === 0) {
                                        var muteBtn = document.querySelector('.ytp-mute-button');
                                        if (muteBtn) {
                                            var vol = (muteBtn.getAttribute('data-title-no-tooltip') ||
                                                       muteBtn.getAttribute('title') || '').toLowerCase();
                                            if (vol.indexOf('unmute') >= 0 || vol.indexOf('muted') >= 0) {
                                                muteBtn.click(); unmuted = true;
                                            }
                                        }
                                    }
                                    attempts++;
                                    if (!unmuted && attempts < 15) setTimeout(tryUnmute, 800);
                                }
                                setTimeout(tryUnmute, 1500);
                                var obs = new MutationObserver(function() {
                                    document.querySelectorAll('video').forEach(function(v) {
                                        if (v.muted && !v.dataset.taplinkUnmuted) {
                                            v.muted = false; v.dataset.taplinkUnmuted = 'true';
                                        }
                                    });
                                });
                                if (document.body) obs.observe(document.body, { childList: true, subtree: true });
                            })();
                            """,
                                    null
                            )
                            injectYouTubePlaylistAutomation(webView, url)
                        }
                    }

                    override fun doUpdateVisitedHistory(
                            view: WebView?,
                            url: String?,
                            isReload: Boolean
                    ) {
                        super.doUpdateVisitedHistory(view, url, isReload)
                        // ${view?.canGoBack()}")
                    }
                }

        cursorLeftView.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        cursorRightView.apply {
            isClickable = false
            isFocusable = false
            isFocusableInTouchMode = false
        }

        webView.setBackgroundColor(Color.BLACK)
        dualWebViewGroup.updateBrowsingMode(dualWebViewGroup.isDesktopMode())

        // Set up the listener
        dualWebViewGroup.linkEditingListener = this

        // Add after other listener assignments
        dualWebViewGroup.ttsToggleListener = this

        // Initialize sensor manager
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as SensorManager
        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)

        // Load preferences (use TapLinkPrefs for settings that are saved there)
        val prefs = getSharedPreferences(Constants.PREFS_NAME, Context.MODE_PRIVATE)
        smoothnessLevel = prefs.getInt(Constants.KEY_ANCHOR_SMOOTHNESS, 40)
        updateSmoothnessFactors(smoothnessLevel)

        // Note: isAnchored is already loaded earlier from BrowserPrefs
        DebugLog.d("AnchorDebug", "Anchored state (loaded earlier): $isAnchored")

        // After initializing webView and dualWebViewGroup but before loadInitialPage()
        // Set initial cursor position
        // Make cursor visible
        cursorLeftView.visibility = View.VISIBLE
        cursorRightView.visibility = View.VISIBLE
        centerCursor(true)

        // Start in saved anchored mode state
        if (isAnchored) {
            rotationSensor?.let { sensor ->
                sensorEventListener = createSensorEventListener()
                sensorManager.registerListener(
                        sensorEventListener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                )
            }
            dualWebViewGroup.startAnchoring()
        } else {
            // Not anchored - make sure anchored mode is off
            dualWebViewGroup.stopAnchoring()
        }

        // Then try to restore the previous state
        setupWebView() // This will attempt to load the saved URL

        val hasExplicitStartupUrl = !startupUrlOverride.isNullOrBlank()
        // Only fall back to the dashboard when we are not servicing an explicit launch URL.
        if ((webView.url == null || webView.url == "about:blank") && !hasExplicitStartupUrl) {
            webView.clearCache(true)
            webView.clearHistory()
            webView.loadUrl(Constants.DEFAULT_URL)
        }

        startupUrlOverride
                ?.takeIf { it.isNotBlank() }
                ?.let { overrideUrl ->
                    val formatted = formatUrl(overrideUrl)
                    val isYouTube = formatted.contains("youtube.com") || formatted.contains("youtu.be")
                    // Force desktop UA for YouTube autoplay
                    if (!youtubeAutoplayQuery.isNullOrBlank() &&
                        !youtubeAutoplayMode.isNullOrBlank() && isYouTube
                    ) {
                        val targetUa = if (shouldUseDesktopUaForYouTube(formatted, youtubeAutoplayMode)) {
                            if (::dualWebViewGroup.isInitialized) dualWebViewGroup.getDesktopUserAgent() else null
                        } else {
                            customUserAgent
                        }
                        if (!targetUa.isNullOrBlank()) {
                            webView.settings.userAgentString = targetUa
                        }
                    }
                    // If launching directly into YouTube, wipe the restored
                    // browsing history so the WebView doesn't try to load
                    // old pages (CNN, Fox News, etc.) from the back stack.
                    if (isYouTube) {
                        webView = dualWebViewGroup.resetToSingleWindow(loadDefaultUrl = false)
                        webView.stopLoading()
                        webView.clearHistory()
                        webView.clearCache(true)
                        try {
                            getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                                .remove(Constants.KEY_WEBVIEW_STATE)
                                .apply()
                        } catch (_: Exception) {}
                        DebugLog.d("YouTubeAuto", "loadInitialPage: cleared history/cache for YouTube cold start")
                    }
                    if (isAddressOrMapsUrl(formatted)) {
                        // Aggressively kill ALL audio across ALL WebViews before loading map
                        killAllWebViewAudio()
                        webView.settings.mediaPlaybackRequiresUserGesture = true // block audio on map page
                        nuclearCleanupInProgress = true
                        webView.loadUrl("about:blank")
                        val arNavUrl = buildArNavUrl(formatted)
                        DebugLog.d("ARNav", "coldStart: intercepted → $arNavUrl")
                        webView.postDelayed({
                            nuclearCleanupInProgress = false
                            webView.loadUrl(arNavUrl)
                        }, 200)
                        persistActiveUrl("tapclaw_intent_arnav", arNavUrl, webView)
                    } else {
                        webView.settings.mediaPlaybackRequiresUserGesture = false // restore for YouTube etc.
                        webView.loadUrl(formatted)
                        persistActiveUrl("tapclaw_intent", formatted, webView)
                    }
                    startupUrlOverride = null
                }

        // Initialize camera after WebView setup
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA) ==
                        PackageManager.PERMISSION_GRANTED
        ) {
            cameraPermissionGranted = true
        } else {
            // Request camera permission if we don't have it
            requestPermissions(arrayOf(android.Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
        }
        initializeSpeechRecognition() // Initialize speech recognition after WebView setup

        // Call permission check during setup
        checkAndRequestPermissions()

        rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
        if (rotationSensor == null) {
            DebugLog.e("Sensor", "No rotation vector sensor found")
        } else {
            DebugLog.d("Sensor", "Rotation vector sensor found")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val incomingOverrideUrl =
                intent.getStringExtra(EXTRA_BROWSER_INITIAL_URL)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
        val incomingFormattedUrl = incomingOverrideUrl?.let { formatUrl(it) }
        val incomingIsYouTube =
                incomingFormattedUrl?.let {
                    it.contains("youtube.com", ignoreCase = true) ||
                            it.contains("youtu.be", ignoreCase = true)
                } == true
        if (incomingIsYouTube && ::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.pauseYouTubeMediaAcrossAllWindows()
        }
        // IMPORTANT: Snapshot the OLD autoplay state BEFORE parsing the new intent,
        // so we can tell if Gemini is sending a fresh YouTube request or a non-YouTube URL.
        val hadOldAutoplay = !youtubeAutoplayQuery.isNullOrBlank() && !youtubeAutoplayMode.isNullOrBlank()
        parseTapClawLaunchIntent(intent)
        val overrideUrl = startupUrlOverride
        if (::webView.isInitialized && !overrideUrl.isNullOrBlank()) {
            val formatted = formatUrl(overrideUrl)
            val isYouTube = formatted.contains("youtube.com") || formatted.contains("youtu.be")
            DebugLog.d("YouTubeAuto", "onNewIntent: url=$formatted isYouTube=$isYouTube " +
                "query='${youtubeAutoplayQuery}' mode='${youtubeAutoplayMode}' " +
                "hadOldAutoplay=$hadOldAutoplay playlistSize=${youtubePlaylist.size}")

            // Force desktop UA for YouTube autoplay so we get standard desktop DOM
            if (!youtubeAutoplayQuery.isNullOrBlank() &&
                !youtubeAutoplayMode.isNullOrBlank() && isYouTube
            ) {
                val targetUa = if (shouldUseDesktopUaForYouTube(formatted, youtubeAutoplayMode)) {
                    if (::dualWebViewGroup.isInitialized) dualWebViewGroup.getDesktopUserAgent() else null
                } else {
                    customUserAgent
                }
                if (!targetUa.isNullOrBlank()) {
                    webView.settings.userAgentString = targetUa
                }
            }

            // ── Clean up before loading a new YouTube URL ──
            // AVOID navigating to about:blank — it triggers onPageStarted/
            // onPageFinished callbacks that save state, restore history URLs
            // (CNN, Fox News, etc.), and fight with DualWebViewGroup's
            // session persistence.  Instead: stop current load, kill media
            // via JS, clear Kotlin-side state, then directly load the new URL.
            // When loadUrl() is called the WebView engine internally tears
            // down the old page (and its media pipeline) before building
            // the new one, which is sufficient.
            if (isYouTube) {
                webView = dualWebViewGroup.resetToSingleWindow(loadDefaultUrl = false)
                // 1. Stop everything
                webView.stopLoading()

                // 2. Wipe the WebView's back/forward history + disk cache so
                //    no stale pages (CNN, Fox News, etc.) can be restored or
                //    replayed by the navigation stack or session persistence.
                webView.clearHistory()
                webView.clearCache(true)

                // 3. Kill media + all our injected timers in the old page
                webView.evaluateJavascript(
                    "(function(){" +
                    "try{document.querySelectorAll('video,audio').forEach(function(el){" +
                    "try{el.pause();el.removeAttribute('src');el.load();}catch(e){}});}catch(e){}" +
                    "var id=window.setTimeout(function(){},0);while(id--)clearTimeout(id);" +
                    "var iid=window.setInterval(function(){},0);while(iid--)clearInterval(iid);" +
                    "})()", null
                )

                // 4. Clear ALL stale Kotlin-side state
                lastYouTubeInjectionUrl = null
                youtubePlaylist = emptyList()
                youtubePlaylistIndex = 0

                // 5. Also clear the persisted WebView state from SharedPreferences
                //    so that if the app is killed+restarted, tryRestoreSession()
                //    doesn't reload the old browsing history.
                try {
                    getSharedPreferences(prefsName, MODE_PRIVATE).edit()
                        .remove(Constants.KEY_WEBVIEW_STATE)
                        .apply()
                } catch (_: Exception) {}

                DebugLog.d("YouTubeAuto", "onNewIntent: cleared history + cache + playlist + persisted state")

                // 6. Load the new YouTube URL on a clean slate
                webView.settings.mediaPlaybackRequiresUserGesture = false // restore for YouTube
                webView.loadUrl(formatted)
                persistActiveUrl("tapclaw_new_intent", formatted, webView)
            } else if (isAddressOrMapsUrl(formatted)) {
                // ── AR Navigation HUD ──
                if (hadOldAutoplay) {
                    youtubeAutoplayQuery = null
                    youtubeAutoplayMode = null
                    youtubePlaylist = emptyList()
                    youtubePlaylistIndex = 0
                    lastYouTubeInjectionUrl = null
                }
                // Aggressively kill ALL audio across ALL WebViews before loading map
                killAllWebViewAudio()
                webView.settings.mediaPlaybackRequiresUserGesture = true // block audio on map page
                nuclearCleanupInProgress = true
                webView.loadUrl("about:blank")
                val arNavUrl = buildArNavUrl(formatted)
                DebugLog.d("ARNav", "onNewIntent: intercepted → $arNavUrl")
                webView.postDelayed({
                    nuclearCleanupInProgress = false
                    webView.loadUrl(arNavUrl)
                }, 200)
                persistActiveUrl("tapclaw_new_intent_arnav", arNavUrl, webView)
            } else {
                // Non-YouTube URL — clear any leftover YouTube state
                if (hadOldAutoplay) {
                    youtubeAutoplayQuery = null
                    youtubeAutoplayMode = null
                    youtubePlaylist = emptyList()
                    youtubePlaylistIndex = 0
                    lastYouTubeInjectionUrl = null
                    DebugLog.d("YouTubeAuto", "onNewIntent: non-YT URL, cleared autoplay state")
                }
                webView.settings.mediaPlaybackRequiresUserGesture = false // restore for non-map
                webView.loadUrl(formatted)
                persistActiveUrl("tapclaw_new_intent", formatted, webView)
            }
            startupUrlOverride = null
        }
        syncTapRadioPlaybackUi()
    }

    private fun parseTapClawLaunchIntent(intent: Intent?) {
        if (intent == null) return
        returnToChatOnDoubleTap =
                intent.getBooleanExtra(
                        EXTRA_RETURN_TO_CHAT_ON_DOUBLE_TAP,
                        returnToChatOnDoubleTap
                )
        startupUrlOverride =
                intent.getStringExtra(EXTRA_BROWSER_INITIAL_URL)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: startupUrlOverride
        youtubeAutoplayQuery =
                intent.getStringExtra(EXTRA_YOUTUBE_AUTOPLAY_QUERY)
                        ?.trim()
                        ?.takeIf { it.isNotBlank() }
                        ?: youtubeAutoplayQuery
        youtubeAutoplayMode =
                intent.getStringExtra(EXTRA_YOUTUBE_AUTOPLAY_MODE)
                        ?.trim()
                        ?.lowercase(Locale.US)
                        ?.takeIf { it == "video" || it == "music" || it == "subscriptions" || it == "history" }
                        ?: youtubeAutoplayMode
    }

    private fun injectYouTubePlaylistAutomation(view: WebView, url: String) {
        DebugLog.d("YouTubeAuto", "injectYouTubePlaylistAutomation called — url=$url")
        var query = youtubeAutoplayQuery?.trim().orEmpty()
        var mode = youtubeAutoplayMode?.trim().orEmpty()
        DebugLog.d("YouTubeAuto", "  extras: query='$query' mode='$mode'")

        // Fallback: extract autoplay parameters from the URL itself
        // (covers typed-chat and Gemini open_taplink paths).
        if ((query.isBlank() || mode.isBlank()) && url.contains("taplink_autoplay=")) {
            try {
                val uri = android.net.Uri.parse(url)
                val urlMode = uri.getQueryParameter("taplink_autoplay")
                    ?.trim()?.lowercase(Locale.US)
                    ?.takeIf { it == "video" || it == "music" || it == "subscriptions" || it == "history" }
                val urlQuery = uri.getQueryParameter("search_query")?.trim()
                if (!urlMode.isNullOrBlank()) {
                    mode = urlMode
                    query = when {
                        !urlQuery.isNullOrBlank() -> urlQuery
                        urlMode == "subscriptions" -> "subscriptions"
                        urlMode == "history" -> "history"
                        else -> query
                    }
                    youtubeAutoplayQuery = query
                    youtubeAutoplayMode = mode
                    DebugLog.d("YouTubeAuto", "  URL fallback: query='$query' mode='$mode'")
                }
            } catch (_: Exception) { /* ignore malformed URIs */ }
        }

        if (query.isBlank() || mode.isBlank()) {
            DebugLog.d("YouTubeAuto", "  SKIPPING — query or mode is blank")
            return
        }
        DebugLog.d("YouTubeAuto", "  INJECTING bootstrap JS for query='$query' mode='$mode'")
        // Only reset injection flags if this is a genuinely new page (different URL).
        // This prevents double-injection when onPageFinished fires multiple times
        // (iframes, redirects), which would toggle fullscreen on and off.
        val urlBase = url.substringBefore("#").substringBefore("&t=")
        if (urlBase != lastYouTubeInjectionUrl) {
            lastYouTubeInjectionUrl = urlBase
            view.evaluateJavascript("window.__taplink_yt_injected=false;window.__taplink_watch_injected=false;", null)
        }
        view.evaluateJavascript(buildYouTubeAutomationBootstrapScript(query, mode), null)
    }

    /**
     * Completely rewritten YouTube automation — simple & robust.
     *
     * SEARCH PAGE: finds the first clickable video link and navigates to it.
     * WATCH  PAGE: enables captions, unmutes, injects a floating ↻ replay
     *              button (bottom-left), and lets YouTube's built-in autoplay
     *              handle the next video.
     */
    internal fun buildYouTubeAutomationBootstrapScript(query: String, mode: String): String {
        return """
            (function(){
                console.log('[TapLink-YT] Bootstrap injected, url=' + location.href);
                if (window.__taplink_yt_injected) { console.log('[TapLink-YT] Already injected, skipping'); return; }
                window.__taplink_yt_injected = true;

                var loc = location.href || '';
                var autoplayMode = ${org.json.JSONObject.quote(mode)};
                var wantsSubscriptions = autoplayMode === 'subscriptions';
                var wantsHistory = autoplayMode === 'history';
                var isSearch = loc.indexOf('youtube.com/results') >= 0;
                var isSubscriptions = loc.indexOf('youtube.com/feed/subscriptions') >= 0;
                var isHistory = loc.indexOf('youtube.com/feed/history') >= 0;
                var isWatch  = loc.indexOf('youtube.com/watch') >= 0
                            || loc.indexOf('youtu.be/') >= 0;
                console.log('[TapLink-YT] isSearch=' + isSearch + ' isSubscriptions=' + isSubscriptions + ' isHistory=' + isHistory + ' isWatch=' + isWatch + ' wantsSubscriptions=' + wantsSubscriptions + ' wantsHistory=' + wantsHistory);

                function extractVideoIdFromHref(href) {
                    if (!href || href.indexOf('/shorts/') >= 0) return null;
                    var m = href.match(/[?&]v=([A-Za-z0-9_-]{11})/);
                    return m ? m[1] : null;
                }

                /* ── Extract unique 11-char video IDs from InnerTube JSON ──
                 *  Parses the JSON structure to pull only videoRenderer items
                 *  from itemSectionRenderer (actual history), skipping sidebar
                 *  recommendations, shorts shelves, and other non-history content.
                 *  Falls back to regex if JSON parsing fails. */
                function extractVideoIdsFromJson(jsonStr) {
                    var ids = [], seen = {};
                    function addId(id) {
                        if (id && id.length === 11 && !seen[id]) { seen[id] = true; ids.push(id); }
                    }
                    try {
                        var data = JSON.parse(jsonStr);
                        /* Navigate: contents.twoColumnBrowseResultsRenderer.tabs[].tabRenderer
                           .content.sectionListRenderer.contents[].itemSectionRenderer.contents[]
                           .videoRenderer.videoId — these are the actual history entries */
                        var tabs = ((data.contents || {}).twoColumnBrowseResultsRenderer || {}).tabs || [];
                        for (var t = 0; t < tabs.length; t++) {
                            var sections = ((((tabs[t].tabRenderer || {}).content || {}).sectionListRenderer || {}).contents) || [];
                            for (var s = 0; s < sections.length; s++) {
                                var items = ((sections[s].itemSectionRenderer || {}).contents) || [];
                                for (var i = 0; i < items.length; i++) {
                                    if (items[i].videoRenderer && items[i].videoRenderer.videoId) {
                                        addId(items[i].videoRenderer.videoId);
                                    }
                                }
                            }
                        }
                        if (ids.length > 0) {
                            console.log('[TapLink-YT] Parsed ' + ids.length + ' history IDs from JSON structure');
                            return ids;
                        }
                    } catch(e) {
                        console.warn('[TapLink-YT] JSON parse failed, falling back to regex:', e.message);
                    }
                    /* Fallback: regex extraction from raw text */
                    var re = /"videoId"\s*:\s*"([A-Za-z0-9_-]{11})"/g;
                    var m;
                    while ((m = re.exec(jsonStr)) !== null) { addId(m[1]); }
                    return ids;
                }

                /* ── Get InnerTube API key from YouTube's global config ── */
                function getInnertubeApiKey() {
                    try { if (window.ytcfg && ytcfg.get) return ytcfg.get('INNERTUBE_API_KEY') || ''; } catch(e) {}
                    try { if (window.ytcfg && ytcfg.data_) return ytcfg.data_.INNERTUBE_API_KEY || ''; } catch(e) {}
                    // Fallback: scan page source for the key
                    try {
                        var html = document.documentElement.innerHTML;
                        var km = html.match(/"INNERTUBE_API_KEY"\s*:\s*"([^"]+)"/);
                        if (km) return km[1];
                    } catch(e) {}
                    return '';
                }

                function getClientVersion() {
                    var clientVersion = '2.20260101.00.00';
                    try {
                        var cv = (ytcfg.get && ytcfg.get('INNERTUBE_CLIENT_VERSION')) ||
                                 (ytcfg.data_ && ytcfg.data_.INNERTUBE_CLIENT_VERSION);
                        if (cv) clientVersion = cv;
                    } catch(e) {}
                    return clientVersion;
                }

                function getVisitorData() {
                    try { if (ytcfg.get) return ytcfg.get('VISITOR_DATA') || ''; } catch(e) {}
                    try { if (ytcfg.data_) return ytcfg.data_.VISITOR_DATA || ''; } catch(e) {}
                    return '';
                }

                /* ── Generate SAPISIDHASH authorization for authenticated InnerTube requests ── */
                function getSapisidFromCookies() {
                    var m = document.cookie.match(/(?:^|;\s*)SAPISID=([^;]+)/);
                    if (m) return m[1];
                    var m3 = document.cookie.match(/(?:^|;\s*)__Secure-3PAPISID=([^;]+)/);
                    if (m3) return m3[1];
                    return '';
                }

                function sha1Hex(str) {
                    // Simple synchronous SHA-1 for SAPISIDHASH (SubtleCrypto is async, so use fallback)
                    // Encode the string to bytes
                    var encoder = new TextEncoder();
                    var data = encoder.encode(str);
                    // Use SubtleCrypto as a promise
                    return crypto.subtle.digest('SHA-1', data).then(function(buf) {
                        var arr = new Uint8Array(buf);
                        var hex = '';
                        for (var i = 0; i < arr.length; i++) {
                            hex += ('0' + arr[i].toString(16)).slice(-2);
                        }
                        return hex;
                    });
                }

                function generateSapiSidHash() {
                    var sapisid = getSapisidFromCookies();
                    if (!sapisid) return Promise.resolve('');
                    var ts = Math.floor(Date.now() / 1000);
                    var origin = 'https://www.youtube.com';
                    return sha1Hex(ts + ' ' + sapisid + ' ' + origin).then(function(hash) {
                        return 'SAPISIDHASH ' + ts + '_' + hash;
                    });
                }

                /* ── Build authenticated headers for InnerTube API ── */
                function getAuthHeaders() {
                    return generateSapiSidHash().then(function(authHash) {
                        var headers = { 'Content-Type': 'application/json' };
                        if (authHash) {
                            headers['Authorization'] = authHash;
                            console.log('[TapLink-YT] SAPISIDHASH auth header generated');
                        } else {
                            console.log('[TapLink-YT] No SAPISID cookie — request will be unauthenticated');
                        }
                        try { var si = ytcfg.get('SESSION_INDEX'); if (si !== undefined && si !== null) headers['X-Goog-AuthUser'] = String(si); } catch(e) {}
                        try { var pageCl = ytcfg.get('PAGE_CL'); if (pageCl) headers['X-Goog-PageId'] = String(pageCl); } catch(e) {}
                        try { var idTok = ytcfg.get('ID_TOKEN'); if (idTok) headers['X-Youtube-Identity-Token'] = idTok; } catch(e) {}
                        headers['X-Youtube-Client-Name'] = '1';
                        headers['X-Youtube-Client-Version'] = getClientVersion();
                        headers['Origin'] = 'https://www.youtube.com';
                        headers['Referer'] = 'https://www.youtube.com/';
                        return headers;
                    }).catch(function(e) {
                        console.warn('[TapLink-YT] Auth header generation failed:', e);
                        return { 'Content-Type': 'application/json' };
                    });
                }

                /* ── Build InnerTube request body with full client context ── */
                function buildBrowseBody(browseId) {
                    var body = {
                        browseId: browseId,
                        context: {
                            client: {
                                clientName: 'WEB',
                                clientVersion: getClientVersion(),
                                hl: 'en',
                                gl: 'US'
                            }
                        }
                    };
                    var vd = getVisitorData();
                    if (vd) body.context.client.visitorData = vd;
                    return body;
                }

                /* ── Fetch subscription video IDs via YouTube InnerTube browse API ── */
                function fetchSubscriptionIds() {
                    var apiKey = getInnertubeApiKey();
                    console.log('[TapLink-YT] InnerTube API key: ' + (apiKey ? apiKey.substring(0,8) + '...' : 'MISSING'));
                    if (!apiKey) return Promise.resolve([]);

                    return getAuthHeaders().then(function(headers) {
                        return fetch('https://www.youtube.com/youtubei/v1/browse?key=' + apiKey + '&prettyPrint=false', {
                            method: 'POST',
                            credentials: 'same-origin',
                            headers: headers,
                            body: JSON.stringify(buildBrowseBody('FEsubscriptions'))
                        });
                    })
                    .then(function(r) {
                        if (!r.ok) throw new Error('HTTP ' + r.status);
                        return r.text();
                    })
                    .then(function(text) {
                        var ids = extractVideoIdsFromJson(text);
                        console.log('[TapLink-YT] InnerTube subscriptions returned ' + ids.length + ' video IDs');
                        return ids;
                    })
                    .catch(function(e) {
                        console.error('[TapLink-YT] InnerTube subscriptions failed:', e);
                        return [];
                    });
                }

                /* ── Fetch history video IDs via YouTube InnerTube browse API ── */
                function fetchHistoryIds() {
                    var apiKey = getInnertubeApiKey();
                    console.log('[TapLink-YT] InnerTube API key (history): ' + (apiKey ? apiKey.substring(0,8) + '...' : 'MISSING'));
                    if (!apiKey) return Promise.resolve([]);

                    return getAuthHeaders().then(function(headers) {
                        return fetch('https://www.youtube.com/youtubei/v1/browse?key=' + apiKey + '&prettyPrint=false', {
                            method: 'POST',
                            credentials: 'same-origin',
                            headers: headers,
                            body: JSON.stringify(buildBrowseBody('FEhistory'))
                        });
                    })
                    .then(function(r) {
                        if (!r.ok) throw new Error('HTTP ' + r.status);
                        return r.text();
                    })
                    .then(function(text) {
                        var ids = extractVideoIdsFromJson(text);
                        console.log('[TapLink-YT] InnerTube history returned ' + ids.length + ' video IDs');
                        return ids;
                    })
                    .catch(function(e) {
                        console.error('[TapLink-YT] InnerTube history failed:', e);
                        return [];
                    });
                }

                /* ── Collect search IDs from page data (for search results pages) ── */
                function collectSearchIds() {
                    var ids = [], seen = {};
                    function addId(id) { if (id && id.length === 11 && !seen[id]) { seen[id]=true; ids.push(id); } }
                    // 1. ytInitialData
                    try {
                        if (window.ytInitialData) {
                            extractVideoIdsFromJson(JSON.stringify(window.ytInitialData)).forEach(addId);
                        }
                    } catch(e) {}
                    // 2. Script tags
                    if (ids.length < 5) {
                        try {
                            var scripts = document.querySelectorAll('script');
                            for (var k = 0; k < scripts.length; k++) {
                                var txt = scripts[k].textContent || '';
                                if (txt.indexOf('"videoId"') < 0) continue;
                                extractVideoIdsFromJson(txt).forEach(addId);
                            }
                        } catch(e) {}
                    }
                    // 3. DOM links
                    var allLinks = document.querySelectorAll('a[href*="/watch?v="]');
                    for (var j = 0; j < allLinks.length; j++) {
                        var vid = extractVideoIdFromHref(allLinks[j].getAttribute('href') || '');
                        if (vid) addId(vid);
                    }
                    return ids;
                }

                /* ── Collect feed entries in the exact DOM order shown to the user ── */
                function collectRenderedFeedEntries(feedType) {
                    var entries = [], seen = {};
                    function addEntry(id, title, tag) {
                        if (!id || id.length !== 11 || seen[id]) return;
                        seen[id] = true;
                        entries.push({ id: id, title: title || '', tag: tag || '' });
                    }
                    function collectFromSelectorList(selectorList) {
                        for (var s = 0; s < selectorList.length; s++) {
                            var items = document.querySelectorAll(selectorList[s]);
                            if (!items || !items.length) continue;
                            for (var i = 0; i < items.length; i++) {
                                var item = items[i];
                                var rect = item.getBoundingClientRect ? item.getBoundingClientRect() : null;
                                if (rect && rect.width <= 0 && rect.height <= 0) continue;
                                var link = item.querySelector('a#video-title[href*="/watch?v="], a#thumbnail[href*="/watch?v="], a[href*="/watch?v="]');
                                if (!link) continue;
                                var vid = extractVideoIdFromHref(link.getAttribute('href') || '');
                                if (!vid) continue;
                                var title = link.getAttribute('title') || link.textContent || '';
                                title = (title || '').replace(/\s+/g, ' ').trim();
                                addEntry(vid, title, item.tagName || selectorList[s]);
                            }
                            if (entries.length > 0) return entries;
                        }
                        return entries;
                    }

                    if (feedType === 'history') {
                        return collectFromSelectorList([
                            'ytd-browse[page-subtype="history"] ytd-rich-item-renderer',
                            'ytd-browse[page-subtype="history"] ytd-rich-grid-media',
                            'ytd-browse[page-subtype="history"] ytd-grid-video-renderer',
                            'ytd-browse[page-subtype="history"] ytd-item-section-renderer #contents ytd-video-renderer',
                            'ytd-browse[page-subtype="history"] #contents ytd-video-renderer',
                            'ytd-page-manager ytd-browse[page-subtype="history"] ytd-rich-grid-media',
                            'ytd-page-manager ytd-browse[page-subtype="history"] ytd-video-renderer'
                        ]);
                    }

                    if (feedType === 'subscriptions') {
                        return collectFromSelectorList([
                            'ytd-browse[page-subtype="subscriptions"] ytd-rich-item-renderer',
                            'ytd-browse[page-subtype="subscriptions"] ytd-rich-grid-media',
                            'ytd-browse[page-subtype="subscriptions"] ytd-grid-video-renderer'
                        ]);
                    }

                    return collectFromSelectorList([
                        'ytd-video-renderer',
                        'ytd-rich-item-renderer ytd-rich-grid-media',
                        'ytd-grid-video-renderer',
                        'ytd-rich-grid-media',
                        'ytd-playlist-panel-video-renderer'
                    ]);
                }

                function collectRenderedFeedIds(feedType) {
                    return collectRenderedFeedEntries(feedType).map(function(entry) { return entry.id; });
                }

                function logFeedSnapshot(sourceLabel, feedType, entries) {
                    try {
                        var browse = document.querySelector('ytd-browse');
                        var subtype = browse ? (browse.getAttribute('page-subtype') || '') : '';
                        console.log('[TapLink-YT] ' + sourceLabel + ' page subtype=' + subtype + ' feedType=' + feedType + ' entries=' + entries.length);
                        entries.slice(0, 10).forEach(function(entry, idx) {
                            console.log('[TapLink-YT] ' + sourceLabel + ' #' + (idx + 1) + ' ' + entry.id + ' [' + entry.tag + '] ' + entry.title);
                        });
                    } catch (e) {
                        console.log('[TapLink-YT] ' + sourceLabel + ' snapshot log failed: ' + e);
                    }
                }

                function collectRenderedFeedIdsWithScroll(sourceLabel, feedType, minIds, maxScrolls, callback) {
                    var pass = 0;
                    var stablePasses = 0;
                    var lastSignature = '';

                    function tick() {
                        var entries = collectRenderedFeedEntries(feedType);
                        var ids = entries.map(function(entry) { return entry.id; });
                        var signature = ids.slice(0, 8).join(',');
                        console.log('[TapLink-YT] ' + sourceLabel + ' DOM pass ' + pass + ': found ' + ids.length + ' videos');
                        logFeedSnapshot(sourceLabel + ' pass ' + pass, feedType, entries);

                        if (ids.length >= minIds) {
                            callback(ids);
                            return;
                        }

                        if (ids.length > 0) {
                            if (signature === lastSignature) stablePasses++; else stablePasses = 0;
                            if (stablePasses >= 2 || pass >= maxScrolls) {
                                callback(ids);
                                return;
                            }
                        } else if (pass >= maxScrolls) {
                            callback(ids);
                            return;
                        }

                        lastSignature = signature;
                        pass++;
                        window.scrollBy(0, Math.max(window.innerHeight * 1.5, 900));
                        setTimeout(tick, 1400);
                    }

                    setTimeout(tick, 1800);
                }

                function finishAndPlay(ids, sourceLabel) {
                    ids = ids.slice(0, 30);
                    console.log('[TapLink-YT] Final playlist (' + sourceLabel + '): ' + ids.length + ' videos');
                    console.log('[TapLink-YT] IDs: ' + ids.slice(0, 10).join(', '));
                    try {
                        var bridge = window.GroqBridge;
                        if (bridge && bridge.setYouTubePlaylist) {
                            bridge.setYouTubePlaylist(JSON.stringify(ids));
                        }
                    } catch(e) { console.log('[TapLink-YT] Bridge error: ' + e); }
                    location.href = 'https://www.youtube.com/watch?v=' + ids[0] + '&autoplay=1&cc_load_policy=1';
                }

                /* ── SUBSCRIPTIONS: prefer exact rendered feed order, fall back to API ── */
                if (wantsSubscriptions && !isWatch) {
                    if (isSubscriptions) {
                        console.log('[TapLink-YT] Collecting subscriptions from rendered feed order...');
                        collectRenderedFeedIdsWithScroll('subscriptions', 'subscriptions', 18, 6, function(feedIds) {
                            if (feedIds.length >= 1) {
                                finishAndPlay(feedIds, 'rendered subscriptions feed');
                                return;
                            }
                            console.log('[TapLink-YT] Rendered subscriptions feed was empty — falling back to InnerTube');
                            fetchSubscriptionIds().then(function(ids) {
                                if (ids.length >= 1) {
                                    finishAndPlay(ids, 'InnerTube subscriptions');
                                    return;
                                }
                                var fallbackIds = collectSearchIds();
                                if (fallbackIds.length >= 1) {
                                    finishAndPlay(fallbackIds, 'ytInitialData subscriptions fallback');
                                    return;
                                }
                                console.log('[TapLink-YT] No subscription videos found via any method');
                            });
                        });
                    } else {
                        console.log('[TapLink-YT] Fetching subscriptions via InnerTube API...');
                        fetchSubscriptionIds().then(function(ids) {
                            if (ids.length >= 1) {
                                finishAndPlay(ids, 'InnerTube subscriptions');
                                return;
                            }
                            var fallbackIds = collectSearchIds();
                            if (fallbackIds.length >= 1) {
                                finishAndPlay(fallbackIds, 'ytInitialData subscriptions fallback');
                                return;
                            }
                            console.log('[TapLink-YT] No subscription videos found via any method');
                        });
                    }
                    return;
                }

                /* ── HISTORY: prefer exact rendered history order, fall back to API ── */
                if (wantsHistory && !isWatch) {
                    if (isHistory) {
                        console.log('[TapLink-YT] Collecting history from rendered feed order...');
                        collectRenderedFeedIdsWithScroll('history', 'history', 12, 7, function(feedIds) {
                            if (feedIds.length >= 1) {
                                finishAndPlay(feedIds, 'rendered history feed');
                                return;
                            }
                            console.log('[TapLink-YT] Rendered history feed was empty — falling back to InnerTube');
                            fetchHistoryIds().then(function(ids) {
                                if (ids.length >= 1) {
                                    finishAndPlay(ids, 'InnerTube history');
                                    return;
                                }
                                var fallbackIds = collectSearchIds();
                                if (fallbackIds.length >= 1) {
                                    finishAndPlay(fallbackIds, 'ytInitialData history fallback');
                                    return;
                                }
                                console.log('[TapLink-YT] No history videos found via any method');
                            });
                        });
                    } else {
                        console.log('[TapLink-YT] Fetching history via InnerTube API...');
                        fetchHistoryIds().then(function(ids) {
                            if (ids.length >= 1) {
                                finishAndPlay(ids, 'InnerTube history');
                                return;
                            }
                            var fallbackIds = collectSearchIds();
                            if (fallbackIds.length >= 1) {
                                finishAndPlay(fallbackIds, 'ytInitialData history fallback');
                                return;
                            }
                            console.log('[TapLink-YT] No history videos found via any method');
                        });
                    }
                    return;
                }

                /* ── SEARCH PAGE: collect IDs from page data ── */
                if (isSearch) {
                    var scrollCount = 0;
                    var maxScrolls = 8;

                    function scrollAndCollect() {
                        var ids = collectSearchIds();
                        console.log('[TapLink-YT] Scroll ' + scrollCount + '/' + maxScrolls + ': found ' + ids.length + ' videos');

                        if (ids.length >= 20 || scrollCount >= maxScrolls) {
                            if (ids.length === 0) {
                                if (scrollCount < maxScrolls + 3) {
                                    scrollCount++;
                                    window.scrollBy(0, window.innerHeight * 2);
                                    setTimeout(scrollAndCollect, 2000);
                                    return;
                                }
                                console.log('[TapLink-YT] GAVE UP — no videos found');
                                return;
                            }
                            finishAndPlay(ids, 'search results');
                            return;
                        }

                        scrollCount++;
                        window.scrollBy(0, window.innerHeight * 2);
                        setTimeout(scrollAndCollect, 1500);
                    }

                    setTimeout(scrollAndCollect, 2000);
                    return;
                }

                /* ── WATCH PAGE: wait for playing → fullscreen → captions → hijack next ── */
                if (isWatch) {
                    console.log('[TapLink-YT] Watch page detected');

                    var fsDone = false;
                    var ccDone = false;
                    var nextHijacked = false;
                    var boundVideoEl = null;

                    /* ── CSS FULLSCREEN ──
                       Since WebView blocks ALL programmatic fullscreen (user gesture
                       required), we use CSS injection to make the video fill the
                       viewport and have Kotlin enter immersive mode. No tap/key
                       simulation needed. Works reliably. */

                    function enterCssFullscreen() {
                        if (fsDone) return;
                        // Don't auto-enter CSS fullscreen if user manually chose a different view mode
                        if (typeof window.__tl_view_mode !== 'undefined' && window.__tl_view_mode !== 0) {
                            console.log('[TapLink-YT] Skipping auto CSS fs — user chose view mode ' + window.__tl_view_mode);
                            fsDone = true;
                            return;
                        }
                        fsDone = true;
                        console.log('[TapLink-YT] Entering CSS fullscreen mode');
                        try { window.GroqBridge.enterCssFullscreen(); } catch(e) {
                            console.log('[TapLink-YT] enterCssFullscreen bridge failed: ' + e);
                        }
                    }

                    /* Wait for video playback, then enter CSS fullscreen after 2s */
                    var videoCheckCount = 0;
                    function waitForVideoPlaying() {
                        var v = document.querySelector('video');
                        if (v) {
                            console.log('[TapLink-YT] Video found: paused=' + v.paused + ' readyState=' + v.readyState + ' currentTime=' + v.currentTime.toFixed(1));
                            if (!v.paused && v.readyState >= 3 && v.currentTime > 0.5) {
                                console.log('[TapLink-YT] Video playing (t=' + v.currentTime.toFixed(1) + ') → CSS fullscreen in 2s');
                                setTimeout(enterCssFullscreen, 2000);
                                return;
                            }
                            var started = false;
                            function onTimeUpdate() {
                                if (started) return;
                                if (v.currentTime > 0.5 && !v.paused) {
                                    started = true;
                                    v.removeEventListener('timeupdate', onTimeUpdate);
                                    console.log('[TapLink-YT] Video timeupdate confirms playback (t=' + v.currentTime.toFixed(1) + ') → CSS fullscreen in 2s');
                                    setTimeout(enterCssFullscreen, 2000);
                                }
                            }
                            v.addEventListener('timeupdate', onTimeUpdate);
                            if (v.paused) {
                                v.play().catch(function(e) {
                                    v.muted = true;
                                    v.play().catch(function(){});
                                });
                            }
                            if (v.muted) v.muted = false;
                            setTimeout(function() {
                                if (!started && !fsDone) {
                                    started = true;
                                    v.removeEventListener('timeupdate', onTimeUpdate);
                                    console.log('[TapLink-YT] SAFETY: 15s elapsed, forcing CSS fullscreen');
                                    enterCssFullscreen();
                                }
                            }, 15000);
                            return;
                        }
                        videoCheckCount++;
                        if (videoCheckCount < 40) {
                            if (videoCheckCount % 10 === 0) console.log('[TapLink-YT] Waiting for video element... attempt ' + videoCheckCount);
                            setTimeout(waitForVideoPlaying, 300);
                        }
                    }
                    waitForVideoPlaying();

                    /* ── NAV BUTTONS: inject immediately (buttons go on document.body) ── */
                    try { window.GroqBridge.injectNavButtons(); } catch(e) {
                        console.log('[TapLink-YT] injectNavButtons bridge failed: ' + e);
                    }

                    /* ── CC ── */
                    function enableCC() {
                        if (ccDone) return;
                        var ccBtn = document.querySelector('.ytp-subtitles-button');
                        if (!ccBtn) return;
                        ccDone = true;
                        if (ccBtn.getAttribute('aria-pressed') !== 'true') {
                            ccBtn.click();
                            console.log('[TapLink-YT] CC enabled');
                        }
                    }

                    /* ── ENSURE PLAY (only until playback first starts) ──
                       Uses window-level flag so re-injections don't reset it. */
                    function ensurePlay() {
                        if (window.__taplink_playback_started) return;
                        var v = document.querySelector('video');
                        if (!v) return;
                        if (v.muted) v.muted = false;
                        if (!v.paused && v.currentTime > 0.5) {
                            window.__taplink_playback_started = true;
                            console.log('[TapLink-YT] Playback confirmed, ensurePlay disabled');
                            return;
                        }
                        if (v.paused) v.play().catch(function(){});
                    }

                    /* ── HIJACK NEXT BUTTON to use our playlist ── */
                    function hijackNextButton() {
                        if (nextHijacked) return;
                        var nb = document.querySelector('.ytp-next-button');
                        if (!nb) return;
                        nextHijacked = true;
                        var clone = nb.cloneNode(true);
                        nb.parentNode.replaceChild(clone, nb);
                        clone.addEventListener('click', function(e) {
                            e.preventDefault();
                            e.stopPropagation();
                            e.stopImmediatePropagation();
                            console.log('[TapLink-YT] Next button → TapLink playlist');
                            try { window.GroqBridge.playNextInPlaylist(); }
                            catch(err) { console.log('[TapLink-YT] Bridge error: ' + err); }
                        }, true);
                        console.log('[TapLink-YT] Next button hijacked');
                    }

                    /* ── AUTO-ADVANCE when video ends ── */
                    function bindEnded() {
                        var v = document.querySelector('video');
                        if (!v || v === boundVideoEl) return;
                        boundVideoEl = v;
                        v.addEventListener('ended', function() {
                            console.log('[TapLink-YT] Video ended — playing next');
                            try { window.GroqBridge.playNextInPlaylist(); }
                            catch(e) { console.log('[TapLink-YT] Bridge error: ' + e); }
                        });
                        console.log('[TapLink-YT] ended listener bound');
                    }

                    /* Periodic tick for CC, play, hijack, ended.
                       Fullscreen is handled separately by the 'playing' event. */
                    var watchAttempts = 0;
                    function tick() {
                        enableCC();
                        ensurePlay();
                        hijackNextButton();
                        bindEnded();
                        watchAttempts++;
                        if (watchAttempts < 25) setTimeout(tick, 1000);
                    }
                    setTimeout(tick, 1000);
                }
            })();
        """.trimIndent()
    }

    /**
     * Lightweight watch-page script for when a YouTube watch URL is opened
     * directly (e.g. via taplink_playlist=1). Enables captions, unmutes,
     * and adds the floating replay button.
     */
    private fun buildYouTubeWatchAutomationScript(): String {
        return """
            (function(){
                if (window.__taplink_watch_injected) return;
                window.__taplink_watch_injected = true;
                console.log('[TapLink-YT] Watch automation script injected');

                var fsDone = false;
                var ccDone = false;
                var nextHijacked = false;
                var boundVideoEl = null;

                /* ── FULLSCREEN: wait 8s for YouTube to settle, then try webkitEnterFullscreen or native tap ── */
                document.addEventListener('fullscreenchange', function() {
                    if (document.fullscreenElement) { fsDone = true; }
                });
                document.addEventListener('webkitfullscreenchange', function() {
                    if (document.webkitFullscreenElement) { fsDone = true; }
                });
                /* CSS FULLSCREEN — same approach as bootstrap */
                function enterCssFs() {
                    if (fsDone) return;
                    if (typeof window.__tl_view_mode !== 'undefined' && window.__tl_view_mode !== 0) {
                        console.log('[TapLink-YT] watch: skipping auto CSS fs — user chose view ' + window.__tl_view_mode);
                        fsDone = true;
                        return;
                    }
                    fsDone = true;
                    console.log('[TapLink-YT] watch: entering CSS fullscreen');
                    try { window.GroqBridge.enterCssFullscreen(); } catch(e) {}
                }
                var vc = 0;
                function waitForPlaying() {
                    var v = document.querySelector('video');
                    if (v) {
                        if (!v.paused && v.readyState >= 3 && v.currentTime > 0.5) {
                            console.log('[TapLink-YT] watch: video playing (t=' + v.currentTime.toFixed(1) + ') → CSS fs in 2s');
                            setTimeout(enterCssFs, 2000);
                            return;
                        }
                        var started = false;
                        function onTime() {
                            if (started) return;
                            if (v.currentTime > 0.5 && !v.paused) {
                                started = true;
                                v.removeEventListener('timeupdate', onTime);
                                console.log('[TapLink-YT] watch: timeupdate (t=' + v.currentTime.toFixed(1) + ') → CSS fs in 2s');
                                setTimeout(enterCssFs, 2000);
                            }
                        }
                        v.addEventListener('timeupdate', onTime);
                        if (v.paused) v.play().catch(function(){});
                        if (v.muted) v.muted = false;
                        setTimeout(function() {
                            if (!started && !fsDone) { started = true; v.removeEventListener('timeupdate', onTime); enterCssFs(); }
                        }, 15000);
                        return;
                    }
                    vc++;
                    if (vc < 40) setTimeout(waitForPlaying, 300);
                }
                waitForPlaying();

                /* ── NAV BUTTONS: inject immediately (buttons go on document.body) ── */
                try { window.GroqBridge.injectNavButtons(); } catch(e) {}

                function enableCC() {
                    if (ccDone) return;
                    var btn = document.querySelector('.ytp-subtitles-button');
                    if (!btn) return;
                    ccDone = true;
                    if (btn.getAttribute('aria-pressed') !== 'true') btn.click();
                }
                function ensurePlay() {
                    if (window.__taplink_playback_started) return;
                    var v = document.querySelector('video');
                    if (!v) return;
                    if (v.muted) v.muted = false;
                    if (!v.paused && v.currentTime > 0.5) {
                        window.__taplink_playback_started = true;
                        return;
                    }
                    if (v.paused) v.play().catch(function(){});
                }
                function hijackNextButton() {
                    if (nextHijacked) return;
                    var nb = document.querySelector('.ytp-next-button');
                    if (!nb) return;
                    nextHijacked = true;
                    var clone = nb.cloneNode(true);
                    nb.parentNode.replaceChild(clone, nb);
                    clone.addEventListener('click', function(e) {
                        e.preventDefault(); e.stopPropagation(); e.stopImmediatePropagation();
                        try { window.GroqBridge.playNextInPlaylist(); } catch(err) {}
                    }, true);
                }
                function bindEnded() {
                    var v = document.querySelector('video');
                    if (!v || v === boundVideoEl) return;
                    boundVideoEl = v;
                    v.addEventListener('ended', function() {
                        try { window.GroqBridge.playNextInPlaylist(); } catch(e) {}
                    });
                }

                var attempts = 0;
                function tick() {
                    enableCC(); ensurePlay(); hijackNextButton(); bindEnded();
                    attempts++;
                    if (attempts < 25) setTimeout(tick, 1000);
                }
                setTimeout(tick, 1000);
            })();
        """.trimIndent()
    }

    // Add method to handle hyperlink button press
    override fun onHyperlinkPressed() {
        DebugLog.d("LinkEditing", "onHyperlinkPressed called")
        dualWebViewGroup.showLinkEditing()
    }

    override fun onNavigationForwardPressed() {
        if (webView.canGoForward()) {
            webView.goForward()
        }
    }

    override fun onPause() {
        super.onPause()
        stopCloudTts()

        if (nativeQrScannerView != null || isQrScanInProgress) {
            isQrScanInProgress = false
            pendingNativeQrStart = false
            stopNativeQrScannerOverlay()
        }

        try {
            unregisterReceiver(notificationReceiver)
        } catch (e: IllegalArgumentException) {
            // Receiver not registered
        }

        if (isAnchored) {
            // Just unregister the sensor listener to save resources
            sensorManager.unregisterListener(sensorEventListener)
        }

        // Don't pause media when screen is masked — the user wants audio to keep playing
        // while the projector is off (power button press triggers onPause on RayNeo X3 Pro)
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.setHostPaused(true)
            if (!dualWebViewGroup.isScreenMasked()) {
                dualWebViewGroup.pauseYouTubeMediaAcrossAllWindows(resetTracking = false)
            }

            // Keep a lightweight snapshot on pause so projector-off/resume does not block audio.
            dualWebViewGroup.saveWindowMetadataState()
        }
    }

    override fun onResume() {
        super.onResume()

        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.setHostPaused(false)
        }

        // Register notification receiver
        val filter = IntentFilter(NotificationService.ACTION_NOTIFICATION_POSTED)
        ContextCompat.registerReceiver(
                this,
                notificationReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
        )

        // Restart mirroring to right eye
        dualWebViewGroup.startRefreshing()
        syncTapRadioPlaybackUi()

        // Check for notification listener permission

        if (isAnchored) {
            // Re-register the sensor listener
            rotationSensor?.let { sensor ->
                sensorManager.registerListener(
                        sensorEventListener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                )
            }
        }
    }

    private fun syncTapRadioPlaybackUi() {
        if (!::dualWebViewGroup.isInitialized) return
        uiHandler.postDelayed({
            dualWebViewGroup.getAllWebViews().forEach { candidate ->
                val url = candidate.url.orEmpty()
                if (!url.contains("radio.html", ignoreCase = true)) return@forEach
                candidate.post {
                    candidate.evaluateJavascript(
                        "(function(){if(window.tapRadioSyncPlaybackUi){window.tapRadioSyncPlaybackUi();}})();",
                        null
                    )
                }
            }
            dualWebViewGroup.refreshMaskedNowPlaying()
        }, 250L)
    }

    fun getLastLocation(): Pair<Double, Double>? {
        return if (lastGpsLat != null && lastGpsLon != null) {
            Pair(lastGpsLat!!, lastGpsLon!!)
        } else {
            null
        }
    }

    private fun ensureGpsUpdates() {
        if (gpsUpdatesRegistered) return

        if (ipcLauncher == null) {
            ipcLauncher = Launcher.getInstance(this)
        }
        ipcLauncher?.addOnResponseListener(gpsResponseListener)
        GPSIPCHelper.registerGPSInfo(this)
        gpsUpdatesRegistered = true
    }

    private fun noteGeolocationUse() {
        lastGpsRequestAt = SystemClock.elapsedRealtime()
        ensureGpsUpdates()

        gpsStopRunnable?.let { gpsHandler.removeCallbacks(it) }
        gpsStopRunnable =
                object : Runnable {
                    override fun run() {
                        val now = SystemClock.elapsedRealtime()
                        if (now - lastGpsRequestAt >= GPS_IDLE_TIMEOUT_MS) {
                            stopGpsUpdates()
                        } else {
                            gpsHandler.postDelayed(this, GPS_IDLE_TIMEOUT_MS)
                        }
                    }
                }
        gpsHandler.postDelayed(gpsStopRunnable!!, GPS_IDLE_TIMEOUT_MS)
    }

    private fun stopGpsUpdates() {
        if (!gpsUpdatesRegistered) return

        GPSIPCHelper.unRegisterGPSInfo(this)
        ipcLauncher?.removeOnResponseListener(gpsResponseListener)
        ipcLauncher?.disConnect()
        ipcLauncher = null
        gpsUpdatesRegistered = false
        gpsStopRunnable?.let { gpsHandler.removeCallbacks(it) }
        gpsStopRunnable = null
    }

    override fun getCurrentUrl(): String {
        return dualWebViewGroup.getWebView().url ?: Constants.DEFAULT_URL
    }

    fun openUrlInNewTab(url: String) {
        if (!::dualWebViewGroup.isInitialized) return
        val formattedUrl = formatUrl(url)
        val newWebView = dualWebViewGroup.createNewWindow()
        if (dualWebViewGroup.isChatVisible()) {
            closeChatOnNextPageStart = true
            closeChatOnNextPageStartDeadlineMs = SystemClock.uptimeMillis() + 5000L
        }
        newWebView.loadUrl(formattedUrl)
    }

    fun getActiveWebViewUrlOrNull(): String? {
        if (!::dualWebViewGroup.isInitialized) return null
        return dualWebViewGroup.getWebView().url
    }

    override fun onBookmarkSelected(url: String) {
        val formattedUrl =
                when {
                    // Check for file: protocol specifically
                    url.startsWith("file:", ignoreCase = true) -> url
                    url.startsWith("http://") || url.startsWith("https://") -> url
                    url.contains(".") -> "https://$url"
                    else -> "https://www.google.com/search?q=${Uri.encode(url)}"
                }
        webView.loadUrl(formattedUrl)
    }

    private fun handleMaskToggle() {
        // Close settings menu if open to prevent state desync
        dualWebViewGroup.hideSettings()

        // de-anchor when masking to avoid issues
        if (isAnchored) {
            toggleAnchor()
        }

        // Store current cursor state before masking
        preMaskCursorState = isCursorVisible
        preMaskCursorX = lastCursorX
        preMaskCursorY = lastCursorY

        // Hide cursor
        isCursorVisible = false
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
        refreshCursor(false)

        // Mask the screen
        dualWebViewGroup.maskScreen()
    }

    override fun onSendCharacterToLink(character: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            val newText = StringBuilder(currentText).insert(currentPosition, character).toString()

            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition + 1)
        }
    }

    override fun onSendBackspaceInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val currentPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (currentPosition > 0) {
                // Delete character before cursor position
                val newText =
                        StringBuilder(currentText).deleteCharAt(currentPosition - 1).toString()

                dualWebViewGroup.setLinkText(newText)

                // Move cursor back one position
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(currentPosition - 1)
            }
        }
    }

    override fun onMaskTogglePressed() {
        handleMaskToggle()
    }

    override fun onSendEnterInLink() {
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(false)
        isKeyboardVisible = false
        if (dualWebViewGroup.isUrlEditing()) {
            val url = dualWebViewGroup.getCurrentLinkText()
            val formattedUrl = formatUrl(url)
            webView.loadUrl(formattedUrl)
            dualWebViewGroup.hideLinkEditing()
            hideCustomKeyboard()
        }
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            webView.requestFocus()
        }
    }

    // Helper function for quaternion multiplication
    fun quaternionMultiply(q1: FloatArray, q2: FloatArray): FloatArray {
        val w = q1[0] * q2[0] - q1[1] * q2[1] - q1[2] * q2[2] - q1[3] * q2[3]
        val x = q1[0] * q2[1] + q1[1] * q2[0] + q1[2] * q2[3] - q1[3] * q2[2]
        val y = q1[0] * q2[2] - q1[1] * q2[3] + q1[2] * q2[0] + q1[3] * q2[1]
        val z = q1[0] * q2[3] + q1[1] * q2[2] - q1[2] * q2[1] + q1[3] * q2[0]
        return floatArrayOf(w, x, y, z)
    }

    // Helper function for quaternion inversion
    fun quaternionInverse(q: FloatArray): FloatArray {
        val magnitudeSquared = q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]
        if (magnitudeSquared == 0f) return floatArrayOf(0f, 0f, 0f, 0f)
        val invMagnitude = 1f / magnitudeSquared
        return floatArrayOf(
                q[0] * invMagnitude,
                -q[1] * invMagnitude,
                -q[2] * invMagnitude,
                -q[3] * invMagnitude
        )
    }

    private fun normalizeQuaternion(q: FloatArray): FloatArray {
        val len = kotlin.math.sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3])
        if (len > 0) {
            return floatArrayOf(q[0] / len, q[1] / len, q[2] / len, q[3] / len)
        }
        return q
    }

    private fun quaternionSlerp(qa: FloatArray, qb: FloatArray, t: Float): FloatArray {
        // q = [w, x, y, z]
        val w1 = qa[0]
        val x1 = qa[1]
        val y1 = qa[2]
        val z1 = qa[3]
        var w2 = qb[0]
        var x2 = qb[1]
        var y2 = qb[2]
        var z2 = qb[3]

        var dot = w1 * w2 + x1 * x2 + y1 * y2 + z1 * z2

        // If the dot product is negative, slerp won't take the shorter path.
        // So we negate one quaternion.
        if (dot < 0.0f) {
            w2 = -w2
            x2 = -x2
            y2 = -y2
            z2 = -z2
            dot = -dot
        }

        val DOT_THRESHOLD = 0.9995f
        if (dot > DOT_THRESHOLD) {
            // If the inputs are too close for comfort, linearly interpolate
            // and normalize.
            val result =
                    floatArrayOf(
                            w1 + t * (w2 - w1),
                            x1 + t * (x2 - x1),
                            y1 + t * (y2 - y1),
                            z1 + t * (z2 - z1)
                    )
            return normalizeQuaternion(result)
        }

        val theta_0 = kotlin.math.acos(dot) // theta_0 = angle between input vectors
        val theta = theta_0 * t // theta = angle between v0 and result
        val sin_theta = kotlin.math.sin(theta) // compute this value only once
        val sin_theta_0 = kotlin.math.sin(theta_0) // compute this value only once

        val s0 =
                kotlin.math.cos(theta) -
                        dot * sin_theta / sin_theta_0 // == sin(theta_0 - theta) / sin(theta_0)
        val s1 = sin_theta / sin_theta_0

        return floatArrayOf(
                s0 * w1 + s1 * w2,
                s0 * x1 + s1 * x2,
                s0 * y1 + s1 * y2,
                s0 * z1 + s1 * z2
        )
    }

    private fun ensureCameraThread() {
        if (cameraThread != null) return
        cameraThread = HandlerThread("CameraThread").apply { start() }
        cameraHandler = Handler(cameraThread!!.looper)
    }

    private fun initializeCamera() {
        if (imageReader != null) return

        ensureCameraThread()
        val handler = cameraHandler ?: Handler(Looper.getMainLooper())

        try {
            cameraManager = getSystemService(Context.CAMERA_SERVICE) as CameraManager

            // Set up ImageReader for capturing photos
            imageReader =
                    ImageReader.newInstance(1920, 1080, android.graphics.ImageFormat.JPEG, 2)
                            .apply {
                                setOnImageAvailableListener(
                                        { reader ->
                                            // When an image is captured
                                            val image = reader.acquireLatestImage()
                                            try {
                                                // Convert image to base64 for web upload
                                                val buffer = image.planes[0].buffer
                                                val bytes = ByteArray(buffer.capacity())
                                                buffer.get(bytes)
                                                val base64Image =
                                                        Base64.encodeToString(bytes, Base64.DEFAULT)

                                                // Send image back to Google's image search
                                                runOnUiThread {
                                                    webView.evaluateJavascript(
                                                            """
                            (function() {
                                // Create a File object from base64
                                fetch('data:image/jpeg;base64,$base64Image')
                                    .then(res => res.blob())
                                    .then(blob => {
                                        const file = new File([blob], "image.jpg", { type: 'image/jpeg' });
                                        
                                        // Find or create file input
                                        let input = document.querySelector('input[type="file"][name="encoded_image"]');
                                        if (!input) {
                                            input = document.createElement('input');
                                            input.type = 'file';
                                            input.name = 'encoded_image';
                                            document.body.appendChild(input);
                                        }
                                        
                                        // Create FileList with our image
                                        const dataTransfer = new DataTransfer();
                                        dataTransfer.items.add(file);
                                        input.files = dataTransfer.files;
                                        
                                        // Trigger form submission
                                        input.dispatchEvent(new Event('change', { bubbles: true }));
                                    });
                            })();
                        """.trimIndent(),
                                                            null
                                                    )
                                                }
                                            } finally {
                                                image.close()
                                            }
                                        },
                                        handler
                                )
                            }
        } catch (e: Exception) {
            DebugLog.e("Camera", "Failed to initialize camera system", e)
            runOnUiThread {
                webView.evaluateJavascript("alert('Failed to initialize camera system.');", null)
            }
        }
    }

    override fun onSendClearInLink() {
        if (dualWebViewGroup.isUrlEditing()) {
            dualWebViewGroup.setLinkText("")
            // Set cursor at the beginning
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(0)
        }
    }

    override fun onShowKeyboardForEdit(text: String) {
        DebugLog.d("MainActivity", "onShowKeyboardForEdit called with text: $text")

        // Ensure we're on the main thread
        if (Looper.myLooper() != Looper.getMainLooper()) {
            runOnUiThread { showCustomKeyboard() }
            return
        }

        showCustomKeyboard()
    }

    override fun onShowKeyboardForNew() {
        showCustomKeyboard()
    }

    override fun onShowLinkEditing() {
        DebugLog.d("LinkEditing", "onShowLinkEditing called")
        isUrlEditing = true // Make sure this state is set
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        showCustomKeyboard()
    }

    override fun onHideLinkEditing() {
        DebugLog.d("LinkEditing", "onHideLinkEditing called")
        isUrlEditing = false
        dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        DebugLog.d("LinkEditing", "isUrlEditing set to false")
        hideCustomKeyboard()
    }

    private fun sendCharacterToLinkEditText(character: String) {
        DebugLog.d("LinkEditing", "sendCharacterToLinkEditText called with: $character")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            val newText = StringBuilder(currentText).insert(cursorPosition, character).toString()
            dualWebViewGroup.setLinkText(newText)
            dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition + 1)
        }
    }

    private fun sendBackspaceInLinkEditText() {
        DebugLog.d("LinkEditing", "sendBackspaceInLinkEditText called")
        if (dualWebViewGroup.isUrlEditing()) {
            val currentText = dualWebViewGroup.getCurrentLinkText()
            val cursorPosition =
                    dualWebViewGroup.getCurrentUrlEditField()?.selectionStart ?: currentText.length

            if (cursorPosition > 0) {
                val newText = StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()
                dualWebViewGroup.setLinkText(newText)
                dualWebViewGroup.getCurrentUrlEditField()?.setSelection(cursorPosition - 1)
            }
        }
    }

    private fun sendEnterInLinkEditText() {
        if (dualWebViewGroup.isUrlEditing()) {
            val url = dualWebViewGroup.getCurrentLinkText()
            val formattedUrl = formatUrl(url)
            webView.loadUrl(formattedUrl)
            dualWebViewGroup.hideLinkEditing()
            keyboardListener?.onHideKeyboard()
        }
    }

    override fun isLinkEditing(): Boolean = isUrlEditing

    private fun formatUrl(url: String): String {
        return when {
            url.startsWith("http://") || url.startsWith("https://") || url.startsWith("file://") -> url
            url.contains(".") -> "https://$url"
            else -> "https://www.google.com/search?q=${Uri.encode(url)}"
        }
    }

    // ── AR Navigation interception ────────────────────────────────────────

    private fun isAddressOrMapsUrl(url: String): Boolean {
        val lower = url.lowercase()
        if (lower.startsWith("file://")) return false  // local asset pages (e.g. multi_pin_map.html)
        return lower.contains("maps.google.com") ||
               lower.contains("google.com/maps") ||
               lower.contains("maps.app.goo.gl") ||
               lower.contains("goo.gl/maps") ||
               lower.contains("waze.com/ul") ||
               lower.startsWith("geo:") ||
               lower.contains("/maps/dir/") ||
               lower.contains("/maps/place/") ||
               lower.contains("/maps/search")
    }

    /**
     * Aggressively stop all audio/video playback across ALL WebView instances.
     * Pauses and mutes all media elements, clears their src, and stops loading.
     */
    private fun killAllWebViewAudio() {
        try {
            val killJs = """
                (function(){
                    document.querySelectorAll('video,audio,iframe').forEach(function(v){
                        try{
                            if(v.tagName==='IFRAME'){v.src='about:blank';return;}
                            v.pause();v.muted=true;v.src='';v.load();
                        }catch(e){}
                    });
                    try{
                        var ctx=window.AudioContext||window.webkitAudioContext;
                        if(window._audioCtx){window._audioCtx.close();}
                    }catch(e){}
                })();
            """.trimIndent()

            if (::dualWebViewGroup.isInitialized) {
                dualWebViewGroup.getAllWebViews().forEach { wv ->
                    wv.stopLoading()
                    wv.evaluateJavascript(killJs, null)
                    // Android-level pause stops all timers, JS execution, plugins/media
                    wv.onPause()
                }
                // Resume the primary webView shortly since it needs to load ar_nav
                dualWebViewGroup.getAllWebViews().firstOrNull()?.postDelayed({
                    dualWebViewGroup.getAllWebViews().forEach { it.onResume() }
                }, 100)
            }
            // Request transient audio focus to interrupt system-level playback,
            // then abandon after a brief delay so the system properly processes the interruption
            try {
                val am = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)
                val focusListener = AudioManager.OnAudioFocusChangeListener { /* no-op for kill */ }
                am?.requestAudioFocus(focusListener, AudioManager.STREAM_MUSIC, AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                // Abandon after 200ms to let the system process the interruption
                android.os.Handler(Looper.getMainLooper()).postDelayed({
                    am?.abandonAudioFocus(focusListener)
                }, 200)
            } catch (_: Exception) {}

            DebugLog.d("ARNav", "killAllWebViewAudio: killed audio on all WebViews")
        } catch (e: Exception) {
            DebugLog.e("ARNav", "killAllWebViewAudio error", e)
        }
    }

    private fun buildArNavUrl(originalUrl: String): String {
        val dest = extractDestinationFromUrl(originalUrl)
        val searchQuery = extractTaplinkSearchQueryFromUrl(originalUrl)
        val googleKey = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
            .getString("google_maps_api_key", "") ?: ""
        val explicitOrigin = extractOriginCoordsFromUrl(originalUrl)
        val lat = explicitOrigin?.first ?: (lastGpsLat ?: 0.0)
        val lng = explicitOrigin?.second ?: (lastGpsLon ?: 0.0)
        val originLocked = if (explicitOrigin != null) 1 else 0
        DebugLog.d("ARNav", "buildArNavUrl: originalUrl='${originalUrl.take(200)}'")
        DebugLog.d("ARNav", "  dest='$dest' search='${searchQuery ?: ""}' lat=$lat lng=$lng originLocked=$originLocked gkey=${if (googleKey.isNotBlank()) googleKey.take(8) + "..." else "MISSING"}")
        // ar_nav.html renders a full 3D photorealistic route overview
        return "file:///android_asset/ar_nav.html" +
               "?dest=${Uri.encode(dest)}" +
               "&search=${Uri.encode(searchQuery ?: "")}" +
               "&gkey=${Uri.encode(googleKey)}" +
               "&lat=$lat" +
               "&lng=$lng" +
               "&origin_locked=$originLocked"
    }

    private fun extractOriginCoordsFromUrl(url: String): Pair<Double, Double>? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        for (param in listOf("origin", "saddr")) {
            val raw = uri.getQueryParameter(param)?.trim().orEmpty()
            if (raw.isBlank()) continue
            parseLatLng(raw)?.let { return it }
        }
        return null
    }

    private fun extractTaplinkSearchQueryFromUrl(url: String): String? {
        val uri = runCatching { Uri.parse(url) }.getOrNull() ?: return null
        return uri.getQueryParameter("taplink_query")
            ?.trim()
            ?.takeIf { it.isNotBlank() }
    }

    private fun parseLatLng(raw: String): Pair<Double, Double>? {
        val match = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*,\s*(-?\d+(?:\.\d+)?)\s*$""")
            .find(Uri.decode(raw)) ?: return null
        val lat = match.groupValues[1].toDoubleOrNull() ?: return null
        val lng = match.groupValues[2].toDoubleOrNull() ?: return null
        if (lat !in -90.0..90.0 || lng !in -180.0..180.0) return null
        return lat to lng
    }

    private fun extractDestinationFromUrl(url: String): String {
        var raw: String? = null
        var extractMethod = "none"
        try {
            val uri = Uri.parse(url)
            if (uri.scheme == "geo") {
                val q = uri.getQueryParameter("q")
                if (!q.isNullOrBlank()) { raw = q; extractMethod = "geo:q" }
                else {
                    val ssp = uri.schemeSpecificPart?.substringBefore('?')
                    if (!ssp.isNullOrBlank()) { raw = ssp; extractMethod = "geo:ssp" }
                }
            }
            if (raw == null) {
                for (param in listOf("q", "query", "daddr", "destination")) {
                    val v = uri.getQueryParameter(param)
                    if (!v.isNullOrBlank()) { raw = v; extractMethod = "param:$param"; break }
                }
            }
            if (raw == null) {
                val path = uri.path ?: ""
                val placeMatch = Regex("/maps/place/([^/@]+)").find(path)
                if (placeMatch != null) {
                    raw = Uri.decode(placeMatch.groupValues[1]).replace("+", " ")
                    extractMethod = "path:place"
                }
            }
            if (raw == null) {
                val path = uri.path ?: ""
                val dirMatch = Regex("/maps/dir/[^/]+/([^/@]+)").find(path)
                if (dirMatch != null) {
                    raw = Uri.decode(dirMatch.groupValues[1]).replace("+", " ")
                    extractMethod = "path:dir"
                }
            }
        } catch (e: Exception) {
            DebugLog.e("ARNav", "extractDestinationFromUrl parse error", e)
        }
        DebugLog.d("ARNav", "extractDestinationFromUrl: method=$extractMethod raw='${(raw ?: url).take(120)}'")
        return cleanAddressText(raw ?: url)
    }

    /** Strip conversational chat text so the geocoder gets a clean destination query. */
    private fun cleanAddressText(text: String): String {
        var c = text.trim()
            .replace(Regex("""\s+"""), " ")
            .removePrefix("→")
            .trim()

        val addressRegex = Regex(
            """\b\d{1,5}\s+[A-Za-z0-9.'#\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b(?:,\s*[A-Za-z .'-]+){0,3}""",
            RegexOption.IGNORE_CASE
        )
        addressRegex.find(c)?.value?.trim()?.trimEnd('.', ',', ';', ':')?.let {
            DebugLog.d("ARNav", "cleanAddressText[address]: '$text' → '$it'")
            return it
        }

        val patterns = listOf(
            Regex("""\baddress:\s*(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:is\s+)?(?:located|location)\s+at\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""\bis\s+at\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(c)
            if (match != null) {
                c = match.groupValues[1].trim()
                break
            }
        }

        val imp = Regex("""^(?:find|visit|go\s+to|head\s+to|navigate\s+to|directions?\s+to)\s+(.+)""", RegexOption.IGNORE_CASE).find(c)
        if (imp != null) c = imp.groupValues[1].trim()

        c = c
            .replace(Regex("""\b(?:currently\s+)?(?:open\s*now|openow|closed|closednow)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:clear|cloudy|overcast|rain|showers|fog|drizzle|snow|storm)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bAQI\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d{1,3}°\s*[FC]\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:walk|drive|transit|eta|parking|weather|temperature)\b.*$""", RegexOption.IGNORE_CASE), "")

        listOf(" — ", " - ", " | ", ". ").forEach { separator ->
            val idx = c.indexOf(separator)
            if (idx > 5) c = c.substring(0, idx)
        }

        c = c.trim().trimEnd('.', ',', ';', ' ')
        DebugLog.d("ARNav", "cleanAddressText: '$text' → '$c'")
        return c
    }

    private fun isStreamingSite(url: String?): Boolean {
        if (url == null) return false
        val streamingDomains =
                listOf(
                        "netflix.com",
                        "disneyplus.com",
                        "hulu.com",
                        "primevideo.com",
                        "amazon.com/gp/video",
                        "max.com",
                        "peacocktv.com",
                        "apple.com/tv",
                        "tv.apple.com",
                        "tubitv.com",
                        "pluto.tv",
                        "paramountplus.com",
                        "discoveryplus.com"
                )
        return streamingDomains.any { url.contains(it, ignoreCase = true) }
    }

    private fun initializeSpeechRecognition() {
        // Check if speech recognition is available
        val isAvailable = SpeechRecognizer.isRecognitionAvailable(this)
        DebugLog.d("SpeechRecognition", "Recognition available: $isAvailable")

        if (!isAvailable) {
            DebugLog.w("SpeechRecognition", "Speech recognition not available on this device")
            return
        }

        try {
            speechRecognizer =
                    SpeechRecognizer.createSpeechRecognizer(this).apply {
                        setRecognitionListener(
                                object : RecognitionListener {
                                    override fun onResults(results: Bundle?) {
                                        isListeningForSpeech = false
                                        results?.getStringArrayList(
                                                        SpeechRecognizer.RESULTS_RECOGNITION
                                                )
                                                ?.let { matches ->
                                                    if (matches.isNotEmpty()) {
                                                        val text = matches[0]
                                                        runOnUiThread {
                                                            onShowKeyboardForEdit(text)
                                                            // Handle inserting text based on what
                                                            // input is focused
                                                            val editFieldVisible =
                                                                    dualWebViewGroup
                                                                            .urlEditText
                                                                            .visibility ==
                                                                            View.VISIBLE

                                                            when {
                                                                dualWebViewGroup
                                                                        .isBookmarksExpanded() &&
                                                                        !editFieldVisible -> {
                                                                    // Handle bookmark menu
                                                                    // navigation - maybe search
                                                                    // bookmarks?
                                                                    // For now just toast or ignore
                                                                }
                                                                editFieldVisible -> {
                                                                    // Handle any edit field input
                                                                    // (URL or bookmark)
                                                                    val currentText =
                                                                            dualWebViewGroup
                                                                                    .getCurrentLinkText()
                                                                    val cursorPosition =
                                                                            dualWebViewGroup
                                                                                    .urlEditText
                                                                                    .selectionStart
                                                                    // Insert the text at cursor
                                                                    // position
                                                                    val newText =
                                                                            StringBuilder(
                                                                                            currentText
                                                                                    )
                                                                                    .insert(
                                                                                            cursorPosition,
                                                                                            text
                                                                                    )
                                                                                    .toString()

                                                                    // Set text and move cursor
                                                                    // after inserted text
                                                                    dualWebViewGroup.setLinkText(
                                                                            newText,
                                                                            cursorPosition +
                                                                                    text.length
                                                                    )
                                                                }
                                                                dualWebViewGroup.getDialogInput() !=
                                                                        null -> {
                                                                    val input =
                                                                            dualWebViewGroup
                                                                                    .getDialogInput()!!
                                                                    val currentText =
                                                                            input.text.toString()
                                                                    val cursorPosition =
                                                                            input.selectionStart
                                                                    val newText =
                                                                            StringBuilder(
                                                                                            currentText
                                                                                    )
                                                                                    .insert(
                                                                                            cursorPosition,
                                                                                            text
                                                                                    )
                                                                                    .toString()
                                                                    input.setText(newText)
                                                                    input.setSelection(
                                                                            cursorPosition +
                                                                                    text.length
                                                                    )
                                                                }
                                                                else -> {
                                                                    sendTextToWebView(text)
                                                                }
                                                            }
                                                        }
                                                    }
                                                }
                                    }

                                    // Implement other RecognitionListener methods with empty bodies
                                    override fun onReadyForSpeech(params: Bundle?) {
                                        DebugLog.d("SpeechRecognition", "Ready for speech")
                                        dualWebViewGroup.showToast("Listening...")
                                    }
                                    override fun onBeginningOfSpeech() {
                                        DebugLog.d("SpeechRecognition", "Speech started")
                                    }
                                    override fun onRmsChanged(rmsdB: Float) {}
                                    override fun onBufferReceived(buffer: ByteArray?) {}
                                    override fun onEndOfSpeech() {
                                        DebugLog.d("SpeechRecognition", "Speech ended")
                                        isListeningForSpeech = false
                                    }
                                    override fun onError(error: Int) {
                                        isListeningForSpeech = false
                                        val errorMsg =
                                                when (error) {
                                                    SpeechRecognizer.ERROR_NETWORK_TIMEOUT ->
                                                            "Network timeout"
                                                    SpeechRecognizer.ERROR_NETWORK ->
                                                            "Network error"
                                                    SpeechRecognizer.ERROR_AUDIO ->
                                                            "Audio recording error"
                                                    SpeechRecognizer.ERROR_SERVER -> "Server error"
                                                    SpeechRecognizer.ERROR_CLIENT ->
                                                            "Speech service unavailable"
                                                    SpeechRecognizer.ERROR_SPEECH_TIMEOUT ->
                                                            "No speech detected"
                                                    SpeechRecognizer.ERROR_NO_MATCH ->
                                                            "No match found"
                                                    SpeechRecognizer.ERROR_RECOGNIZER_BUSY ->
                                                            "Recognizer busy"
                                                    SpeechRecognizer
                                                            .ERROR_INSUFFICIENT_PERMISSIONS ->
                                                            "Permission denied"
                                                    else -> "Error: $error"
                                                }
                                        DebugLog.e("SpeechRecognition", "Error: $error ($errorMsg)")
                                        dualWebViewGroup.showToast(errorMsg)
                                    }
                                    override fun onPartialResults(partialResults: Bundle?) {}
                                    override fun onEvent(eventType: Int, params: Bundle?) {}
                                }
                        )
                    }
            DebugLog.d("SpeechRecognition", "SpeechRecognizer created successfully")
        } catch (e: Exception) {
            DebugLog.e("SpeechRecognition", "Failed to create SpeechRecognizer", e)
            speechRecognizer = null
        }
    }

    fun hideCustomKeyboard() {
        DebugLog.d("KeyboardDebug", "Hiding keyboard")

        // First blur any focused element
        webView.evaluateJavascript(
                """
       (function() {
           const activeElement = document.activeElement;
           if (activeElement && activeElement !== document.body) {
               activeElement.blur();
               // For React/custom components that might need extra cleanup
               const event = new Event('blur', { bubbles: true });
               activeElement.dispatchEvent(event);
           }
       })();
       """,
                null
        )

        // First handle cleanup of keyboard state
        keyboardView?.visibility = View.GONE
        isKeyboardVisible = false
        keyboardView?.let { dualWebViewGroup.setKeyboard(it) }

        // Show info bars when keyboard hides
        dualWebViewGroup.showInfoBars()

        // Reset interaction states
        isSimulatingTouchEvent = false
        cursorJustAppeared = false
        isToggling = false

        // Instruct DualWebViewGroup to hide the link field
        dualWebViewGroup.hideLinkEditing()

        // Clean up input state
        webView.evaluateJavascript(
                """
        (function() {
            var activeElement = document.activeElement;
            if (activeElement) {
                activeElement.blur();
            }
        })();
    """,
                null
        )

        // Notify DualWebViewGroup about keyboard being hidden
        dualWebViewGroup.onKeyboardHidden()

        // Restore original webView state
        webView.translationY = 0f

        // Clear any existing animations
        webView.clearAnimation()

        // Force layout update
        webView.requestLayout()
        webView.parent?.requestLayout()

        // Show cursor if not in URL editing mode

        isUrlEditing = false

        dualWebViewGroup.post { dualWebViewGroup.updateScrollBarsVisibility() }

        dualWebViewGroup.cleanupResources()
    }

    override fun onClearPressed() {
        when {
            dualWebViewGroup.isBookmarksExpanded() &&
                    dualWebViewGroup.urlEditText.visibility != View.VISIBLE -> {
                // Handle bookmark menu navigation
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("clear")
            }
            dualWebViewGroup.urlEditText.visibility == View.VISIBLE -> {
                // Clear edit field for both URL and bookmark editing
                dualWebViewGroup.setLinkText("")
            }
            dualWebViewGroup.getDialogInput() != null -> {
                dualWebViewGroup.getDialogInput()?.setText("")
            }
            else -> {
                // Preserve existing JavaScript functionality for web content
                runOnUiThread {
                    webView.evaluateJavascript(
                            """
    (function() {
        var el = document.activeElement;
        if (!el) {
            console.log('No active element found');
            return null;
        }
        
        function simulateClearInput(element) {
            // Start composition
            const compStart = new Event('compositionstart', { bubbles: true });
            element.dispatchEvent(compStart);
            
            // Create beforeinput event
            const beforeInputEvent = new InputEvent('beforeinput', {
                bubbles: true,
                cancelable: true,
                inputType: 'deleteContent',
                data: null
            });
            element.dispatchEvent(beforeInputEvent);
            
            if (!beforeInputEvent.defaultPrevented) {
                // Use execCommand for deletion
                if (document.execCommand) {
                    // First select all
                    document.execCommand('selectAll', false);
                    // Then delete selection
                    document.execCommand('delete', false);
                }
                
                // Dispatch native input event
                const nativeInputEvent = new Event('input', { bubbles: true });
                element.dispatchEvent(nativeInputEvent);
            }
            
            // End composition
            const compEnd = new Event('compositionend', { bubbles: true });
            element.dispatchEvent(compEnd);
            
            // Handle React components
            if (element._valueTracker) {
                element._valueTracker.setValue('');
                element.dispatchEvent(new Event('input', { bubbles: true }));
            }
            
            return {
                success: true,
                type: element.type
            };
        }
        
        return JSON.stringify(simulateClearInput(el));
    })();
    """,
                            null
                    )
                }
            }
        }
    }

    override fun onMoveCursorLeft() {
        runOnUiThread { moveCursor(-1) }
    }

    override fun onMoveCursorRight() {
        runOnUiThread { moveCursor(1) }
    }

    private var isListeningForSpeech = false
    private var groqAudioService: GroqAudioService? = null
    private var lastMicPressTime = 0L

    private fun setVoiceAssistantAudioRoute(enabled: Boolean) {
        val value = if (enabled) "voiceassistant" else "off"
        try {
            audioManager?.mode = AudioManager.MODE_NORMAL
            audioManager?.adjustStreamVolume(
                    AudioManager.STREAM_MUSIC,
                    AudioManager.ADJUST_UNMUTE,
                    0
            )
            audioManager?.setParameters("audio_source_record=$value")
            DebugLog.d("AudioRoute", "audio_source_record=$value")
        } catch (e: Exception) {
            DebugLog.e("AudioRoute", "Failed to set audio route: $value", e)
        }
    }

    fun prepareAudioForTtsPlayback() {
        runOnUiThread { setVoiceAssistantAudioRoute(true) }
    }

    override fun onMicrophonePressed() {
        val currentTime = System.currentTimeMillis()
        if (currentTime - lastMicPressTime < 500) {
            DebugLog.d("SpeechRecognition", "Ignoring rapid microphone press")
            return
        }
        lastMicPressTime = currentTime

        DebugLog.d(
                "SpeechRecognition",
                "onMicrophonePressed called, isListening: $isListeningForSpeech"
        )
        runOnUiThread {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                DebugLog.d("SpeechRecognition", "Requesting audio permission")
                requestPermissions(
                        arrayOf(Manifest.permission.RECORD_AUDIO),
                        PERMISSIONS_REQUEST_CODE
                )
                return@runOnUiThread
            }

            if (groqAudioService == null) {
                initializeGroqService()
            }

            if (!groqAudioService!!.hasApiKey()) {
                showGroqKeyDialog()
                return@runOnUiThread
            }

            if (groqAudioService!!.isRecording()) {
                // Stop listening
                DebugLog.d("SpeechRecognition", "Stopping Groq recording")
                groqAudioService?.stopRecording()
                setVoiceAssistantAudioRoute(false)
                dualWebViewGroup.showToast("Processing...")
            } else {
                // Start listening
                DebugLog.d("SpeechRecognition", "Starting Groq recording")
                setVoiceAssistantAudioRoute(true)
                groqAudioService?.startRecording()
                dualWebViewGroup.showToast("Listening...")
            }
        }
    }

    fun showGroqKeyDialog() {
        if (groqAudioService == null) {
            initializeGroqService()
        }
        val currentKey = groqAudioService?.getApiKey()
        dualWebViewGroup.showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    groqAudioService?.setApiKey(key)
                    dualWebViewGroup.showToast("API Key Saved")
                    hideCustomKeyboard()
                },
                { dualWebViewGroup.showToast("API Key Required for Voice") }
        )
    }

    private fun initializeGroqService() {
        groqAudioService =
                GroqAudioService(this).apply {
                    setListener(
                            object : GroqAudioService.TranscriptionListener {
                                override fun onTranscriptionResult(text: String) {
                                    DebugLog.d("SpeechRecognition", "Groq result: $text")
                                    runOnUiThread {
                                        // Restore playback route after recording so chat TTS is
                                        // audible.
                                        setVoiceAssistantAudioRoute(true)
                                        // If chat is visible, insert text there
                                        if (dualWebViewGroup.isChatVisible()) {
                                            dualWebViewGroup.insertVoiceToChatInput(text)
                                        } else {
                                            handleVoiceResult(text)
                                        }
                                        dualWebViewGroup.showToast("Success")
                                        keyboardView?.setMicActive(false)
                                        dualWebViewGroup.setChatMicActive(false)
                                    }
                                }

                                override fun onError(message: String) {
                                    DebugLog.e("SpeechRecognition", "Groq error: $message")
                                    runOnUiThread {
                                        setVoiceAssistantAudioRoute(false)
                                        dualWebViewGroup.showToast("Voice Error: $message")
                                        keyboardView?.setMicActive(false)
                                        dualWebViewGroup.setChatMicActive(false)
                                        if (message.contains("No API Key")) {
                                            showGroqKeyDialog()
                                        }
                                    }
                                }

                                override fun onRecordingStart() {
                                    DebugLog.d("SpeechRecognition", "Groq recording started")
                                    runOnUiThread {
                                        isListeningForSpeech = true
                                        keyboardView?.setMicActive(true)
                                        dualWebViewGroup.setChatMicActive(true)
                                    }
                                }

                                override fun onRecordingStop() {
                                    DebugLog.d("SpeechRecognition", "Groq recording stopped")
                                    runOnUiThread {
                                        isListeningForSpeech = false
                                        // Don't turn off mic indicator yet, wait for processing
                                        // result
                                    }
                                }
                            }
                    )
                }
    }

    private fun handleVoiceResult(text: String) {
        if (text.isBlank()) return

        onShowKeyboardForEdit(text)
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                // Handle bookmark menu navigation - maybe search bookmarks?
                // For now just ignore
            }
            editFieldVisible -> {
                // Handle URL/bookmark edit field input
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart.coerceAtLeast(0)
                val newText = StringBuilder(currentText).insert(cursorPosition, text).toString()
                dualWebViewGroup.setLinkText(newText, cursorPosition + text.length)
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart.coerceAtLeast(0)
                val newText = StringBuilder(currentText).insert(cursorPosition, text).toString()
                input.setText(newText)
                input.setSelection((cursorPosition + text.length).coerceAtMost(newText.length))
            }
            else -> {
                sendTextToWebView(text)
            }
        }

        isListeningForSpeech = false
    }

    private fun moveCursor(offset: Int) {
        val focusedView = currentFocus
        if (focusedView != null) {
            val inputConnection = BaseInputConnection(focusedView, true)
            val now = SystemClock.uptimeMillis()
            val keyCode =
                    if (offset < 0) KeyEvent.KEYCODE_DPAD_LEFT else KeyEvent.KEYCODE_DPAD_RIGHT
            val keyEventDown = KeyEvent(now, now, KeyEvent.ACTION_DOWN, keyCode, 0)
            val keyEventUp = KeyEvent(now, now, KeyEvent.ACTION_UP, keyCode, 0)
            inputConnection.sendKeyEvent(keyEventDown)
            inputConnection.sendKeyEvent(keyEventUp)
        } else {
            DebugLog.d("MainActivity", "No focused view to move cursor")
        }
    }

    /**
     * Sync dashboard data between SharedPreferences (companion app) and
     * the WebView's localStorage used by the AR Dashboard HTML.
     *
     * 1. If SharedPreferences has data from the companion editor, write it
     *    into localStorage and re-render the dashboard.
     * 2. Override the dashboard's `persistState` to also write back to
     *    SharedPreferences via AndroidInterface so edits on the glasses
     *    are visible to the companion app.
     */
    private fun injectDashboardSync(view: WebView?) {
        view?.evaluateJavascript("""
        (function() {
            var KEY = 'dashboardLinksV1';
            function ensureTapRadio(parsed) {
                var changed = false;
                parsed.apps = parsed.apps || {};
                parsed.groups = Array.isArray(parsed.groups) ? parsed.groups : [];
                if (!parsed.apps.tapradio) {
                    parsed.apps.tapradio = { name: 'TapRadio', url: 'file:///android_asset/radio.html' };
                    changed = true;
                }
                var music = parsed.groups.find(function(group) {
                    return String((group && group.title) || '').trim().toLowerCase() === 'music / streaming';
                });
                if (!music) {
                    music = { title: 'Music / Streaming', cls: 'sec-music', keys: ['tapradio'] };
                    parsed.groups.push(music);
                    changed = true;
                }
                if (!Array.isArray(music.keys)) {
                    music.keys = [];
                    changed = true;
                }
                if (!music.keys.includes('tapradio')) {
                    music.keys.unshift('tapradio');
                    changed = true;
                }
                return changed;
            }
            // Pull companion-edited data from SharedPreferences
            var saved = '';
            try { saved = window.AndroidInterface.getDashboardData(); } catch(e) {}
            if (saved && saved.length > 2) {
                try {
                    var parsed = JSON.parse(saved);
                    if (parsed.apps && parsed.groups) {
                        var changed = ensureTapRadio(parsed);
                        var serialized = JSON.stringify(parsed);
                        localStorage.setItem(KEY, serialized);
                        if (changed && window.AndroidInterface) {
                            window.AndroidInterface.saveDashboardData(serialized);
                        }
                        // Update the in-memory state and re-render
                        if (typeof state !== 'undefined') {
                            state.apps = parsed.apps;
                            state.groups = parsed.groups;
                            if (typeof renderAll === 'function') renderAll();
                        }
                    }
                } catch(e) { console.error('[Dashboard] Sync parse error:', e); }
            }
            // Hook persistState to also write back to SharedPreferences
            var origPersist = (typeof persistState === 'function') ? persistState : null;
            window.persistState = function() {
                if (origPersist) origPersist();
                try {
                    var data = localStorage.getItem(KEY);
                    if (data && window.AndroidInterface) {
                        window.AndroidInterface.saveDashboardData(data);
                    }
                } catch(e) {}
            };
        })();
        """.trimIndent(), null)
    }

    fun injectJavaScriptForInputFocus() {
        webView.evaluateJavascript(
                """
    (function() {
        // Store state about known popups to prevent re-triggering
        const knownPopups = new WeakSet();
        
        function canActuallyInputText(element) {
            try {
                // If we've previously identified this as part of a popup, skip input checks
                if (knownPopups.has(element)) {
                    console.log('Element is part of known popup, skipping input checks');
                    return false;
                }

                // First check if it's a popup/menu element
                if (element.getAttribute('aria-haspopup') === 'true' ||
                    element.getAttribute('aria-expanded') === 'false' ||
                    element.getAttribute('role') === 'button' ||
                    element.getAttribute('role') === 'menu' ||
                    element.getAttribute('role') === 'menuitem') {
                    console.log('Element identified as popup/menu');
                    
                    // Mark this and its children as known popup elements
                    knownPopups.add(element);
                    element.querySelectorAll('*').forEach(child => knownPopups.add(child));
                    
                    return false;
                }

                // Rest of the input detection code remains the same
                if (element instanceof HTMLInputElement) {
                    const textInputTypes = ['text', 'email', 'password', 'search', 'tel', 'url', 'number'];
                    return textInputTypes.includes(element.type);
                }
                
                if (element instanceof HTMLTextAreaElement) return true;
                if (element.isContentEditable) return true;

                return false;
            } catch (e) {
                console.log('Input validation error: ' + e.toString());
                return false;
            }
        }

        // Function to handle clicks
        function handleClick(event) {
            console.log('Click event detected');
            let target = event.target;
            let currentNode = target;
            
            // Log the click path
            console.log('Click path:', {
                targetTag: target.tagName,
                targetClass: target.className,
                targetRole: target.getAttribute('role')
            });
            
            while (currentNode && currentNode !== document.body) {
                if (canActuallyInputText(currentNode)) {
                    console.log('Found input-capable element');
                    window.Android?.onInputFocus();
                    break;
                }
                currentNode = currentNode.parentElement;
            }
        }

        // Remove any existing listeners to prevent duplicates
        document.removeEventListener('click', handleClick, true);
        
        // Add the click listener
        document.addEventListener('click', handleClick, true);

        // Set up a more robust mutation observer
        const observer = new MutationObserver((mutations) => {
            mutations.forEach((mutation) => {
                // For any new nodes, check if they're part of a popup
                mutation.addedNodes.forEach((node) => {
                    if (node.nodeType === 1) { // ELEMENT_NODE
                        if (node.getAttribute('role') === 'menu' ||
                            node.getAttribute('role') === 'dialog' ||
                            node.getAttribute('aria-haspopup') === 'true') {
                            console.log('New popup/menu element detected');
                            knownPopups.add(node);
                            // Mark all children as part of the popup
                            node.querySelectorAll('*').forEach(child => knownPopups.add(child));
                        }
                    }
                });
            });
        });

        observer.observe(document.body, {
            childList: true,
            subtree: true,
            attributes: true,
            attributeFilter: ['role', 'aria-haspopup']
        });
    })();
    """,
                null
        )
    }

    private fun sendCharacterToWebView(character: String) {
        sendTextToWebView(character)
    }

    private fun sendTextToWebView(text: String) {
        if (dualWebViewGroup.isUrlEditing()) {
            // For now only supports single character for link editing in current impl,
            // but we can loop if needed or assume sendCharacterToLinkEditText works for chars.
            // But this method is generic.
            // If text is longer than 1 char, we should handle it.
            // The existing sendCharacterToLinkEditText handles single char.
            // Let's iterate if it's multiple chars or just insert if we make a
            // sendTextToLinkEditText
            text.forEach { char -> sendCharacterToLinkEditText(char.toString()) }
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendTextToChatInput(text)
            return
        } else {
            webView.evaluateJavascript(
                    """
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Create a composition event to better simulate natural typing
            function simulateNaturalInput(element, text) {
                // First, create a compositionstart event
                const compStart = new Event('compositionstart', { bubbles: true });
                element.dispatchEvent(compStart);
                
                // Then create main input event with the data
                const inputEvent = new InputEvent('input', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text,
                    composed: true
                });
                
                // Store original value
                const originalValue = element.value || '';
                
                // Create a beforeinput event
                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'insertText',
                    data: text
                });
                element.dispatchEvent(beforeInputEvent);
                
                if (!beforeInputEvent.defaultPrevented) {
                    // Let the browser handle the input naturally
                    const nativeInputEvent = new Event('input', { bubbles: true });
                    element.dispatchEvent(nativeInputEvent);
                    
                    // Use execCommand for more natural insertion
                    if (document.execCommand) {
                        document.execCommand('insertText', false, text);
                    } else {
                        // Fallback: try to preserve cursor position
                        const start = element.selectionStart;
                        const end = element.selectionEnd;
                        element.value = originalValue.slice(0, start) + 
                                      text +
                                      originalValue.slice(end);
                    }
                }
                
                // Finally dispatch composition end
                const compEnd = new Event('compositionend', { bubbles: true });
                element.dispatchEvent(compEnd);
                
                // Ensure React and other frameworks pick up the change
                if (element._valueTracker) {
                    element._valueTracker.setValue(originalValue);
                    element.dispatchEvent(new Event('input', { bubbles: true }));
                }
                
                return {
                    success: true,
                    type: element.type,
                    originalValue: originalValue,
                    newValue: element.value
                };
            }
            
            return JSON.stringify(simulateNaturalInput(el, ${JSONObject.quote(text)}));
        })();
        """
            ) { result -> DebugLog.d("InputDebug", "JavaScript result: $result") }
        }
    }

    private fun sendBackspaceToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendBackspaceInLinkEditText()
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendBackspaceToChatInput()
            return
        } else {
            webView.evaluateJavascript(
                    """
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Capture initial state for verification
            const initialState = {
                value: el.value,
                selectionStart: el.selectionStart,
                selectionEnd: el.selectionEnd
            };
            console.log('Initial state:', JSON.stringify(initialState));
            
            function simulateNaturalBackspace(element) {
                // Signal the upcoming deletion
                const beforeInputEvent = new InputEvent('beforeinput', {
                    bubbles: true,
                    cancelable: true,
                    inputType: 'deleteContentBackward'
                });
                element.dispatchEvent(beforeInputEvent);
                
                if (!beforeInputEvent.defaultPrevented) {
                    let deletionSuccessful = false;
                    const originalValue = element.value;
                    
                    // Method 1: Try execCommand first
                    if (!deletionSuccessful && document.execCommand) {
                        try {
                            document.execCommand('delete', false);
                            // Verify if deletion worked
                            deletionSuccessful = element.value !== originalValue;
                            console.log('execCommand method:', deletionSuccessful ? 'succeeded' : 'failed');
                        } catch (e) {
                            console.log('execCommand failed:', e);
                        }
                    }
                    
                    // Method 2: Try keyboard events if execCommand didn't work
                    if (!deletionSuccessful) {
                        const backspaceKey = new KeyboardEvent('keydown', {
                            key: 'Backspace',
                            code: 'Backspace',
                            keyCode: 8,
                            which: 8,
                            bubbles: true,
                            cancelable: true
                        });
                        element.dispatchEvent(backspaceKey);
                        
                        // Verify if keyboard event worked
                        deletionSuccessful = element.value !== originalValue;
                        console.log('Keyboard event method:', deletionSuccessful ? 'succeeded' : 'failed');
                    }
                    
                    // Method 3: Manual manipulation as last resort
                    if (!deletionSuccessful) {
                        const start = element.selectionStart;
                        const end = element.selectionEnd;
                        
                        if (start === end && start > 0) {
                            // Delete single character
                            element.value = element.value.substring(0, start - 1) + 
                                          element.value.substring(end);
                            element.setSelectionRange(start - 1, start - 1);
                            deletionSuccessful = true;
                            console.log('Manual deletion succeeded');
                        } else if (start !== end) {
                            // Delete selection
                            element.value = element.value.substring(0, start) + 
                                          element.value.substring(end);
                            element.setSelectionRange(start, start);
                            deletionSuccessful = true;
                            console.log('Manual selection deletion succeeded');
                        }
                    }
                    
                    // Only dispatch input event if we actually made changes
                    if (deletionSuccessful) {
                        element.dispatchEvent(new Event('input', { bubbles: true }));
                        
                        // Handle React components
                        if (element._valueTracker) {
                            element._valueTracker.setValue('');
                            element.dispatchEvent(new Event('input', { bubbles: true }));
                        }
                    }
                }
                
                // Capture final state for verification
                const finalState = {
                    value: el.value,
                    selectionStart: el.selectionStart,
                    selectionEnd: el.selectionEnd
                };
                console.log('Final state:', JSON.stringify(finalState));
                
                return {
                    success: true,
                    initialState: initialState,
                    finalState: finalState
                };
            }
            
            return JSON.stringify(simulateNaturalBackspace(el));
        })();
        """
            ) { result -> DebugLog.d("InputDebug", "Backspace JavaScript result: $result") }
        }
    }

    private fun suppressImmediateWebClickLeak() {
        suppressWebClickUntil = SystemClock.uptimeMillis() + 250L
    }

    private fun dispatchTouchEventAtCursor() {

        if (isSimulatingTouchEvent || cursorJustAppeared || isToggling) {
            return
        }

        // Don't let the enter key tap pass through to the webview when keyboard closes
        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            return
        }

        // Suppress any webview click immediately after keyboard dismissal (hide button).
        val now = SystemClock.uptimeMillis()
        if (now < suppressWebClickUntil) {
            return
        }

        val scale = dualWebViewGroup.uiScale
        val interactionX: Float
        val interactionY: Float
        val groupLocation = IntArray(2)
        dualWebViewGroup.getLocationOnScreen(groupLocation)

        if (isAnchored) {
            // In anchored mode, interaction center is always screen center of the eye
            interactionX = 320f + groupLocation[0]
            interactionY = 240f + groupLocation[1]
        } else {
            // In non-anchored mode, interaction follows the visual cursor scaled around (320, 240)
            // and translated
            val transX = dualWebViewGroup.leftEyeUIContainer.translationX
            val transY = dualWebViewGroup.leftEyeUIContainer.translationY

            val visualX = 320f + (lastCursorX - 320f) * scale + transX
            val visualY = 240f + (lastCursorY - 240f) * scale + transY

            interactionX = visualX + groupLocation[0]
            interactionY = visualY + groupLocation[1]
        }

        // Intercept touches for mask overlay buttons when screen is masked
        if (dualWebViewGroup.isScreenMasked()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchMaskOverlayTouch(interactionX, interactionY)
            return
        }

        // Intercept touches for fullscreen overlay controls
        if (dualWebViewGroup.isFullScreenOverlayVisible()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchFullScreenOverlayTouch(interactionX, interactionY)
            return
        }

        // Intercept touches for dialogs
        if (dualWebViewGroup.isDialogAction(interactionX, interactionY)) {
            val dialogContainer = dualWebViewGroup.dialogContainer
            val location = IntArray(2)
            dialogContainer.getLocationOnScreen(location)

            // Calculate local coordinates relative to dialog container
            val localX = (interactionX - location[0]) / scale
            val localY = (interactionY - location[1]) / scale

            // Dispatch DOWN
            val downEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            // Dispatch UP
            val upEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            suppressImmediateWebClickLeak()
            return
        }

        // Check if settings menu is visible first
        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()

            if (interactionX >= settingsMenuLocation[0] &&
                            interactionX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            interactionY >= settingsMenuLocation[1] &&
                            interactionY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {

                // Dispatch touch event to settings menu using screen coordinates
                suppressImmediateWebClickLeak()
                dualWebViewGroup.dispatchSettingsTouchEvent(interactionX, interactionY)
                return
            }
        }

        // Check for restore button click
        if (dualWebViewGroup.isPointInRestoreButton(interactionX, interactionY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.performRestoreButtonClick()
            return
        }

        if (dualWebViewGroup.isChatVisible()) {
            if (dualWebViewGroup.isPointInChat(interactionX, interactionY)) {
                suppressImmediateWebClickLeak()
                dualWebViewGroup.dispatchChatTouchEvent(interactionX, interactionY)
                return
            }
        }

        // Hit test for custom keyboard first so clicks never pass through it.
        if (isKeyboardVisible &&
                        wasKeyboardVisibleAtDown &&
                        dualWebViewGroup.isPointInKeyboard(interactionX, interactionY)
        ) {
            // Anchored mode needs explicit dispatch; non-anchored is handled by the view itself.
            if (isAnchored) {
                dualWebViewGroup.dispatchKeyboardTap(interactionX, interactionY)
            }
            suppressImmediateWebClickLeak()
            return
        }

        // Check for bookmarks interaction (prevent click propagation to webview)
        if (dualWebViewGroup.isBookmarksExpanded()) {
            if (dualWebViewGroup.isPointInBookmarks(interactionX, interactionY)) {
                // Handled by DualWebViewGroup.onTouchEvent - just don't dispatch to webview
                DebugLog.d("ClickDebug", "Click consumed by bookmarks window")
                return
            }
        }

        // If the tap started on the keyboard, never let it fall through to the WebView.
        if (wasTouchOnKeyboard) {
            DebugLog.d("ClickDebug", "Click consumed by keyboard")
            return
        }

        // Check for windows overview interaction
        if (dualWebViewGroup.isWindowsOverviewVisible()) {
            if (dualWebViewGroup.isPointInWindowsOverview(interactionX, interactionY)) {
                suppressImmediateWebClickLeak()
                dualWebViewGroup.performWindowsOverviewClick()
                return
            }
        }

        // Handle toggle/navigation bar clicks before scrollbars/web content
        val toggleHit =
                dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(interactionX, interactionY)
        val navHit =
                dualWebViewGroup.isNavBarVisible() &&
                        dualWebViewGroup.isPointInNavBar(interactionX, interactionY)
        if (toggleHit || navHit) {
            isSimulatingTouchEvent = false
            suppressImmediateWebClickLeak()
            dualWebViewGroup.handleNavigationClick(interactionX, interactionY)
            return
        }

        // Check for scrollbar interaction
        if (dualWebViewGroup.isPointInScrollbar(interactionX, interactionY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchScrollbarTouch(interactionX, interactionY)
            return
        }

        val currentTime = SystemClock.uptimeMillis()
        if (currentTime - lastClickTime < MIN_CLICK_INTERVAL) {
            return
        }
        lastClickTime = currentTime

        // WebView click path
        isSimulatingTouchEvent = true
        try {
            val webViewLocation = IntArray(2)
            webView.getLocationOnScreen(webViewLocation)

            val translatedX = interactionX - webViewLocation[0]
            val translatedY = interactionY - webViewLocation[1]

            val adjustedX: Float
            val adjustedY: Float

            if (isAnchored) {
                val rotationRad =
                        Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                val unscaledX = translatedX * cos + translatedY * sin
                val unscaledY = -translatedX * sin + translatedY * cos
                adjustedX = unscaledX / scale
                adjustedY = unscaledY / scale
            } else {
                adjustedX = translatedX / scale
                adjustedY = translatedY / scale
            }

            val eventTime = SystemClock.uptimeMillis()

            // Set simulation flag to true to prevent this event from being counted as a new gesture
            // by the OnTouchListener (which would trigger onDown and inaccurate tap counts).
            // It gets reset to false in the cleanup handler below (line 2842).
            isSimulatingTouchEvent = true

            // DOWN event
            val motionEventDown =
                    MotionEvent.obtain(
                                    eventTime,
                                    eventTime,
                                    MotionEvent.ACTION_DOWN,
                                    adjustedX,
                                    adjustedY,
                                    1 // pointer count
                            )
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(motionEventDown)

            webView.evaluateJavascript(
                    """
    (function() {
        var element = document.elementFromPoint($adjustedX, $adjustedY);

        // TapLink nav buttons: force-click if cursor lands on them.
        // This guarantees the button action fires regardless of touch chain.
        if (element) {
            var btn = (element.id === '__tl_view' || element.id === '__tl_next') ? element
                    : element.closest ? element.closest('#__tl_nav button') : null;
            if (btn) {
                btn.click();
                console.log('[TapLink-YT] Force-clicked nav button: ' + btn.id);
                return 'tl_btn_' + btn.id;
            }
        }

        var targetUrl = null;

        function findTargetUrl(el) {
            if (!el) return null;
            if (el.href) return el.href;
            if (el.dataset && (el.dataset.url || el.dataset.articleUrl)) {
                return el.dataset.url || el.dataset.articleUrl;
            }
            var linkParent = el.closest('a');
            if (linkParent && linkParent.href) return linkParent.href;
            return null;
        }

        targetUrl = findTargetUrl(element);
        if (targetUrl && targetUrl.includes('news.google.com')) {
            // Instead of returning the URL, create and trigger a real navigation
            var a = document.createElement('a');
            a.href = targetUrl;
            a.style.display = 'none';
            document.body.appendChild(a);
            a.click();
            document.body.removeChild(a);
            return "clicked";  // Signal that we handled it
        }
        return null;
    })();
"""
            ) { _ ->
                // Complete the touch sequence regardless of whether we found a special link
                Handler(Looper.getMainLooper())
                        .postDelayed(
                                {
                                    val motionEventUp =
                                            MotionEvent.obtain(
                                                            eventTime,
                                                            SystemClock.uptimeMillis(),
                                                            MotionEvent.ACTION_UP,
                                                            adjustedX,
                                                            adjustedY,
                                                            1
                                                    )
                                                    .apply {
                                                        source = InputDevice.SOURCE_TOUCHSCREEN
                                                    }
                                    webView.dispatchTouchEvent(motionEventUp)

                                    // Clean up
                                    motionEventDown.recycle()
                                    motionEventUp.recycle()

                                    // Reset states and check keyboard
                                    Handler(Looper.getMainLooper())
                                            .postDelayed(
                                                    {
                                                        checkAndShowKeyboard(
                                                                adjustedX.toInt(),
                                                                adjustedY.toInt()
                                                        )
                                                        isSimulatingTouchEvent = false
                                                        cursorJustAppeared = false
                                                        isToggling = false
                                                    },
                                                    150
                                            )
                                },
                                16
                        )
            }
        } catch (e: Exception) {
            DebugLog.e("ClickDebug", "Error in dispatchTouchEventAtCursor: ${e.message}")
            e.printStackTrace()
            isSimulatingTouchEvent = false
        }
    }

    private fun checkAndShowKeyboard(adjustedX: Int, adjustedY: Int) {
        webView.evaluateJavascript(
                """
        (function() {
            var element = document.elementFromPoint($adjustedX, $adjustedY);
            var node = element;

            function isPopupTrigger(el) {
                return (
                    el.getAttribute('aria-haspopup') === 'true' || 
                    el.getAttribute('aria-expanded') === 'false' ||
                    el.hasAttribute('aria-controls') ||
                    el.tagName.toLowerCase() === 'select' ||
                    el.tagName === 'BUTTON' ||
                    el.getAttribute('role') === 'button' ||
                    el.getAttribute('role') === 'menu' ||
                    el.getAttribute('role') === 'menuitem' ||
                    el.classList.contains('dropdown-toggle') ||
                    /(menu|dropdown|popup|button|signout|logout)/i.test(el.className) ||
                    el.getAttribute('aria-label')?.toLowerCase().includes('sign out')
                );
            }

            function canAcceptTextInput(el) {
                if (!el) return false;

                // First check if this is a button/menu - should take precedence
                const isMenuOrButton = (
                    el.getAttribute('role') === 'button' ||
                    el.getAttribute('role') === 'menuitem' ||
                    el.getAttribute('aria-haspopup') === 'true' ||
                    el.getAttribute('aria-expanded') !== null ||
                    el.tagName === 'BUTTON' ||
                    (el.tagName === 'A' && el.getAttribute('role') === 'button')
                );

                if (isMenuOrButton) {
                    //console.log('Element is a button or menu control');
                    return false;
                }

                // Enhanced check for text cursor and focus state
                const activeElement = document.activeElement;
                if (activeElement && activeElement !== document.body) {
                    console.log('Active element state:', {
                        tagName: activeElement.tagName,
                        className: activeElement.className,
                        hasSelection: window.getSelection().toString().length > 0,
                        selectionRangeCount: window.getSelection().rangeCount,
                        isInput: activeElement instanceof HTMLInputElement,
                        isTextarea: activeElement instanceof HTMLTextAreaElement,
                        selectionStart: 
                            activeElement instanceof HTMLInputElement || 
                            activeElement instanceof HTMLTextAreaElement ? 
                            activeElement.selectionStart : null,
                        isFocusInElement: activeElement === el || activeElement.contains(el) || el.contains(activeElement)
                    });

                    // Check for any visible text selection
                    const selection = window.getSelection();
                    // Check if there's a real text cursor (not just any selection)
                    const hasVisibleCursor = selection && (
                        // Has actual text selection
                        selection.toString().length > 0 ||
                        // Or has a collapsed cursor (blinking text cursor) in an editable element
                        (selection.rangeCount > 0 && 
                         selection.getRangeAt(0).collapsed &&
                         (activeElement.isContentEditable || 
                          activeElement instanceof HTMLInputElement || 
                          activeElement instanceof HTMLTextAreaElement ||
                          // Also check if it's inside a custom editor
                          (activeElement.closest('[contenteditable="true"]') ||
                           activeElement.closest('[role="textbox"]'))))
                    );

                    if (hasVisibleCursor && 
                        (activeElement === el || 
                         activeElement.contains(el) || 
                         el.contains(activeElement))) {
                        //console.log('Found element with visible text cursor');
                        return true;
                    }
                }

                const capabilities = {
                    tagName: el.tagName,
                    className: el.className,
                    isContentEditable: el.isContentEditable,
                    role: el.getAttribute('role'),
                    inputType: el instanceof HTMLInputElement ? el.type : null,
                    isTextarea: el instanceof HTMLTextAreaElement,
                    hasTextboxRole: el.getAttribute('role') === 'textbox',
                    contentEditable: el.getAttribute('contenteditable'),
                    ariaMultiline: el.getAttribute('aria-multiline'),
                    hasSearchRole: el.getAttribute('role') === 'search'
                };
                //console.log('Text input capabilities:', JSON.stringify(capabilities, null, 2));

                if (
                    el.isContentEditable ||
                    el instanceof HTMLTextAreaElement ||
                    el.getAttribute('role') === 'textbox' ||
                    el.getAttribute('role') === 'searchbox' ||
                    el.getAttribute('role') === 'search' ||
                    el.getAttribute('contenteditable') === 'true' ||
                    el.getAttribute('aria-multiline') === 'true' ||
                    (el instanceof HTMLInputElement && 
                        ['text', 'email', 'password', 'search', 'tel', 'url', 'number'].includes(el.type)) ||
                    (el.tagName.toLowerCase().includes('editor') ||
                     el.tagName.toLowerCase().includes('composer') ||
                     el.tagName.toLowerCase().includes('search'))
                ) {
                    //console.log('Element directly supports text input');
        // If the element directly supports text input, check if it can gain focus:
        return canElementGainFocus(el);
                }
                
                

                return false;
            }
            
            function canElementGainFocus(el) {
    try {
        const isAlreadyFocused = document.activeElement === el;
        el.focus();
        // Check if the element is focused after trying to focus
        const isFocused = document.activeElement === el;
        //console.log("Focus attempt: " + isFocused);
        // Remove focus only if it wasn't meant to be focused previously, and we have actually gained focus
        if (isFocused && !isAlreadyFocused) {
            el.blur();
        }
        return isFocused;
    } catch (e) {
        //console.log("Focus error", e);
        return false; // Return false if any exception occurs during focusing
    }
}


            // Check the element and its hierarchy for input capability
            node = element;
            while (node && node !== document.body) {
                if (canAcceptTextInput(node)) {
                    console.log('Input-capable element found:', node);
                    return 'input';
                }
                node = node.parentElement;
            }

            // Check active element with detailed logging
            const activeElement = document.activeElement;
            if (activeElement && activeElement !== document.body) {
                const activeElementInfo = {
                    tagName: activeElement.tagName,
                    className: activeElement.className,
                    isContentEditable: activeElement.isContentEditable,
                    role: activeElement.getAttribute('role'),
                    containsTarget: activeElement.contains(element),
                    isContainedByTarget: element.contains(activeElement)
                };
                //console.log('Active element details:', JSON.stringify(activeElementInfo, null, 2));

                if (activeElement === element || 
                    element.contains(activeElement) || 
                    activeElement.contains(element)) {
                    
                    if (canAcceptTextInput(activeElement)) {
                        //console.log('Active element can accept text input');
                        return 'input';
                    }
                }
            }

            return 'regular';
        })();
    """
        ) { result ->
            DebugLog.d("InputDebug", "Element detection result after touch: $result")
            if (result?.contains("input") == true) {
                Handler(Looper.getMainLooper()).post { showCustomKeyboard() }
            }
        }
    }

    private fun maybeShowKeyboardForMouseClick(rawScreenX: Float, rawScreenY: Float) {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return

        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = rawScreenX - webViewLocation[0]
        val translatedY = rawScreenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return
        }

        val scale = dualWebViewGroup.uiScale
        val adjustedX: Float
        val adjustedY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
    }

    private fun mapMousePointForVirtualTap(rawScreenX: Float, rawScreenY: Float): Pair<Float, Float> {
        if (!isMouseTapMode) return rawScreenX to rawScreenY
        val fallbackEyeWidth = 640f
        if (!::dualWebViewGroup.isInitialized) {
            if (rawScreenX < fallbackEyeWidth) return rawScreenX to rawScreenY
            return (rawScreenX - fallbackEyeWidth) to rawScreenY
        }

        val groupLocation = IntArray(2)
        dualWebViewGroup.getLocationOnScreen(groupLocation)
        val groupLeft = groupLocation[0].toFloat()
        val groupWidth = dualWebViewGroup.width.toFloat().takeIf { it > 0f } ?: (fallbackEyeWidth * 2f)
        val eyeWidth = (groupWidth / 2f).coerceAtLeast(1f)

        val xWithinGroup = rawScreenX - groupLeft
        if (xWithinGroup < 0f || xWithinGroup >= groupWidth) {
            // Outside dual-eye surface: do not remap.
            return rawScreenX to rawScreenY
        }

        if (xWithinGroup < eyeWidth) {
            return rawScreenX to rawScreenY
        }

        return (rawScreenX - eyeWidth) to rawScreenY
    }

    private fun mapScreenPointToWebViewTouch(screenX: Float, screenY: Float): Pair<Float, Float>? {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return null
        val scale = dualWebViewGroup.uiScale
        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = screenX - webViewLocation[0]
        val translatedY = screenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return null
        }

        val adjustedX: Float
        val adjustedY: Float
        if (isAnchored) {
            val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        return adjustedX to adjustedY
    }

    private fun dispatchWebTouchFromScreen(
            action: Int,
            screenX: Float,
            screenY: Float,
            eventTime: Long = SystemClock.uptimeMillis(),
            downTime: Long = mouseSwipeDownTime
    ): Boolean {
        val mapped = mapScreenPointToWebViewTouch(screenX, screenY) ?: return false
        val adjustedX = mapped.first
        val adjustedY = mapped.second

        val event =
                MotionEvent.obtain(
                                downTime,
                                eventTime,
                                action,
                                adjustedX,
                                adjustedY,
                                0
                        )
                        .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
        isSimulatingTouchEvent = true
        try {
            webView.dispatchTouchEvent(event)
        } finally {
            isSimulatingTouchEvent = false
            event.recycle()
        }
        return true
    }

    private fun isPointOnCustomUi(screenX: Float, screenY: Float): Boolean {
        if (!::dualWebViewGroup.isInitialized) return false
        if (dualWebViewGroup.isScreenMasked()) return true
        if (dualWebViewGroup.isFullScreenOverlayVisible()) return true
        if (dualWebViewGroup.isDialogAction(screenX, screenY)) return true

        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()
            if (screenX >= settingsMenuLocation[0] &&
                            screenX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            screenY >= settingsMenuLocation[1] &&
                            screenY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {
                return true
            }
        }

        if (dualWebViewGroup.isPointInRestoreButton(screenX, screenY)) return true
        if (dualWebViewGroup.isChatVisible() && dualWebViewGroup.isPointInChat(screenX, screenY)) return true
        if (isKeyboardVisible && dualWebViewGroup.isPointInKeyboard(screenX, screenY)) return true
        if (dualWebViewGroup.isWindowsOverviewVisible() &&
                        dualWebViewGroup.isPointInWindowsOverview(screenX, screenY)
        ) {
            return true
        }
        if (dualWebViewGroup.isToggleBarVisible() && dualWebViewGroup.isPointInToggleBar(screenX, screenY)) {
            return true
        }
        if (dualWebViewGroup.isNavBarVisible() && dualWebViewGroup.isPointInNavBar(screenX, screenY)) {
            return true
        }
        if (dualWebViewGroup.isPointInScrollbar(screenX, screenY)) return true
        return false
    }

    private fun resolveMouseScreenPoint(ev: MotionEvent): Pair<Float, Float> {
        var screenX = ev.rawX
        var screenY = ev.rawY

        if (!screenX.isFinite() || !screenY.isFinite()) {
            val rootLoc = IntArray(2)
            window.decorView.getLocationOnScreen(rootLoc)
            screenX = ev.x + rootLoc[0]
            screenY = ev.y + rootLoc[1]
        }

        return screenX to screenY
    }

    private fun dispatchWebTapAtScreenCoordinates(screenX: Float, screenY: Float) {
        if (!::webView.isInitialized || !::dualWebViewGroup.isInitialized) return
        if (isSimulatingTouchEvent) return

        val scale = dualWebViewGroup.uiScale
        val webViewLocation = IntArray(2)
        webView.getLocationOnScreen(webViewLocation)

        val translatedX = screenX - webViewLocation[0]
        val translatedY = screenY - webViewLocation[1]
        if (translatedX < 0f ||
                        translatedY < 0f ||
                        translatedX > webView.width ||
                        translatedY > webView.height
        ) {
            return
        }

        val adjustedX: Float
        val adjustedY: Float
        if (isAnchored) {
            val rotationRad = Math.toRadians(dualWebViewGroup.leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            val unscaledX = translatedX * cos + translatedY * sin
            val unscaledY = -translatedX * sin + translatedY * cos
            adjustedX = unscaledX / scale
            adjustedY = unscaledY / scale
        } else {
            adjustedX = translatedX / scale
            adjustedY = translatedY / scale
        }

        val eventTime = SystemClock.uptimeMillis()
        isSimulatingTouchEvent = true
        try {
            val downEvent =
                    MotionEvent.obtain(
                                    eventTime,
                                    eventTime,
                                    MotionEvent.ACTION_DOWN,
                                    adjustedX,
                                    adjustedY,
                                    1
                            )
                            .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
            webView.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                val upEvent =
                                        MotionEvent.obtain(
                                                        eventTime,
                                                        SystemClock.uptimeMillis(),
                                                        MotionEvent.ACTION_UP,
                                                        adjustedX,
                                                        adjustedY,
                                                        1
                                                )
                                                .apply { source = InputDevice.SOURCE_TOUCHSCREEN }
                                webView.dispatchTouchEvent(upEvent)
                                upEvent.recycle()
                                checkAndShowKeyboard(adjustedX.toInt(), adjustedY.toInt())
                                isSimulatingTouchEvent = false
                            },
                            16
                    )
        } catch (e: Exception) {
            isSimulatingTouchEvent = false
            DebugLog.e("MouseTap", "Failed to dispatch virtual web tap: ${e.message}")
        }
    }

    private fun handleMouseClickForCustomUi(rawScreenX: Float, rawScreenY: Float): Boolean {
        if (!::dualWebViewGroup.isInitialized) return false

        val scale = dualWebViewGroup.uiScale

        if (dualWebViewGroup.isScreenMasked()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchMaskOverlayTouch(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isFullScreenOverlayVisible()) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchFullScreenOverlayTouch(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isDialogAction(rawScreenX, rawScreenY)) {
            val dialogContainer = dualWebViewGroup.dialogContainer
            val location = IntArray(2)
            dialogContainer.getLocationOnScreen(location)
            val localX = (rawScreenX - location[0]) / scale
            val localY = (rawScreenY - location[1]) / scale

            val downEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_DOWN,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(downEvent)
            downEvent.recycle()

            val upEvent =
                    MotionEvent.obtain(
                            SystemClock.uptimeMillis(),
                            SystemClock.uptimeMillis(),
                            MotionEvent.ACTION_UP,
                            localX,
                            localY,
                            0
                    )
            dialogContainer.dispatchTouchEvent(upEvent)
            upEvent.recycle()
            suppressImmediateWebClickLeak()
            return true
        }

        if (dualWebViewGroup.isSettingsVisible()) {
            val settingsMenuLocation = IntArray(2)
            dualWebViewGroup.getSettingsMenuLocation(settingsMenuLocation)
            val settingsMenuSize = dualWebViewGroup.getSettingsMenuSize()
            if (rawScreenX >= settingsMenuLocation[0] &&
                            rawScreenX <= settingsMenuLocation[0] + settingsMenuSize.first &&
                            rawScreenY >= settingsMenuLocation[1] &&
                            rawScreenY <= settingsMenuLocation[1] + settingsMenuSize.second
            ) {
                suppressImmediateWebClickLeak()
                dualWebViewGroup.dispatchSettingsTouchEvent(rawScreenX, rawScreenY)
                return true
            }
        }

        if (dualWebViewGroup.isPointInRestoreButton(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.performRestoreButtonClick()
            return true
        }

        if (dualWebViewGroup.isChatVisible() && dualWebViewGroup.isPointInChat(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchChatTouchEvent(rawScreenX, rawScreenY)
            return true
        }

        if (isKeyboardVisible && dualWebViewGroup.isPointInKeyboard(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchKeyboardTap(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isWindowsOverviewVisible() &&
                        dualWebViewGroup.isPointInWindowsOverview(rawScreenX, rawScreenY)
        ) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.performWindowsOverviewClick()
            return true
        }

        val toggleHit =
                dualWebViewGroup.isToggleBarVisible() &&
                        dualWebViewGroup.isPointInToggleBar(rawScreenX, rawScreenY)
        val navHit =
                dualWebViewGroup.isNavBarVisible() &&
                        dualWebViewGroup.isPointInNavBar(rawScreenX, rawScreenY)
        if (toggleHit || navHit) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.handleNavigationClick(rawScreenX, rawScreenY)
            return true
        }

        if (dualWebViewGroup.isPointInScrollbar(rawScreenX, rawScreenY)) {
            suppressImmediateWebClickLeak()
            dualWebViewGroup.dispatchScrollbarTouch(rawScreenX, rawScreenY)
            return true
        }

        return false
    }

    private fun sendEnterToWebView() {
        if (dualWebViewGroup.isUrlEditing()) {
            sendEnterInLinkEditText()
            return
        }
        if (dualWebViewGroup.isChatVisible()) {
            dualWebViewGroup.sendEnterToChatInput()
            hideCustomKeyboard()
            return
        } else {
            webView.evaluateJavascript(
                    """
        (function() {
            var el = document.activeElement;
            if (!el) {
                console.log('No active element found');
                return null;
            }
            
            // Log the active element for debugging
            console.log('Active element for enter:', {
                tagName: el.tagName,
                id: el.id,
                className: el.className,
                type: el.type,
                value: el.value
            });

            function dispatchKeyEvents(element) {
                // Create keydown event
                const keyDown = new KeyboardEvent('keydown', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                element.dispatchEvent(keyDown);

                // Create keypress event
                const keyPress = new KeyboardEvent('keypress', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                element.dispatchEvent(keyPress);

                // Create keyup event
                const keyUp = new KeyboardEvent('keyup', {
                    key: 'Enter',
                    code: 'Enter',
                    keyCode: 13,
                    which: 13,
                    bubbles: true,
                    cancelable: true,
                    composed: true
                });
                element.dispatchEvent(keyUp);

                // Dispatch input and change events
                element.dispatchEvent(new Event('input', { bubbles: true, composed: true }));
                element.dispatchEvent(new Event('change', { bubbles: true, composed: true }));
            }

            // Handle both direct elements and shadow DOM
            if (el.shadowRoot) {
                const shadowInput = el.shadowRoot.querySelector('input, textarea');
                if (shadowInput) {
                    dispatchKeyEvents(shadowInput);
                    return true;
                }
            }

            dispatchKeyEvents(el);
            return true;
        })();
        """
            ) { result ->
                DebugLog.d("InputDebug", "Enter JavaScript result: $result")
                Handler(Looper.getMainLooper()).post { hideCustomKeyboard() }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun setupWebView() {
        configureWebView(webView)
    }

    override fun onWindowCreated(webView: WebView) {
        configureWebView(webView)
        // Default URL loading is now handled in DualWebViewGroup.createNewWindow()
        // to avoid overriding restored state
    }

    override fun onWindowSwitched(webView: WebView) {
        // Update reference
        this.webView = webView

        // Ensure the correct touch listener is attached (though configureWebView likely did it)
        attachTouchListener(webView)
        applyForceDarkModeSetting(webView)

        // Persist the newly active window so reopen returns to the correct tab/page.

        // Persist the newly active window so reopen returns to the correct tab/page.
        persistActiveUrl("onWindowSwitched", webView.url ?: Constants.DEFAULT_URL, webView)
    }

    private fun isForceDarkWebEnabled(): Boolean {
        return getSharedPreferences("TapLinkPrefs", MODE_PRIVATE)
                .getBoolean("forceDarkWebEnabled", true)
    }

    private fun applyForceDarkModeSetting(targetWebView: WebView, enabled: Boolean = isForceDarkWebEnabled()) {
        targetWebView.settings.apply {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                isAlgorithmicDarkeningAllowed = enabled
            } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                @Suppress("DEPRECATION")
                forceDark =
                        if (enabled) WebSettings.FORCE_DARK_ON else WebSettings.FORCE_DARK_OFF
            }
        }
    }

    fun setForceDarkWebEnabled(enabled: Boolean) {
        getSharedPreferences("TapLinkPrefs", MODE_PRIVATE)
                .edit()
                .putBoolean("forceDarkWebEnabled", enabled)
                .apply()

        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.getAllWebViews().forEach { applyForceDarkModeSetting(it, enabled) }
        } else if (::webView.isInitialized) {
            applyForceDarkModeSetting(webView, enabled)
        }
    }

    private fun attachTouchListener(targetWebView: WebView) {
        targetWebView.setOnTouchListener { _, event ->
            val isMouseEvent = isMousePointerEvent(event)
            // Allow simulated events to pass through immediately (used for scrolling)
            // CRITICAL: Check this FIRST to prevent infinite loops where simulated events
            // are fed back into gestureDetector, triggering more scrolls.
            if (isSimulatingTouchEvent) {
                return@setOnTouchListener false
            }

            if (isMouseTapMode && isMouseEvent) {
                return@setOnTouchListener false
            }

            // DO NOT call gestureDetector.onTouchEvent(event) here!
            // It is already called in MainActivity.dispatchTouchEvent().
            // Calling it again causes double-counting of taps (1 physical tap = 2 gesture taps).
            // We just use the result stored in isGestureHandled.

            // Logic to clear pending runnables on touch interaction
            if (event.action == MotionEvent.ACTION_DOWN ||
                            event.action == MotionEvent.ACTION_UP ||
                            event.action == MotionEvent.ACTION_CANCEL
            ) {
                pendingTouchRunnable?.let { pendingTouchHandler?.removeCallbacks(it) }
                pendingTouchRunnable = null
            }

            // --- BLOCKING LOGIC START ---

            if (isAnchored && isKeyboardVisible) {
                // Special case for anchored keyboard? (Original logic preserved)
                return@setOnTouchListener false
            }

            if (isKeyboardVisible) {
                // If keyboard is visible, block touches to WebView (prevent clicks behind keyboard)
                return@setOnTouchListener true
            }

            if (dualWebViewGroup.isSettingsVisible()) {
                // If settings are open, only block if cursor is visible?
                // logic matches original: return isCursorVisible
                return@setOnTouchListener isCursorVisible
            }

            if (dualWebViewGroup.isInScrollMode()) {
                // SCROLL MODE ENFORCEMENT:
                // Block ALL real touch events. The only way to interact is via
                // simulated scroll events (caught by isSimulatingTouchEvent check above)
                // or via gestures (caught by gestureDetector above).
                return@setOnTouchListener true
            }

            if (isCursorVisible && !isMouseEvent) {
                // If cursor is visible, we are in "mouse mode" - block direct touches.
                return@setOnTouchListener true
            }

            // --- BLOCKING LOGIC END ---

            // Otherwise, return handled state from GestureDetector
            // If GestureDetector consumed it (e.g., tap, long press), we consume.
            // If not, we fall through to false?
            // Actually original code returned 'handled' which was 'isGestureHandled'
            isGestureHandled
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun configureWebView(webView: WebView) {
        attachTouchListener(webView)

        // First check if speech recognition is available
        val speechRecognizerIntent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
        val speechRecognitionAvailable =
                packageManager.resolveActivity(speechRecognizerIntent, 0) != null
        DebugLog.d("WebView", "Speech recognition available: $speechRecognitionAvailable")

        // Section 1: Basic WebView Configuration
        WebView.setWebContentsDebuggingEnabled(BuildConfig.DEBUG)
        webView.addJavascriptInterface(WebAppInterface(this, webView), "GroqBridge")

        // Intercept taplink://chat URLs and media file URLs
        webView.webViewClient =
                object : WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: WebView?,
                            request: WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        DebugLog.d("MainActivityClient", "Checking URL: $url")

                        if (url.startsWith("taplink://chat")) {
                            DebugLog.d("MainActivityClient", "Intercepted taplink://chat")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                val webInterface =
                                        com.TapLinkX3.app.WebAppInterface(this@MainActivity, view)
                                webInterface.chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        // Intercept media file links → open in TapInsight media player
                        if (view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(view: WebView?, url: String?): Boolean {
                        DebugLog.d("MainActivityClient", "Checking URL (deprecated): $url")
                        if (url != null && url.startsWith("taplink://chat")) {
                            DebugLog.d(
                                    "MainActivityClient",
                                    "Intercepted taplink://chat (deprecated)"
                            )
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                val webInterface =
                                        com.TapLinkX3.app.WebAppInterface(this@MainActivity, view)
                                webInterface.chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        if (url != null && view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }
                }

        webView.apply {
            isFocusable = true
            isFocusableInTouchMode = true
            importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_YES
            setBackgroundColor(Color.BLACK)
            visibility = View.INVISIBLE
            overScrollMode = View.OVER_SCROLL_NEVER

            // Section 2: WebView Settings Configuration
            settings.apply {
                // JavaScript and Content Settings
                javaScriptEnabled = true
                domStorageEnabled = true
                @Suppress("DEPRECATION")
                databaseEnabled = true
                javaScriptCanOpenWindowsAutomatically = false
                mediaPlaybackRequiresUserGesture = false

                // Security and Access Settings — restrict file/content access to prevent
                // XSS attacks from reading local files via file:// or content:// URIs
                allowFileAccess = false
                allowContentAccess = false
                setGeolocationEnabled(true)

                // Display and Layout Settings
                @Suppress("DEPRECATION")
                defaultZoom = WebSettings.ZoomDensity.MEDIUM
                useWideViewPort = true
                loadWithOverviewMode = true
                layoutAlgorithm = WebSettings.LayoutAlgorithm.NORMAL
                textZoom = 80

                // Disable Unnecessary Zoom Controls
                setSupportZoom(false)
                builtInZoomControls = false
                displayZoomControls = false

                // Multi-window Support
                setSupportMultipleWindows(false)

                // Handle Mixed Content
                mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW

                // Keep secure HTTPS navigation
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    safeBrowsingEnabled = true
                }

                // Store default UA for sites that require it (like Netflix)
                if (defaultUserAgent == null) {
                    defaultUserAgent = WebSettings.getDefaultUserAgent(this@MainActivity)
                }

                // Use the actual runtime WebView UA to avoid auth providers flagging spoofed clients.
                customUserAgent = defaultUserAgent
                if (!customUserAgent.isNullOrBlank()) {
                    settings.userAgentString = customUserAgent

                    // Pass runtime UA to DualWebViewGroup so mobile/desktop modes derive from it.
                    dualWebViewGroup.setMobileUserAgent(customUserAgent!!)
                }

                // Explicitly enable media
                setMediaPlaybackRequiresUserGesture(false)
            }
            applyForceDarkModeSetting(this)

            // Enable third-party cookies specifically for auth
            CookieManager.getInstance().apply {
                setAcceptThirdPartyCookies(webView, true)
                setAcceptCookie(true)
                acceptCookie()
            }

            // Section 3: Single WebViewClient Implementation
            webViewClient =
                    object : WebViewClient() {
                        private var lastValidUrl: String? = null

                        override fun onPageStarted(view: WebView?, url: String?, favicon: Bitmap?) {
                            super.onPageStarted(view, url, favicon)
                            DebugLog.d("YouTubeAuto", "onPageStarted[2]: url=$url")
                            DebugLog.d("WebViewDebug", "Page started loading: $url")

                            if (closeChatOnNextPageStart) {
                                val now = SystemClock.uptimeMillis()
                                if (now > closeChatOnNextPageStartDeadlineMs) {
                                    closeChatOnNextPageStart = false
                                } else if (!url.isNullOrBlank() && !url.startsWith("about:blank")) {
                                    closeChatOnNextPageStart = false
                                    dualWebViewGroup.hideChat()
                                }
                            }

                            dualWebViewGroup.clearExternalScrollMetrics()

                            // Streaming Fix: Force default User Agent to ensure Widevine CDM works
                            val isStreaming = isStreamingSite(url)
                            if (isStreaming) {
                                if (view?.settings?.userAgentString != defaultUserAgent) {
                                    view?.settings?.userAgentString = defaultUserAgent
                                    DebugLog.d(
                                            "StreamingFix",
                                            "Switched to default User Agent for Streaming Site"
                                    )
                                }
                            } else {
                                // Force desktop UA for YouTube when autoplay is active
                                val isYouTubeAutoplay = !youtubeAutoplayQuery.isNullOrBlank() &&
                                    !youtubeAutoplayMode.isNullOrBlank() &&
                                    url != null &&
                                    (url.contains("youtube.com") || url.contains("youtu.be"))

                                val forceDesktopUa = if (isYouTubeAutoplay) {
                                    shouldUseDesktopUaForYouTube(url, youtubeAutoplayMode)
                                } else {
                                    dualWebViewGroup.isDesktopMode()
                                }

                                if (forceDesktopUa) {
                                    val desktopUA = dualWebViewGroup.getDesktopUserAgent()
                                    if (view?.settings?.userAgentString != desktopUA) {
                                        view?.settings?.userAgentString = desktopUA
                                    }
                                } else {
                                    if (view?.settings?.userAgentString != customUserAgent &&
                                                    customUserAgent != null
                                    ) {
                                        view?.settings?.userAgentString = customUserAgent
                                    }
                                }
                            }

                            // Show loading bar immediately
                            dualWebViewGroup.updateLoadingProgress(0)

                            if (url != null && !url.startsWith("about:blank")) {
                                lastValidUrl = url

                                // Persist the URL as soon as navigation starts so app relaunch
                                // returns to the newest page even if load doesn't finish.
                                persistActiveUrl("onPageStarted", url, view)

                                // Start observers early so scrollbars can appear before full load.
                                view?.let { dualWebViewGroup.injectPageObservers(it) }

                                // Inject location early so it's available before page JS runs
                                if (lastGpsLat != null && lastGpsLon != null) {
                                    dualWebViewGroup.injectLocation(lastGpsLat!!, lastGpsLon!!)
                                }
                            } else if (url?.startsWith("about:blank") == true &&
                                            lastValidUrl != null
                            ) {
                                // Skip about:blank recovery if we're intentionally
                                // navigating to about:blank for nuclear media cleanup
                                if (nuclearCleanupInProgress) {
                                    DebugLog.d("YouTubeAuto", "onPageStarted: about:blank during nuclear cleanup — NOT recovering to $lastValidUrl")
                                    lastValidUrl = null
                                } else {
                                    // Cancel about:blank load immediately
                                    view?.stopLoading()
                                    view?.loadUrl(lastValidUrl!!)
                                }
                            }
                        }

                        override fun onPageFinished(view: WebView?, url: String?) {
                            super.onPageFinished(view, url)
                            DebugLog.d("WebViewDebug", "Page finished loading: $url")

                            // Ensure loading bar is hidden when finished
                            dualWebViewGroup.updateLoadingProgress(100)

                            // Keep the latest URL hot without serializing full WebView history on every load.
                            url?.let { persistActiveUrl("onPageFinished", it, view) }
                            dualWebViewGroup.saveWindowMetadataState()

                            if (url != null && !url.startsWith("about:blank")) {
                                view?.visibility = View.VISIBLE
                                injectJavaScriptForInputFocus()

                                // Reset horizontal scroll to prevent right-offset rendering
                                view?.let { wv ->
                                    if (wv.scrollX > 0) {
                                        wv.postDelayed({ wv.scrollTo(0, wv.scrollY) }, 100)
                                    }
                                }

                                // ── Dashboard ↔ SharedPreferences sync ──
                                // When the dashboard HTML loads, pull any data
                                // saved by the companion app into localStorage,
                                // and hook persistState to also write back.
                                if (url.contains("AR_Dashboard")) {
                                    injectDashboardSync(view)
                                    dualWebViewGroup.recenterViewportForDashboard(view)
                                }

                                // Re-apply saved font settings to new page
                                dualWebViewGroup.reapplyWebFontSettings()

                                // Inject last known location if available
                                if (lastGpsLat != null && lastGpsLon != null) {
                                    dualWebViewGroup.injectLocation(lastGpsLat!!, lastGpsLon!!)
                                }

                                // ── YouTube autoplay automation ──
                                val isYouTubePage = url.contains("youtube.com") || url.contains("youtu.be")
                                if (isYouTubePage) {
                                    view?.let { injectYouTubePlaylistAutomation(it, url) }
                                }

                                // Restore media listeners and scrollbar logic from DualWebViewGroup
                                view?.let { dualWebViewGroup.injectPageObservers(it) }
                                dualWebViewGroup.updateScrollBarsVisibility()
                                dualWebViewGroup.refreshMaskedNowPlaying()

                                val viewportContent =
                                        if (dualWebViewGroup.isDesktopMode()) {
                                            "width=1280, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                        } else {
                                            "width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no"
                                        }
                                view?.evaluateJavascript(
                                        """
                            (function() {
                                var viewport = document.querySelector('meta[name="viewport"]');
                                if (!viewport) {
                                    viewport = document.createElement('meta');
                                    viewport.name = 'viewport';
                                    document.head.appendChild(viewport);
                                }
                                viewport.content = '$viewportContent';
                            })();
                            """,
                                        null
                                )

                                // Inject media listeners with enhanced YouTube support
                                view?.evaluateJavascript(
                                        """
                            (function() {
                                console.log('[TapLink] Media detection script starting...');
                                let lastPlayingState = false;
                                
                                function notifyMediaState(isPlaying) {
                                    if (lastPlayingState !== isPlaying) {
                                        console.log('[TapLink] Media state changed:', isPlaying);
                                        lastPlayingState = isPlaying;
                                        var bridge = window.GroqBridge || window.Android;
                                        if (bridge && typeof bridge.onMediaPlaying === 'function') {
                                            bridge.onMediaPlaying(isPlaying);
                                        } else {
                                            console.error('[TapLink] Media bridge not available!');
                                        }
                                    }
                                }
                                
                                function checkMediaState() {
                                    // Check all video and audio elements
                                    const mediaElements = document.querySelectorAll('video, audio');
                                    let isAnyPlaying = false;
                                    
                                    mediaElements.forEach(media => {
                                        if (!media.paused && !media.ended && media.readyState > 2) {
                                            isAnyPlaying = true;
                                        }
                                    });
                                    
                                    notifyMediaState(isAnyPlaying);
                                    return isAnyPlaying;
                                }

                                let mediaCheckTimer = null;
                                function scheduleMediaCheck() {
                                    if (mediaCheckTimer !== null) return;
                                    mediaCheckTimer = setTimeout(() => {
                                        mediaCheckTimer = null;
                                        checkMediaState();
                                    }, 300);
                                }
                                
                                function attachMediaListeners() {
                                    const mediaElements = document.querySelectorAll('video, audio');
                                    console.log('[TapLink] Found', mediaElements.length, 'media elements');
                                    
                                    mediaElements.forEach((media, index) => {
                                        if (media.dataset.taplinkListening) return;
                                        media.dataset.taplinkListening = 'true';
                                        
                                        console.log('[TapLink] Attaching listeners to media element', index, media.tagName);
                                        
                                        media.addEventListener('play', () => {
                                            console.log('[TapLink] Play event');
                                            notifyMediaState(true);
                                        });
                                        media.addEventListener('playing', () => {
                                            console.log('[TapLink] Playing event');
                                            notifyMediaState(true);
                                        });
                                        media.addEventListener('pause', () => {
                                            console.log('[TapLink] Pause event');
                                            scheduleMediaCheck();
                                        });
                                        media.addEventListener('ended', () => {
                                            console.log('[TapLink] Ended event');
                                            scheduleMediaCheck();
                                        });
                                    });
                                }
                                
                                // Run initially
                                attachMediaListeners();
                                checkMediaState();
                                scheduleMediaCheck();
                                
                                // Watch for new media elements (YouTube loads videos dynamically)
                                const observer = new MutationObserver((mutations) => {
                                    attachMediaListeners();
                                    scheduleMediaCheck();
                                });
                                observer.observe(document.body, { childList: true, subtree: true });
                                
                                console.log('[TapLink] Media detection script initialized');
                            })();
                        """,
                                        null
                                )

                                // Auto-unmute YouTube (and similar) videos that start muted
                                // due to browser autoplay policies.  The script watches for
                                // <video> elements and unmutes them shortly after playback
                                // begins, simulating what the user would do by tapping the
                                // speaker icon.
                                if (url.contains("youtube.com") || url.contains("youtu.be")) {
                                    view?.evaluateJavascript(
                                            """
                                (function() {
                                    console.log('[TapLink] YouTube auto-unmute script starting...');
                                    var attempts = 0;
                                    function tryUnmute() {
                                        var videos = document.querySelectorAll('video');
                                        var unmuted = false;
                                        videos.forEach(function(v) {
                                            if (v.muted) {
                                                v.muted = false;
                                                console.log('[TapLink] Unmuted video element');
                                                unmuted = true;
                                            }
                                        });
                                        // Also try clicking YouTube's own unmute button as fallback
                                        if (!unmuted || videos.length === 0) {
                                            var muteBtn = document.querySelector('.ytp-mute-button');
                                            if (muteBtn) {
                                                var vol = muteBtn.getAttribute('data-title-no-tooltip') ||
                                                          muteBtn.getAttribute('title') || '';
                                                if (vol.toLowerCase().indexOf('unmute') >= 0 ||
                                                    vol.toLowerCase().indexOf('muted') >= 0) {
                                                    muteBtn.click();
                                                    console.log('[TapLink] Clicked YouTube unmute button');
                                                    unmuted = true;
                                                }
                                            }
                                        }
                                        attempts++;
                                        if (!unmuted && attempts < 15) {
                                            setTimeout(tryUnmute, 800);
                                        }
                                    }
                                    // YouTube loads the player dynamically; wait a moment
                                    setTimeout(tryUnmute, 1500);

                                    // Also watch for new video elements via MutationObserver
                                    var ytObserver = new MutationObserver(function() {
                                        var videos = document.querySelectorAll('video');
                                        videos.forEach(function(v) {
                                            if (v.muted && !v.dataset.taplinkUnmuted) {
                                                v.muted = false;
                                                v.dataset.taplinkUnmuted = 'true';
                                                console.log('[TapLink] MutationObserver unmuted video');
                                            }
                                        });
                                    });
                                    if (document.body) {
                                        ytObserver.observe(document.body, { childList: true, subtree: true });
                                    }
                                    console.log('[TapLink] YouTube auto-unmute script initialized');
                                })();
                                """,
                                            null
                                    )
                                }
                            }
                        }

                        override fun onRenderProcessGone(
                                view: WebView?,
                                detail: android.webkit.RenderProcessGoneDetail?
                        ): Boolean {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                                if (detail?.didCrash() == true) {
                                    DebugLog.e("WebView", "Render process crashed!")
                                    dualWebViewGroup.showConfirmDialog(
                                            "The web page crashed. Reload?",
                                            { view?.reload() },
                                            { /* Do nothing */}
                                    )
                                } else {
                                    DebugLog.e("WebView", "Render process killed by system (OOM).")
                                    // If system killed it, we can just return true and let the OS
                                    // handle it,
                                    // or offer a reload.
                                }
                            }
                            return true // Prevent app crash
                        }

                        override fun shouldOverrideUrlLoading(
                                view: WebView?,
                                request: WebResourceRequest?
                        ): Boolean {
                            val uri = request?.url ?: return false
                            val url = uri.toString()

                            // Block about:blank navigations
                            if (url.startsWith("about:blank")) {
                                return true
                            }

                            val scheme = uri.scheme?.lowercase()

                            // Handle app intents
                            if (scheme == "intent" || scheme == "market") {
                                val fallbackUrl =
                                        url.substringAfter("fallback_url=", "")
                                                .substringBefore("#", "")
                                                .substringBefore("&", "")

                                if (fallbackUrl.isNotEmpty() &&
                                                (fallbackUrl.startsWith("http") ||
                                                        fallbackUrl.startsWith("https"))
                                ) {
                                    view?.loadUrl(fallbackUrl)
                                    return true
                                }
                                return true
                            }

                            // Let WebView handle schemes it natively understands
                            if (scheme == null ||
                                            scheme == "http" ||
                                            scheme == "https" ||
                                            scheme == "file" ||
                                            scheme == "about" ||
                                            scheme == "data" ||
                                            scheme == "blob" ||
                                            scheme == "javascript"
                            ) {
                                return false
                            }

                            // For app/deep-link schemes (e.g., TikTok snssdk1233://), try external
                            return try {
                                val intent = Intent(Intent.ACTION_VIEW, uri)
                                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                                startActivity(intent)
                                true
                            } catch (e: ActivityNotFoundException) {
                                DebugLog.w("WebView", "No handler for URL scheme: $scheme ($url)")
                                true
                            } catch (e: Exception) {
                                DebugLog.w("WebView", "Failed to open external URL: $url")
                                true
                            }
                        }
                    }
            // Add more detailed logging to track input field interactions
            webView.evaluateJavascript(
                    """
        (function() {
            document.addEventListener('focus', function(e) {
                console.log('Focus event:', {
                    target: e.target.tagName,
                    type: e.target.type,
                    isInput: e.target instanceof HTMLInputElement,
                    isTextArea: e.target instanceof HTMLTextAreaElement,
                    isContentEditable: e.target.isContentEditable
                });
            }, true);
        })();
    """,
                    null
            )

            // Consolidate WebChromeClient to handle permissions, file choosing, and custom views
            webChromeClient =
                    object : WebChromeClient() {
                        // From first client
                        override fun onProgressChanged(view: WebView?, newProgress: Int) {
                            super.onProgressChanged(view, newProgress)
                            dualWebViewGroup.updateLoadingProgress(newProgress)
                        }
                        override fun onReceivedTouchIconUrl(
                                view: WebView?,
                                url: String?,
                                precomposed: Boolean
                        ) {
                            DebugLog.d("WebViewDebug", "Received touch icon URL: $url")
                            super.onReceivedTouchIconUrl(view, url, precomposed)
                        }

                        override fun onReceivedTitle(view: WebView?, title: String?) {
                            super.onReceivedTitle(view, title)
                            dualWebViewGroup.refreshMaskedNowPlaying()
                        }

                        override fun onConsoleMessage(consoleMessage: ConsoleMessage): Boolean {
                            DebugLog.d(
                                    "WebViewInput",
                                    "${consoleMessage.messageLevel()} [${consoleMessage.lineNumber()}]: ${consoleMessage.message()}"
                            )
                            return true
                        }

                        // Combined onPermissionRequest
                        override fun onPermissionRequest(request: PermissionRequest) {
                            DebugLog.d(
                                    "WebView",
                                    "Permission request: ${request.resources.joinToString()}"
                            )

                            val permissions = mutableListOf<String>()
                            val requiredAndroidPermissions = mutableListOf<String>()

                            request.resources.forEach { resource ->
                                when (resource) {
                                    PermissionRequest.RESOURCE_AUDIO_CAPTURE -> {
                                        permissions.add(resource)
                                        requiredAndroidPermissions.add(
                                                android.Manifest.permission.RECORD_AUDIO
                                        )
                                        // Configure AR glasses microphone for voice assistant mode
                                        audioManager?.setParameters(
                                                "audio_source_record=voiceassistant"
                                        )
                                    }
                                    PermissionRequest.RESOURCE_VIDEO_CAPTURE -> {
                                        permissions.add(resource)
                                        requiredAndroidPermissions.add(
                                                android.Manifest.permission.CAMERA
                                        )
                                    }
                                }
                            }

                            runOnUiThread {
                                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                                    val notGrantedPermissions =
                                            requiredAndroidPermissions.filter {
                                                checkSelfPermission(it) !=
                                                        PackageManager.PERMISSION_GRANTED
                                            }

                                    if (notGrantedPermissions.isNotEmpty()) {
                                        pendingPermissionRequest = request
                                        requestPermissions(
                                                notGrantedPermissions.toTypedArray(),
                                                PERMISSIONS_REQUEST_CODE
                                        )
                                    } else {
                                        request.grant(permissions.toTypedArray())
                                    }
                                } else {
                                    request.grant(permissions.toTypedArray())
                                }
                            }
                        }

                        override fun onPermissionRequestCanceled(request: PermissionRequest) {
                            pendingPermissionRequest = null
                            // Reset audio source when permissions are cancelled
                            audioManager?.setParameters("audio_source_record=off")
                        }

                        override fun onGeolocationPermissionsShowPrompt(
                                origin: String,
                                callback: GeolocationPermissions.Callback
                        ) {
                            // Always grant permission for WebView content so our injected GPS logic
                            // takes over
                            noteGeolocationUse()
                            callback.invoke(origin, true, false)
                        }

                        // From second client
                        override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
                            if (view == null) {
                                callback?.onCustomViewHidden()
                                return
                            }
                            showFullScreenCustomView(view, callback)
                        }

                        override fun onHideCustomView() {
                            hideFullScreenCustomView()
                        }

                        // From first client
                        override fun onShowFileChooser(
                                webView: WebView?,
                                filePathCallback: ValueCallback<Array<Uri>>?,
                                fileChooserParams: FileChooserParams?
                        ): Boolean {
                            // Cancel any ongoing request
                            this@MainActivity.filePathCallback?.onReceiveValue(null)
                            this@MainActivity.filePathCallback = null

                            this@MainActivity.filePathCallback = filePathCallback

                            // Build an Intent array to include camera capture + file choose
                            val takePictureIntent = createCameraIntent()
                            val contentSelectionIntent =
                                    createContentSelectionIntent(fileChooserParams?.acceptTypes)

                            // Let user pick from either camera or existing files
                            val intentArray =
                                    if (takePictureIntent != null) arrayOf(takePictureIntent)
                                    else arrayOfNulls<Intent>(0)

                            // Create a chooser
                            val chooserIntent =
                                    Intent(Intent.ACTION_CHOOSER).apply {
                                        putExtra(Intent.EXTRA_INTENT, contentSelectionIntent)
                                        putExtra(Intent.EXTRA_TITLE, "Image Chooser")
                                        putExtra(
                                                Intent.EXTRA_INITIAL_INTENTS,
                                                intentArray.filterNotNull().toTypedArray()
                                        )
                                    }

                            try {
                                @Suppress("DEPRECATION")
                                startActivityForResult(chooserIntent, FILE_CHOOSER_REQUEST_CODE)
                            } catch (e: ActivityNotFoundException) {
                                this@MainActivity.filePathCallback = null
                                return false
                            }
                            return true
                        }

                        // Custom Dialog Handling
                        override fun onJsAlert(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showAlertDialog(message ?: "") { result?.confirm() }
                            return true
                        }

                        override fun onJsConfirm(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showConfirmDialog(
                                    message ?: "",
                                    { result?.confirm() },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onJsPrompt(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                defaultValue: String?,
                                result: android.webkit.JsPromptResult?
                        ): Boolean {
                            dualWebViewGroup.showPromptDialog(
                                    message ?: "",
                                    defaultValue,
                                    { text -> result?.confirm(text) },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onJsBeforeUnload(
                                view: WebView?,
                                url: String?,
                                message: String?,
                                result: android.webkit.JsResult?
                        ): Boolean {
                            dualWebViewGroup.showConfirmDialog(
                                    message ?: "Are you sure you want to leave this page?",
                                    { result?.confirm() },
                                    { result?.cancel() }
                            )
                            return true
                        }

                        override fun onCreateWindow(
                                view: WebView?,
                                isDialog: Boolean,
                                isUserGesture: Boolean,
                                resultMsg: android.os.Message?
                        ): Boolean {
                            // Must provide a pristine WebView here; Chromium will navigate it.
                            val newWebView = dualWebViewGroup.createNewWindow(loadDefaultUrl = false)
                            val transport = resultMsg?.obj as? WebView.WebViewTransport
                            if (transport != null) {
                                transport.webView = newWebView
                                resultMsg.sendToTarget()
                                return true
                            }
                            return false
                        }
                    }
        }

        // Section 5: Input Handling Configuration
        // Section 5: Input Handling Configuration
        disableDefaultKeyboard(webView)
        @Suppress("DEPRECATION")
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)

        // Only restore session for a plain reopen. If TapClaw explicitly launched
        // a URL, that explicit request must win over any persisted browser state.
        if (webView == dualWebViewGroup.getWebView() && startupUrlOverride.isNullOrBlank()) {
            tryRestoreSession()
        }

        // Initialize AudioManager
        audioManager = getSystemService(Context.AUDIO_SERVICE) as AudioManager

        // Additional WebView settings for media support
        webView.settings.apply {
            mediaPlaybackRequiresUserGesture = false
            domStorageEnabled = true
            javaScriptEnabled = true
            @Suppress("DEPRECATION")
            databaseEnabled = true
            useWideViewPort = true
            loadWithOverviewMode = true
            setSupportMultipleWindows(true)
        }

        logPermissionState() // Log initial permission state

        val androidIface = AndroidInterface(this, webView)
        ttsAndroidInterface = androidIface
        webView.addJavascriptInterface(androidIface, "AndroidInterface")
        // Add JavaScript interface for custom media handling if needed
        webView.addJavascriptInterface(
                object {
                    @JavascriptInterface
                    fun onMediaStart(type: String) {
                        when (type) {
                            "audio" ->
                                    audioManager?.setParameters(
                                            "audio_source_record=voiceassistant"
                                    )
                            "video" -> {
                                /* Handle camera initialization if needed */
                            }
                        }
                    }

                    @JavascriptInterface
                    fun onMediaStop() {
                        audioManager?.setParameters("audio_source_record=off")
                    }
                },
                "AndroidMediaInterface"
        )
    }

    private fun tryRestoreSession() {
        // Before loading the initial page, try to restore the previous session
        DebugLog.d("YouTubeAuto", "tryRestoreSession: startupUrl=$startupUrlOverride query=$youtubeAutoplayQuery")
        DebugLog.d("WebViewDebug", "Attempting to restore previous session")

        try {
            dualWebViewGroup.updateBrowsingMode(dualWebViewGroup.isDesktopMode())
            val prefs = getSharedPreferences(prefsName, MODE_PRIVATE)
            val savedState = prefs.getString(Constants.KEY_WEBVIEW_STATE, null)
            val lastUrl = prefs.getString(keyLastUrl, null)
            DebugLog.d("WebViewDebug", "Last saved URL: $lastUrl")

            val defaultDashboardUrl = Constants.DEFAULT_URL
            var restored = false

            if (!savedState.isNullOrBlank()) {
                try {
                    val data = Base64.decode(savedState, Base64.DEFAULT)
                    val parcel = Parcel.obtain()
                    parcel.unmarshall(data, 0, data.size)
                    parcel.setDataPosition(0)
                    val bundle = Bundle.CREATOR.createFromParcel(parcel)
                    parcel.recycle()
                    restored = webView.restoreState(bundle) != null
                    DebugLog.d("WebViewDebug", "WebView state restored: $restored")
                } catch (e: Exception) {
                    DebugLog.e("WebViewDebug", "Error restoring WebView state", e)
                }
            }

            if (!restored) {
                if (lastUrl != null && !lastUrl.startsWith("about:blank")) {
                    DebugLog.d("WebViewDebug", "Loading saved URL: $lastUrl")
                    webView.loadUrl(lastUrl)
                } else {
                    DebugLog.d("WebViewDebug", "No valid saved URL, loading default AR dashboard")
                    webView.loadUrl(defaultDashboardUrl)
                }
            } else {
                // Restored pages may skip onPageFinished; inject observers and refresh scrollbars.
                webView.post {
                    dualWebViewGroup.injectPageObservers(webView)
                    dualWebViewGroup.updateScrollBarsVisibility()
                }
                webView.postDelayed(
                        {
                            dualWebViewGroup.injectPageObservers(webView)
                            dualWebViewGroup.updateScrollBarsVisibility()
                        },
                        750
                )
            }
        } catch (e: Exception) {
            DebugLog.e("WebViewDebug", "Error restoring session", e)
            webView.loadUrl(Constants.DEFAULT_URL)
        }
    }

    private fun persistTapRadioPlaybackState(stationName: String?, genre: String?, playing: Boolean) {
        try {
            val prefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
            prefs.edit().apply {
                putBoolean("tapradio_now_playing_active", playing)
                putLong("tapradio_now_playing_updated_at", System.currentTimeMillis())
                if (playing && !stationName.isNullOrBlank()) {
                    putString("tapradio_now_playing_name", stationName.trim())
                    putString("tapradio_now_playing_genre", genre?.trim())
                } else {
                    remove("tapradio_now_playing_name")
                    remove("tapradio_now_playing_genre")
                }
                apply()
            }
        } catch (e: Exception) {
            DebugLog.e("TapRadioNative", "Error saving radio playback state", e)
        }
    }

    private fun buildNativeRadioPlaybackStateJson(): String {
        return org.json.JSONObject().apply {
            put("available", true)
            put("playing", nativeRadioPlayer?.isPlaying == true && !nativeRadioPreparing)
            put("preparing", nativeRadioPreparing)
            put("buffering", nativeRadioBuffering)
            put("stationName", nativeRadioStationName ?: "")
            put("genre", nativeRadioGenre ?: "")
            put("url", nativeRadioUrl ?: "")
            put("error", nativeRadioError ?: "")
            put("updatedAt", System.currentTimeMillis())
        }.toString()
    }

    private fun notifyNativeRadioStateChanged() {
        if (!::dualWebViewGroup.isInitialized) return
        val stateJson = buildNativeRadioPlaybackStateJson()
        val script =
            """
            (function() {
                if (window.tapRadioNativePlaybackUpdate) {
                    window.tapRadioNativePlaybackUpdate($stateJson);
                }
            })();
            """.trimIndent()
        dualWebViewGroup.getAllWebViews().forEach { candidate ->
            val url = candidate.url.orEmpty()
            if (!url.contains("radio.html", ignoreCase = true)) return@forEach
            candidate.post { candidate.evaluateJavascript(script, null) }
        }
    }

    private fun requestNativeRadioAudioFocus(): Boolean {
        val am = audioManager ?: (getSystemService(AUDIO_SERVICE) as? AudioManager)?.also { audioManager = it }
        return try {
            am?.requestAudioFocus(
                nativeRadioFocusListener,
                AudioManager.STREAM_MUSIC,
                AudioManager.AUDIOFOCUS_GAIN
            ) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        } catch (e: Exception) {
            DebugLog.w("TapRadioNative", "Audio focus request failed: ${e.message}")
            false
        }
    }

    private fun abandonNativeRadioAudioFocus() {
        try {
            audioManager?.abandonAudioFocus(nativeRadioFocusListener)
        } catch (_: Exception) {}
    }

    private fun releaseNativeRadioPlayer(clearMetadata: Boolean, abandonFocus: Boolean) {
        try {
            nativeRadioPlayer?.stop()
            nativeRadioPlayer?.release()
        } catch (_: Exception) {}
        nativeRadioPlayer = null
        nativeRadioPreparing = false
        nativeRadioBuffering = false
        if (clearMetadata) {
            nativeRadioUrl = null
            nativeRadioStationName = null
            nativeRadioGenre = null
            nativeRadioError = null
        }
        if (abandonFocus) {
            abandonNativeRadioAudioFocus()
        }
    }

    private fun applyNativeRadioPlaybackUiState() {
        val playing = nativeRadioPlayer?.isPlaying == true && !nativeRadioPreparing
        persistTapRadioPlaybackState(nativeRadioStationName, nativeRadioGenre, playing || nativeRadioPreparing || nativeRadioBuffering)
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.updateMediaState(playing || nativeRadioPreparing || nativeRadioBuffering)
        }
        notifyNativeRadioStateChanged()
    }

    @androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
    private fun playNativeRadioStream(url: String, stationName: String?, genre: String?): String {
        val trimmedUrl = url.trim()
        if (trimmedUrl.isBlank()) {
            nativeRadioError = "Missing stream URL"
            notifyNativeRadioStateChanged()
            return buildNativeRadioPlaybackStateJson()
        }

        val sameStream = trimmedUrl == nativeRadioUrl && nativeRadioPlayer != null
        if (sameStream) {
            return resumeNativeRadioStream()
        }

        requestNativeRadioAudioFocus()
        releaseNativeRadioPlayer(clearMetadata = false, abandonFocus = false)

        nativeRadioUrl = trimmedUrl
        nativeRadioStationName = stationName?.trim().takeUnless { it.isNullOrBlank() }
        nativeRadioGenre = genre?.trim().takeUnless { it.isNullOrBlank() }
        nativeRadioPreparing = true
        nativeRadioBuffering = false
        nativeRadioError = null
        applyNativeRadioPlaybackUiState()

        try {
            // ExoPlayer with large buffers to eliminate rebuffer stutters on MP3 streams.
            // MediaPlayer's fixed ~336KB buffer drains in ~14s at 192kbps, causing audible gaps.
            // ExoPlayer buffers 60s minimum / 120s target, making stutters virtually impossible.
            val loadControl = DefaultLoadControl.Builder()
                .setBufferDurationsMs(
                    /* minBufferMs = */       60_000,   // 60s minimum before playback starts rebuffering
                    /* maxBufferMs = */       120_000,  // 120s max buffer
                    /* bufferForPlaybackMs = */ 2_500,   // start playback after 2.5s buffered
                    /* bufferForPlaybackAfterRebufferMs = */ 5_000  // after a rebuffer, wait for 5s
                )
                .build()
            val player = ExoPlayer.Builder(this)
                .setLoadControl(loadControl)
                .setAudioAttributes(
                    androidx.media3.common.AudioAttributes.Builder()
                        .setUsage(androidx.media3.common.C.USAGE_MEDIA)
                        .setContentType(androidx.media3.common.C.AUDIO_CONTENT_TYPE_MUSIC)
                        .build(),
                    /* handleAudioFocus = */ false  // we manage focus ourselves
                )
                .setWakeMode(androidx.media3.common.C.WAKE_MODE_LOCAL)
                .build()

            player.addListener(object : Player.Listener {
                override fun onPlaybackStateChanged(playbackState: Int) {
                    when (playbackState) {
                        Player.STATE_READY -> {
                            nativeRadioPreparing = false
                            nativeRadioBuffering = false
                            nativeRadioError = null
                            applyNativeRadioPlaybackUiState()
                        }
                        Player.STATE_BUFFERING -> {
                            nativeRadioBuffering = true
                            applyNativeRadioPlaybackUiState()
                        }
                        Player.STATE_ENDED -> {
                            nativeRadioPreparing = false
                            nativeRadioBuffering = false
                            applyNativeRadioPlaybackUiState()
                        }
                        Player.STATE_IDLE -> { /* no-op */ }
                    }
                }

                override fun onPlayerError(error: PlaybackException) {
                    nativeRadioPreparing = false
                    nativeRadioBuffering = false
                    nativeRadioError = "Playback error: ${error.message}"
                    releaseNativeRadioPlayer(clearMetadata = false, abandonFocus = true)
                    applyNativeRadioPlaybackUiState()
                }
            })

            player.setMediaItem(MediaItem.fromUri(trimmedUrl))
            player.prepare()
            player.playWhenReady = true
            nativeRadioPlayer = player
        } catch (e: Exception) {
            nativeRadioPreparing = false
            nativeRadioBuffering = false
            nativeRadioError = e.message ?: "Failed to start stream"
            releaseNativeRadioPlayer(clearMetadata = false, abandonFocus = true)
            applyNativeRadioPlaybackUiState()
        }

        return buildNativeRadioPlaybackStateJson()
    }

    private fun pauseNativeRadioStreamInternal(abandonFocus: Boolean): String {
        try {
            nativeRadioPlayer?.pause()
        } catch (_: Exception) {}
        nativeRadioPreparing = false
        nativeRadioBuffering = false
        if (abandonFocus) {
            abandonNativeRadioAudioFocus()
        }
        applyNativeRadioPlaybackUiState()
        return buildNativeRadioPlaybackStateJson()
    }

    private fun pauseNativeRadioStream(): String = pauseNativeRadioStreamInternal(abandonFocus = true)

    private fun resumeNativeRadioStream(): String {
        val player = nativeRadioPlayer
        if (player == null) {
            val url = nativeRadioUrl
            return if (!url.isNullOrBlank()) {
                playNativeRadioStream(url, nativeRadioStationName, nativeRadioGenre)
            } else {
                buildNativeRadioPlaybackStateJson()
            }
        }
        requestNativeRadioAudioFocus()
        nativeRadioError = null
        try {
            if (!player.isPlaying) {
                player.play()
            }
        } catch (e: Exception) {
            nativeRadioError = e.message ?: "Failed to resume stream"
        }
        nativeRadioPreparing = false
        nativeRadioBuffering = false
        applyNativeRadioPlaybackUiState()
        return buildNativeRadioPlaybackStateJson()
    }

    private fun stopNativeRadioStream(): String {
        releaseNativeRadioPlayer(clearMetadata = true, abandonFocus = true)
        applyNativeRadioPlaybackUiState()
        return buildNativeRadioPlaybackStateJson()
    }

    private fun getNativeRadioPlaybackState(): String = buildNativeRadioPlaybackStateJson()

    private class WebAppInterface(
            private val activity: MainActivity,
            private val webView: WebView
    ) {
        @JavascriptInterface
        fun onInputFocus() {
            activity.runOnUiThread {
                // Double check that we're not already showing the keyboard
                if (!activity.isKeyboardVisible) {
                    activity.showCustomKeyboard()
                }
            }
        }

        @JavascriptInterface
        fun onMediaPlaying(isPlaying: Boolean) {
            activity.runOnUiThread {
                if (activity.dualWebViewGroup.isActiveWebView(webView)) {
                    activity.dualWebViewGroup.updateMediaState(isPlaying)
                    if (isPlaying) {
                        activity.dualWebViewGroup.pauseBackgroundMedia(webView)
                    }
                }
            }
        }

        @JavascriptInterface
        fun onMediaDetected(hasMedia: Boolean) {
            activity.runOnUiThread {
                if (!hasMedia && activity.dualWebViewGroup.isActiveWebView(webView)) {
                    activity.dualWebViewGroup.hideMediaControls()
                }
            }
        }

        /** Called by search-page JS with a JSON array of video IDs scraped
         *  from the search results (chronological order). */
        @JavascriptInterface
        fun setYouTubePlaylist(jsonIds: String) {
            try {
                val arr = org.json.JSONArray(jsonIds)
                val ids = mutableListOf<String>()
                for (i in 0 until arr.length()) {
                    val id = arr.optString(i, "").trim()
                    if (id.length == 11) ids.add(id)
                }
                activity.runOnUiThread {
                    activity.youtubePlaylist = ids
                    activity.youtubePlaylistIndex = 0
                    DebugLog.d("YouTubeAuto", "Playlist set: ${ids.size} videos — ${ids.take(5)}")
                }
            } catch (e: Exception) {
                DebugLog.d("YouTubeAuto", "Failed to parse playlist JSON: $e")
            }
        }

        /** Called by watch-page JS to enter a CSS-based "fullscreen" mode.
         *  Since Android WebView blocks all programmatic fullscreen requests
         *  (requires real user gesture), we instead:
         *  1. Inject CSS to hide everything except the video player and
         *     make it fill the entire viewport
         *  2. Enter Android immersive mode (hide system bars)
         *  This gives the same visual result as real fullscreen. */
        @JavascriptInterface
        fun enterCssFullscreen() {
            activity.runOnUiThread {
                try {
                    DebugLog.d("YouTubeAuto", "enterCssFullscreen called")

                    // CSS-only fullscreen: hide non-video elements, make video fill viewport.
                    // Buttons are injected separately by injectNavButtons().
                    val js = "(function(){" +
                        "try{" +
                        "if(document.getElementById('__taplink_fs_style'))return 'already';" +
                        "var s=document.createElement('style');" +
                        "s.id='__taplink_fs_style';" +
                        "s.textContent=" +
                        "'body>*:not(#player):not(#movie_player):not(.html5-video-player):not(ytd-player):not(#player-container-outer):not(#player-container-inner):not(#player-container):not(ytd-watch-flexy):not(#content):not(#page-manager):not(ytd-app):not(#columns):not(#primary):not(#primary-inner):not(#__tl_nav){display:none!important}'" +
                        "+'\\n#movie_player,.html5-video-player,video{position:fixed!important;top:0!important;left:0!important;width:100vw!important;height:100vh!important;z-index:999999!important;background:#000!important;object-fit:contain!important}'" +
                        "+'\\nhtml,body{overflow:hidden!important;margin:0!important;padding:0!important;background:#000!important}'" +
                        "+'\\n#masthead-container,#guide,ytd-masthead,#secondary,#below,#comments,#related,#meta,#info,#owner{display:none!important}'" +
                        "+'\\nytd-watch-flexy{max-width:100vw!important}'" +
                        "+'\\nytd-watch-flexy[theater] #player-theater-container,#player-theater-container,#player-container-outer,#player-container-inner,#player-container,ytd-player,#ytd-player{width:100vw!important;height:100vh!important;max-height:100vh!important;position:fixed!important;top:0!important;left:0!important;z-index:999998!important}'" +
                        ";" +
                        "document.head.appendChild(s);" +
                        "console.log('[TapLink-YT] CSS fullscreen applied');" +
                        "return 'ok';" +
                        "}catch(err){console.log('[TapLink-YT] enterCssFs JS error: '+err);return 'error:'+err;}" +
                        "})()"
                    webView.evaluateJavascript(js) { result ->
                        DebugLog.d("YouTubeAuto", "CSS fullscreen result: $result")
                    }

                    // Enter Android immersive mode
                    activity.window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                View.SYSTEM_UI_FLAG_FULLSCREEN or
                                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)
                    DebugLog.d("YouTubeAuto", "Entered CSS fullscreen + immersive mode")
                } catch (e: Exception) {
                    DebugLog.d("YouTubeAuto", "enterCssFullscreen failed: $e")
                }
            }
        }

        /** Injects persistent View Mode + Next buttons on any YouTube watch page.
         *  View Mode cycles: Full → Theater → Mini → Full...
         *  Full = our CSS fullscreen overlay (video fills viewport).
         *  Theater/Mini = YouTube's native modes (CSS overlay removed).
         *  Buttons go on document.body to survive YouTube DOM rebuilds.
         *  window.__tl_view_mode is preserved across re-injections. */
        @JavascriptInterface
        fun injectNavButtons() {
            activity.runOnUiThread {
                try {
                    DebugLog.d("YouTubeAuto", "injectNavButtons called")

                    val js = "(function(){" +
                        "try{" +
                        "if(document.getElementById('__tl_nav'))return 'already';" +
                        // Style
                        "if(!document.getElementById('__tl_nav_style')){" +
                        "var s=document.createElement('style');" +
                        "s.id='__tl_nav_style';" +
                        "s.textContent=" +
                        "'#__tl_nav{position:fixed;top:6px;right:12px;z-index:2000000;display:flex;gap:8px;pointer-events:auto!important}'" +
                        "+'\\n#__tl_nav button{background:rgba(0,0,0,0.7);border:1px solid rgba(255,255,255,0.3);color:#fff;font-size:16px;padding:8px 14px;border-radius:8px;cursor:pointer;white-space:nowrap;pointer-events:auto!important}'" +
                        "+'\\n#__tl_nav button:active{background:rgba(255,255,255,0.3)}'" +
                        "+'\\n#__tl_nav .tl-mode{font-size:13px;padding:8px 10px}';" +
                        "document.head.appendChild(s);" +
                        "}" +
                        // Nav container on document.body
                        "var nav=document.createElement('div');" +
                        "nav.id='__tl_nav';" +
                        //
                        // === View Mode button ===
                        // 0=Full(CSS), 1=Theater(YT native), 2=Mini(YT native)
                        //
                        "var bView=document.createElement('button');" +
                        "bView.id='__tl_view';" +
                        "bView.className='tl-mode';" +
                        // Preserve mode across re-injections; only detect if undefined
                        "if(typeof window.__tl_view_mode==='undefined'||window.__tl_view_mode===null){" +
                        "window.__tl_view_mode=0;" +
                        "if(document.getElementById('__taplink_fs_style'))window.__tl_view_mode=0;" +
                        "else{var fx=document.querySelector('ytd-watch-flexy');" +
                        "if(fx&&fx.hasAttribute('theater'))window.__tl_view_mode=1;" +
                        "}" +
                        "}" +
                        "var labels=['Full','Theater','Mini'];" +
                        "bView.textContent=labels[window.__tl_view_mode||0];" +
                        //
                        // === Click handler: self-contained transition ===
                        // Each click reads the ACTUAL page state to stay in sync.
                        //
                        "bView.addEventListener('click',function(e){" +
                        "e.stopPropagation();e.preventDefault();" +
                        // Debounce
                        "var now=Date.now();" +
                        "if(window.__tl_last_view_click&&now-window.__tl_last_view_click<800)return;" +
                        "window.__tl_last_view_click=now;" +
                        //
                        "var cur=window.__tl_view_mode||0;" +
                        "var next=(cur+1)%3;" +
                        "console.log('[TapLink-YT] View: '+labels[cur]+' -> '+labels[next]);" +
                        //
                        // --- Do the transition in one shot ---
                        //
                        "if(cur===0&&next===1){" +
                        // Full → Theater: remove CSS fs, exit immersive, enter theater
                        "var fs=document.getElementById('__taplink_fs_style');if(fs)fs.remove();" +
                        "try{window.GroqBridge.exitImmersiveMode();}catch(x){}" +
                        // Ensure theater is clean then click after delay
                        "setTimeout(function(){" +
                        "var fx=document.querySelector('ytd-watch-flexy');" +
                        "if(fx&&fx.hasAttribute('theater'))return;" + // already in theater
                        "var sb=document.querySelector('.ytp-size-button');" +
                        "if(sb)sb.click();" +
                        "},500);" +
                        "}" +
                        //
                        "else if(cur===1&&next===2){" +
                        // Theater → Mini: exit theater, then enter miniplayer
                        "var fx2=document.querySelector('ytd-watch-flexy');" +
                        "if(fx2&&fx2.hasAttribute('theater')){" +
                        "var sb2=document.querySelector('.ytp-size-button');if(sb2)sb2.click();" +
                        "}" +
                        "setTimeout(function(){" +
                        "var mb=document.querySelector('.ytp-miniplayer-button');" +
                        "if(mb)mb.click();" +
                        "},500);" +
                        "}" +
                        //
                        "else if(cur===2&&next===0){" +
                        // Mini → Full: exit miniplayer, then apply CSS fs
                        "var exp=document.querySelector('.ytp-miniplayer-expand-watch-page-button');" +
                        "if(exp){exp.click();}else{" +
                        "document.dispatchEvent(new KeyboardEvent('keydown',{key:'Escape',code:'Escape',keyCode:27,bubbles:true}));}" +
                        "setTimeout(function(){" +
                        "try{window.GroqBridge.enterCssFullscreen();}catch(x){}" +
                        "},500);" +
                        "}" +
                        //
                        // Update state and label
                        "window.__tl_view_mode=next;" +
                        "bView.textContent=labels[next];" +
                        "console.log('[TapLink-YT] View mode set to: '+labels[next]);" +
                        "});" +
                        //
                        // === Next button ===
                        //
                        "var bNext=document.createElement('button');" +
                        "bNext.id='__tl_next';" +
                        "bNext.textContent='Next';" +
                        "bNext.addEventListener('click',function(e){" +
                        "e.stopPropagation();e.preventDefault();" +
                        "var now=Date.now();" +
                        "if(window.__tl_last_next_click&&now-window.__tl_last_next_click<800)return;" +
                        "window.__tl_last_next_click=now;" +
                        "try{window.GroqBridge.playNextInPlaylist();}catch(x){}" +
                        "console.log('[TapLink-YT] Nav: Next clicked');" +
                        "});" +
                        //
                        // === Append to body + watchdog ===
                        //
                        "nav.appendChild(bView);nav.appendChild(bNext);" +
                        "document.body.appendChild(nav);" +
                        // Watchdog: re-inject if YouTube removes buttons
                        "if(window.__tl_nav_watchdog)clearInterval(window.__tl_nav_watchdog);" +
                        "window.__tl_nav_watchdog=setInterval(function(){" +
                        "if(!document.getElementById('__tl_nav')){" +
                        "clearInterval(window.__tl_nav_watchdog);" +
                        "console.log('[TapLink-YT] Nav buttons lost, re-injecting');" +
                        "try{window.GroqBridge.injectNavButtons();}catch(x){}" +
                        "}" +
                        "},2000);" +
                        "console.log('[TapLink-YT] Nav buttons injected on body (View:'+labels[window.__tl_view_mode||0]+' + Next)');" +
                        "return 'ok';" +
                        "}catch(err){console.log('[TapLink-YT] injectNav error: '+err);return 'error:'+err;}" +
                        "})()"
                    webView.evaluateJavascript(js) { result ->
                        DebugLog.d("YouTubeAuto", "injectNavButtons result: $result")
                    }
                } catch (e: Exception) {
                    DebugLog.d("YouTubeAuto", "injectNavButtons failed: $e")
                }
            }
        }

        /** Exits Android immersive mode (called when leaving CSS fullscreen view). */
        @JavascriptInterface
        fun exitImmersiveMode() {
            activity.runOnUiThread {
                try {
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                        (View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
                    DebugLog.d("YouTubeAuto", "Exited immersive mode")
                } catch (e: Exception) {
                    DebugLog.d("YouTubeAuto", "exitImmersiveMode failed: $e")
                }
            }
        }

        /** Called by watch-page JS when the current video ends or user clicks
         *  the hijacked "next" button. Loads the next video in the TapLink
         *  playlist using YouTube's internal player API so we STAY in fullscreen
         *  mode — no size changes between videos. Falls back to page navigation
         *  only if the player API isn't available. */
        @JavascriptInterface
        fun playNextInPlaylist() {
            activity.runOnUiThread {
                val pl = activity.youtubePlaylist
                val nextIdx = activity.youtubePlaylistIndex + 1
                if (nextIdx < pl.size) {
                    activity.youtubePlaylistIndex = nextIdx
                    val nextId = pl[nextIdx]
                    DebugLog.d("YouTubeAuto", "Playing next [$nextIdx/${pl.size}]: $nextId")

                    // Try YouTube's internal player API first (stays in fullscreen).
                    // The API is available on desktop-mode YouTube pages.
                    val jsLoadVideo = """
                        (function(){
                            try {
                                var p = document.getElementById('movie_player');
                                if (p && typeof p.loadVideoById === 'function') {
                                    p.loadVideoById('$nextId');
                                    console.log('[TapLink-YT] Loaded next via player API: $nextId');
                                    return 'api';
                                }
                            } catch(e) {}
                            return 'nav';
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsLoadVideo) { result ->
                        val method = result?.replace("\"", "") ?: "nav"
                        if (method == "api") {
                            // Player API worked — we're still in fullscreen.
                            // Re-bind the ended listener for the new video and
                            // re-hijack the next button (YouTube may rebuild controls).
                            DebugLog.d("YouTubeAuto", "  → player API success, staying fullscreen")
                            val rebindJs = """
                                (function(){
                                    window.__taplink_yt_injected = false;
                                    window.__taplink_watch_injected = false;
                                    window.__taplink_playback_started = false;
                                    var old = document.getElementById('__tl_nav');
                                    if (old) old.remove();
                                })();
                            """.trimIndent()
                            webView.evaluateJavascript(rebindJs, null)
                            // Re-inject the watch-page automation after a short
                            // delay to let the new video load its UI.
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                webView.evaluateJavascript(
                                    activity.buildYouTubeAutomationBootstrapScript(
                                        activity.youtubeAutoplayQuery ?: "",
                                        activity.youtubeAutoplayMode ?: ""
                                    ), null
                                )
                            }, 2000)
                        } else {
                            // Player API not available — fall back to full navigation.
                            DebugLog.d("YouTubeAuto", "  → falling back to loadUrl")
                            activity.hideFullScreenCustomView()
                            activity.lastYouTubeInjectionUrl = null
                            webView.loadUrl("https://www.youtube.com/watch?v=$nextId&autoplay=1&cc_load_policy=1")
                        }
                    }
                } else {
                    DebugLog.d("YouTubeAuto", "Playlist finished (${pl.size} videos)")
                }
            }
        }

        /** Go back one video in the playlist. If already at the first video,
         *  just restart from the beginning. */
        @JavascriptInterface
        fun playPrevInPlaylist() {
            activity.runOnUiThread {
                val pl = activity.youtubePlaylist
                val prevIdx = activity.youtubePlaylistIndex - 1
                if (prevIdx >= 0 && prevIdx < pl.size) {
                    activity.youtubePlaylistIndex = prevIdx
                    val prevId = pl[prevIdx]
                    DebugLog.d("YouTubeAuto", "Playing prev [$prevIdx/${pl.size}]: $prevId")

                    val jsLoadVideo = """
                        (function(){
                            try {
                                var p = document.getElementById('movie_player');
                                if (p && typeof p.loadVideoById === 'function') {
                                    p.loadVideoById('$prevId');
                                    console.log('[TapLink-YT] Loaded prev via player API: $prevId');
                                    return 'api';
                                }
                            } catch(e) {}
                            return 'nav';
                        })();
                    """.trimIndent()

                    webView.evaluateJavascript(jsLoadVideo) { result ->
                        val method = result?.replace("\"", "") ?: "nav"
                        if (method == "api") {
                            DebugLog.d("YouTubeAuto", "  → player API success (prev), staying fullscreen")
                            val rebindJs = """
                                (function(){
                                    window.__taplink_yt_injected = false;
                                    window.__taplink_watch_injected = false;
                                    window.__taplink_playback_started = false;
                                    var old = document.getElementById('__tl_nav');
                                    if (old) old.remove();
                                })();
                            """.trimIndent()
                            webView.evaluateJavascript(rebindJs, null)
                            android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                                webView.evaluateJavascript(
                                    activity.buildYouTubeAutomationBootstrapScript(
                                        activity.youtubeAutoplayQuery ?: "",
                                        activity.youtubeAutoplayMode ?: ""
                                    ), null
                                )
                            }, 2000)
                        } else {
                            DebugLog.d("YouTubeAuto", "  → falling back to loadUrl (prev)")
                            activity.hideFullScreenCustomView()
                            activity.lastYouTubeInjectionUrl = null
                            webView.loadUrl("https://www.youtube.com/watch?v=$prevId&autoplay=1&cc_load_policy=1")
                        }
                    }
                } else {
                    DebugLog.d("YouTubeAuto", "Already at first video, restarting")
                    val v = "javascript:void(document.querySelector('video').currentTime=0)"
                    webView.loadUrl(v)
                }
            }
        }
    }

    // ── Media file interception for TapInsight media player ──
    private val MEDIA_TEXT_EXTS = setOf("txt","md","log","csv","json","xml","html","htm","rtf","ini","cfg","conf","yaml","yml","toml")
    private val MEDIA_AUDIO_EXTS = setOf("mp3","wav","ogg","m4a","aac","flac","wma","opus")
    private val MEDIA_VIDEO_EXTS = setOf("mp4","webm","mkv","avi","mov","m4v","ogv","3gp")

    /**
     * Checks if [url] points to a recognized media file and opens it in the
     * built-in media_player.html asset. Returns true if intercepted.
     *
     * For text files: downloads content in background, then loads media player with
     * content passed via `window._textContent` injection (avoids CORS from file:// origin).
     * For audio/video: loads media player directly — `<audio>/<video>` elements aren't
     * subject to CORS restrictions so remote URLs work.
     */
    private fun interceptMediaUrl(view: WebView, url: String): Boolean {
        // Strip query string and fragment for extension detection
        val path = url.split("?")[0].split("#")[0]
        val ext = path.substringAfterLast('.', "").lowercase()
        val mediaType = when {
            MEDIA_TEXT_EXTS.contains(ext) -> "text"
            MEDIA_AUDIO_EXTS.contains(ext) -> "audio"
            MEDIA_VIDEO_EXTS.contains(ext) -> "video"
            else -> return false
        }
        val title = try {
            java.net.URLDecoder.decode(path.substringAfterLast('/'), "UTF-8")
        } catch (_: Exception) { path.substringAfterLast('/') }
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")

        // Read media player preferences from companion app settings
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }

        if (mediaType == "text") {
            // Download text content in background to avoid CORS
            DebugLog.d("MediaPlayer", "Intercepted text ($ext): $url — fetching content")
            Thread {
                try {
                    val conn = java.net.URL(url).openConnection() as java.net.HttpURLConnection
                    conn.connectTimeout = 15000
                    conn.readTimeout = 15000
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    // Escape for JavaScript injection
                    val escaped = text
                        .replace("\\", "\\\\")
                        .replace("'", "\\'")
                        .replace("\n", "\\n")
                        .replace("\r", "\\r")
                        .replace("<", "\\x3c")
                        .replace(">", "\\x3e")
                    runOnUiThread {
                        val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
                        view.loadUrl(playerUrl)
                        // Inject content after page loads via a tiny delay
                        view.postDelayed({
                            view.evaluateJavascript("window._textContent='$escaped';", null)
                        }, 300)
                    }
                } catch (e: Exception) {
                    DebugLog.e("MediaPlayer", "Failed to fetch text: ${e.message}")
                    runOnUiThread {
                        // Fall back to loading the URL directly
                        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
                        view.loadUrl("file:///android_asset/media_player.html?url=$encodedUrl&type=text&title=$encodedTitle$mediaParams")
                    }
                }
            }.start()
            return true
        }

        // Audio & Video — direct URL works via <audio>/<video> elements
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val srtParam = if (mediaType == "video") {
            val srtUrl = path.substringBeforeLast('.') + ".srt"
            "&srt=" + java.net.URLEncoder.encode(srtUrl, "UTF-8")
        } else ""
        val playerUrl = "file:///android_asset/media_player.html?url=$encodedUrl&type=$mediaType&title=$encodedTitle$srtParam$mediaParams"
        DebugLog.d("MediaPlayer", "Intercepted $mediaType ($ext): $url")
        view.loadUrl(playerUrl)
        return true
    }

    private fun createCameraIntent(): Intent? {
        val cameraIntent = Intent(MediaStore.ACTION_IMAGE_CAPTURE)
        if (cameraIntent.resolveActivity(packageManager) == null) {
            // No camera activity on device
            return null
        }
        if (cameraPermissionGranted) {
            initializeCamera()
        }
        // Create a file/URI to store the image
        val imageFile = createTempImageFile() ?: return null
        cameraImageUri = FileProvider.getUriForFile(this, "$packageName.fileprovider", imageFile)
        cameraIntent.putExtra(MediaStore.EXTRA_OUTPUT, cameraImageUri)
        return cameraIntent
    }

    private fun createContentSelectionIntent(acceptTypes: Array<String>?): Intent {
        val mimeTypes = acceptTypes?.filter { it.isNotEmpty() }?.toTypedArray() ?: arrayOf("*/*")
        return Intent(Intent.ACTION_GET_CONTENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type = if (mimeTypes.size == 1) mimeTypes[0] else "*/*"
            putExtra(Intent.EXTRA_MIME_TYPES, mimeTypes)
        }
    }

    // Example for creating a temp file
    private fun createTempImageFile(): File? {
        return try {
            val storageDir = getExternalFilesDir(Environment.DIRECTORY_PICTURES)
            File.createTempFile("tmp_image_", ".jpg", storageDir)
        } catch (e: IOException) {
            DebugLog.e("FileChooser", "Cannot create temp file", e)
            null
        }
    }

    fun getWebViewVersion(): String? {
        return try {
            // Try Google’s webview package first
            val pInfo = packageManager.getPackageInfo("com.google.android.webview", 0)
            pInfo.versionName // e.g. "114.0.5735.196"
        } catch (e: PackageManager.NameNotFoundException) {
            // Fallback: older AOSP webview, or it may be missing
            try {
                val pInfo = packageManager.getPackageInfo("com.android.webview", 0)
                pInfo.versionName // e.g. "97.0.4692.87"
            } catch (e2: PackageManager.NameNotFoundException) {
                null
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun showFullScreenCustomView(
            view: View,
            callback: WebChromeClient.CustomViewCallback?
    ) {
        if (fullScreenCustomView != null) {
            callback?.onCustomViewHidden()
            return
        }

        fullScreenCustomView = view
        customViewCallback = callback
        originalSystemUiVisibility = window.decorView.systemUiVisibility
        originalOrientation = requestedOrientation

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
                (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                        View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                        View.SYSTEM_UI_FLAG_FULLSCREEN or
                        View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY)

        dualWebViewGroup.showFullScreenOverlay(view)
        cursorLeftView.visibility = View.GONE
        cursorRightView.visibility = View.GONE
    }

    @Suppress("DEPRECATION")
    internal fun hideFullScreenCustomView() {
        if (fullScreenCustomView == null) {
            return
        }

        // If the fullscreen view is the native QR scanner, clear scanner state on exit.
        if (nativeQrScannerView != null || isQrScanInProgress) {
            nativeQrScannerView?.pause()
            nativeQrScannerView = null
            pendingNativeQrStart = false
            isQrScanInProgress = false
            qrScanCallbackWebView = null
            dualWebViewGroup.setSuppressFullscreenMediaControls(false)
        }

        dualWebViewGroup.hideFullScreenOverlay()
        fullScreenCustomView = null

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            @Suppress("DEPRECATION") window.setDecorFitsSystemWindows(false)
            window.insetsController?.hide(
                    android.view.WindowInsets.Type.statusBars() or
                            android.view.WindowInsets.Type.navigationBars()
            )
            window.insetsController?.systemBarsBehavior =
                    android.view.WindowInsetsController.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        } else {
            @Suppress("DEPRECATION")
            window.decorView.systemUiVisibility =
                    (View.SYSTEM_UI_FLAG_FULLSCREEN or
                            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                            View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                            View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                            View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        }
        requestedOrientation = originalOrientation
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        cursorLeftView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE
        cursorRightView.visibility = if (isCursorVisible) View.VISIBLE else View.GONE

        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
    }

    private fun startNativeQrScanner(sourceWebView: WebView) {
        if (isQrScanInProgress) {
            val quotedMessage = JSONObject.quote("A scan is already in progress.")
            sourceWebView.evaluateJavascript("window.__taplinkOnNativeQrError($quotedMessage);", null)
            return
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) !=
                        PackageManager.PERMISSION_GRANTED
        ) {
            qrScanCallbackWebView = sourceWebView
            pendingNativeQrStart = true
            requestPermissions(arrayOf(Manifest.permission.CAMERA), CAMERA_PERMISSION_CODE)
            return
        }

        qrScanCallbackWebView = sourceWebView
        isQrScanInProgress = true
        pendingNativeQrStart = false

        val scannerContainer =
                FrameLayout(this).apply {
                    setBackgroundColor(Color.BLACK)
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                }

        val scannerView =
                DecoratedBarcodeView(this).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.MATCH_PARENT
                            )
                    barcodeView.decoderFactory =
                            DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                    setStatusText("Point at a QR code")
                }

        scannerView.barcodeView.addStateListener(
                object : CameraPreview.StateListener {
                    override fun previewSized() {}

                    override fun previewStarted() {
                        applyDefaultQrZoom(scannerView)
                    }

                    override fun previewStopped() {}

                    override fun cameraError(error: Exception) {}

                    override fun cameraClosed() {}
                }
        )

        scannerContainer.addView(scannerView)
        nativeQrScannerView = scannerView

        dualWebViewGroup.setSuppressFullscreenMediaControls(true)
        showFullScreenCustomView(scannerContainer, null)

        scannerView.decodeContinuous(
                object : BarcodeCallback {
                    override fun barcodeResult(result: BarcodeResult?) {
                        val value = result?.text?.trim().orEmpty()
                        if (value.isEmpty() || !isQrScanInProgress) {
                            return
                        }

                        runOnUiThread {
                            if (!isQrScanInProgress) {
                                return@runOnUiThread
                            }
                            isQrScanInProgress = false
                            stopNativeQrScannerOverlay()
                            dispatchNativeQrResult(value)
                        }
                    }

                    override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>?) {
                        // No-op.
                    }
                }
        )
        scannerView.resume()
    }

    private fun applyDefaultQrZoom(scannerView: DecoratedBarcodeView) {
        @Suppress("DEPRECATION")
        scannerView.changeCameraParameters { parameters: Camera.Parameters ->
            try {
                CameraConfigurationUtils.setZoom(parameters, defaultQrZoomRatio)
            } catch (e: Exception) {
                DebugLog.w("QRScanner", "Unable to apply default camera zoom: ${e.message}")
            }
            parameters
        }
    }

    private fun dispatchNativeQrResult(contents: String) {
        val targetWebView = qrScanCallbackWebView ?: webView
        val quotedContents = JSONObject.quote(contents)
        targetWebView.evaluateJavascript("window.__taplinkOnNativeQrResult($quotedContents);", null)
        qrScanCallbackWebView = null
    }

    private fun dispatchNativeQrError(message: String, target: WebView? = qrScanCallbackWebView) {
        val targetWebView = target ?: webView
        val quotedMessage = JSONObject.quote(message)
        targetWebView.evaluateJavascript("window.__taplinkOnNativeQrError($quotedMessage);", null)
        pendingNativeQrStart = false
        isQrScanInProgress = false
        qrScanCallbackWebView = null
    }

    private fun stopNativeQrScannerOverlay() {
        nativeQrScannerView?.pause()
        nativeQrScannerView = null
        dualWebViewGroup.setSuppressFullscreenMediaControls(false)
        if (fullScreenCustomView != null) {
            hideFullScreenCustomView()
        }
    }

    private fun stopNativeQrScannerSession() {
        if (nativeQrScannerView == null && !isQrScanInProgress && !pendingNativeQrStart) {
            return
        }
        pendingNativeQrStart = false
        isQrScanInProgress = false
        qrScanCallbackWebView = null
        stopNativeQrScannerOverlay()
    }

    @Suppress("DEPRECATION")
    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        if (requestCode == FILE_CHOOSER_REQUEST_CODE) {
            if (resultCode == RESULT_OK) {
                if (filePathCallback != null) {
                    var results: Array<Uri>? = null

                    // Check if response is from Camera (data is null/empty but cameraImageUri is
                    // set)
                    // or from File Picker (data has URI)
                    if (data == null || data.data == null) {
                        // If cameraImageUri is populated, use it
                        if (cameraImageUri != null) {
                            results = arrayOf(cameraImageUri!!)
                        }
                    } else {
                        // File picker result
                        data.dataString?.let { results = arrayOf(Uri.parse(it)) }
                    }

                    filePathCallback?.onReceiveValue(results)
                    filePathCallback = null
                }
            } else {
                filePathCallback?.onReceiveValue(null)
                filePathCallback = null
            }
        }

        if (requestCode == CAMERA_REQUEST_CODE && resultCode == RESULT_OK) {
            @Suppress("DEPRECATION")
            data?.extras?.get("data")?.let { imageBitmap ->
                // Convert bitmap to base64
                val base64Image = convertBitmapToBase64(imageBitmap as Bitmap)

                // Send the image back to Google Search
                webView.evaluateJavascript(
                        """
                (function() {
                    // Find Google's image search input
                    var input = document.querySelector('input[type="file"][name="image_url"]');
                    if (!input) {
                        input = document.createElement('input');
                        input.type = 'file';
                        input.name = 'image_url';
                        document.body.appendChild(input);
                    }
                    
                    // Create a File object from base64
                    fetch('data:image/jpeg;base64,$base64Image')
                        .then(res => res.blob())
                        .then(blob => {
                            const file = new File([blob], "image.jpg", { type: 'image/jpeg' });
                            
                            // Create a FileList object
                            const dataTransfer = new DataTransfer();
                            dataTransfer.items.add(file);
                            
                            // Set the file and dispatch change event
                            input.files = dataTransfer.files;
                            input.dispatchEvent(new Event('change', { bubbles: true }));
                        });
                })();
            """,
                        null
                )
            }
        }
    }

    private fun convertBitmapToBase64(bitmap: Bitmap): String {
        val outputStream = ByteArrayOutputStream()
        bitmap.compress(Bitmap.CompressFormat.JPEG, 85, outputStream)
        val imageBytes = outputStream.toByteArray()
        return Base64.encodeToString(imageBytes, Base64.DEFAULT)
    }

    private fun checkAndRequestPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            val permissionsToRequest = mutableListOf<String>()

            // Check both permissions
            if (checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.RECORD_AUDIO)
            }
            if (checkSelfPermission(android.Manifest.permission.MODIFY_AUDIO_SETTINGS) !=
                            PackageManager.PERMISSION_GRANTED
            ) {
                permissionsToRequest.add(android.Manifest.permission.MODIFY_AUDIO_SETTINGS)
            }

            if (permissionsToRequest.isNotEmpty()) {
                requestPermissions(permissionsToRequest.toTypedArray(), PERMISSIONS_REQUEST_CODE)
            }
        }
    }

    private fun logPermissionState() {
        val cameraPermission =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.CAMERA)
        val micPermission =
                ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO)

        DebugLog.d(
                "PermissionDebug",
                """
        Permission State:
        Camera: ${if (cameraPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
        Microphone: ${if (micPermission == PackageManager.PERMISSION_GRANTED) "GRANTED" else "DENIED"}
    """.trimIndent()
        )
    }

    private fun disableDefaultKeyboard(targetWebView: WebView) {
        try {
            val method =
                    WebView::class.java.getMethod("setShowSoftInputOnFocus", Boolean::class.java)
            method.invoke(targetWebView, false)
        } catch (e: Exception) {
            // Fallback for older Android versions
            targetWebView.evaluateJavascript(
                    """
            document.addEventListener('focus', function(e) {
                if (e.target.tagName === 'INPUT' || 
                    e.target.tagName === 'TEXTAREA' || 
                    e.target.isContentEditable) {
                    window.Android.onInputFocus();
                    e.target.blur();
                    setTimeout(() => e.target.focus(), 50);
                }
            }, true);
        """,
                    null
            )
        }
    }

    // ── Google Cloud TTS state ────────────────────────────────────────────
    private var cloudTtsPlayer: android.media.MediaPlayer? = null
    private var cloudTtsSentences: List<String> = emptyList()
    private var cloudTtsCurrentSentence = 0
    @Volatile private var cloudTtsPlaying = false

    override fun onTtsTogglePressed() {
        // Toggle: if already speaking, stop
        if (cloudTtsPlaying) {
            stopCloudTts()
            dualWebViewGroup.showToast("TTS stopped")
            return
        }

        // Check API key
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val apiKey = vcPrefs.getString("cloud_tts_api_key", "") ?: ""
        if (apiKey.isBlank()) {
            dualWebViewGroup.showToast("Set Cloud TTS API key in companion app")
            return
        }

        val currentUrl = webView.url ?: ""

        // ── Google Drive: download the raw file via export URL ──
        val driveFileIdRegex = Regex("""drive\.google\.com/file/d/([^/]+)""")
        val driveMatch = driveFileIdRegex.find(currentUrl)
        if (driveMatch != null) {
            val fileId = driveMatch.groupValues[1]
            DebugLog.d("TTS", "Google Drive file detected: id=$fileId")
            val cookies = android.webkit.CookieManager.getInstance().getCookie("https://drive.google.com") ?: ""
            dualWebViewGroup.showToast("Reading document...")
            Thread {
                try {
                    val exportUrl = "https://drive.google.com/uc?export=download&id=$fileId"
                    val conn = java.net.URL(exportUrl).openConnection() as java.net.HttpURLConnection
                    conn.setRequestProperty("Cookie", cookies)
                    conn.instanceFollowRedirects = true
                    conn.connectTimeout = 10_000
                    conn.readTimeout = 15_000
                    val text = conn.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() }
                    conn.disconnect()
                    if (text.isNotBlank() && text.length >= 10) {
                        runOnUiThread { startCloudTts(text.trim()) }
                        return@Thread
                    }
                } catch (e: Exception) {
                    DebugLog.e("TTS", "Google Drive download failed", e)
                }
                runOnUiThread { extractTextForCloudTts() }
            }.start()
            return
        }

        // ── Normal path: extract text via JS ──
        extractTextForCloudTts()
    }

    /** Reference to the AndroidInterface attached to webView, for setting pending text. */
    private var ttsAndroidInterface: AndroidInterface? = null

    /**
     * Extract text from the current page via JavaScript, then start Cloud TTS.
     */
    private fun extractTextForCloudTts() {
        webView.evaluateJavascript(
                """
            (function() {
                function cleanText(s) {
                    return (s || '').replace(/ /g, ' ').replace(/\s{3,}/g, '  ').trim();
                }
                function candidateText(el) {
                    if (!el) return '';
                    return cleanText(el.innerText || el.textContent || '');
                }
                function chooseLongest(list) {
                    var best = '';
                    for (var i = 0; i < list.length; i++) {
                        var t = cleanText(list[i]);
                        if (t.length > best.length) best = t;
                    }
                    return best;
                }
                function grabText(doc) {
                    // Prefer visible modal/preview/document surfaces first.
                    var prioritySelectors = [
                        '[role="dialog"] [role="document"]',
                        '[role="dialog"] [data-target="doc"]',
                        '[role="dialog"] pre',
                        '[role="dialog"] article',
                        '[role="dialog"] .doc-container',
                        '[role="dialog"] [contenteditable="true"]',
                        '.modal-dialog pre',
                        '.modal-dialog article',
                        '.modal-dialog .doc-container',
                        '.ReactModal__Content pre',
                        '.ReactModal__Content article',
                        '.drive-viewer-text-layer',
                        '.ndfHFb-c4YZDc-Wrql6b',
                        '.ndfHFb-c4YZDc-aTv5jf',
                        'pre',
                        'article',
                        '.doc-container',
                        '[contenteditable="true"]',
                        '[role="main"]',
                        'main'
                    ];
                    for (var s = 0; s < prioritySelectors.length; s++) {
                        var nodes = doc.querySelectorAll(prioritySelectors[s]);
                        if (nodes && nodes.length) {
                            var texts = [];
                            for (var n = 0; n < nodes.length; n++) {
                                var cs = doc.defaultView && doc.defaultView.getComputedStyle ? doc.defaultView.getComputedStyle(nodes[n]) : null;
                                if (cs && (cs.display === 'none' || cs.visibility === 'hidden')) continue;
                                texts.push(candidateText(nodes[n]));
                            }
                            var picked = chooseLongest(texts);
                            if (picked.length >= 40) return picked;
                        }
                    }
                    return candidateText(doc.body);
                }
                var text = '';
                try { text = grabText(document); } catch(e) {}
                if ((!text || text.trim().length < 40) && document.querySelectorAll('iframe').length > 0) {
                    var frames = document.querySelectorAll('iframe');
                    for (var i = 0; i < frames.length; i++) {
                        try {
                            var fdoc = frames[i].contentDocument || frames[i].contentWindow.document;
                            if (fdoc && fdoc.body) {
                                var ft = grabText(fdoc);
                                if (ft && ft.trim().length > text.trim().length) text = ft;
                            }
                        } catch(e) {}
                    }
                }
                return (text || '').trim();
            })();
            """) { result ->
            val cleaned = result
                ?.removeSurrounding("\"")
                ?.replace("\\n", "\n")
                ?.replace("\\t", "\t")
                ?.replace("\\\"", "\"")
                ?.replace("\\'", "'")
                ?.replace("\\\\", "\\")
                ?.trim()
                ?: ""
            if (cleaned.length < 10 || cleaned == "null") {
                dualWebViewGroup.showToast("No readable text found on this page")
            } else {
                DebugLog.d("TTS", "Extracted ${cleaned.length} chars for Cloud TTS")
                startCloudTts(cleaned)
            }
        }
    }

    /**
     * Split text into chunks (~4000 chars max, breaking at sentence boundaries)
     * and start synthesizing + playing via Google Cloud TTS API.
     * Audio is played natively via MediaPlayer — no WebView audio needed.
     */
    private fun startCloudTts(text: String) {
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val apiKey = vcPrefs.getString("cloud_tts_api_key", "")?.trim().orEmpty()
        val configuredVoiceName = vcPrefs.getString("cloud_tts_voice_name", "")?.trim().orEmpty()
        val voiceName = configuredVoiceName.ifBlank { "en-US-Standard-A" }
        val configuredLanguage = vcPrefs.getString("cloud_tts_language", "")?.trim().orEmpty()
        val language = configuredLanguage.ifBlank {
            Regex("""^[a-z]{2,3}-[A-Z]{2}""")
                .find(voiceName)
                ?.value
                ?: "en-US"
        }

        if (apiKey.isBlank()) {
            dualWebViewGroup.showToast("Set Cloud TTS API key in companion app")
            return
        }

        // Split text into chunks at sentence boundaries (~4000 chars each, Cloud TTS limit is 5000)
        cloudTtsSentences = splitTextIntoChunks(text, 4000)
        cloudTtsCurrentSentence = 0
        cloudTtsPlaying = true

        DebugLog.d("TTS", "Starting Cloud TTS: ${text.length} chars, ${cloudTtsSentences.size} chunks, voice=$voiceName")
        dualWebViewGroup.showToast("Speaking...")

        // Start synthesizing and playing the first chunk
        synthesizeAndPlayChunk(0, apiKey, voiceName, language)
    }

    /**
     * Split text into chunks of maxLen chars, breaking at sentence boundaries.
     */
    private fun splitTextIntoChunks(text: String, maxLen: Int): List<String> {
        val chunks = mutableListOf<String>()
        var remaining = text
        while (remaining.isNotEmpty()) {
            if (remaining.length <= maxLen) {
                chunks.add(remaining)
                break
            }
            var splitAt = -1
            for (i in maxLen downTo maxLen / 2) {
                val ch = remaining[i]
                if (ch == '.' || ch == '!' || ch == '?' || ch == '\n') {
                    splitAt = i + 1
                    break
                }
            }
            if (splitAt == -1) splitAt = maxLen
            chunks.add(remaining.substring(0, splitAt))
            remaining = remaining.substring(splitAt).trimStart()
        }
        return chunks
    }

    /**
     * Call Google Cloud TTS REST API to synthesize one chunk, then play the
     * resulting MP3 via MediaPlayer. On completion, auto-advances to next chunk.
     */
    private fun synthesizeAndPlayChunk(
        chunkIndex: Int,
        apiKey: String,
        voiceName: String,
        language: String
    ) {
        if (chunkIndex >= cloudTtsSentences.size || !cloudTtsPlaying) {
            cloudTtsPlaying = false
            return
        }
        cloudTtsCurrentSentence = chunkIndex
        val chunkText = cloudTtsSentences[chunkIndex]

        Thread {
            try {
                val url = java.net.URL("https://texttospeech.googleapis.com/v1/text:synthesize?key=$apiKey")
                val conn = url.openConnection() as java.net.HttpURLConnection
                conn.requestMethod = "POST"
                conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8")
                conn.doOutput = true
                conn.connectTimeout = 15_000
                conn.readTimeout = 30_000

                // Build voice name: if user specified one use it, otherwise construct default
                val effectiveVoiceName = if (voiceName.isNotBlank()) voiceName
                    else "${language}-Standard-A"

                DebugLog.d("TTS", "Chunk $chunkIndex: voice=$effectiveVoiceName lang=$language text=${chunkText.length} chars")

                val json = org.json.JSONObject().apply {
                    put("input", org.json.JSONObject().put("text", chunkText))
                    put("voice", org.json.JSONObject().apply {
                        put("languageCode", language)
                        put("name", effectiveVoiceName)
                    })
                    put("audioConfig", org.json.JSONObject().apply {
                        put("audioEncoding", "MP3")
                    })
                }

                conn.outputStream.use { it.write(json.toString().toByteArray(Charsets.UTF_8)) }

                val responseCode = conn.responseCode
                if (responseCode != 200) {
                    val errorBody = conn.errorStream?.bufferedReader()?.use { it.readText() } ?: ""
                    DebugLog.e("TTS", "Cloud TTS API error $responseCode: $errorBody")
                    val msg = when (responseCode) {
                        400 -> {
                            // Parse the error detail from the response
                            val detail = try {
                                val errJson = org.json.JSONObject(errorBody)
                                errJson.optJSONObject("error")?.optString("message", "") ?: ""
                            } catch (_: Exception) { "" }
                            if (detail.isNotBlank()) "TTS 400: ${detail.take(80)}"
                            else "TTS bad request — check voice/language settings"
                        }
                        403 -> "TTS API not enabled — enable it in Google Cloud Console"
                        401 -> "Invalid API key — check companion app settings"
                        429 -> "TTS rate limit — try again shortly"
                        else -> "TTS API error: $responseCode"
                    }
                    runOnUiThread { dualWebViewGroup.showToast(msg) }
                    cloudTtsPlaying = false
                    conn.disconnect()
                    return@Thread
                }

                val responseBody = conn.inputStream.bufferedReader().use { it.readText() }
                conn.disconnect()

                val responseJson = org.json.JSONObject(responseBody)
                val audioBase64 = responseJson.getString("audioContent")
                val audioBytes = Base64.decode(audioBase64, Base64.DEFAULT)

                // Write to temp file and play via MediaPlayer
                val tempFile = java.io.File(cacheDir, "tts_chunk_$chunkIndex.mp3")
                tempFile.writeBytes(audioBytes)

                runOnUiThread {
                    playTtsAudioFile(tempFile, chunkIndex, apiKey, voiceName, language)
                }

            } catch (e: Exception) {
                DebugLog.e("TTS", "Cloud TTS synthesis failed for chunk $chunkIndex", e)
                runOnUiThread {
                    dualWebViewGroup.showToast("TTS error: ${e.message?.take(50)}")
                }
                cloudTtsPlaying = false
            }
        }.start()
    }

    /**
     * Play an MP3 file via MediaPlayer, then auto-advance to next chunk on completion.
     */
    private fun playTtsAudioFile(
        file: java.io.File,
        chunkIndex: Int,
        apiKey: String,
        voiceName: String,
        language: String
    ) {
        cloudTtsPlayer?.release()
        cloudTtsPlayer = android.media.MediaPlayer().apply {
            setDataSource(file.absolutePath)
            setOnCompletionListener {
                file.delete()
                if (cloudTtsPlaying) {
                    synthesizeAndPlayChunk(chunkIndex + 1, apiKey, voiceName, language)
                }
            }
            setOnErrorListener { _, what, extra ->
                DebugLog.e("TTS", "MediaPlayer error: what=$what extra=$extra")
                file.delete()
                cloudTtsPlaying = false
                true
            }
            prepare()
            start()
        }
    }

    /**
     * Stop Cloud TTS playback.
     */
    private fun stopCloudTts() {
        cloudTtsPlaying = false
        cloudTtsPlayer?.let {
            try {
                if (it.isPlaying) it.stop()
            } catch (_: Exception) {}
            it.release()
        }
        cloudTtsPlayer = null
    }

    // ── Legacy media_player.html text reader (kept for MediaInterface/DualWebViewGroup) ──

    fun openTextReaderDirect(title: String) {
        val vcPrefs = getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val encodedTitle = java.net.URLEncoder.encode(title.take(200), "UTF-8")
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }
        val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
        webView.loadUrl(playerUrl)
    }

    // Add this method to handle permission results
    override fun onRequestPermissionsResult(
            requestCode: Int,
            permissions: Array<out String>,
            grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        when (requestCode) {
            CAMERA_PERMISSION_CODE -> {
                cameraPermissionGranted =
                        grantResults.isNotEmpty() &&
                                grantResults[0] == PackageManager.PERMISSION_GRANTED

                if (!cameraPermissionGranted) {
                    DebugLog.e("Camera", "Camera permission denied")
                    // Inform user that camera features won't work
                    webView.evaluateJavascript(
                            "alert('Camera permission is required for image search');",
                            null
                    )
                    if (pendingNativeQrStart) {
                        dispatchNativeQrError("Camera permission denied.")
                    }
                } else if (pendingNativeQrStart) {
                    val targetWebView = qrScanCallbackWebView ?: webView
                    startNativeQrScanner(targetWebView)
                }
            }
        }
    }

    private fun loadInitialPage() {
        DebugLog.d("WebViewDebug", "loadInitialPage called")
        // Load Google directly without the intermediate blank page
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    fun showCustomKeyboard() {
        DebugLog.d("KeyboardDebug", "1. Starting showCustomKeyboard")

        if (isKeyboardVisible &&
                        keyboardView?.visibility == View.VISIBLE &&
                        dualWebViewGroup.keyboardContainer.visibility == View.VISIBLE
        ) {
            DebugLog.d("KeyboardDebug", "Keyboard already visible; ignoring show request")
            return
        }

        // Force WebView to lose focus
        webView.clearFocus()
        DebugLog.d("KeyboardDebug", "2. WebView focus cleared")

        if (wasKeyboardDismissedByEnter) {
            wasKeyboardDismissedByEnter = false
            if (!dualWebViewGroup.isUrlEditing()) {
                return
            }
        }

        // Ensure keyboard view exists and is properly configured
        if (keyboardView == null) {
            DebugLog.d("KeyboardDebug", "3. Creating new keyboard view")
            keyboardView =
                    CustomKeyboardView(this).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(
                                        FrameLayout.LayoutParams.MATCH_PARENT,
                                        FrameLayout.LayoutParams.WRAP_CONTENT,
                                        Gravity.BOTTOM
                                )
                        setOnKeyboardActionListener(this@MainActivity)
                        DebugLog.d("KeyboardDebug", "Keyboard created with visibility: $visibility")
                    }
        }

        // Hide info bars when keyboard shows
        dualWebViewGroup.hideInfoBars()

        keyboardView?.let { keyboard ->
            // Log state before setting keyboard
            DebugLog.d(
                    "KeyboardDebug",
                    """
            Before setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent()
            )

            // Force visibility BEFORE setting keyboard
            keyboard.visibility = View.VISIBLE
            dualWebViewGroup.keyboardContainer.visibility = View.VISIBLE

            dualWebViewGroup.setKeyboard(keyboard)

            // Log state after setting keyboard
            DebugLog.d(
                    "KeyboardDebug",
                    """
            After setKeyboard:
            Keyboard visibility: ${keyboard.visibility}
            Container visibility: ${dualWebViewGroup.keyboardContainer.visibility}
            isKeyboardVisible: $isKeyboardVisible
        """.trimIndent()
            )
            isKeyboardVisible = true

            // Ensure keyboard is on top of dialogs
            dualWebViewGroup.keyboardContainer.elevation = 3000f
            dualWebViewGroup.keyboardContainer.bringToFront()
        }

        isKeyboardVisible = true

        dualWebViewGroup.post {
            dualWebViewGroup.updateScrollBarsVisibility()
            dualWebViewGroup.requestLayout()
            dualWebViewGroup.invalidate()
            refreshCursor()
        }
    }

    private fun toggleAnchor() {

        isAnchored = !isAnchored

        // Save anchored mode state
        getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putBoolean(Constants.KEY_IS_ANCHORED, isAnchored)
                .apply()

        hideCustomKeyboard()
        DebugLog.d(
                "AnchorDebug",
                """
        Anchor toggled:
        isAnchored: $isAnchored
        isKeyboardVisible: $isKeyboardVisible
        keyboardView null?: ${keyboardView == null}
    """.trimIndent()
        )
        if (isAnchored) {
            // Move cursor to center of left screen
            centerCursor()

            // Initialize sensor handling with reset velocity tracking
            smoothedDeltaX = 0f
            smoothedDeltaY = 0f
            smoothedRollDeg = 0f
            lastFrameTime = 0L

            sensorEventListener = createSensorEventListener()
            rotationSensor?.let { sensor ->
                // Use UI rate for good responsiveness with power savings (smoothing handles jitter)
                sensorManager.registerListener(
                        sensorEventListener,
                        sensor,
                        SensorManager.SENSOR_DELAY_UI
                )
            }
            dualWebViewGroup.startAnchoring()
        } else {
            // CRITICAL: Set isAnchored to false FIRST to stop any pending sensor callbacks
            // (The sensor listener checks this flag before applying updates)

            // Stop sensor updates immediately
            sensorManager.unregisterListener(sensorEventListener)

            // Wait a tiny bit for any in-flight Choreographer callbacks to complete
            // before resetting positions
            Handler(Looper.getMainLooper())
                    .postDelayed(
                            {
                                // Now reset view positions after sensor updates have stopped
                                dualWebViewGroup.stopAnchoring()

                                // Restore cursor position
                                refreshCursor()
                            },
                            50
                    ) // Small delay to ensure pending frames are processed
        }
    }

    private fun scheduleCursorUpdate() {
        if (!pendingCursorUpdate) {
            pendingCursorUpdate = true
            uiHandler.postDelayed(
                    {
                        pendingCursorUpdate = false
                        refreshCursor()
                    },
                    8
            )
        }
    }

    /**
     * Updates the smoothing factors based on user preference slider (0-100) 0 = Fastest/least
     * smooth (high factor values = more responsive) 100 = Slowest/most smooth (low factor values =
     * very smooth) 80 = Default balanced setting
     */
    private fun updateSmoothnessFactors(level: Int) {
        smoothnessLevel = level.coerceIn(0, 100)

        // Map 0-100 to smoothing factors with INVERTED non-linear scaling
        // Higher slider values = LOWER factors = MORE smoothing
        // Lower slider values = HIGHER factors = LESS smoothing (more responsive)

        // Quaternion SLERP: 0.40 (fast/left) to 0.02 (very smooth/right)
        // Inverting: 100 - level gives us the inverse
        // Range expanded to compensate for SENSOR_DELAY_UI timing
        val invertedLevel = 100 - smoothnessLevel
        anchorSmoothingFactor = 0.02f + (invertedLevel / 100f) * 0.38f

        // Velocity smoothing: 0.55 (fast/left) to 0.05 (very smooth/right)
        velocitySmoothing = 0.05f + (invertedLevel / 100f) * 0.50f

        DebugLog.d(
                "SmoothnessDebug",
                """
            Smoothness updated:
            Level: $smoothnessLevel (0=fast, 100=smooth)
            Inverted: $invertedLevel
            Quaternion SLERP: $anchorSmoothingFactor
            Velocity Damping: $velocitySmoothing
        """.trimIndent()
        )
    }

    /** Public function called from DualWebViewGroup when user adjusts smoothness slider */
    fun updateAnchorSmoothness(level: Int) {
        updateSmoothnessFactors(level)
        // Preference is already saved by DualWebViewGroup, just update the factors
    }

    private fun createSensorEventListener(): SensorEventListener {
        return object : SensorEventListener {
            var initialQuaternion: FloatArray? = null
            var smoothedQuaternion: FloatArray? = null

            override fun onSensorChanged(event: SensorEvent) {
                if (!isAnchored || event.sensor.type != Sensor.TYPE_ROTATION_VECTOR) return

                // Frame rate limiting to prevent excessive updates
                val currentTime = SystemClock.elapsedRealtime()
                if (currentTime - lastFrameTime < MIN_FRAME_INTERVAL_MS) return
                lastFrameTime = currentTime

                val qx = event.values[0]
                val qy = event.values[1]
                val qz = event.values[2]
                val qw = event.values[3]
                val currentQuaternion = floatArrayOf(qw, qx, qy, qz)

                // Initialize smoothed quaternion if needed
                if (smoothedQuaternion == null) {
                    smoothedQuaternion = currentQuaternion.clone()
                } else {
                    // Apply smoothing (SLERP) using dynamic factor
                    smoothedQuaternion =
                            quaternionSlerp(
                                    smoothedQuaternion!!,
                                    currentQuaternion,
                                    anchorSmoothingFactor
                            )
                }

                // Use the smoothed quaternion for calculations
                val activeQuaternion = smoothedQuaternion!!

                // Reset initial quaternion if requested
                if (shouldResetInitialQuaternion || initialQuaternion == null) {
                    initialQuaternion = activeQuaternion.clone()
                    shouldResetInitialQuaternion = false
                    // Reset velocity smoothing
                    smoothedDeltaX = 0f
                    smoothedDeltaY = 0f
                    smoothedRollDeg = 0f
                    return
                }

                val initialQuaternionInv = quaternionInverse(initialQuaternion!!)
                val relativeQuaternion = quaternionMultiply(initialQuaternionInv, activeQuaternion)

                val euler = quaternionToEuler(relativeQuaternion) // [roll, pitch, yaw]
                val rollRad = euler[2] // or [2], etc., depends on your system
                val rollDeg = Math.toDegrees(rollRad.toDouble()).toFloat()

                val deltaX = relativeQuaternion[1] * TRANSLATION_SCALE
                val deltaY = relativeQuaternion[2] * TRANSLATION_SCALE

                // Apply velocity smoothing (double exponential smoothing) using dynamic factor
                smoothedDeltaX =
                        smoothedDeltaX * (1f - velocitySmoothing) + deltaX * velocitySmoothing
                smoothedDeltaY =
                        smoothedDeltaY * (1f - velocitySmoothing) + deltaY * velocitySmoothing
                smoothedRollDeg =
                        smoothedRollDeg * (1f - velocitySmoothing) + rollDeg * velocitySmoothing

                // Use Choreographer to sync with display vsync for buttery smooth updates
                Choreographer.getInstance().postFrameCallback {
                    // Double-check isAnchored before applying update (prevents race conditions)
                    if (isAnchored) {
                        dualWebViewGroup.updateLeftEyePosition(
                                smoothedDeltaX,
                                smoothedDeltaY,
                                smoothedRollDeg
                        )
                    }
                }
            }

            override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
    }

    fun quaternionToEuler(q: FloatArray): FloatArray {
        val w = q[0]
        val x = q[1]
        val y = q[2]
        val z = q[3]

        // roll (x-axis rotation)
        val sinrCosp = 2f * (w * x + y * z)
        val cosrCosp = 1f - 2f * (x * x + y * y)
        val roll = atan2(sinrCosp, cosrCosp)

        // pitch (y-axis rotation)
        val sinp = 2f * (w * y - z * x)
        val pitch =
                if (abs(sinp) >= 1f) {
                    // Use 90 degrees if out of range
                    PI.toFloat() / 2f * if (sinp > 0f) 1f else -1f
                } else {
                    asin(sinp)
                }

        // yaw (z-axis rotation)
        val sinyCosp = 2f * (w * z + x * y)
        val cosyCosp = 1f - 2f * (y * y + z * z)
        val yaw = atan2(sinyCosp, cosyCosp)

        return floatArrayOf(roll, pitch, yaw)
    }

    /**
     * Toggles the cursor visibility.
     *
     * The optional [forceHide] and [forceShow] parameters make sure that callers can explicitly
     * request a desired state instead of relying on the current value. All state changes happen
     * within a synchronized block to avoid race conditions when rapid tap gestures or delayed
     * callbacks attempt to toggle the cursor simultaneously.
     */
    private fun toggleCursorVisibility(forceHide: Boolean = false, forceShow: Boolean = false) {
        DebugLog.d(
                "DoubleTapDebug",
                """
            Toggle Cursor Visibility:
            Force Hide: $forceHide
            Force Show: $forceShow
            Previous Visible: $isCursorVisible
            isSimulating: $isSimulatingTouchEvent
            cursorJustAppeared: $cursorJustAppeared
            isToggling: $isToggling
        """.trimIndent()
        )

        synchronized(cursorToggleLock) {
            if (isToggling) return
            isToggling = true

            try {
                val previouslyVisible = isCursorVisible
                val targetVisibility =
                        when {
                            forceHide -> false
                            forceShow -> true
                            else -> !previouslyVisible
                        }

                // Early exit if already in the desired state (but still reset isToggling in
                // finally)
                if (targetVisibility == previouslyVisible) {
                    return
                }

                isCursorVisible = targetVisibility

                // Synchronise scroll mode with the cursor visibility state.
                dualWebViewGroup.setScrollMode(!isCursorVisible)

                if (isCursorVisible) {
                    cancelActiveTouchScrollGesture()

                    if (!isAnchored) {
                        lastCursorX = lastKnownCursorX
                        lastCursorY = lastKnownCursorY
                    } else {
                        lastCursorX = 320f
                        lastCursorY = 240f
                    }

                    cursorJustAppeared = true
                    // Block interactions briefly to prevent stale taps from firing as the cursor
                    // reappears.
                    // Note: removed isSimulatingTouchEvent = true as it causes OnTouchListener to
                    // ALLOW events through
                    Handler(Looper.getMainLooper()).postDelayed({ cursorJustAppeared = false }, 300)
                } else {
                    lastKnownCursorX = lastCursorX
                    lastKnownCursorY = lastCursorY

                    val webViewLocation = IntArray(2)
                    webView.getLocationOnScreen(webViewLocation)
                    lastKnownWebViewX = lastCursorX - webViewLocation[0]
                    lastKnownWebViewY = lastCursorY - webViewLocation[1]

                    webView.evaluateJavascript("window.toggleTouchEvents(false);", null)
                }

                refreshCursor()
            } finally {
                isToggling = false
            }
        }
    }

    override fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean) {
        val scale = dualWebViewGroup.uiScale
        val shouldRenderCursor = isVisible && !isMouseTapMode

        // Calculate visual position scaled around center (320, 240) and translated (only in
        // non-anchored mode)
        val transX = if (isAnchored) 0f else dualWebViewGroup.leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else dualWebViewGroup.leftEyeUIContainer.translationY

        val visualX = 320f + (x - 320f) * scale + transX
        val visualY = 240f + (y - 240f) * scale + transY

        // Logic to prevent "wrapping":
        // 1. Left cursor should ONLY be visible if it is within the left screen bounds (< 640)
        // 2. Right cursor (which is at visualX + 640) should ONLY be visible if visualX >= 0 (so
        // final x >= 640)
        // Note: We use a small buffer (e.g. -20 to 660) if we want to allow partial cursor
        // visibility at edges,
        // but strictly preventing wrapping means keeping it to the 640 boundary.

        val showLeft = shouldRenderCursor && visualX < 640f
        val showRight = shouldRenderCursor && visualX >= 0f

        // Left screen cursor - pivot at top-left so scaling happens from cursor tip
        cursorLeftView.pivotX = 0f
        cursorLeftView.pivotY = 0f
        cursorLeftView.x = visualX
        cursorLeftView.y = visualY
        cursorLeftView.scaleX = scale
        cursorLeftView.scaleY = scale
        cursorLeftView.visibility = if (showLeft) View.VISIBLE else View.GONE

        // Right screen cursor, offset by 640 pixels to appear on the right screen
        cursorRightView.pivotX = 0f
        cursorRightView.pivotY = 0f
        cursorRightView.x = visualX + 640
        cursorRightView.y = visualY
        cursorRightView.scaleX = scale
        cursorRightView.scaleY = scale
        cursorRightView.visibility = if (showRight) View.VISIBLE else View.GONE

        // Force layout and redraw for both cursors to ensure visibility
        cursorLeftView.requestLayout()
        cursorRightView.requestLayout()
        cursorLeftView.invalidate()
        cursorRightView.invalidate()
    }

    override fun onKeyPressed(key: String) {
        DebugLog.d("LinkEditing", "onKeyPressed called with: $key")
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                // Handle bookmark menu navigation
                dualWebViewGroup.getBookmarksView().handleKeyboardInput(key)
            }
            editFieldVisible -> {
                // Handle any edit field input (URL or bookmark)
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart

                // Insert the key at cursor position
                val newText = StringBuilder(currentText).insert(cursorPosition, key).toString()

                // Set text and move cursor after inserted character
                dualWebViewGroup.setLinkText(newText, cursorPosition + 1)
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart
                val newText = StringBuilder(currentText).insert(cursorPosition, key).toString()
                input.setText(newText)
                input.setSelection(cursorPosition + 1)
            }
            else -> {
                sendCharacterToWebView(key)
            }
        }
    }

    private fun handleUserInteraction() {
        if (isCursorVisible && !isKeyboardVisible) {}
    }

    override fun onBackspacePressed() {
        DebugLog.d("LinkEditing", "onBackspacePressed called")
        val editFieldVisible = dualWebViewGroup.urlEditText.visibility == View.VISIBLE

        when {
            dualWebViewGroup.isBookmarksExpanded() && !editFieldVisible -> {
                dualWebViewGroup.getBookmarksView().handleKeyboardInput("backspace")
            }
            editFieldVisible -> {
                val currentText = dualWebViewGroup.getCurrentLinkText()
                val cursorPosition = dualWebViewGroup.urlEditText.selectionStart

                if (cursorPosition > 0) {
                    // Delete character before cursor
                    val newText =
                            StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()

                    // Set text and move cursor to position before deleted character
                    dualWebViewGroup.setLinkText(newText, cursorPosition - 1)
                }
            }
            dualWebViewGroup.getDialogInput() != null -> {
                val input = dualWebViewGroup.getDialogInput()!!
                val currentText = input.text.toString()
                val cursorPosition = input.selectionStart
                if (cursorPosition > 0) {
                    val newText =
                            StringBuilder(currentText).deleteCharAt(cursorPosition - 1).toString()
                    input.setText(newText)
                    input.setSelection(cursorPosition - 1)
                }
            }
            else -> {
                sendBackspaceToWebView()
            }
        }
    }

    override fun onEnterPressed() {
        isKeyboardVisible = false // if enter is pressed keyboard is no longer visible
        if (isUrlEditing) {

            isUrlEditing = false
            dualWebViewGroup.toggleIsUrlEditing(isUrlEditing)
        }

        wasKeyboardDismissedByEnter = true
        when {

            // If bookmarks are visible and being edited, handle bookmark updates
            dualWebViewGroup.isBookmarksExpanded() -> {
                dualWebViewGroup.getBookmarksView().onEnterPressed()
                hideCustomKeyboard()
            }
            dualWebViewGroup.getDialogInput() != null -> {
                // If in dialog input, enter might mean confirm, or just hide keyboard?
                // Usually OK button handles the confirm.
                // Let's just hide keyboard for now or do nothing.
                hideCustomKeyboard()
            }
            // Otherwise handle regular keyboard input
            else -> {
                sendEnterToWebView()
            }
        }
    }

    override fun onHideKeyboard() {
        suppressWebClickUntil = SystemClock.uptimeMillis() + 250
        if (dualWebViewGroup.isBookmarkEditing()) {
            dualWebViewGroup.hideBookmarkEditing()
        }
        hideCustomKeyboard()
    }

    override fun onRefreshPressed() {
        val currentUrl = webView.url
        webView.evaluateJavascript(
                """
            (function() {
                const injectedStyles = document.querySelectorAll('style[data-injected="true"]');
                injectedStyles.forEach(style => style.remove());
                
                let viewport = document.querySelector('meta[name="viewport"]');
                if (viewport) {
                    viewport.content = 'width=device-width, initial-scale=1.0, maximum-scale=1.0, user-scalable=no';
                }
            })();
        """,
                null
        )

        Handler(Looper.getMainLooper())
                .postDelayed(
                        {
                            if (currentUrl != null) {
                                webView.loadUrl(currentUrl)
                            } else {
                                webView.loadUrl(Constants.DEFAULT_URL)
                            }
                        },
                        50
                )
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (event.action == KeyEvent.ACTION_DOWN) {
            when (event.keyCode) {
                KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE -> {
                    dualWebViewGroup.toggleMediaPlayback()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PLAY -> {
                    dualWebViewGroup.playMedia()
                    return true
                }
                KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                    dualWebViewGroup.pauseMedia()
                    return true
                }
            }
        }
        return super.dispatchKeyEvent(event)
    }

    override fun dispatchTouchEvent(ev: MotionEvent): Boolean {
        val deviceName = ev.device?.name ?: InputDevice.getDevice(ev.deviceId)?.name
        val isMainTouchpadEvent = deviceName?.contains("cyttsp5_mt", ignoreCase = true) == true
        if (isMainTouchpadEvent) {
            ensureMouseTapModeDisabled()
        }

        // Temple arm input should only be used for mode-toggle double taps.
        // The temple controller is cyttsp6_mt (NOT cyttsp5_mt which is the main touchpad).
        // Match cyttsp6 specifically but allow suffix variants for hardware revisions.
        val templeDeviceName = ev.device?.name ?: ""
        if (templeDeviceName.contains("cyttsp6", ignoreCase = true)) {
            templeDoubleTapDetector.onTouchEvent(ev)
            return true
        }

        autoEnterMouseModeForMudraInput(ev)

        val isMouseEvent = isMousePointerEvent(ev)

        // Track state at start of touch to prevent double-dispatch issues
        if (ev.action == MotionEvent.ACTION_DOWN) {
            wasTouchOnBookmarks = false
            wasTouchOnKeyboard = false
            wasKeyboardVisibleAtDown = isKeyboardVisible

            if (::dualWebViewGroup.isInitialized) {
                // In anchored mode, use the eye center (look-to-click) for coordinate checks
                // In non-anchored mode, use the raw touch coordinates
                val checkX: Float
                val checkY: Float

                if (isAnchored) {
                    val groupLocation = IntArray(2)
                    dualWebViewGroup.getLocationOnScreen(groupLocation)
                    checkX = 320f + groupLocation[0]
                    checkY = 240f + groupLocation[1]
                } else {
                    // In non-anchored mode, check if the CURSOR (not the touch) is over the
                    // bookmarks
                    // This matches the logic in dispatchTouchEventAtCursor
                    val scale = dualWebViewGroup.uiScale
                    val transX = dualWebViewGroup.leftEyeUIContainer.translationX
                    val transY = dualWebViewGroup.leftEyeUIContainer.translationY

                    val visualX = 320f + (lastCursorX - 320f) * scale + transX
                    val visualY = 240f + (lastCursorY - 240f) * scale + transY

                    val groupLocation = IntArray(2)
                    dualWebViewGroup.getLocationOnScreen(groupLocation)

                    checkX = visualX + groupLocation[0]
                    checkY = visualY + groupLocation[1]
                }

                if (dualWebViewGroup.isPointInBookmarks(checkX, checkY)) {
                    wasTouchOnBookmarks = true
                }
                if (dualWebViewGroup.isPointInKeyboard(checkX, checkY)) {
                    wasTouchOnKeyboard = true
                }
            }
        }

        // Let gestureDetector see the event for global gestures (like double-tap back)
        // regardless of whether a child view consumes it.
        if (!isMouseEvent) {
            isDispatchingTouchEvent = true
            try {
                isGestureHandled = gestureDetector.onTouchEvent(ev)
            } finally {
                isDispatchingTouchEvent = false
            }
        } else {
            val mousePoint = resolveMouseScreenPoint(ev)
            val rawX = mousePoint.first
            val rawY = mousePoint.second
            val mappedPoint = mapMousePointForVirtualTap(rawX, rawY)
            val mappedX = mappedPoint.first
            val mappedY = mappedPoint.second
            val usedRightEyeMapping = isMouseTapMode && mappedX != rawX

            lastMouseRawX = rawX
            lastMouseRawY = rawY
            lastMouseMappedX = mappedX
            lastMouseMappedY = mappedY

            val useMouseForGestures = !isMouseTapMode
            if (useMouseForGestures) {
                val gestureAction =
                        when (ev.actionMasked) {
                            MotionEvent.ACTION_BUTTON_PRESS -> MotionEvent.ACTION_DOWN
                            MotionEvent.ACTION_BUTTON_RELEASE -> MotionEvent.ACTION_UP
                            else -> ev.actionMasked
                        }

                val shouldSendToGestureDetector =
                        gestureAction == MotionEvent.ACTION_DOWN ||
                                gestureAction == MotionEvent.ACTION_MOVE ||
                                gestureAction == MotionEvent.ACTION_UP ||
                                gestureAction == MotionEvent.ACTION_CANCEL

                isDispatchingTouchEvent = true
                try {
                    if (shouldSendToGestureDetector) {
                        val injectDownBeforeUp =
                                gestureAction == MotionEvent.ACTION_UP && !mouseGestureActive

                        if (injectDownBeforeUp) {
                            val syntheticDownTime =
                                    if (ev.downTime > 0L && ev.downTime <= ev.eventTime) ev.downTime
                                    else ev.eventTime
                            val syntheticDown =
                                    MotionEvent.obtain(
                                            syntheticDownTime,
                                            syntheticDownTime,
                                            MotionEvent.ACTION_DOWN,
                                            mappedX,
                                            mappedY,
                                            ev.metaState
                                    )
                            syntheticDown.source = InputDevice.SOURCE_TOUCHSCREEN
                            try {
                                gestureDetector.onTouchEvent(syntheticDown)
                            } finally {
                                syntheticDown.recycle()
                            }
                            mouseGestureDownTime = syntheticDownTime
                            mouseGestureActive = true
                        }

                        if (gestureAction == MotionEvent.ACTION_DOWN) {
                            mouseGestureDownTime = ev.eventTime
                            mouseGestureActive = true
                        }

                        val gestureDownTime =
                                if (gestureAction == MotionEvent.ACTION_DOWN) ev.eventTime
                                else if (mouseGestureActive) mouseGestureDownTime
                                else ev.downTime

                        val gestureEvent =
                                MotionEvent.obtain(
                                        gestureDownTime,
                                        ev.eventTime,
                                        gestureAction,
                                        mappedX,
                                        mappedY,
                                        ev.metaState
                                )
                        gestureEvent.source = InputDevice.SOURCE_TOUCHSCREEN
                        try {
                            isGestureHandled = gestureDetector.onTouchEvent(gestureEvent)
                        } finally {
                            gestureEvent.recycle()
                        }

                        if (gestureAction == MotionEvent.ACTION_UP ||
                                        gestureAction == MotionEvent.ACTION_CANCEL
                        ) {
                            mouseGestureActive = false
                        }
                    } else {
                        isGestureHandled = false
                    }
                } finally {
                    isDispatchingTouchEvent = false
                }
            } else {
                isGestureHandled = false
            }

            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.updatePointerHover(mappedX, mappedY)
                    }
                }
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_CANCEL -> {
                    if (::dualWebViewGroup.isInitialized) {
                        dualWebViewGroup.clearPointerHover()
                    }
                }
            }

            if (ev.actionMasked == MotionEvent.ACTION_UP ||
                            ev.actionMasked == MotionEvent.ACTION_BUTTON_RELEASE
            ) {
                if (!useMouseForGestures) {
                    val dragSlop = 10f
                    val movedSinceDown =
                            kotlin.math.abs(mappedX - mouseSwipeStartX) >= dragSlop ||
                                    kotlin.math.abs(mappedY - mouseSwipeStartY) >= dragSlop
                    val longPressLike =
                            mouseSwipeTracking &&
                                    (ev.eventTime - mouseSwipeDownTime) >= 120L &&
                                    !mouseSwipeStartedOnCustomUi
                    val dragLikeRelease = movedSinceDown || longPressLike

                    if (mouseSwipeDownDispatched) {
                        dispatchWebTouchFromScreen(
                                MotionEvent.ACTION_CANCEL,
                                mappedX,
                                mappedY,
                                ev.eventTime
                        )
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }

                    if (dragLikeRelease && !mouseSwipeStartedOnCustomUi) {
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }

                    if (handleMouseClickForCustomUi(mappedX, mappedY)) {
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }
                    if (usedRightEyeMapping) {
                        dispatchWebTapAtScreenCoordinates(mappedX, mappedY)
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                        return true
                    }
                    maybeShowKeyboardForMouseClick(mappedX, mappedY)
                    mouseSwipeTracking = false
                    mouseSwipeStartedOnCustomUi = false
                    mouseSwipeDownDispatched = false
                }
            }

            if (!useMouseForGestures) {
                when (ev.actionMasked) {
                    MotionEvent.ACTION_DOWN,
                    MotionEvent.ACTION_BUTTON_PRESS -> {
                        mouseSwipeTracking = true
                        mouseSwipeStartedOnCustomUi = isPointOnCustomUi(mappedX, mappedY)
                        mouseSwipeDownDispatched = false
                        mouseSwipeStartX = mappedX
                        mouseSwipeStartY = mappedY
                        mouseSwipeLastX = mappedX
                        mouseSwipeLastY = mappedY
                        mouseSwipeDownTime = ev.eventTime
                    }
                    MotionEvent.ACTION_MOVE,
                    MotionEvent.ACTION_HOVER_MOVE -> {
                        if (mouseSwipeTracking && !mouseSwipeStartedOnCustomUi) {
                            val dragSlop = 10f
                            val movedEnough =
                                    kotlin.math.abs(mappedX - mouseSwipeStartX) >= dragSlop ||
                                            kotlin.math.abs(mappedY - mouseSwipeStartY) >= dragSlop

                            if (!mouseSwipeDownDispatched && movedEnough) {
                                mouseSwipeDownDispatched =
                                        dispatchWebTouchFromScreen(
                                                MotionEvent.ACTION_DOWN,
                                                mouseSwipeStartX,
                                                mouseSwipeStartY,
                                                mouseSwipeDownTime,
                                                mouseSwipeDownTime
                                        )
                            }

                            if (mouseSwipeDownDispatched) {
                                dispatchWebTouchFromScreen(
                                        MotionEvent.ACTION_MOVE,
                                        mappedX,
                                        mappedY,
                                        ev.eventTime,
                                        mouseSwipeDownTime
                                )
                                mouseSwipeLastX = mappedX
                                mouseSwipeLastY = mappedY
                                return true
                            }
                        }
                    }
                    MotionEvent.ACTION_CANCEL -> {
                        if (mouseSwipeDownDispatched) {
                            dispatchWebTouchFromScreen(
                                    MotionEvent.ACTION_CANCEL,
                                    mouseSwipeLastX,
                                    mouseSwipeLastY,
                                    ev.eventTime,
                                    mouseSwipeDownTime
                            )
                        }
                        mouseSwipeTracking = false
                        mouseSwipeStartedOnCustomUi = false
                        mouseSwipeDownDispatched = false
                    }
                }
            }
        }

        // Reset idle timer on any touch to restore full refresh rate
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.noteUserInteraction()
        }

        // The main glasses touchpad is handled through our own cursor/gesture pipeline.
        // Letting those raw events keep bubbling through the Android view tree can
        // trigger duplicate actions underneath the intended target ("ghost taps").
        if (!isMouseEvent && isMainTouchpadEvent) {
            return true
        }

        return super.dispatchTouchEvent(ev)
    }

    override fun dispatchGenericMotionEvent(ev: MotionEvent): Boolean {
        autoEnterMouseModeForMudraInput(ev)

        if (isMousePointerEvent(ev) && ::dualWebViewGroup.isInitialized) {
            val mousePoint = resolveMouseScreenPoint(ev)
            val rawX = mousePoint.first
            val rawY = mousePoint.second
            val mappedPoint = mapMousePointForVirtualTap(rawX, rawY)
            val mappedX = mappedPoint.first
            val mappedY = mappedPoint.second

            lastMouseRawX = rawX
            lastMouseRawY = rawY
            lastMouseMappedX = mappedX
            lastMouseMappedY = mappedY

            when (ev.actionMasked) {
                MotionEvent.ACTION_HOVER_MOVE,
                MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    dualWebViewGroup.updatePointerHover(mappedX, mappedY)
                }
                MotionEvent.ACTION_HOVER_EXIT,
                MotionEvent.ACTION_CANCEL -> {
                    dualWebViewGroup.clearPointerHover()
                }
            }
        }

        return super.dispatchGenericMotionEvent(ev)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        autoEnterMouseModeForMudraInput(event)

        DebugLog.d(
                "RingInput",
                """
        Touch Event:
        Action: ${event.action}
        Source: ${event.source}
        Device: ${event.device?.name}
        ButtonState: ${event.buttonState}
        Pressure: ${event.pressure}
        Size: ${event.size}
        EventTime: ${event.eventTime}
        DownTime: ${event.downTime}
        Duration: ${event.eventTime - event.downTime}ms
    """.trimIndent()
        )

        // Use the result captured in dispatchTouchEvent to avoid calling it twice
        val handled = isGestureHandled

        if (event.action == MotionEvent.ACTION_UP || event.action == MotionEvent.ACTION_CANCEL) {
            // triple click menu fling logic was here
        }

        // If gesture detector handled it, consume the event
        return handled || super.onTouchEvent(event)
    }

    // In the implementation of NavigationListener
    override fun onHomePressed() {
        val homeUrl = dualWebViewGroup.getBookmarksView().getHomeUrl()
        webView.loadUrl(homeUrl)
    }

    // In the implementation of NavigationListener
    override fun onSettingsPressed() {
        DebugLog.d("Navigation", "Settings pressed")
        dualWebViewGroup.showSettings()
    }

    // Add the navigation interface implementations
    override fun onNavigationBackPressed() {
        val historyList = webView.copyBackForwardList()
        val canGoBack = webView.canGoBack()

        DebugLog.d(
                "NavigationDebug",
                """
            Back pressed:
            Current URL: ${webView.url}
            Can go back: $canGoBack
            History size: ${historyList.size}
        """.trimIndent()
        )

        if (!canGoBack) {
            DebugLog.d("NavigationDebug", "No history entry available for goBack()")
            return
        }

        dualWebViewGroup.updateLoadingProgress(0)

        if (historyList.size > 1) {
            historyList.getItemAtIndex(historyList.size - 2).url.also {
                DebugLog.d("NavigationDebug", "Attempting to go back to: $it")
            }
        } else {
            DebugLog.d("NavigationDebug", "History stack did not expose a previous URL")
        }

        // First, stop all JavaScript execution and ongoing loads
        webView.evaluateJavascript("window.stop();", null)
        webView.stopLoading()

        // Clear all JavaScript intervals and timeouts
        webView.evaluateJavascript(
                """
                (function() {
                    // Clear all intervals and timeouts
                    const highestId = window.setInterval(() => {}, 0);
                    for (let i = highestId; i >= 0; i--) {
                        window.clearInterval(i);
                        window.clearTimeout(i);
                    }

                    // Clear onbeforeunload which some sites use to trap users
                    window.onbeforeunload = null;

                    // Force clear any alert/confirm/prompt dialogs
                    window.alert = function(){};
                    window.confirm = function(){return true;};
                    window.prompt = function(){return '';};
                })();
            """.trimIndent(),
                null
        )

        // Keep JavaScript enabled and go back
        webView.goBack()
        webView.invalidate()
        dualWebViewGroup.invalidate()
    }

    override fun onQuitPressed() {
        finish()
    }

    override fun onDestroy() {
        if (activeInstanceRef?.get() === this) {
            activeInstanceRef = null
        }
        super.onDestroy()
        // Cancel all pending handler callbacks to prevent activity leaks
        uiHandler.removeCallbacksAndMessages(null)
        gpsHandler.removeCallbacksAndMessages(null)
        speechRecognizer?.destroy()
        speechRecognizer = null
        stopCloudTts()
        cameraDevice?.close()
        imageReader?.close()
        cameraThread?.quitSafely()
        cameraThread = null
        cameraHandler = null
        sensorManager.unregisterListener(sensorEventListener)
        stopGpsUpdates()
        // Release DualWebViewGroup resources
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.cleanupResources()
        }
    }

    private fun prepareForIncomingYouTubeAutoplayInternal() {
        if (!::dualWebViewGroup.isInitialized || !::webView.isInitialized) return

        DebugLog.d("YouTubeAuto", "prepareForIncomingYouTubeAutoplayInternal: suspending existing YouTube playback before handoff")
        dualWebViewGroup.pauseYouTubeMediaAcrossAllWindows()

        val currentUrl = webView.url.orEmpty()
        val isCurrentYouTube =
                currentUrl.contains("youtube.com", ignoreCase = true) ||
                        currentUrl.contains("youtu.be", ignoreCase = true)
        if (!isCurrentYouTube) return

        try {
            webView.stopLoading()
        } catch (_: Exception) {}

        webView.evaluateJavascript(
                """
                (function() {
                    try {
                        document.querySelectorAll('video, audio').forEach(function(el) {
                            try {
                                el.pause();
                                el.autoplay = false;
                                el.muted = true;
                                el.currentTime = 0;
                                el.removeAttribute('src');
                                el.load();
                            } catch (inner) {}
                        });
                    } catch (outer) {}
                    try {
                        var tid = window.setTimeout(function(){}, 0);
                        while (tid--) clearTimeout(tid);
                        var iid = window.setInterval(function(){}, 0);
                        while (iid--) clearInterval(iid);
                    } catch (timers) {}
                    window.__taplink_yt_injected = false;
                    window.__taplink_watch_injected = false;
                    window.__taplink_playback_started = false;
                })();
                """.trimIndent(),
                null
        )
    }

    override fun onStop() {
        super.onStop()

        // Save a full window snapshot only when we are actually stopping.
        if (::dualWebViewGroup.isInitialized) {
            dualWebViewGroup.saveAllWindowsState(forceSync = true)
        }

        // Persist active state on stop as a final snapshot.
        persistActiveWebViewState("onStop", webView)
        stopGpsUpdates()
    }

    private fun persistActiveWebViewState(reason: String, activeView: WebView? = webView) {
        if (!::dualWebViewGroup.isInitialized) {
            return
        }

        val targetView = activeView ?: return
        if (!dualWebViewGroup.isActiveWebView(targetView)) {
            return
        }

        val currentUrl = targetView.url
        if (currentUrl.isNullOrBlank() || currentUrl.startsWith("about:blank")) {
            return
        }

        DebugLog.d("WebViewDebug", "Persisting active state ($reason): $currentUrl")

        getSharedPreferences(prefsName, MODE_PRIVATE)
                .edit()
                .putString(keyLastUrl, currentUrl)
                .apply()
        lastUrl = currentUrl

        try {
            val webViewState = Bundle()
            targetView.saveState(webViewState)

            val parcel = Parcel.obtain()
            webViewState.writeToParcel(parcel, 0)
            val serializedState = Base64.encodeToString(parcel.marshall(), Base64.DEFAULT)
            parcel.recycle()

            getSharedPreferences(prefsName, MODE_PRIVATE).edit {
                putString(Constants.KEY_WEBVIEW_STATE, serializedState)
            }

            DebugLog.d("WebViewDebug", "WebView state persisted successfully ($reason)")
        } catch (e: Exception) {
            DebugLog.e("WebViewDebug", "Error persisting WebView state ($reason)", e)
        }
    }

    private fun persistActiveUrl(reason: String, url: String, activeView: WebView? = webView) {
        if (!::dualWebViewGroup.isInitialized) {
            return
        }

        val targetView = activeView ?: return
        if (!dualWebViewGroup.isActiveWebView(targetView)) {
            return
        }

        if (url.startsWith("about:blank")) {
            return
        }

        DebugLog.d("WebViewDebug", "Persisting last URL ($reason): $url")
        getSharedPreferences(prefsName, MODE_PRIVATE).edit().putString(keyLastUrl, url).apply()
        lastUrl = url
    }

    // Add JavaScript interface to reset capturing state
    class AndroidInterface(private val activity: MainActivity, private val webView: WebView) {

        // ── Pending text for the media player pull-based approach ──
        // openTextReader stores the text here; media_player.html pulls it
        // via getPendingText() once it has fully loaded — no race conditions.
        @Volatile var pendingReaderText: String? = null
            private set
        @Volatile var pendingReaderTitle: String? = null
            private set

        fun setPendingText(text: String, title: String) {
            pendingReaderText = text
            pendingReaderTitle = title
        }

        @JavascriptInterface
        fun getPendingText(): String {
            val text = pendingReaderText ?: ""
            pendingReaderText = null   // consume once
            pendingReaderTitle = null
            return text
        }

        @JavascriptInterface
        fun onScrollMetrics(
                rangeX: Double,
                extentX: Double,
                offsetX: Double,
                rangeY: Double,
                extentY: Double,
                offsetY: Double
        ) {
            if (!activity.dualWebViewGroup.isActiveWebView(webView)) {
                return
            }
            activity.runOnUiThread {
                activity.dualWebViewGroup.updateExternalScrollMetrics(
                        rangeX.toInt(),
                        extentX.toInt(),
                        offsetX.toInt(),
                        rangeY.toInt(),
                        extentY.toInt(),
                        offsetY.toInt()
                )
            }
        }

        @JavascriptInterface
        fun onCaptureComplete() {
            activity.runOnUiThread { activity.isCapturing = false }
        }

        @JavascriptInterface
        fun startNativeQrScanner() {
            activity.runOnUiThread { activity.startNativeQrScanner(webView) }
        }

        @JavascriptInterface
        fun stopNativeQrScanner() {
            activity.runOnUiThread { activity.stopNativeQrScannerSession() }
        }

        /**
         * Called from the dashboard JS when the user edits links.
         * Writes the full dashboard JSON to SharedPreferences so the
         * companion app can read/write the same data.
         */
        @JavascriptInterface
        fun saveDashboardData(json: String) {
            try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.edit().putString("dashboard_data", json).apply()
                DebugLog.d("AndroidInterface", "Dashboard data saved to SharedPreferences (${json.length} chars)")
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error saving dashboard data", e)
            }
        }

        /**
         * Returns saved dashboard JSON from SharedPreferences (written by
         * the companion app's Dashboard editor), or empty string if none.
         */
        @JavascriptInterface
        fun getDashboardData(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("dashboard_data", "") ?: ""
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error reading dashboard data", e)
                ""
            }
        }

        /**
         * Returns saved TapRadio stations JSON from SharedPreferences
         * (written by the companion app's TapRadio editor).
         */
        @JavascriptInterface
        fun getRadioStations(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.getString("tapradio_stations", "") ?: ""
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error reading radio stations", e)
                ""
            }
        }

        /**
         * Saves TapRadio stations JSON to SharedPreferences so the
         * companion app and glasses player share the same station list.
         */
        @JavascriptInterface
        fun saveRadioStations(json: String) {
            try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                prefs.edit().putString("tapradio_stations", json).apply()
                DebugLog.d("AndroidInterface", "Radio stations saved to SharedPreferences (${json.length} chars)")
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error saving radio stations", e)
            }
        }

        /**
         * Persists the actual TapRadio playback state so the chat HUD can
         * reflect what is truly playing when the user returns from TapBrowser.
         */
        @JavascriptInterface
        fun saveRadioPlaybackState(stationName: String?, genre: String?, playing: Boolean) {
            activity.persistTapRadioPlaybackState(stationName, genre, playing)
        }

        @JavascriptInterface
        fun getRadioPlaybackState(): String {
            return try {
                val prefs = activity.getSharedPreferences("visionclaw_prefs", MODE_PRIVATE)
                org.json.JSONObject().apply {
                    put("playing", prefs.getBoolean("tapradio_now_playing_active", false))
                    put("stationName", prefs.getString("tapradio_now_playing_name", "") ?: "")
                    put("genre", prefs.getString("tapradio_now_playing_genre", "") ?: "")
                    put("updatedAt", prefs.getLong("tapradio_now_playing_updated_at", 0L))
                }.toString()
            } catch (e: Exception) {
                DebugLog.e("AndroidInterface", "Error reading radio playback state", e)
                "{\"playing\":false}"
            }
        }

        // ── Media player exit ──────────────────────────────────────────
        @JavascriptInterface
        fun exitMediaPlayer() {
            DebugLog.d("AndroidInterface", "exitMediaPlayer called from media_player.html")
            activity.runOnUiThread {
                // Navigate back in WebView history (returns to the page that opened the file)
                val wv = activity.findViewById<WebView>(android.R.id.content)
                    ?: activity.window?.decorView?.rootView?.findViewWithTag<WebView>("mainWebView")
                if (wv != null && wv.canGoBack()) {
                    wv.goBack()
                } else {
                    activity.onBackPressed()
                }
            }
        }

        // ── Open text reader from any page ────────────────────────────────
        @JavascriptInterface
        fun openTextReader(text: String, title: String) {
            DebugLog.d("AndroidInterface", "openTextReader: ${text.length} chars, title=$title")
            if (text.isBlank()) return
            // Store text so media_player.html can pull it via getPendingText()
            // once it has fully loaded — no race conditions.
            setPendingText(text, title)
            activity.runOnUiThread {
                activity.openTextReaderDirect(title)
            }
        }

        // ── Native radio bridge (ExoPlayer) ──────────────────────────────
        // These methods are called from radio.html JavaScript to use the
        // native ExoPlayer-backed radio player instead of the HTML5 <audio>
        // element, providing much larger configurable buffers and
        // eliminating periodic rebuffer stutters.

        /** Helper: run a block on the UI thread and wait for its String result. */
        private fun runOnUiBlocking(block: () -> String): String {
            val latch = java.util.concurrent.CountDownLatch(1)
            var result = ""
            activity.runOnUiThread {
                try { result = block() } finally { latch.countDown() }
            }
            try { latch.await(3, java.util.concurrent.TimeUnit.SECONDS) } catch (_: Exception) {}
            return result.ifEmpty { activity.buildNativeRadioPlaybackStateJson() }
        }

        @JavascriptInterface
        fun playNativeRadioStream(url: String, stationName: String?, genre: String?): String {
            return runOnUiBlocking { activity.playNativeRadioStream(url, stationName, genre) }
        }

        @JavascriptInterface
        fun pauseNativeRadioStream(): String {
            return runOnUiBlocking { activity.pauseNativeRadioStream() }
        }

        @JavascriptInterface
        fun resumeNativeRadioStream(): String {
            return runOnUiBlocking { activity.resumeNativeRadioStream() }
        }

        @JavascriptInterface
        fun stopNativeRadioStream(): String {
            return runOnUiBlocking { activity.stopNativeRadioStream() }
        }

        @JavascriptInterface
        fun getNativeRadioPlaybackState(): String {
            return activity.buildNativeRadioPlaybackStateJson()
        }
    }
}
