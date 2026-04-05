package com.TapLinkX3.app

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.SharedPreferences
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PixelFormat
import android.graphics.Rect
import android.graphics.drawable.GradientDrawable
import android.media.AudioManager
import android.media.audiofx.Visualizer
import android.net.wifi.WifiManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcel
import android.os.PowerManager
import android.os.SystemClock
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
import android.text.method.ScrollingMovementMethod
import android.util.AttributeSet
import android.util.Base64
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.PixelCopy
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.ViewTreeObserver
import android.webkit.WebView
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.SeekBar
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import kotlin.math.roundToInt
import kotlin.math.pow
import org.json.JSONObject

@SuppressLint("ClickableViewAccessibility")
class DualWebViewGroup
@JvmOverloads
constructor(context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0) :
        ViewGroup(context, attrs, defStyleAttr) {

    // Custom WebView to expose protected scroll methods
    private inner class InternalWebView(context: Context) : WebView(context) {
        fun getHorizontalScrollRange() = super.computeHorizontalScrollRange()
        fun getHorizontalScrollExtent() = super.computeHorizontalScrollExtent()
        fun getHorizontalScrollOffset() = super.computeHorizontalScrollOffset()
        fun getVerticalScrollRange() = super.computeVerticalScrollRange()
        fun getVerticalScrollExtent() = super.computeVerticalScrollExtent()
        fun getVerticalScrollOffset() = super.computeVerticalScrollOffset()
    }

    private val PREFS_NAME = "TapLinkPrefs"
    private val KEY_WINDOWS_STATE = "saved_windows_state"
    private val KEY_BROWSER_SHOW_SYSTEM_INFO = "browser_show_system_info"
    private val sharedConfigPrefs =
            context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
    private val sharedConfigListener =
            SharedPreferences.OnSharedPreferenceChangeListener { _, key ->
                if (key == KEY_BROWSER_SHOW_SYSTEM_INFO) {
                    post {
                        updateSystemInfoBarVisibility()
                        requestLayout()
                        invalidate()
                    }
                }
            }

    private data class BrowserWindow(
            val id: String = java.util.UUID.randomUUID().toString(),
            val webView: InternalWebView,
            var thumbnail: Bitmap? = null,
            var title: String = "New Tab"
    )

    private data class ScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int
    )

    private data class ExternalScrollMetrics(
            val rangeX: Int,
            val extentX: Int,
            val offsetX: Int,
            val rangeY: Int,
            val extentY: Int,
            val offsetY: Int,
            val timestamp: Long
    )

    private val windows = java.util.concurrent.CopyOnWriteArrayList<BrowserWindow>()
    private var activeWindowId: String? = null

    interface WindowCallback {
        fun onWindowCreated(webView: WebView)
        fun onWindowSwitched(webView: WebView)
    }

    var windowCallback: WindowCallback? = null

    private var webView: InternalWebView

    val webViewsContainer: FrameLayout =
            FrameLayout(context).apply { setBackgroundColor(Color.BLACK) }

    // REFACTORED: rightEyeView no longer needed - single viewport mode
    // BinocularSbsLayout now handles the binocular SBS rendering
    private val rightEyeView: SurfaceView = SurfaceView(context)

    val dialogContainer: FrameLayout =
            FrameLayout(context).apply {
                layoutParams = FrameLayout.LayoutParams(640, 480) // Full left eye size
                setBackgroundColor(Color.parseColor("#CC000000")) // Semi-transparent black
                visibility = View.GONE
                isClickable = true
                isFocusable = true
                elevation = 2000f
            }
    private var customKeyboard: CustomKeyboardView? = null
    private var bitmap: Bitmap? = null

    private var velocityTracker: android.view.VelocityTracker? = null
    private val refreshHandler = Handler(Looper.getMainLooper())
    private var refreshInterval = 16L // ~60fps for smooth mirroring
    private val maskedRefreshIntervalMs = 100L // ~10fps while the screen is masked
    private var lastCaptureTime = 0L
    private var lastScrollBarCheckTime = 0L
    private val scrollBarVisibilityThrottleMs = 50L
    private val MIN_CAPTURE_INTERVAL = 16L // Cap at ~60fps
    private var lastCursorUpdateTime = 0L
    private val CURSOR_UPDATE_INTERVAL = 16L // 60fps cap for cursor updates
    private var lastScrollBarInteractionTime = 0L
    private val scrollBarHoldMs = 1200L
    private var lastHorzScrollableAt = 0L
    private var lastVertScrollableAt = 0L
    private var externalScrollMetrics: ExternalScrollMetrics? = null
    private val externalScrollMetricsStaleMs = 600000L // 10 minutes
    private var isMediaPlaying = false
    private var lastMediaPlayingAt = 0L
    private var lastMediaInteractionTime = 0L
    private val mediaScrollFreezeMs = 1500L
    private val mediaStateByWindowId = java.util.concurrent.ConcurrentHashMap<String, Boolean>()
    private val mediaLastPlayedAtByWindowId = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private val youtubeMediaTeardownScript =
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
            })();
            """.trimIndent()

    // Idle detection for power saving
    private var lastUserInteractionTime = 0L
    private val idleThresholdMs = 5000L // 5 seconds before considered idle
    private val idleRefreshIntervalMs = 100L // ~10fps when idle

    private lateinit var leftSystemInfoView: SystemInfoView

    lateinit var leftNavigationBar: View
    private val navBarHeightPx = 32.dp()
    private val toggleBarWidthPx = 32.dp()
    private val toggleButtonSizePx = toggleBarWidthPx

    val keyboardContainer: FrameLayout =
            FrameLayout(context).apply {
                val containerWidth = 640 - toggleBarWidthPx
                layoutParams =
                        FrameLayout.LayoutParams(
                                        containerWidth,
                                        FrameLayout.LayoutParams.WRAP_CONTENT
                                )
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    gravity = Gravity.TOP or Gravity.START
                                }
                setBackgroundColor(Color.TRANSPARENT)
                visibility = View.GONE
            }
    private val buttonFeedbackDuration = 200L
    var lastCursorX = 0f
    var lastCursorY = 0f

    private var anchoredGestureActive = false
    private var anchoredTarget = 0 // 0: None, 1: Keyboard, 2: Bookmarks, 3: Menu
    private var anchoredTouchStartX = 0f
    private var anchoredTouchStartY = 0f
    private var lastAnchoredY = 0f
    private var isAnchoredDrag = false
    private val ANCHORED_TOUCH_SLOP = 10f

    lateinit var leftToggleBar: View
    var progressBar: android.widget.ProgressBar =
            android.widget.ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal)
                    .apply {
                        layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, 4)
                        progressDrawable.setTint(Color.BLUE)
                        max = 100
                        visibility = View.GONE
                        elevation = 200f // Ensure it's above other views
                    }
    private var btnShowNavBars: ImageButton =
            ImageButton(context).apply {
                layoutParams =
                        FrameLayout.LayoutParams(toggleButtonSizePx, toggleButtonSizePx).apply {
                            gravity = Gravity.BOTTOM or Gravity.END
                            rightMargin = 8
                            bottomMargin = 8
                        }
                setImageResource(R.drawable.ic_visibility_on)
                setBackgroundColor(Color.BLACK)
                scaleType = ImageView.ScaleType.FIT_CENTER
                setPadding(8, 8, 8, 8)
                alpha = 1.0f
                visibility = View.GONE
                elevation = 2000f
                setOnClickListener {
                    setScrollMode(false)
                    setNavBarsHidden(false)
                }
            }

    @Volatile private var isRefreshing = false
    private val refreshLock = Any()

    private var isDesktopMode = false
    private var currentWebZoom = 1.0f
    private var isHoveringModeToggle = false
    private var isHoveringDashboardToggle = false
    private var isHoveringBookmarksMenu = false

    private lateinit var leftBookmarksView: BookmarksView
    private lateinit var chatView: ChatView

    var navigationListener: NavigationListener? = null
    var linkEditingListener: LinkEditingListener? = null

    private var isBookmarkEditing = false

    private var mobileUserAgent: String
    private var desktopUserAgent: String = ""

    private val verticalScrollFraction = 0.25f // Scroll vertically by 25% of the viewport per tap

    private var isHoveringZoomIn = false
    private var isHoveringZoomOut = false
    private var isHoveringWindowsToggle = false
    private var windowsButton: FontIconView? = null

    private var fullScreenTapDetector: GestureDetector =
            GestureDetector(
                    context,
                    object : GestureDetector.SimpleOnGestureListener() {
                        override fun onDown(e: MotionEvent): Boolean {
                            // Always accept the initial down event so we can track the full gesture
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }

                        override fun onSingleTapUp(e: MotionEvent): Boolean {
                            // Controls visibility is now managed by dispatchFullScreenOverlayTouch
                            // in MainActivity, which handles button hit-testing first.
                            // Just consume the event here to prevent propagation.
                            return fullScreenOverlayContainer.visibility == View.VISIBLE
                        }
                    }
            )

    var isAnchored = false
        set(value) {
            field = value
            updateRefreshRate()
        }
    private var isHoveringTtsToggle = false

    private val bitmapLock = Any()
    private var settingsMenu: View? = null
    private var isSettingsVisible = false

    interface DualWebViewGroupListener {
        fun onCursorPositionChanged(x: Float, y: Float, isVisible: Boolean)
    }

    interface MaskToggleListener {
        fun onMaskTogglePressed()
    }

    interface TtsToggleListener {
        fun onTtsTogglePressed()
    }

    interface FullscreenListener {
        fun onEnterFullscreen()
        fun onExitFullscreen()
    }

    var fullscreenListener: FullscreenListener? = null

    private var hideProgressBarRunnable: Runnable? = null

    fun updateLoadingProgress(progress: Int) {

        post {
            // Cancel any pending hide action whenever we get an update
            hideProgressBarRunnable?.let { removeCallbacks(it) }
            hideProgressBarRunnable = null

            if (progress < 100) {
                progressBar.visibility = View.VISIBLE
                progressBar.progress = progress
                progressBar.bringToFront()
                requestLayout() // Force layout update to position progress bar correctly
            } else {
                progressBar.progress = 100
                // Delay hiding to ensure user sees 100%
                hideProgressBarRunnable = Runnable { progressBar.visibility = View.GONE }
                postDelayed(hideProgressBarRunnable!!, 500)
            }
        }
    }

    private data class NavButton(
            val left: FontIconView,
            val right: FontIconView,
            var isHovered: Boolean = false
    )

    private fun FontIconView.configureToggleButton(iconRes: Int) {
        visibility = View.VISIBLE
        setText(iconRes)
        setBackgroundResource(R.drawable.nav_button_background)
        gravity = android.view.Gravity.CENTER
        setPadding(8, 8, 8, 8)
        alpha = 1.0f
        elevation = 2f
        stateListAnimator = null
    }

    private fun clearNavigationButtonStates() {
        navButtons.values.forEach { navButton ->
            navButton.isHovered = false
            navButton.left.isHovered = false
            navButton.right.isHovered = false
        }
    }

    // Properties for link editing
    lateinit var urlEditText: EditText
    private val urlFieldMinHeight = 56.dp()

    private var leftEditField: EditText
    private var rightEditField: EditText
    private var _isUrlEditing = false

    // Keyboard listener interface
    interface KeyboardListener {
        fun onShowKeyboard()
        fun onHideKeyboard()
    }

    var keyboardListener: KeyboardListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.keyboardListener = value
            }
        }

    var micListener: ChatView.MicListener? = null
        set(value) {
            field = value
            if (::chatView.isInitialized) {
                chatView.micListener = value
            }
        }

    private var navButtons: Map<String, NavButton>

    var listener: DualWebViewGroupListener? = null
    var maskToggleListener: MaskToggleListener? = null

    val leftEyeUIContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true

                setBackgroundColor(Color.TRANSPARENT) // Make sure background is transparent
            }

    fun isActiveWebView(webView: WebView): Boolean {
        return this.webView == webView
    }

    fun setChatMicActive(active: Boolean) {
        if (::chatView.isInitialized) {
            chatView.setMicActive(active)
        }
    }

    fun insertVoiceToChatInput(text: String) {
        if (::chatView.isInitialized) {
            chatView.insertVoiceText(text)
        }
    }

    fun pauseBackgroundMedia(sourceWebView: WebView) {
        windows.forEach { win ->
            if (win.webView != sourceWebView) {
                // Pause all media elements
                win.webView.evaluateJavascript(
                        "document.querySelectorAll('video, audio').forEach(function(e) { e.pause(); });",
                        null
                )
            }
        }
    }

    fun pauseYouTubeMediaAcrossAllWindows(resetTracking: Boolean = true) {
        windows.forEach { win ->
            val url = win.webView.url.orEmpty()
            if (!url.contains("youtube.com", ignoreCase = true) &&
                            !url.contains("youtu.be", ignoreCase = true)
            ) {
                return@forEach
            }

            try {
                win.webView.stopLoading()
            } catch (_: Exception) {}

            win.webView.post { win.webView.evaluateJavascript(youtubeMediaTeardownScript, null) }
            mediaStateByWindowId[win.id] = false
        }

        if (resetTracking) {
            updateMediaState(mediaStateByWindowId.values.any { it })
        }
    }

    private val fullScreenOverlayContainer =
            FrameLayout(context).apply {
                clipChildren = true
                clipToOutline = true // Ensure clipping to bounds
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                isClickable = true
                isFocusable = true
            }

    // UI scale factor (0.5 to 1.0) - controlled by screen size slider
    var uiScale = 1.0f

    private val fullScreenHiddenViews: List<View> by lazy {
        listOf(
                webViewsContainer,
                leftToggleBar,
                leftNavigationBar,
                keyboardContainer,
                leftSystemInfoView,
                urlEditText
        )
    }

    private val previousFullScreenVisibility = mutableMapOf<View, Int>()

    val leftEyeClipParent =
            FrameLayout(context).apply {
                // Force it to be exactly 640px wide and match height (or some fixed height).
                // Using MATCH_PARENT for height is common if you want the full vertical space.
                layoutParams = FrameLayout.LayoutParams(640, FrameLayout.LayoutParams.MATCH_PARENT)

                // Ensure that children are clipped to our bounds
                clipToPadding = true
                clipChildren = true
                setBackgroundColor(Color.BLACK) // Set background to ensure proper rendering
            }

    fun updateUiScale(scale: Float) {
        uiScale = scale

        // Set pivot point to center (320, 240) so scaling happens around the center
        leftEyeUIContainer.pivotX = 320f
        leftEyeUIContainer.pivotY = 240f
        leftEyeUIContainer.scaleX = scale
        leftEyeUIContainer.scaleY = scale

        fullScreenOverlayContainer.pivotX = 320f
        fullScreenOverlayContainer.pivotY = 240f
        fullScreenOverlayContainer.scaleX = scale
        fullScreenOverlayContainer.scaleY = scale

        // Ensure parent is not scaled so it acts as a fixed window
        leftEyeClipParent.scaleX = 1f
        leftEyeClipParent.scaleY = 1f

        updateUiTranslation()

        // Update scroll bar visibility based on scale and anchor mode
        updateScrollBarsVisibility()

        // Notify listener to refresh cursor scale visually
        listener?.onCursorPositionChanged(lastCursorX, lastCursorY, true)

        requestLayout()
        invalidate()
    }

    private fun updateUiTranslation() {
        if (isAnchored) {
            leftEyeUIContainer.translationX = 0f
            leftEyeUIContainer.translationY = 0f
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            return
        }

        // Calculate max allowed translation based on current scale
        val maxTransX = 320f * (1f - uiScale)
        val maxTransY = 240f * (1f - uiScale)

        // Get saved progress (default 50)
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val xProgress = prefs.getInt("uiTransXProgress", 50)
        val yProgress = prefs.getInt("uiTransYProgress", 50)

        // Calculate translation
        val transX = ((xProgress - 50) / 50f) * maxTransX
        val transY = ((yProgress - 50) / 50f) * maxTransY

        leftEyeUIContainer.translationX = transX
        leftEyeUIContainer.translationY = transY

        fullScreenOverlayContainer.translationX = transX
        fullScreenOverlayContainer.translationY = transY

        // Update scroll bar thumb positions
        updateScrollBarThumbs(xProgress, yProgress)
        applyScrollbarTransform()
    }

    fun recenterViewportForDashboard(targetWebView: WebView? = webView) {
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putInt("uiTransXProgress", 50)
                .putInt("uiTransYProgress", 50)
                .apply()
        updateUiTranslation()

        targetWebView?.post {
            try {
                targetWebView.scrollTo(0, 0)
            } catch (_: Exception) {}
            try {
                targetWebView.evaluateJavascript(
                        """
                        (function() {
                            try {
                                window.scrollTo(0, 0);
                                if (document.documentElement) {
                                    document.documentElement.scrollLeft = 0;
                                    document.documentElement.scrollTop = 0;
                                }
                                if (document.body) {
                                    document.body.scrollLeft = 0;
                                    document.body.scrollTop = 0;
                                }
                            } catch (e) {}
                        })();
                        """.trimIndent(),
                        null
                )
            } catch (_: Exception) {}
        }
    }

    private fun isWebViewScrollEnabled(): Boolean {
        // Always return true to ensure scrollbars ONLY scroll the WebView content
        // and never move the screen position (viewport panning).
        return true
    }

    private fun scrollPageHorizontal(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = scrollAmount,
                        top = null,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(scrollAmount, 0)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransXProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun scrollPageVertical(delta: Int) {
        if (isWebViewScrollEnabled()) {
            // Scroll the WebView content
            val scrollAmount = delta * 15 // Increase sensitivity
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            if (shouldUseJsScroll(metrics)) {
                scrollWebViewByJs(
                        left = null,
                        top = scrollAmount,
                        smooth = false,
                        useScrollTo = false
                )
            } else {
                webView.scrollBy(0, scrollAmount)
            }
            updateScrollBarThumbs(0, 0) // Update thumbs immediately
        } else {
            // Pan the viewport
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            val currentProgress = prefs.getInt("uiTransYProgress", 50)
            val newProgress = (currentProgress + delta).coerceIn(0, 100)

            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun shouldFreezeScrollBars(): Boolean {
        val now = SystemClock.uptimeMillis()
        return isMediaPlaying || (now - lastMediaPlayingAt < mediaScrollFreezeMs)
    }

    private fun updateScrollBarThumbs(xProgress: Int, yProgress: Int) {
        val now = SystemClock.uptimeMillis()
        // Guard against updates during or shortly after scrollbar interaction to prevent bouncing
        if (now - lastScrollBarInteractionTime < 250L) return

        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(now)
            // Update Horizontal Thumb based on WebView scroll
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                // Calculate ratio: scrollX / (contentWidth - viewportWidth)
                // Since we can't easily get full content width without computeHorizontalScrollRange
                // (protected),
                // we'll rely on an approximation or need to subclass WebView.
                // For now, let's try using the standard range approximation if possible, or just
                // skip if we can't get it.
                // Actually, we can use computeHorizontalScrollRange via reflection or just use
                // scrollX/ArbitraryLargeNumber if needed,
                // but simpler is to use `webView.scrollX` relative to estimated width.
                // Let's defer exact horizontal proportion calculation or use a safe fallback.

                // Using standard view methods available on WebView (which is a View)
                val range = metrics.rangeX
                val extent = metrics.extentX
                val offset = metrics.offsetX

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val hMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    hScrollThumb.translationX = hMargin.toFloat()
                    hScrollThumb.invalidate()
                }
            }

            // Update Vertical Thumb based on WebView scroll
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight

                val range = metrics.rangeY
                val extent = metrics.extentY
                val offset = metrics.offsetY

                if (range > extent) {
                    val maxScroll = range - extent
                    val ratio = offset.coerceIn(0, maxScroll).toFloat() / maxScroll
                    val vMargin = (ratio * maxMargin).toInt().coerceIn(0, maxMargin)
                    vScrollThumb.translationY = vMargin.toFloat()
                    vScrollThumb.invalidate()
                }
            }
        } else {
            // Existing logic for non-anchored (viewport pan)
            // Update horizontal thumb position
            val hTrackContainer = horizontalScrollBar.getChildAt(1) as? FrameLayout
            val hTrackWidth =
                    when {
                        hTrackContainer != null && hTrackContainer.width > 0 ->
                                hTrackContainer.width
                        hTrackContainer != null && hTrackContainer.measuredWidth > 0 ->
                                hTrackContainer.measuredWidth
                        horizontalScrollBar.width > 0 -> {
                            val leftBtnWidth = horizontalScrollBar.getChildAt(0)?.width ?: 0
                            val rightBtnWidth = horizontalScrollBar.getChildAt(2)?.width ?: 0
                            (horizontalScrollBar.width - leftBtnWidth - rightBtnWidth)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (hTrackWidth > 0) {
                val thumbWidth = 60
                val maxMargin = hTrackWidth - thumbWidth
                val hMargin = (xProgress / 100f * maxMargin).toInt()
                hScrollThumb.translationX = hMargin.toFloat()
                hScrollThumb.invalidate()
            }

            // Update vertical thumb position
            val vTrackContainer = verticalScrollBar.getChildAt(1) as? FrameLayout
            val vTrackHeight =
                    when {
                        vTrackContainer != null && vTrackContainer.height > 0 ->
                                vTrackContainer.height
                        vTrackContainer != null && vTrackContainer.measuredHeight > 0 ->
                                vTrackContainer.measuredHeight
                        verticalScrollBar.height > 0 -> {
                            val topBtnHeight = verticalScrollBar.getChildAt(0)?.height ?: 0
                            val bottomBtnHeight = verticalScrollBar.getChildAt(2)?.height ?: 0
                            (verticalScrollBar.height - topBtnHeight - bottomBtnHeight)
                                    .coerceAtLeast(0)
                        }
                        else -> 0
                    }
            if (vTrackHeight > 0) {
                val thumbHeight = 60
                val maxMargin = vTrackHeight - thumbHeight
                val vMargin = (yProgress / 100f * maxMargin).toInt()
                vScrollThumb.translationY = vMargin.toFloat()
                vScrollThumb.invalidate()
            }
        }
    }

    fun updateScrollBarsVisibility() {
        // DebugLog.d("ScrollDebug", "updateScrollBarsVisibility called. isAnchored=$isAnchored,
        // isInScrollMode=$isInScrollMode, uiScale=$uiScale")
        val now = SystemClock.uptimeMillis()
        // Check freeze state but don't return early - we need to update layout
        val isFrozen = shouldFreezeScrollBars() && !isInteractingWithScrollBar

        // Determine mode-specific base constraints
        val isScrollModeActive = isInScrollMode || isNavBarsHidden

        // Base dimensions
        val containerWidth = 640
        val baseLeftMargin = if (isScrollModeActive) 0 else toggleBarWidthPx
        val rawBottomMargin = if (isScrollModeActive) 0 else navBarHeightPx
        val keyboardVisible = keyboardContainer.visibility == View.VISIBLE
        if (keyboardVisible) {
            val keyboardWidth = 640 - toggleBarWidthPx
            keyboardContainer.measure(
                    MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
        }
        val keyboardHeight =
                if (keyboardVisible) {
                    val measured = keyboardContainer.measuredHeight
                    if (measured > 0) measured else 160
                } else {
                    0
                }
        val baseBottomMargin = if (keyboardVisible) 0 else rawBottomMargin

        // If anchored, scrollbars are always hidden
        if (isAnchored) {
            lastHorzScrollableAt = 0L
            lastVertScrollableAt = 0L
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE

            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                var targetWidth: Int
                var targetHeight: Int
                if (isScrollModeActive) {
                    targetWidth = containerWidth
                    targetHeight = (480 - keyboardHeight).coerceAtLeast(0)
                } else {
                    targetWidth = containerWidth - baseLeftMargin
                    targetHeight = (480 - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                }

                var changed = false
                if (p.width != targetWidth) changed = true
                if (p.height != targetHeight) changed = true
                if (p.leftMargin != baseLeftMargin) changed = true
                if (p.rightMargin != 0) changed = true
                if (p.bottomMargin != baseBottomMargin) changed = true

                if (changed) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    p.gravity = Gravity.TOP or Gravity.START
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                    webViewsContainer.invalidate()
                    // Reset horizontal scroll to prevent right-offset
                    if (webView.scrollX > 0) webView.scrollTo(0, webView.scrollY)
                }
            }
            return
        }

        // Hide scrollbars entirely on AR nav map pages (full-viewport 3D map)
        val currentUrl = webView.url ?: ""
        if (currentUrl.contains("ar_nav.html")) {
            horizontalScrollBar.visibility = View.GONE
            verticalScrollBar.visibility = View.GONE
            (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
                val targetWidth = if (isScrollModeActive) containerWidth else containerWidth - baseLeftMargin
                val targetHeight = (480 - baseBottomMargin - keyboardHeight).coerceAtLeast(0)
                if (p.width != targetWidth || p.height != targetHeight || p.rightMargin != 0) {
                    p.width = targetWidth
                    p.height = targetHeight
                    p.leftMargin = baseLeftMargin
                    p.rightMargin = 0
                    p.bottomMargin = baseBottomMargin
                    p.gravity = Gravity.TOP or Gravity.START
                    webViewsContainer.layoutParams = p
                    webViewsContainer.requestLayout()
                }
            }
            return
        }

        // Always check WebView scrollability since we disabled viewport panning
        val metrics = resolveScrollMetrics(now)
        val webHRange = metrics.rangeX
        val webHExtent = metrics.extentX
        val webVRange = metrics.rangeY
        val webVExtent = metrics.extentY
        val scrollDeltaThreshold = 1
        val webHDelta = webHRange - webHExtent
        val webVDelta = webVRange - webVExtent
        val showHorzRaw = webHDelta > scrollDeltaThreshold
        val showVertRaw = webVDelta > scrollDeltaThreshold
        if (showHorzRaw) {
            lastHorzScrollableAt = now
        }
        if (showVertRaw) {
            lastVertScrollableAt = now
        }
        val showHorz = showHorzRaw || (now - lastHorzScrollableAt < scrollBarHoldMs)
        val showVert = showVertRaw || (now - lastVertScrollableAt < scrollBarHoldMs)

        if (!isFrozen) {
            horizontalScrollBar.apply {
                visibility = if (showHorz) View.VISIBLE else View.INVISIBLE
                isClickable = showHorz
                isFocusable = false
            }

            verticalScrollBar.apply {
                visibility = if (showVert) View.VISIBLE else View.INVISIBLE
                isClickable = showVert
                isFocusable = false
            }
        }

        // Apply layout adjustments
        (webViewsContainer.layoutParams as? FrameLayout.LayoutParams)?.let { p ->
            // Keep WebView sizing stable to avoid layout churn (prevents media pauses/flicker).
            val rightMarginShift = if (verticalScrollBar.visibility == View.VISIBLE) 20 else 0
            val bottomMarginShift = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

            var targetWidth: Int
            var targetHeight: Int
            var targetLeftMargin: Int
            var targetBottomMargin: Int
            var targetRightMargin: Int

            if (isScrollModeActive) {
                // Scroll Mode: 640 total width
                targetWidth = 640 - rightMarginShift
                targetHeight = (480 - bottomMarginShift - keyboardHeight).coerceAtLeast(0)
                targetLeftMargin = 0
                targetRightMargin = rightMarginShift
                targetBottomMargin = bottomMarginShift
            } else {
                // Normal Mode:
                // Width: 640 total - toggle bar - margin
                targetWidth = (640 - baseLeftMargin) - rightMarginShift

                // Height: 480 total - nav bar - margin
                // We must be explicit here so onMeasure picks it up
                targetHeight =
                        (480 - baseBottomMargin - bottomMarginShift - keyboardHeight).coerceAtLeast(
                                0
                        )

                targetLeftMargin = baseLeftMargin
                targetRightMargin = rightMarginShift
                targetBottomMargin = baseBottomMargin + bottomMarginShift
            }

            var changed = false
            if (p.width != targetWidth) changed = true
            if (p.height != targetHeight) changed = true
            if (p.leftMargin != targetLeftMargin) changed = true
            if (p.rightMargin != targetRightMargin) changed = true
            if (p.bottomMargin != targetBottomMargin) changed = true

            if (changed) {
                p.width = targetWidth
                p.height = targetHeight
                p.leftMargin = targetLeftMargin
                p.rightMargin = targetRightMargin
                p.bottomMargin = targetBottomMargin
                p.gravity = Gravity.TOP or Gravity.START

                webViewsContainer.layoutParams = p
                // Force layout update on WebView itself to ensure it resizes
                webView.requestLayout()
                webViewsContainer.requestLayout()
                webViewsContainer.invalidate()

                // Reset horizontal scroll to prevent right-offset after layout change
                if (!showHorz && webView.scrollX > 0) {
                    webView.scrollTo(0, webView.scrollY)
                }
            }
        }

        // Remove unconditional requestLayout/invalidate here
        // webView.requestLayout()
        // webView.invalidate()

        if (horizontalScrollBar.visibility == View.VISIBLE ||
                        verticalScrollBar.visibility == View.VISIBLE
        ) {
            updateScrollBarThumbs(0, 0)
        }
    }

    fun updateExternalScrollMetrics(
            rangeX: Int,
            extentX: Int,
            offsetX: Int,
            rangeY: Int,
            extentY: Int,
            offsetY: Int
    ) {
        val now = SystemClock.uptimeMillis()
        externalScrollMetrics =
                ExternalScrollMetrics(
                        rangeX = rangeX.coerceAtLeast(0),
                        extentX = extentX.coerceAtLeast(0),
                        offsetX = offsetX.coerceAtLeast(0),
                        rangeY = rangeY.coerceAtLeast(0),
                        extentY = extentY.coerceAtLeast(0),
                        offsetY = offsetY.coerceAtLeast(0),
                        timestamp = now
                )

        if (!isAnchored && now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
            updateScrollBarsVisibility()
            lastScrollBarCheckTime = now
        } else if (now - lastScrollBarInteractionTime >= 250L) {
            // Only update thumb position if not recently interacting with scrollbar
            updateScrollBarThumbs(0, 0)
        }
    }

    fun clearExternalScrollMetrics() {
        externalScrollMetrics = null
    }

    private fun resolveScrollMetrics(now: Long): ScrollMetrics {
        val webRangeX = webView.getHorizontalScrollRange()
        val webExtentX = webView.getHorizontalScrollExtent()
        val webOffsetX = webView.getHorizontalScrollOffset()
        val webRangeY = webView.getVerticalScrollRange()
        val webExtentY = webView.getVerticalScrollExtent()
        val webOffsetY = webView.getVerticalScrollOffset()

        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        if (external == null) {
            return ScrollMetrics(
                    rangeX = webRangeX,
                    extentX = webExtentX,
                    offsetX = webOffsetX,
                    rangeY = webRangeY,
                    extentY = webExtentY,
                    offsetY = webOffsetY
            )
        }

        // Use external metrics whenever available so nested scrollers can suppress bars correctly.
        val useExternalH = external.extentX > 0
        val useExternalV = external.extentY > 0
        return ScrollMetrics(
                rangeX = if (useExternalH) external.rangeX else webRangeX,
                extentX = if (useExternalH) external.extentX else webExtentX,
                offsetX = if (useExternalH) external.offsetX else webOffsetX,
                rangeY = if (useExternalV) external.rangeY else webRangeY,
                extentY = if (useExternalV) external.extentY else webExtentY,
                offsetY = if (useExternalV) external.offsetY else webOffsetY
        )
    }

    private fun shouldUseJsScroll(metrics: ScrollMetrics): Boolean {
        val now = SystemClock.uptimeMillis()
        val external =
                externalScrollMetrics?.takeIf { now - it.timestamp <= externalScrollMetricsStaleMs }
        return external != null &&
                (metrics.rangeX > metrics.extentX || metrics.rangeY > metrics.extentY)
    }

    private fun scrollWebViewByJs(left: Int?, top: Int?, smooth: Boolean, useScrollTo: Boolean) {
        val leftValue = left?.toString() ?: "undefined"
        val topValue = top?.toString() ?: "undefined"
        val behavior = if (smooth) "'smooth'" else "'auto'"
        val useScrollToJs = if (useScrollTo) "true" else "false"
        webView.evaluateJavascript(
                """
            (function() {
                var leftVal = $leftValue;
                var topVal = $topValue;
                var behavior = $behavior;
                var useScrollTo = $useScrollToJs;

                function isNumber(v) {
                    return typeof v === 'number' && !isNaN(v);
                }

                function scrollWindow() {
                    if (useScrollTo && typeof window.scrollTo === 'function') {
                        window.scrollTo({
                            left: isNumber(leftVal) ? leftVal : window.scrollX,
                            top: isNumber(topVal) ? topVal : window.scrollY,
                            behavior: behavior
                        });
                    } else if (!useScrollTo && typeof window.scrollBy === 'function') {
                        window.scrollBy({
                            left: isNumber(leftVal) ? leftVal : 0,
                            top: isNumber(topVal) ? topVal : 0,
                            behavior: behavior
                        });
                    } else if (typeof window.scrollTo === 'function') {
                        window.scrollTo({
                            left: isNumber(leftVal) ? leftVal : window.scrollX,
                            top: isNumber(topVal) ? topVal : window.scrollY,
                            behavior: behavior
                        });
                    }
                }

                function scrollElement(el) {
                    if (!el) {
                        scrollWindow();
                        return;
                    }
                    var hasScrollTo = typeof el.scrollTo === 'function';
                    var hasScrollBy = typeof el.scrollBy === 'function';
                    if (useScrollTo && hasScrollTo) {
                        el.scrollTo({
                            left: isNumber(leftVal) ? leftVal : el.scrollLeft,
                            top: isNumber(topVal) ? topVal : el.scrollTop,
                            behavior: behavior
                        });
                        return;
                    }
                    if (!useScrollTo && hasScrollBy) {
                        el.scrollBy({
                            left: isNumber(leftVal) ? leftVal : 0,
                            top: isNumber(topVal) ? topVal : 0,
                            behavior: behavior
                        });
                        return;
                    }

                    var targetLeft = isNumber(leftVal) ? leftVal : el.scrollLeft;
                    var targetTop = isNumber(topVal) ? topVal : el.scrollTop;
                    if (!useScrollTo) {
                        targetLeft = el.scrollLeft + (isNumber(leftVal) ? leftVal : 0);
                        targetTop = el.scrollTop + (isNumber(topVal) ? topVal : 0);
                    }
                    el.scrollLeft = targetLeft;
                    el.scrollTop = targetTop;
                }

                var target = window.__taplinkScrollTarget;
                var root = document.scrollingElement || document.documentElement || document.body;
                var isRoot = !target || target === root || target === document.documentElement || target === document.body;
                if (!isRoot && target && target.isConnected !== false) {
                    scrollElement(target);
                } else {
                    scrollWindow();
                }
            })();
        """,
                null
        )
    }

    private fun applyScrollbarTransform() {
        val scale = if (uiScale <= 0f) 1f else 1f / uiScale
        val transX = -leftEyeUIContainer.translationX
        val transY = -leftEyeUIContainer.translationY
        listOf(horizontalScrollBar, verticalScrollBar).forEach { bar ->
            bar.pivotX = 0f
            bar.pivotY = 0f
            bar.scaleX = scale
            bar.scaleY = scale
            bar.translationX = transX
            bar.translationY = transY
        }
    }

    private fun updateHorizontalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            val range = metrics.rangeX
            val extent = metrics.extentX
            if (range > extent) {
                val targetX = percent * (range - extent)
                if (shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = targetX.toInt(),
                            top = null,
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(targetX.toInt(), webView.scrollY)
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransXProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    private fun updateVerticalScroll(percent: Float) {
        if (isWebViewScrollEnabled()) {
            val metrics = resolveScrollMetrics(SystemClock.uptimeMillis())
            val range = metrics.rangeY
            val extent = metrics.extentY
            if (range > extent) {
                val targetY = percent * (range - extent)
                if (shouldUseJsScroll(metrics)) {
                    scrollWebViewByJs(
                            left = null,
                            top = targetY.toInt(),
                            smooth = false,
                            useScrollTo = true
                    )
                } else {
                    webView.scrollTo(webView.scrollX, targetY.toInt())
                }
            }
        } else {
            val newProgress = (percent * 100).toInt()
            val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
            prefs.edit().putInt("uiTransYProgress", newProgress).apply()
            updateUiTranslation()
        }
    }

    // Function to update the cursor positions and visibility
    fun updateCursorPosition(x: Float, y: Float, isVisible: Boolean) {
        val currentTime = System.currentTimeMillis()
        lastCursorX = x
        lastCursorY = y

        if (!isAttachedToWindow) {
            return
        }

        if (currentTime - lastCursorUpdateTime >= CURSOR_UPDATE_INTERVAL) {
            if (isVisible) {
                // Convert cursor from container-local to screen coordinates
                val containerLocation = IntArray(2)
                getLocationOnScreen(containerLocation)

                // Account for UI scale and translation when calculating screen position
                // Visual cursor is scaled around (320, 240) and then translated (only in
                // non-anchored mode)
                val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
                val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

                val visualX = 320f + (x - 320f) * uiScale + transX
                val visualY = 240f + (y - 240f) * uiScale + transY

                val screenX = visualX + containerLocation[0]
                val screenY = visualY + containerLocation[1]

                // Pass screen coordinates - buttons also use screen coordinates
                updateButtonHoverStates(screenX, screenY)
            }
            listener?.onCursorPositionChanged(x, y, isVisible)
            lastCursorUpdateTime = currentTime
        }
    }

    private fun refreshHoverAtCurrentCursor() {
        if (!isAttachedToWindow) return

        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)
    }

    fun updatePointerHover(screenX: Float, screenY: Float) {
        if (!isAttachedToWindow) return
        updateButtonHoverStates(screenX, screenY)
    }

    fun clearPointerHover() {
        if (!isAttachedToWindow) return
        clearAllHoverStates()
    }

    private var isScreenMasked = false
    private var isHostPaused = false
    private var isHoveringMaskToggle = false
    // WakeLock keeps CPU awake while screen is masked (projector off) so audio doesn't skip.
    // Without this, Android Doze will periodically sleep the CPU causing ~10s audio stutters.
    private val maskWakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapInsight:MaskAudioPlayback")
    private val pausedMediaWakeLock: PowerManager.WakeLock =
        (context.getSystemService(Context.POWER_SERVICE) as PowerManager)
            .newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TapInsight:PausedMediaPlayback")
    private val mediaWifiLock: WifiManager.WifiLock? =
        (context.applicationContext.getSystemService(Context.WIFI_SERVICE) as? WifiManager)?.run {
            @Suppress("DEPRECATION")
            createWifiLock(WifiManager.WIFI_MODE_FULL_HIGH_PERF, "TapInsight:StreamingAudio")
        }
    private var maskOverlay: FrameLayout =
            FrameLayout(context).apply {
                setBackgroundColor(Color.BLACK)
                visibility = View.GONE
                layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT) // Left eye width only
                elevation = 1000f // Put it above everything except cursors
                isClickable = true
                isFocusable = true

                // Consume all touch events to prevent propagation to navbar/webview behind
                // and route taps into mask overlay controls.
                setOnTouchListener { _, event ->
                    when (event.actionMasked) {
                        MotionEvent.ACTION_DOWN -> {
                            maskOverlayTouchDownX = event.rawX
                            maskOverlayTouchDownY = event.rawY
                            maskOverlayTouchDownTime = SystemClock.uptimeMillis()
                        }
                        MotionEvent.ACTION_UP -> {
                            val dx = event.rawX - maskOverlayTouchDownX
                            val dy = event.rawY - maskOverlayTouchDownY
                            val distSq = dx * dx + dy * dy
                            val duration = SystemClock.uptimeMillis() - maskOverlayTouchDownTime
                            val isTap = distSq <= (maskOverlayTapSlopPx * maskOverlayTapSlopPx) && duration <= maskOverlayTapMaxDurationMs
                            if (isTap) {
                                dispatchMaskOverlayTouch(event.rawX, event.rawY)
                            }
                        }
                        MotionEvent.ACTION_CANCEL -> {
                            // Reset touch state when parent cancels the touch sequence
                            maskOverlayTouchDownTime = 0L
                        }
                    }
                    true
                }
            }

    // Mask mode UI elements
    private lateinit var maskMediaControlsContainer: LinearLayout
    private lateinit var btnMaskPrevTrack: FontIconView // Skip to previous song
    private lateinit var btnMaskPrev: FontIconView // 10s back
    private lateinit var btnMaskPlay: FontIconView
    private lateinit var btnMaskPause: FontIconView
    private lateinit var btnMaskNext: FontIconView // 10s forward
    private lateinit var btnMaskNextTrack: FontIconView // Skip to next song
    private lateinit var btnMaskUnmask: ImageButton
    private lateinit var maskNowPlayingText: TextView
    private var lastMaskedDomTitle: String? = null
    private var lastMaskedDomTitleUrl: String? = null
    private var lastMaskedDomTitleAt: Long = 0L
    private val maskedDomTitleFreshMs = 15000L
    private val maskNowPlayingPeriodicRefresh: Runnable = object : Runnable {
        override fun run() {
            if (!isScreenMasked) return
            refreshMaskedNowPlayingFromJs()
            refreshMaskedNowPlaying()
            postDelayed(this, 5000L)
        }
    }
    private lateinit var btnVisualizerToggle: FontIconView
    private lateinit var maskVisualizerView: AudioVisualizerView
    private var isVisualizerVisible = false
    private var audioVisualizer: Visualizer? = null
    // Double-buffer for thread-safe FFT data: audio thread writes to back buffer,
    // UI thread reads from front buffer. References swapped atomically.
    @Volatile private var fftFrontBuffer = FloatArray(32)
    private var fftBackBuffer = FloatArray(32)
    private val fftMagnitudes: FloatArray get() = fftFrontBuffer  // read alias for UI thread
    private var lastVisualizerToggleTime = 0L
    private var lastVisualizerThemeTapTime = 0L
    private var maskOverlayTouchDownX = 0f
    private var maskOverlayTouchDownY = 0f
    private var maskOverlayTouchDownTime = 0L
    private val maskOverlayTapSlopPx = 24f
    // Lightweight dedup for mask overlay touch dispatch — prevents the same physical tap
    // from being processed twice when multiple code paths fire within the same input cycle.
    // Kept very short (50ms) to avoid blocking intentional rapid taps (e.g. visualizer theme cycling).
    private var lastMaskOverlayDispatchTime = 0L
    private val MASK_OVERLAY_DISPATCH_DEBOUNCE_MS = 400L
    private val maskOverlayTapMaxDurationMs = 350L

    // Fullscreen Mode UI elements
    private lateinit var fullScreenControlsContainer: FrameLayout
    private lateinit var fullScreenMediaControls: LinearLayout
    private var suppressFullscreenMediaControls = false
    private lateinit var btnFsPrevTrack: FontIconView
    private lateinit var btnFsPrev: FontIconView
    private lateinit var btnFsPlayPause: FontIconView // Single toggle button
    private var isFsPlaying: Boolean = false // Track play state
    private lateinit var btnFsNext: FontIconView
    private lateinit var btnFsNextTrack: FontIconView
    private lateinit var btnFsExit: FontIconView

    var ttsToggleListener: TtsToggleListener? = null

    // Add properties to track translations
    private var _translationX = 0f
    private var _translationY = 0f
    private var _rotationZ = 0f

    private var isInScrollMode = false
    private var isNavBarsHidden = false // Tracks nav bar visibility independent of scroll mode
    private var settingsScrim: View? = null

    // Scroll bar containers for non-anchored mode
    private var horizontalScrollBar: LinearLayout
    private var verticalScrollBar: LinearLayout
    private var hScrollThumb: View
    private var vScrollThumb: View
    private var isInteractingWithScrollBar = false

    private var windowsOverviewContainer: android.widget.ScrollView? = null
    private var hoveredWindowsOverviewItem: View? = null

    fun showWindowsOverview() {
        DebugLog.d(
                "WindowsOverview",
                "showWindowsOverview called, windowsOverviewContainer=${windowsOverviewContainer != null}"
        )
        if (windowsOverviewContainer == null) {
            createWindowsOverviewUI()
            DebugLog.d("WindowsOverview", "Created windows overview UI")
        }

        // Populate container with current windows
        val container = windowsOverviewContainer?.getChildAt(0) as? LinearLayout
        if (container == null) {
            Log.e(
                    "WindowsOverview",
                    "Container is null! windowsOverviewContainer has ${windowsOverviewContainer?.childCount ?: 0} children"
            )
            return
        }
        DebugLog.d(
                "WindowsOverview",
                "Container found, clearing views. Windows count: ${windows.size}"
        )
        container.removeAllViews()
        hoveredWindowsOverviewItem = null

        // Add "Add Window" button at the top - shorter with label
        val addButton =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    layoutParams =
                            LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 50)
                                    .apply { bottomMargin = 12 }
                    // Create StateListDrawable for hover feedback
                    val normalBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#2A5298"))
                                cornerRadius = 12f
                            }
                    val hoveredBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#3A72C8"))
                                cornerRadius = 12f
                                setStroke(2, Color.parseColor("#6BAAFF"))
                            }
                    val pressedBg =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#4A82D8"))
                                cornerRadius = 12f
                            }
                    background =
                            android.graphics.drawable.StateListDrawable().apply {
                                addState(intArrayOf(android.R.attr.state_pressed), pressedBg)
                                addState(intArrayOf(android.R.attr.state_hovered), hoveredBg)
                                addState(intArrayOf(), normalBg)
                            }
                    gravity = Gravity.CENTER
                    isClickable = true
                    isFocusable = true
                    setOnClickListener { createNewWindow() }
                }

        val addIcon =
                FontIconView(context).apply {
                    setText(R.string.fa_plus)
                    textSize = 18f
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.WRAP_CONTENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { rightMargin = 12 }
                }
        val addLabel =
                TextView(context).apply {
                    text = "Open New Tab"
                    textSize = 14f
                    setTextColor(Color.WHITE)
                }
        addButton.addView(addIcon)
        addButton.addView(addLabel)
        container.addView(addButton)
        DebugLog.d("WindowsOverview", "Added 'Add Window' button")

        // Calculate item dimensions for 3-column grid with stretching
        // Container width = 608 - 32 (padding) = 576
        val itemMargin = 8
        val columnsPerRow = 3
        val itemHeight = 120 // Fixed height for items

        // Create rows for 3-column grid
        var currentRow: LinearLayout? = null
        windows.forEachIndexed { index, win ->
            DebugLog.d("WindowsOverview", "Adding window item: ${win.id}, title: ${win.title}")

            // Create new row every 3 items
            if (index % columnsPerRow == 0) {
                currentRow =
                        LinearLayout(context).apply {
                            orientation = LinearLayout.HORIZONTAL
                            layoutParams =
                                    LinearLayout.LayoutParams(
                                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                                    LinearLayout.LayoutParams.WRAP_CONTENT
                                            )
                                            .apply { bottomMargin = itemMargin }
                        }
                container.addView(currentRow)
            }

            // Calculate position in current row
            val positionInRow = index % columnsPerRow

            val item =
                    FrameLayout(context).apply {
                        // Use weight=1 so items stretch to fill available width
                        layoutParams =
                                LinearLayout.LayoutParams(0, itemHeight, 1f).apply {
                                    marginStart = if (positionInRow == 0) 0 else itemMargin / 2
                                    marginEnd =
                                            if (positionInRow == columnsPerRow - 1) 0
                                            else itemMargin / 2
                                }
                        background =
                                android.graphics.drawable.StateListDrawable().apply {
                                    val isActive = win.id == activeWindowId

                                    // Colors
                                    val normalBgColor =
                                            if (isActive) Color.parseColor("#444444")
                                            else Color.parseColor("#252525")
                                    val hoverBgColor =
                                            if (isActive) Color.parseColor("#555555")
                                            else Color.parseColor("#353535")
                                    val normalStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else Color.parseColor("#404040")
                                    val hoverStrokeColor =
                                            if (isActive) Color.parseColor("#4488FF")
                                            else
                                                    Color.parseColor(
                                                            "#505050"
                                                    ) // Lighter stroke on hover for inactive

                                    val hoveredDrawable =
                                            GradientDrawable().apply {
                                                setColor(hoverBgColor)
                                                setStroke(2, hoverStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    val normalDrawable =
                                            GradientDrawable().apply {
                                                setColor(normalBgColor)
                                                setStroke(2, normalStrokeColor)
                                                cornerRadius = 12f
                                            }

                                    addState(
                                            intArrayOf(android.R.attr.state_hovered),
                                            hoveredDrawable
                                    )
                                    addState(intArrayOf(), normalDrawable)
                                }
                        isClickable = true
                        isFocusable = true
                        setOnClickListener { switchToWindow(win.id) }
                    }

            // Thumbnail (Placeholder or actual bitmap)
            val thumbView =
                    ImageView(context).apply {
                        if (win.thumbnail != null) {
                            setImageBitmap(win.thumbnail)
                        } else {
                            setBackgroundColor(Color.parseColor("#1A1A1A"))
                        }
                        scaleType = ImageView.ScaleType.CENTER_CROP
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.MATCH_PARENT
                                        )
                                        .apply { setMargins(3, 3, 3, 3) }
                        alpha = 0.6f
                    }
            item.addView(thumbView)

            // Title - smaller text, truncated
            val titleView =
                    TextView(context).apply {
                        text = win.title.take(20) + if (win.title.length > 20) "..." else ""
                        textSize = 10f
                        setTextColor(Color.WHITE)
                        maxLines = 2
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.MATCH_PARENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM
                                            setMargins(6, 0, 6, 6)
                                        }
                        setShadowLayer(3f, 0f, 0f, Color.BLACK)
                    }
            item.addView(titleView)

            // Delete button - smaller
            val deleteBtn =
                    FontIconView(context).apply {
                        setText(R.string.fa_xmark)
                        textSize = 12f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#CC333333"))
                                    shape = GradientDrawable.OVAL
                                }
                        layoutParams =
                                FrameLayout.LayoutParams(28, 28).apply {
                                    gravity = Gravity.TOP or Gravity.END
                                    topMargin = 4
                                    rightMargin = 4
                                }
                        setOnClickListener { closeWindow(win.id) }
                    }
            item.addView(deleteBtn)

            currentRow?.addView(item)
        }

        DebugLog.d(
                "WindowsOverview",
                "Setting container visible, total items: ${container.childCount}"
        )
        windowsOverviewContainer?.visibility = View.VISIBLE
        webView.visibility = View.GONE

        // Force the container to the front by removing and re-adding at the end
        // Preserve the layout params
        // Force the container to the front using bringToFront() instead of remove/add
        // which can cause layout state loss
        val params =
                windowsOverviewContainer?.layoutParams as? FrameLayout.LayoutParams
                        ?: FrameLayout.LayoutParams(640 - toggleBarWidthPx, 480 - navBarHeightPx)
                                .apply {
                                    leftMargin = toggleBarWidthPx
                                    // Explicitly set Gravity to avoid any ambiguity
                                    gravity = Gravity.TOP or Gravity.START
                                }

        // Ensure params are applied
        windowsOverviewContainer?.layoutParams = params

        windowsOverviewContainer?.bringToFront()

        requestLayout()
        invalidate()

        // Log layout info after layout pass
        windowsOverviewContainer?.post {
            val woc = windowsOverviewContainer
            DebugLog.d(
                    "WindowsOverview",
                    "Post-layout: width=${woc?.width}, height=${woc?.height}, " +
                            "x=${woc?.x}, y=${woc?.y}, visibility=${woc?.visibility}, " +
                            "layoutParams=${woc?.layoutParams?.width}x${woc?.layoutParams?.height}"
            )
            refreshHoverAtCurrentCursor()
        }
    }

    fun hideWindowsOverview() {
        windowsOverviewContainer?.visibility = View.GONE
        webView.visibility = View.VISIBLE
        requestLayout()
        invalidate()
    }

    private fun createWindowsOverviewUI() {
        DebugLog.d("WindowsOverview", "createWindowsOverviewUI called")
        // Use explicit dimensions since MATCH_PARENT wasn't resolving
        val containerWidth = 640 - toggleBarWidthPx // 608
        val containerHeight = 480 - navBarHeightPx // 448

        windowsOverviewContainer =
                android.widget.ScrollView(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(containerWidth, containerHeight).apply {
                                leftMargin = toggleBarWidthPx
                                gravity = Gravity.TOP or Gravity.START
                            }
                    setBackgroundColor(Color.parseColor("#101010"))
                    visibility = View.GONE
                    elevation = 1500f
                    isFillViewport = true // Ensure content fills the viewport
                }
        DebugLog.d("WindowsOverview", "Container created: ${containerWidth}x${containerHeight}")

        val content =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    // Use ViewGroup.LayoutParams for ScrollView children
                    layoutParams =
                            ViewGroup.LayoutParams(
                                    ViewGroup.LayoutParams.MATCH_PARENT,
                                    ViewGroup.LayoutParams.WRAP_CONTENT
                            )
                    setPadding(16, 16, 16, 16)
                    setBackgroundColor(Color.parseColor("#101010")) // Match parent background
                }

        windowsOverviewContainer?.addView(content)
        leftEyeUIContainer.addView(windowsOverviewContainer)
        DebugLog.d(
                "WindowsOverview",
                "UI created: ScrollView has ${windowsOverviewContainer?.childCount} children, added to leftEyeUIContainer (${leftEyeUIContainer.childCount} children)"
        )
    }

    fun toggleWindowMode() {
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            hideWindowsOverview()
        } else {
            // Capture thumbnail of current window before showing overview
            val currentWin = windows.find { it.id == activeWindowId }
            if (currentWin != null) {
                try {
                    // Simple capture of the webview drawing cache or similar
                    // Using drawing cache is deprecated but works for simple needs, or
                    // PixelCopy/draw
                    // Here we'll use a simple draw to canvas if possible
                    val w = webView.width
                    val h = webView.height
                    if (w > 0 && h > 0) {
                        val bmp = Bitmap.createBitmap(w / 4, h / 4, Bitmap.Config.RGB_565)
                        val c = Canvas(bmp)
                        c.scale(0.25f, 0.25f)
                        webView.draw(c)
                        currentWin.thumbnail = bmp
                    }
                } catch (e: Exception) {
                    Log.e("Windows", "Failed to capture thumbnail", e)
                }
                currentWin.title = webView.title ?: "Tab"
            }
            showWindowsOverview()
        }
    }

    fun createNewWindow(loadDefaultUrl: Boolean = true): WebView {
        val newWebView = InternalWebView(context)
        configureWebView(newWebView)
        applyBrowsingModeToWebView(newWebView, isDesktopMode)
        // Popup windows supplied via WebViewTransport must be pristine (not pre-navigated).
        if (loadDefaultUrl) {
            newWebView.loadUrl(Constants.DEFAULT_URL)
        }
        val newWindow = BrowserWindow(webView = newWebView, title = "New Tab")

        // Add to container but invisible
        newWebView.visibility = View.INVISIBLE
        webViewsContainer.addView(
                newWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )

        // Notify MainActivity to configure the new WebView (clients, settings, etc.)
        windowCallback?.onWindowCreated(newWebView)

        windows.add(newWindow)
        switchToWindow(newWindow.id)
        saveAllWindowsState()
        return newWebView
    }

    fun resetToSingleWindow(loadDefaultUrl: Boolean = false): WebView {
        windows.toList().forEach { win ->
            try {
                win.webView.stopLoading()
            } catch (_: Exception) {}
            try {
                webViewsContainer.removeView(win.webView)
            } catch (_: Exception) {}
            try {
                win.webView.destroy()
            } catch (_: Exception) {}
            win.thumbnail?.recycle()
        }

        windows.clear()
        mediaStateByWindowId.clear()
        mediaLastPlayedAtByWindowId.clear()
        activeWindowId = null
        isMediaPlaying = false
        hideMediaControls()
        webViewsContainer.removeAllViews()

        val freshWebView = InternalWebView(context)
        configureWebView(freshWebView)
        applyBrowsingModeToWebView(freshWebView, isDesktopMode)
        if (loadDefaultUrl) {
            freshWebView.loadUrl(Constants.DEFAULT_URL)
        }

        val freshWindow = BrowserWindow(webView = freshWebView, title = "New Tab")
        freshWebView.visibility = View.VISIBLE
        webViewsContainer.addView(
                freshWebView,
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        )
        windowCallback?.onWindowCreated(freshWebView)

        windows.add(freshWindow)
        activeWindowId = freshWindow.id
        webView = freshWebView
        updateScrollBarsVisibility()
        windowCallback?.onWindowSwitched(freshWebView)
        freshWebView.post { injectPageObservers(freshWebView) }
        startRefreshing()
        hideWindowsOverview()
        saveAllWindowsState()
        return freshWebView
    }

    fun switchToWindow(id: String) {
        val targetWindow = windows.find { it.id == id } ?: return

        if (activeWindowId == id) {
            // Already active, just hide overview if visible
            hideWindowsOverview()
            return
        }

        // Pause the old WebView to free CPU/network resources while inactive
        val oldWebView = webView
        oldWebView.visibility = View.INVISIBLE
        oldWebView.onPause()
        oldWebView.pauseTimers()

        // Switch active window
        activeWindowId = id
        webView = targetWindow.webView

        // Resume the new WebView and bring to front
        webView.resumeTimers()
        webView.onResume()
        webView.visibility = View.VISIBLE
        webView.bringToFront()

        // Ensure settings are applied (zoom, font size, etc.) which might be instance specific if
        // not global
        // MainActivity's setup should handle most, but we might need to re-apply UI specific things
        updateScrollBarsVisibility()

        // Notify callback
        windowCallback?.onWindowSwitched(webView)

        // Ensure observers exist for restored pages where onPageFinished may not fire.
        webView.post { injectPageObservers(webView) }
        // Ensure refresh loop is running (it might have died if previous webview was detached)
        startRefreshing()

        hideWindowsOverview()
        saveAllWindowsState()
    }

    fun closeWindow(id: String) {
        val windowToRemove = windows.find { it.id == id } ?: return

        // Don't close the last window, or create a new one if we do
        val wasActive = activeWindowId == id

        windows.remove(windowToRemove)
        mediaStateByWindowId.remove(id)
        mediaLastPlayedAtByWindowId.remove(id)
        webViewsContainer.removeView(windowToRemove.webView)
        windowToRemove.webView.destroy()
        windowToRemove.thumbnail?.recycle()

        if (windows.isEmpty()) {
            createNewWindow()
        } else if (wasActive) {
            // Switch to the last window in the list
            switchToWindow(windows.last().id)
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        } else {
            // If overview was open, refresh it
            if (windowsOverviewContainer?.visibility == View.VISIBLE) {
                showWindowsOverview()
            }
        }
        saveAllWindowsState()
        if (mediaStateByWindowId.isNotEmpty()) {
            updateMediaState(mediaStateByWindowId.values.any { it })
        } else {
            isMediaPlaying = false
            hideMediaControls()
        }
    }

    fun saveWindowMetadataState(forceSync: Boolean = false) {
        saveAllWindowsState(forceSync = forceSync, includeWebViewState = false)
    }

    fun saveAllWindowsState(forceSync: Boolean = false, includeWebViewState: Boolean = true) {
        try {
            val root = org.json.JSONObject()
            root.put("activeId", activeWindowId)
            root.put("isDesktopMode", isDesktopMode)

            val windowsArray = org.json.JSONArray()
            val maxStateSize = 500_000 // 500KB per window max

            windows.forEach { win ->
                // Update title from WebView if available
                if (!win.webView.title.isNullOrEmpty()) {
                    win.title = win.webView.title!!
                }

                val winObj = org.json.JSONObject()
                winObj.put("id", win.id)
                winObj.put("title", win.title)
                winObj.put("url", win.webView.url ?: "")

                if (includeWebViewState) {
                    // Save full WebView state (history, etc) - with size limit
                    try {
                        val state = Bundle()
                        win.webView.saveState(state)
                        val parcel = Parcel.obtain()
                        state.writeToParcel(parcel, 0)
                        val bytes = parcel.marshall()
                        parcel.recycle()

                        // Only save state if under size limit
                        if (bytes.size < maxStateSize) {
                            val stateString = Base64.encodeToString(bytes, Base64.DEFAULT)
                            winObj.put("state", stateString)
                        } else {
                            Log.w(
                                    "Persistence",
                                    "Window ${win.id} state too large (${bytes.size} bytes), skipping state save"
                            )
                            // Don't save state, just URL - will reload on restore
                        }
                    } catch (e: Exception) {
                        Log.e("Persistence", "Error saving state for window ${win.id}", e)
                        // Continue without state for this window
                    }
                }

                windowsArray.put(winObj)
            }
            root.put("windows", windowsArray)

            // Final size check before saving
            val jsonString = root.toString()
            if (jsonString.length > 5_000_000) { // 5MB total limit
                Log.e(
                        "Persistence",
                        "Total state size too large (${jsonString.length} chars), clearing old state"
                )
                context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                        .edit()
                        .remove(KEY_WINDOWS_STATE)
                        .apply()
                return
            }

            val editor = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                    .edit()
                    .putString(KEY_WINDOWS_STATE, jsonString)
            if (forceSync) {
                editor.commit()
            } else {
                editor.apply()
            }

            DebugLog.d(
                    "Persistence",
                    "Saved ${windows.size} windows with${if (includeWebViewState) "" else "out"} bundles (${jsonString.length} chars)"
            )
        } catch (e: Exception) {
            Log.e("Persistence", "Error saving window state", e)
        }
    }

    fun restoreState() {
        try {
            val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val jsonString = prefs.getString(KEY_WINDOWS_STATE, null)

            if (jsonString.isNullOrEmpty()) {
                if (windows.isEmpty()) {
                    createNewWindow()
                }
                return
            }

            val root = org.json.JSONObject(jsonString)
            val savedActiveId = if (root.has("activeId")) root.getString("activeId") else null
            val restoredDesktopMode = root.optBoolean("isDesktopMode", isDesktopMode)
            isDesktopMode = restoredDesktopMode
            prefs.edit().putBoolean("isDesktopMode", isDesktopMode).apply()
            val windowsArray = root.optJSONArray("windows")

            if (windowsArray != null && windowsArray.length() > 0) {
                // Clear existing windows (default one)
                windows.toList().forEach {
                    it.webView.destroy()
                    it.thumbnail?.recycle()
                }
                windows.clear()

                webViewsContainer.removeAllViews()

                for (i in 0 until windowsArray.length()) {
                    val winObj = windowsArray.getJSONObject(i)
                    val id = winObj.getString("id")
                    val title = winObj.getString("title")
                    val url = winObj.getString("url")
                    val stateString = winObj.optString("state", "")

                    val newWebView = InternalWebView(context)
                    configureWebView(newWebView)
                    applyBrowsingModeToWebView(newWebView, isDesktopMode)
                    // Important: notify MainActivity to attach its logic
                    windowCallback?.onWindowCreated(newWebView)

                    var restored = false
                    if (stateString.isNotEmpty()) {
                        try {
                            val bytes = Base64.decode(stateString, Base64.DEFAULT)
                            val parcel = Parcel.obtain()
                            parcel.unmarshall(bytes, 0, bytes.size)
                            parcel.setDataPosition(0)
                            val state = Bundle()
                            state.readFromParcel(parcel)
                            parcel.recycle()
                            // Restore state returns the WebBackForwardList but we don't need it
                            // explicitly
                            newWebView.restoreState(state)
                            restored = true
                        } catch (e: Exception) {
                            Log.e("Persistence", "Failed to restore webview bundle", e)
                        }
                    }

                    if (!restored) {
                        if (url.isNotEmpty()) {
                            newWebView.loadUrl(url)
                        } else {
                            newWebView.loadUrl(Constants.DEFAULT_URL)
                        }
                    }

                    val win = BrowserWindow(id = id, webView = newWebView, title = title)
                    windows.add(win)

                    // Add to container
                    newWebView.visibility = View.INVISIBLE
                    webViewsContainer.addView(
                            newWebView,
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                    )
                }

                if (windows.isNotEmpty()) {
                    val targetId =
                            if (savedActiveId != null && windows.any { it.id == savedActiveId }) {
                                savedActiveId
                            } else {
                                windows.last().id
                            }
                    switchToWindow(targetId)
                    syncBrowsingModeUi()
                } else {
                    // Fallback if parsing failed
                    createNewWindow()
                }
            } else if (windows.isEmpty()) {
                createNewWindow()
            }
        } catch (e: Exception) {
            Log.e("Persistence", "Error restoring window state", e)
            // Restore default if failed
            if (windows.isEmpty()) createNewWindow()
        }
    }

    private fun configureWebView(webView: WebView) {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        @Suppress("DEPRECATION") // Suppress for extensive database usage
        settings.databaseEnabled = true
        settings.useWideViewPort = true
        settings.loadWithOverviewMode = true
        settings.setSupportZoom(true)
        settings.builtInZoomControls = true
        settings.displayZoomControls = false
        settings.mediaPlaybackRequiresUserGesture = false

        webView.addJavascriptInterface(MediaInterface(this, webView), "MediaInterface")

        // Keep WebAppInterface for referencing context/logic if needed, but primary comms via URL
        // scheme
        // Enable Native Bridge for Chat
        // GroqBridge removed

        webView.webViewClient =
                object : android.webkit.WebViewClient() {
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            request: android.webkit.WebResourceRequest?
                    ): Boolean {
                        val url = request?.url?.toString() ?: return false
                        DebugLog.d("GroqUrl", "Checking URL: $url")

                        if (url.startsWith("taplink://chat")) {
                            DebugLog.d("GroqUrl", "Intercepted taplink://chat")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                // Use the top-level WebAppInterface class we created
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        // Intercept media file links → open in TapInsight media player
                        if (view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    override fun onPageStarted(
                            view: android.webkit.WebView?,
                            url: String?,
                            favicon: Bitmap?
                    ) {
                        super.onPageStarted(view, url, favicon)
                        clearExternalScrollMetrics()
                    }

                    @Deprecated("Deprecated in Java")
                    override fun shouldOverrideUrlLoading(
                            view: android.webkit.WebView?,
                            url: String?
                    ): Boolean {
                        DebugLog.d("GroqUrl", "Checking URL (deprecated): $url")
                        if (url != null && url.startsWith("taplink://chat")) {
                            DebugLog.d("GroqUrl", "Intercepted taplink://chat (deprecated)")
                            val uri = android.net.Uri.parse(url)
                            val msg = uri.getQueryParameter("msg")
                            val history = uri.getQueryParameter("history")

                            if (msg != null && view != null) {
                                WebAppInterface(context, view).chatWithGroq(msg, history ?: "[]")
                            }
                            return true
                        }
                        if (url != null && view != null && interceptMediaUrl(view, url)) return true
                        return false
                    }

                    override fun onPageFinished(view: android.webkit.WebView?, url: String?) {
                        super.onPageFinished(view, url)
                        try {
                            view?.let { injectPageObservers(it) }
                            updateScrollBarsVisibility()

                        } catch (e: Exception) {
                            android.util.Log.e("TapLink", "Error in onPageFinished", e)
                        }
                    }
                }

        webView.apply {
            setBackgroundColor(Color.BLACK)
            isClickable = true
            isFocusable = true
            isFocusableInTouchMode = true
            setLayerType(View.LAYER_TYPE_HARDWARE, null)
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setOnTouchListener { _, _ -> keyboardContainer.visibility == View.VISIBLE }
            setOnLongClickListener { true }

            setOnScrollChangeListener { _, _, _, _, _ ->
                if (isWebViewScrollEnabled()) {
                    updateScrollBarThumbs(0, 0)
                    val now = System.currentTimeMillis()
                    if (now - lastScrollBarCheckTime > scrollBarVisibilityThrottleMs) {
                        updateScrollBarsVisibility()
                        lastScrollBarCheckTime = now
                    }
                }
            }
        }
    }

    init {
        // Initialize the first WebView
        // Initial WebView configuration
        val initialWebView = InternalWebView(context)
        webView = initialWebView
        configureWebView(webView) // Local basic config
        mobileUserAgent = webView.settings.userAgentString
        desktopUserAgent = buildDesktopUserAgentFromMobile(mobileUserAgent)

        // CRITICAL FIX: Do NOT add the initial webview to the container or windows list yet.
        // This prevents the "Dashboard flash" on startup.
        // The container starts empty.
        // restoreState() will either:
        // 1. Restore saved windows (and set active one)
        // 2. Or call createNewWindow() calls which will add a window and load the default URL.

        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        isDesktopMode = prefs.getBoolean("isDesktopMode", false)
        currentWebZoom = prefs.getFloat("webZoomLevel", 1.0f)
        updateBrowsingMode(isDesktopMode)

        // Set the background of the entire DualWebViewGroup to black
        setBackgroundColor(Color.BLACK)

        // Ensure the left eye (Activity Window) uses the same pixel format as the right eye
        // (SurfaceView)
        // This ensures consistent color saturation between both eyes.
        (context as? Activity)?.window?.setFormat(PixelFormat.RGBA_8888)

        fullScreenOverlayContainer.setOnTouchListener { _, event ->
            if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
                fullScreenTapDetector.onTouchEvent(event)
                true
            } else {
                false
            }
        }

        // Initial WebView configuration moved to configureWebView() and MainActivity

        // Configure SurfaceView for right eye mirroring
        rightEyeView.apply {
            isClickable = false
            layoutParams = LayoutParams(640, LayoutParams.MATCH_PARENT)
            setBackgroundColor(Color.TRANSPARENT)
            holder.setFormat(PixelFormat.RGBA_8888)
            holder.addCallback(
                    object : SurfaceHolder.Callback {
                        override fun surfaceCreated(holder: SurfaceHolder) {
                            setupBitmap(width, height)
                            startRefreshing()
                        }

                        override fun surfaceChanged(
                                holder: SurfaceHolder,
                                format: Int,
                                width: Int,
                                height: Int
                        ) {
                            setupBitmap(width, height)
                        }

                        override fun surfaceDestroyed(holder: SurfaceHolder) {
                            synchronized(bitmapLock) {
                                val currentBitmap = bitmap
                                bitmap = null // Set to null first
                                currentBitmap?.let { bmp ->
                                    if (!bmp.isRecycled) {
                                        bmp.recycle()
                                    }
                                }
                            }
                            stopRefreshing()
                        }
                    }
            )
        }

        // Initialize keyboard containers
        keyboardContainer.apply {
            visibility = View.GONE
            setBackgroundColor(Color.TRANSPARENT)
            setOnClickListener {
                // DebugLog.d("KeyboardDebug", "leftKeyboardContainer clicked")
            }
            setOnTouchListener { _, _ ->
                // DebugLog.d("KeyboardDebug", "leftKeyboardContainer received touch event:
                // ${event.action}")
                true
            }
        }

        // Initialize navigation bars
        leftNavigationBar =
                LayoutInflater.from(context).inflate(R.layout.navigation_bar, this, false).apply {
                    layoutParams = LayoutParams(LayoutParams.MATCH_PARENT, navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    setPadding(16, 0, 16, 0)
                }

        // Initialize navigation buttons
        navButtons =
                mapOf(
                        "back" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnBack),
                                        right = leftNavigationBar.findViewById(R.id.btnBack)
                                ),
                        "forward" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnForward),
                                        right = leftNavigationBar.findViewById(R.id.btnForward)
                                ),
                        "home" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHome),
                                        right = leftNavigationBar.findViewById(R.id.btnHome)
                                ),
                        "link" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnLink),
                                        right = leftNavigationBar.findViewById(R.id.btnLink)
                                ),
                        "settings" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnSettings),
                                        right = leftNavigationBar.findViewById(R.id.btnSettings)
                                ),
                        "refresh" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnRefresh),
                                        right = leftNavigationBar.findViewById(R.id.btnRefresh)
                                ),
                        "hide" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnHide),
                                        right = leftNavigationBar.findViewById(R.id.btnHide)
                                ),
                        "quit" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnQuit),
                                        right = leftNavigationBar.findViewById(R.id.btnQuit)
                                ),
                        "chat" to
                                NavButton(
                                        left = leftNavigationBar.findViewById(R.id.btnChat),
                                        right = leftNavigationBar.findViewById(R.id.btnChat)
                                )
                )

        // Initialize all buttons with same base properties
        navButtons.values.forEach { navButton ->
            navButton.left.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
            navButton.right.apply {
                visibility = View.VISIBLE
                isClickable = true
                isFocusable = true
            }
        }

        // Ensure physical pointer clicks (mouse/touch) work on all nav buttons, not only via
        // cursor hit-testing.
        navButtons.forEach { (key, navButton) ->
            navButton.left.setOnClickListener { triggerNavigationAction(key, navButton) }
            if (navButton.right !== navButton.left) {
                navButton.right.setOnClickListener { triggerNavigationAction(key, navButton) }
            }
        }

        // Initialize left toggle bar
        leftToggleBar =
                LayoutInflater.from(context).inflate(R.layout.toggle_bar, this, false).apply {
                    layoutParams = LayoutParams(toggleBarWidthPx, 480 - navBarHeightPx)
                    setBackgroundColor(Color.parseColor("#202020"))
                    visibility = View.VISIBLE
                    clipToOutline = true // Add this
                    clipChildren = true // Add this
                    isClickable = true // Add this
                    isFocusable = true // Add this
                }

        // DebugLog.d("ViewDebug", "Toggle bar initialized with hash: ${leftToggleBar.hashCode()}")

        setupMaskOverlayUI()
        setupFullScreenControlsUI()

        // Set background styles - use gradient drawables for modern look
        setBackgroundColor(Color.BLACK)
        leftNavigationBar.background =
                ContextCompat.getDrawable(context, R.drawable.nav_bar_background)
        leftToggleBar.background =
                ContextCompat.getDrawable(context, R.drawable.toggle_bar_background)

        // Set up the toggle buttons with explicit configurations
        leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle).apply {
            configureToggleButton(R.string.fa_mobile_screen)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube).apply {
            configureToggleButton(R.string.fa_glasses)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks).apply {
            visibility = View.VISIBLE
            setText(R.string.fa_bookmark)
            setBackgroundResource(R.drawable.nav_button_background)
            gravity = android.view.Gravity.CENTER
            setPadding(8, 8, 8, 8)
            alpha = 1.0f
            elevation = 2f
            stateListAnimator = null
        }

        // Initialize URL EditTexts
        urlEditText = setupUrlEditText(true)

        // Bring urlEditTextLeft to front
        urlEditText.bringToFront()

        // Disable text handles for both EditTexts
        disableTextHandles(urlEditText)

        //

        // Initialize the edit fields
        leftEditField =
                EditText(context).apply {
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)

                    // Style the edit field
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        rightEditField =
                EditText(context).apply {
                    // Same styling as leftEditField
                    layoutParams =
                            LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
                    setBackgroundColor(Color.parseColor("#303030"))
                    setTextColor(Color.WHITE)
                    visibility = View.GONE
                    setPadding(16, 12, 16, 12)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#303030"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 8f
                            }
                }

        // Add edit fields to view hierarchy
        addView(leftEditField)
        addView(rightEditField)

        leftSystemInfoView =
                SystemInfoView(context).apply {
                    layoutParams =
                            LayoutParams(
                                            200, // Fixed initial width, will be adjusted after
                                            // measure
                                            24
                                    )
                                    .apply { gravity = Gravity.TOP or Gravity.END }
                    elevation = 900f
                    visibility = View.VISIBLE // Explicitly set visibility
                }

        viewTreeObserver.addOnGlobalLayoutListener(
                object : ViewTreeObserver.OnGlobalLayoutListener {
                    override fun onGlobalLayout() {
                        viewTreeObserver.removeOnGlobalLayoutListener(this)
                    }
                }
        )

        // Make sure they're above other elements
        leftSystemInfoView.bringToFront()

        post {
            // Ensure bookmarks views are always on top when added to view hierarchy
            if (::leftBookmarksView.isInitialized) {}
            if (::chatView.isInitialized) {
                chatView.bringToFront()
            }
        }

        // Set up the container hierarchy
        leftEyeClipParent.addView(leftEyeUIContainer)
        leftEyeClipParent.addView(
                fullScreenOverlayContainer
        ) // Add to clip parent for proper clipping

        // Add views to UI container
        leftEyeUIContainer.apply {
            // Add views in the correct z-order
            // Add webViewsContainer with correct position
            addView(
                    webViewsContainer,
                    FrameLayout.LayoutParams(640 - toggleBarWidthPx, LayoutParams.MATCH_PARENT)
                            .apply {
                                leftMargin = toggleBarWidthPx // Position after toggle bar
                                bottomMargin = navBarHeightPx // Account for nav bar
                                gravity = Gravity.TOP or Gravity.START
                            }
            )
            addView(leftToggleBar)
            // DebugLog.d("ViewDebug", "Toggle bar added to UI container with hash:
            // ${leftToggleBar.hashCode()}")

            addView(leftNavigationBar.apply { elevation = 101f })
            addView(btnShowNavBars) // Add show nav bars button
            addView(progressBar) // Add progress bar
            addView(keyboardContainer)
            addView(dialogContainer)
            addView(leftSystemInfoView)
            addView(urlEditText)
            addView(
                    maskOverlay
            ) // Add mask overlay for proper mirroring to both eyes // Add mask overlay for proper
            // mirroring to both eyes

            // Initialize ChatView here
            chatView =
                    ChatView(context).apply {
                        layoutParams =
                                FrameLayout.LayoutParams(560, 420)
                                        .apply { // Slightly smaller than full window
                                            gravity = Gravity.CENTER
                                        }
                        visibility = View.GONE
                        elevation = 2000f // High elevation
                        keyboardListener = this@DualWebViewGroup.keyboardListener
                    }
            addView(chatView)
            chatView.disableSystemKeyboard()

            // Setup listener for Chat button
            leftNavigationBar.findViewById<View>(R.id.btnChat)?.setOnClickListener { toggleChat() }
            postDelayed(
                    {
                        initializeToggleButtons()
                        requestLayout()
                        invalidate()
                    },
                    100
            )

            post {
                leftSystemInfoView.measure(
                        MeasureSpec.makeMeasureSpec(640, MeasureSpec.AT_MOST),
                        MeasureSpec.makeMeasureSpec(24, MeasureSpec.EXACTLY)
                )
                updateSystemInfoBarVisibility()
                leftSystemInfoView.requestLayout()
                leftSystemInfoView.invalidate()
            }

            // Make sure container is visible and properly layered
            visibility = View.VISIBLE
            elevation = 100f // Keep it above webview
        }

        // After other view initializations

        // Add the clip parent to the main view
        addView(leftEyeClipParent)
        // REFACTORED: rightEyeView no longer added - single viewport mode
        // addView(rightEyeView) // Keep right eye view separate
        // maskOverlay now added to leftEyeUIContainer above for proper mirroring

        // Create horizontal scroll bar
        horizontalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Left arrow button
                    // Left arrow button
                    val btnLeft =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_left)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnLeft)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(0, 20, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    hScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(60, 16).apply {
                                            gravity = Gravity.CENTER_VERTICAL
                                            leftMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(hScrollThumb)
                    addView(trackContainer)

                    // Right arrow button
                    // Right arrow button
                    val btnRight =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_right)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnRight)

                    // Click handlers
                    btnLeft.setOnClickListener { scrollPageHorizontal(-10) }
                    btnRight.setOnClickListener { scrollPageHorizontal(10) }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullWidth = v.width
                        val thumbWidth = hScrollThumb.width
                        val trackableWidth = fullWidth - thumbWidth

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isInteractingWithScrollBar = true
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                val clickX = event.x
                                val clickLeft = clickX - thumbWidth / 2
                                val percent = (clickLeft / trackableWidth).coerceIn(0f, 1f)
                                updateHorizontalScroll(percent)

                                // Optimistic visual update
                                val hMargin = (percent * trackableWidth).toInt()
                                hScrollThumb.translationX = hMargin.toFloat()
                                hScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isInteractingWithScrollBar = false
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                updateScrollBarThumbs(0, 0)
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Create vertical scroll bar
        verticalScrollBar =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    setBackgroundColor(Color.TRANSPARENT) // Transparent background
                    visibility = View.GONE
                    elevation = 150f
                    isClickable = true // Prevent click propagation
                    isFocusable = false
                    isFocusableInTouchMode = false

                    // Up arrow button
                    // Up arrow button
                    val btnUp =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_up)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnUp)

                    // Track container with thumb
                    val trackContainer =
                            FrameLayout(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 0, 1f)
                                setBackgroundColor(Color.parseColor("#303030"))
                            }
                    vScrollThumb =
                            View(context).apply {
                                layoutParams =
                                        FrameLayout.LayoutParams(16, 60).apply {
                                            gravity = Gravity.CENTER_HORIZONTAL
                                            topMargin = 0
                                        }
                                setBackgroundResource(R.drawable.scroll_button_background)
                            }
                    trackContainer.addView(vScrollThumb)
                    addView(trackContainer)

                    // Down arrow button
                    // Down arrow button
                    val btnDown =
                            FontIconView(context).apply {
                                layoutParams = LinearLayout.LayoutParams(20, 20)
                                setText(R.string.fa_arrow_down)
                                setBackgroundResource(R.drawable.scroll_button_background)
                                gravity = Gravity.CENTER
                                textSize = 10f
                                setPadding(0, 0, 0, 0)
                            }
                    addView(btnDown)

                    // Click handlers
                    btnUp.setOnClickListener { scrollPageVertical(-10) }
                    btnDown.setOnClickListener { scrollPageVertical(10) }
                    trackContainer.setOnTouchListener { v, event ->
                        val fullHeight = v.height
                        val thumbHeight = vScrollThumb.height
                        val trackableHeight = fullHeight - thumbHeight

                        when (event.action) {
                            MotionEvent.ACTION_DOWN -> {
                                isInteractingWithScrollBar = true
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(true)
                                // Immediate jump on touch down
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                DebugLog.d(
                                        "ScrollDebug",
                                        "Vertical Down: y=$clickY, height=$fullHeight, percent=$percent"
                                )

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_MOVE -> {
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                val clickY = event.y
                                val clickTop = clickY - thumbHeight / 2
                                val percent = (clickTop / trackableHeight).coerceIn(0f, 1f)

                                // DebugLog.d("ScrollDebug", "Vertical Move: y=$clickY,
                                // percent=$percent")

                                updateVerticalScroll(percent)

                                // Optimistic visual update
                                val vMargin = (percent * trackableHeight).toInt()
                                vScrollThumb.translationY = vMargin.toFloat()
                                vScrollThumb.invalidate()
                                true
                            }
                            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                                isInteractingWithScrollBar = false
                                lastScrollBarInteractionTime = SystemClock.uptimeMillis()
                                v.parent.requestDisallowInterceptTouchEvent(false)
                                updateScrollBarThumbs(0, 0)
                                true
                            }
                            else -> false
                        }
                    }
                }

        // Add scroll bars to UI container
        leftEyeUIContainer.addView(
                horizontalScrollBar,
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, 20).apply {
                    gravity = Gravity.BOTTOM
                    leftMargin = toggleBarWidthPx
                    rightMargin = 20 // Prevent overlap with vertical scroll bar
                    bottomMargin = navBarHeightPx // Sit on top of the nav bar
                }
        )
        leftEyeUIContainer.addView(
                verticalScrollBar,
                FrameLayout.LayoutParams(20, FrameLayout.LayoutParams.MATCH_PARENT).apply {
                    gravity = Gravity.END
                    bottomMargin = navBarHeightPx // End at the nav bar
                }
        )

        // Load and apply saved UI scale after view hierarchy is ready
        post {
            val savedScaleProgress =
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .getInt("uiScaleProgress", 100)
            val savedScale = 0.35f + (savedScaleProgress / 100f) * 0.65f
            updateUiScale(savedScale)
        }
    }

    // Track fullscreen toggles for debugging
    private var fullscreenEntryCount = 0
    private var lastFullscreenViewHashCode = 0

    fun showFullScreenOverlay(view: View) {
        fullscreenEntryCount++
        val viewHashCode = view.hashCode()
        // val isSameView = viewHashCode == lastFullscreenViewHashCode
        lastFullscreenViewHashCode = viewHashCode

        // Remove from current parent if any
        if (view.parent is ViewGroup) {
            // DebugLog.d("FullscreenDebug", "  Removing view from parent: ${(view.parent as
            // ViewGroup).javaClass.simpleName}")
            (view.parent as ViewGroup).removeView(view)
        }

        // Clear any existing children
        if (fullScreenOverlayContainer.childCount > 0) {
            // DebugLog.d("FullscreenDebug", "  Clearing ${fullScreenOverlayContainer.childCount}
            // existing children from container")
            fullScreenOverlayContainer.removeAllViews()
        }

        // Add the new view
        fullScreenOverlayContainer.addView(
                view,
                FrameLayout.LayoutParams(
                        FrameLayout.LayoutParams.MATCH_PARENT,
                        FrameLayout.LayoutParams.MATCH_PARENT
                )
        )

        // Add the full screen controls overlay
        if (::fullScreenControlsContainer.isInitialized) {
            // Remove from parent if it was already added (defensive)
            (fullScreenControlsContainer.parent as? ViewGroup)?.removeView(
                    fullScreenControlsContainer
            )

            fullScreenOverlayContainer.addView(fullScreenControlsContainer)
            fullScreenControlsContainer.visibility = View.VISIBLE
            if (::fullScreenMediaControls.isInitialized) {
                fullScreenMediaControls.visibility =
                        if (suppressFullscreenMediaControls) View.GONE else View.VISIBLE
            }
            fullScreenControlsContainer.bringToFront()
        }

        // DebugLog.d("FullscreenDebug", "  View added. Container child count:
        // ${fullScreenOverlayContainer.childCount}")

        previousFullScreenVisibility.clear()
        DebugLog.d("FullscreenDebug", "Hiding ${fullScreenHiddenViews.size} UI elements")
        fullScreenHiddenViews.forEach { target ->

            // DebugLog.d("FullscreenDebug", "  Hiding $name (was ${if (target.visibility ==
            // View.VISIBLE) "VISIBLE" else "GONE/INVISIBLE"})")
            previousFullScreenVisibility[target] = target.visibility
            // Use GONE for everything to maximize power saving (remove from layout)
            target.visibility = View.GONE
        }

        fullScreenOverlayContainer.visibility = View.VISIBLE
        fullScreenOverlayContainer.elevation = 2000f
        fullScreenOverlayContainer.bringToFront()

        // Force refresh to ensure the fullscreen content is captured
        post {
            fullScreenOverlayContainer.invalidate()
            fullScreenOverlayContainer.requestLayout()
            startRefreshing()
            // DebugLog.d("FullscreenDebug", "  Post-show refresh triggered")
        }

        // DebugLog.d("FullscreenDebug", "About to call hideSystemUI()")
        hideSystemUI()

        // Power saving: reduce refresh rate and notify listener
        fullscreenListener?.onEnterFullscreen()
        updateRefreshRate()
    }

    fun hideFullScreenOverlay() {

        // Get reference to the view being removed for logging
        val removedView =
                if (fullScreenOverlayContainer.childCount > 0) {
                    fullScreenOverlayContainer.getChildAt(0)
                } else null

        if (removedView != null) {
            // DebugLog.d("FullscreenDebug", "  Removing view: ${removedView.javaClass.simpleName},
            // hashCode: ${removedView.hashCode()}")
        }

        fullScreenOverlayContainer.removeAllViews()

        // Use INVISIBLE instead of GONE to keep the container surface attached
        // This may help prevent surface corruption on second fullscreen entry
        fullScreenOverlayContainer.visibility = View.INVISIBLE
        fullScreenOverlayContainer.elevation = 0f

        previousFullScreenVisibility.forEach { (target, visibility) ->

            // DebugLog.d("FullscreenDebug", "  Restoring $name to ${if (visibility == View.VISIBLE)
            // "VISIBLE" else "GONE/INVISIBLE"}")
            target.visibility = visibility
        }
        previousFullScreenVisibility.clear()

        // Force WebView to redraw
        webView.invalidate()
        webView.requestLayout()

        // Force the entire UI container to relayout and redraw
        leftEyeUIContainer.invalidate()
        leftEyeUIContainer.requestLayout()

        // Also refresh the parent to ensure proper alignment
        leftEyeClipParent.invalidate()
        leftEyeClipParent.requestLayout()

        // Force a full view hierarchy refresh
        this.invalidate()
        this.requestLayout()

        // Restart the mirroring refresh with a slight delay to let layout complete
        postDelayed(
                {
                    // Reset capture throttling so next capture runs immediately
                    lastCaptureTime = 0L

                    // Force bitmap recreation on next capture
                    synchronized(bitmapLock) {
                        bitmap?.recycle()
                        bitmap = null
                    }

                    startRefreshing()
                    // DebugLog.d("FullscreenDebug", "  Post-hide refresh triggered")
                },
                300
        ) // Small delay to let layout settle

        hideSystemUI()

        // Restore normal refresh rate and notify listener
        fullscreenListener?.onExitFullscreen()
        updateRefreshRate()

        // DebugLog.d("FullscreenDebug", "hideFullScreenOverlay complete")
    }

    private fun updateRefreshRate() {
        val isFullscreen = fullScreenOverlayContainer.visibility == View.VISIBLE
        val now = System.currentTimeMillis()
        val isIdle = (now - lastUserInteractionTime) > idleThresholdMs

        // With BinocularSbsLayout handling SBS rendering directly (no PixelCopy),
        // the refresh loop only drives scrollbar checks and cursor blink.
        // Lower rates save CPU/GPU for audio decoding and reduce thermal throttling.
        //
        // 1. Screen masked: 10fps (100ms) - minimal updates
        // 2. Scrolling: 60fps (16ms) - smooth scroll bar tracking
        // 3. Idle and not playing media: 10fps (100ms)
        // 4. Media playing (audio/video): 4fps (250ms) — scrollbars rarely change,
        //    and freeing the main-thread + GPU eliminates audio-thread starvation
        // 5. Anchored browsing: 30fps (33ms) - responsive scroll bars
        // 6. Default: 30fps (33ms)
        refreshInterval =
                when {
                    isScreenMasked -> maskedRefreshIntervalMs
                    isInScrollMode -> 16L
                    isIdle && !isMediaPlaying -> idleRefreshIntervalMs
                    isMediaPlaying -> 250L
                    isAnchored && !isFullscreen -> 33L
                    else -> 33L
                }
    }

    /** Call this from touch handlers to reset idle timer */
    fun noteUserInteraction() {
        lastUserInteractionTime = System.currentTimeMillis()
        // If we were in idle mode, restore normal refresh rate
        updateRefreshRate()
    }

    private fun hideSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot hide system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // CRITICAL: Must set decorFitsSystemWindows to false first
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.let { controller ->
                        controller.hide(
                                android.view.WindowInsets.Type.statusBars() or
                                        android.view.WindowInsets.Type.navigationBars()
                        )
                        controller.systemBarsBehavior =
                                android.view.WindowInsetsController
                                        .BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
                        DebugLog.d("FullscreenDebug", "System UI hidden (API 30+)")
                    }
                            ?: Log.w("FullscreenDebug", "WindowInsetsController is null!")
                } else {
                    // Older Android versions - Use deprecated flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_FULLSCREEN or
                                    View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or
                                    View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    DebugLog.d("FullscreenDebug", "System UI hidden (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error hiding system UI", e)
            }
        }
    }

    private fun showSystemUI() {
        val activity =
                context as? Activity
                        ?: run {
                            Log.w(
                                    "FullscreenDebug",
                                    "Cannot show system UI - context is not an Activity"
                            )
                            return
                        }

        post {
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    // Android 11+ (API 30+) - Use WindowInsetsController
                    // Restore decorFitsSystemWindows
                    @Suppress("DEPRECATION") activity.window.setDecorFitsSystemWindows(false)

                    activity.window.insetsController?.show(
                            android.view.WindowInsets.Type.statusBars() or
                                    android.view.WindowInsets.Type.navigationBars()
                    )
                    DebugLog.d("FullscreenDebug", "System UI shown (API 30+)")
                } else {
                    // Older Android versions - Clear flags
                    @Suppress("DEPRECATION")
                    activity.window.decorView.systemUiVisibility =
                            (View.SYSTEM_UI_FLAG_LAYOUT_STABLE or
                                    View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
                    DebugLog.d("FullscreenDebug", "System UI shown (legacy API)")
                }
            } catch (e: Exception) {
                Log.e("FullscreenDebug", "Error showing system UI", e)
            }
        }
    }

    fun maskScreen() {
        if (isScreenMasked) return  // Idempotent: prevent duplicate handler accumulation
        isScreenMasked = true
        maskOverlay.visibility = View.VISIBLE
        maskOverlay.bringToFront()
        // Hide both cursor views
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye_slash)
        keepScreenOn = true
        updatePlaybackWakeLocks()
        refreshMaskedNowPlaying()
        refreshMaskedNowPlayingFromJs()
        // Start periodic now-playing refresh (every 5s) — remove first to guarantee single handler
        removeCallbacks(maskNowPlayingPeriodicRefresh)
        postDelayed(maskNowPlayingPeriodicRefresh, 5000L)
        updateRefreshRate()
    }

    fun unmaskScreen() {
        isScreenMasked = false
        updatePlaybackWakeLocks()
        removeCallbacks(maskNowPlayingPeriodicRefresh)
        lastMaskedDomTitle = null
        lastMaskedDomTitleUrl = null
        lastMaskedDomTitleAt = 0L
        maskOverlay.visibility = View.GONE
        if (::maskNowPlayingText.isInitialized) {
            maskNowPlayingText.visibility = View.GONE
        }
        if (isVisualizerVisible) hideVisualizer()
        // Let MainActivity handle cursor visibility restoration - cursors will be shown
        // if they were visible before masking through updateCursorPosition call
        leftToggleBar.findViewById<FontIconView>(R.id.btnMask)?.setText(R.string.fa_eye)
        keepScreenOn = false
        updateRefreshRate()
    }

    fun isScreenMasked() = isScreenMasked

    fun setHostPaused(paused: Boolean) {
        if (isHostPaused == paused) return
        isHostPaused = paused
        updatePlaybackWakeLocks()
    }

    fun isFullScreenOverlayVisible() = fullScreenOverlayContainer.visibility == View.VISIBLE

    fun dispatchMaskOverlayTouch(screenX: Float, screenY: Float) {
        // Global debounce: prevent the same physical tap from triggering this method
        // multiple times via different code paths (maskOverlay listener + MainActivity handlers)
        val now = SystemClock.uptimeMillis()
        if (now - lastMaskOverlayDispatchTime < MASK_OVERLAY_DISPATCH_DEBOUNCE_MS) return
        lastMaskOverlayDispatchTime = now

        val location = IntArray(2)
        maskOverlay.getLocationOnScreen(location)
        val scale = uiScale

        // Convert to local coordinates relative to mask overlay
        // val localX = screenX - location[0]
        // val localY = screenY - location[1]

        // DebugLog.d("MediaControls", "dispatchMaskOverlayTouch at local ($localX, $localY), scale:
        // $scale")

        // Check unmask button hit (account for scale in button dimensions)
        val unmaskLocation = IntArray(2)
        btnMaskUnmask.getLocationOnScreen(unmaskLocation)
        val unmaskWidth = btnMaskUnmask.width * scale
        val unmaskHeight = btnMaskUnmask.height * scale
        if (screenX >= unmaskLocation[0] &&
                        screenX <= unmaskLocation[0] + unmaskWidth &&
                        screenY >= unmaskLocation[1] &&
                        screenY <= unmaskLocation[1] + unmaskHeight
        ) {
            // DebugLog.d("MediaControls", "Unmask button pressed")
            unmaskScreen()
            return
        }

        // Check visualizer toggle button
        if (::btnVisualizerToggle.isInitialized && btnVisualizerToggle.visibility == View.VISIBLE) {
            val vizBtnLoc = IntArray(2)
            btnVisualizerToggle.getLocationOnScreen(vizBtnLoc)
            val vizW = btnVisualizerToggle.width * scale
            val vizH = btnVisualizerToggle.height * scale
            // Expand hit area for small button
            val pad = 12f * scale
            if (screenX >= vizBtnLoc[0] - pad &&
                screenX <= vizBtnLoc[0] + vizW + pad &&
                screenY >= vizBtnLoc[1] - pad &&
                screenY <= vizBtnLoc[1] + vizH + pad
            ) {
                btnVisualizerToggle.performClick()
                return
            }
        }

        // Tap on the visualizer itself → cycle themes (debounced 400ms)
        if (::maskVisualizerView.isInitialized && maskVisualizerView.visibility == View.VISIBLE) {
            val vizLoc = IntArray(2)
            maskVisualizerView.getLocationOnScreen(vizLoc)
            val vizW = maskVisualizerView.width * scale
            val vizH = maskVisualizerView.height * scale
            if (screenX >= vizLoc[0] &&
                screenX <= vizLoc[0] + vizW &&
                screenY >= vizLoc[1] &&
                screenY <= vizLoc[1] + vizH
            ) {
                val now = SystemClock.uptimeMillis()
                if (now - lastVisualizerThemeTapTime < 500) return  // debounce theme taps (prevent multi-fire from touch+mouse)
                lastVisualizerThemeTapTime = now
                maskVisualizerView.cycleThemeOrWrap()
                updateVisualizerButtonColor()
                return
            }
        }

        // Check media control buttons
        if (maskMediaControlsContainer.visibility == View.VISIBLE) {
            val controlsLocation = IntArray(2)
            maskMediaControlsContainer.getLocationOnScreen(controlsLocation)

            // Iterate through children (the media buttons)
            for (i in 0 until maskMediaControlsContainer.childCount) {
                val button = maskMediaControlsContainer.getChildAt(i)
                if (button.visibility != View.VISIBLE) continue

                val btnLocation = IntArray(2)
                button.getLocationOnScreen(btnLocation)
                val btnWidth = button.width * scale
                val btnHeight = button.height * scale

                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    // DebugLog.d("MediaControls", "Media button $i pressed")
                    button.performClick()
                    return
                }
            }
        }

        // DebugLog.d("MediaControls", "Touch on mask overlay but not on any button")
    }

    fun dispatchFullScreenOverlayTouch(screenX: Float, screenY: Float) {
        val scale = uiScale
        DebugLog.d("FullscreenTouch", "Touch at screen ($screenX, $screenY), scale: $scale")

        // Check controls container if visible
        if (::fullScreenControlsContainer.isInitialized &&
                        fullScreenControlsContainer.visibility == View.VISIBLE
        ) {
            DebugLog.d("FullscreenTouch", "Controls container is visible")

            // Check exit button
            if (::btnFsExit.isInitialized && btnFsExit.visibility == View.VISIBLE) {
                val btnLocation = IntArray(2)
                btnFsExit.getLocationOnScreen(btnLocation)
                val btnWidth = btnFsExit.width * scale
                val btnHeight = btnFsExit.height * scale
                DebugLog.d(
                        "FullscreenTouch",
                        "Exit button: loc=(${btnLocation[0]}, ${btnLocation[1]}), size=($btnWidth, $btnHeight), raw=(${btnFsExit.width}, ${btnFsExit.height})"
                )
                if (screenX >= btnLocation[0] &&
                                screenX <= btnLocation[0] + btnWidth &&
                                screenY >= btnLocation[1] &&
                                screenY <= btnLocation[1] + btnHeight
                ) {
                    DebugLog.d("FullscreenTouch", "Exit button HIT!")
                    btnFsExit.performClick()
                    return
                }
            }

            // Check media control buttons
            if (::fullScreenMediaControls.isInitialized &&
                            fullScreenMediaControls.visibility == View.VISIBLE
            ) {
                DebugLog.d(
                        "FullscreenTouch",
                        "Media controls visible with ${fullScreenMediaControls.childCount} children"
                )
                for (i in 0 until fullScreenMediaControls.childCount) {
                    val button = fullScreenMediaControls.getChildAt(i)
                    if (button.visibility != View.VISIBLE) continue

                    val btnLocation = IntArray(2)
                    button.getLocationOnScreen(btnLocation)
                    val btnWidth = button.width * scale
                    val btnHeight = button.height * scale
                    DebugLog.d(
                            "FullscreenTouch",
                            "Button $i: loc=(${btnLocation[0]}, ${btnLocation[1]}), size=($btnWidth, $btnHeight)"
                    )

                    if (screenX >= btnLocation[0] &&
                                    screenX <= btnLocation[0] + btnWidth &&
                                    screenY >= btnLocation[1] &&
                                    screenY <= btnLocation[1] + btnHeight
                    ) {
                        DebugLog.d("FullscreenTouch", "Button $i HIT!")
                        button.performClick()
                        return
                    }
                }
            }
        } else {
            DebugLog.d("FullscreenTouch", "Controls container NOT visible or not initialized")
        }

        // If no button hit, toggle controls visibility
        DebugLog.d("FullscreenTouch", "No button hit, toggling controls visibility")
        if (::fullScreenControlsContainer.isInitialized) {
            if (fullScreenControlsContainer.visibility == View.VISIBLE) {
                fullScreenControlsContainer.visibility = View.GONE
            } else {
                fullScreenControlsContainer.visibility = View.VISIBLE
                fullScreenControlsContainer.bringToFront()
            }
        }
    }

    private fun drawBitmapToSurface() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    fun getCurrentLinkText(): String {
        return urlEditText.text.toString()
    }

    fun toggleIsUrlEditing(isEditing: Boolean) {
        _isUrlEditing = isEditing
        // DebugLog.d("LinkEditing", "DualWebViewGroup isUrlEditing toggled to: $isEditing")
    }

    fun setLinkText(text: String, newCursorPosition: Int = -1) {
        urlEditText.setText(text)

        // If no specific cursor position requested, maintain current position
        val cursorPos =
                if (newCursorPosition >= 0) {
                    // Ensure requested position doesn't exceed text length
                    minOf(newCursorPosition, text.length)
                } else {
                    // Keep current cursor position but ensure it's valid
                    minOf(urlEditText.selectionStart, text.length)
                }

        urlEditText.setSelection(cursorPos)
    }

    fun adjustViewportAndFields(adjustment: Float) {
        // Apply adjustment to all elements
        // translationY = adjustment // Don't move the entire group, just children
        webView.translationY = adjustment
        urlEditText.translationY = adjustment
        dialogContainer.translationY = adjustment

        if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
            // Ensure bookmarks view stays above keyboard
            leftBookmarksView.translationY = adjustment

            // Get the current edit field from bookmarks view
            val editField = leftBookmarksView.getCurrentEditField()
            editField?.translationY = adjustment
        }
    }

    fun getCurrentUrlEditField(): EditText? {
        return if (_isUrlEditing) urlEditText else null
    }

    fun animateViewportAdjustment() {
        webView.animate().setDuration(200).translationY(webView.translationY).start()
    }

    // Method to show link editing UI
    fun showLinkEditing() {
        if (!_isUrlEditing) {
            _isUrlEditing = true

            val currentUrl = webView.url ?: ""
            urlEditText.apply {
                text.clear()
                append(currentUrl)
                visibility = View.VISIBLE
                requestFocus()
                setSelection(text.length)
                bringToFront()
            }

            keyboardListener?.onShowKeyboard()
        }
    }

    fun isUrlEditing(): Boolean {
        // DebugLog.d("LinkEditing", "isUrlEditing check, value: $isUrlEditing")
        return _isUrlEditing
    }

    fun isBookmarksExpanded(): Boolean {
        return leftBookmarksView.visibility == View.VISIBLE
    }

    private fun toggleChat() {
        if (chatView.visibility == View.VISIBLE) {
            chatView.visibility = View.GONE
        } else {
            chatView.visibility = View.VISIBLE
            chatView.bringToFront()
            maybePromptForGroqApiKey()
        }
        post {
            requestLayout()
            invalidate()
        }
    }

    fun hideChat() {
        if (!::chatView.isInitialized || chatView.visibility != View.VISIBLE) return
        chatView.visibility = View.GONE
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun maybePromptForGroqApiKey() {
        if (dialogContainer.visibility == View.VISIBLE) return
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val currentKey = prefs.getString("groq_api_key", null)?.trim()
        if (!currentKey.isNullOrBlank()) return

        showPromptDialog(
                "Enter Groq API Key",
                currentKey,
                { key ->
                    val trimmed = key.trim()
                    if (trimmed.isBlank()) {
                        showToast("API Key Required")
                        post { maybePromptForGroqApiKey() }
                        return@showPromptDialog
                    }
                    prefs.edit().putString("groq_api_key", trimmed).apply()
                    showToast("API Key Saved")
                    keyboardListener?.onHideKeyboard()
                },
                { showToast("API Key Required") }
        )
    }

    private fun toggleBookmarks() {
        leftBookmarksView.toggle()

        if (leftBookmarksView.visibility == View.VISIBLE) {
            leftBookmarksView.bringToFront()
            leftBookmarksView.elevation = 1000f

            // Force immediate refresh to ensure mirroring
            post {
                invalidate()
                startRefreshing()
            }
        }

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleBookmarkTap(): Boolean {
        if (leftBookmarksView.visibility != View.VISIBLE) {
            // DebugLog.d("BookmarksDebug", "No tap handling - bookmarks not visible")
            return false
        }

        // Let BookmarksView handle the tap
        val handled = leftBookmarksView.handleTap()
        if (handled) {
            // Force refresh to update the mirrored view
            startRefreshing()
        }
        return handled
    }

    fun handleBookmarkDoubleTap(): Boolean {
        return if (leftBookmarksView.visibility == View.VISIBLE) {
            // DebugLog.d("BookmarksDebug", "handleBookmarkDoubleTap() called.
            // leftVisibility=${leftBookmarksView.visibility}")
            val handled = leftBookmarksView.handleDoubleTap()
            if (handled) {
                leftBookmarksView.logStackTrace(
                        "BookmarksDebug",
                        "handleBookmarkDoubleTap(): double tap handled"
                )
                // Force refresh to update the mirrored view
                startRefreshing()
            }
            handled
        } else false
    }

    fun getBookmarksView(): BookmarksView {
        return leftBookmarksView
    }

    // Provide WebView access
    @SuppressLint("SetJavaScriptEnabled")
    fun getWebView(): WebView {
        return webView.apply {
            val settings = this.settings
            settings.javaScriptEnabled = true
            settings.domStorageEnabled = true
            @Suppress("DEPRECATION") run { settings.databaseEnabled = true }
            settings.useWideViewPort = true
            settings.loadWithOverviewMode = true
            settings.setSupportZoom(true)
            settings.builtInZoomControls = true
            settings.displayZoomControls = false
            settings.mediaPlaybackRequiresUserGesture = false

            // Clean up legacy JS interface - we use URL scheme now
            // addJavascriptInterface(WebAppInterface(context, this), "Android")

            // Set User Agent

            // Set User Agent
            // settings.userAgentString = desktopUserAgent // Default to Desktop
        }
    }

    private fun setupUrlEditText(isRight: Boolean = false): EditText {
        return EditText(context).apply {
            layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.MATCH_PARENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    Gravity.TOP
                            )
                            .apply {
                                leftMargin = toggleBarWidthPx // Single margin for left side
                            }
            setBackgroundColor(Color.parseColor("#202020"))
            setTextColor(Color.WHITE)
            textSize = 16f
            setPadding(32, 12, 32, 12)
            isSingleLine = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = urlFieldMinHeight
            visibility = View.GONE
            isFocusable = true
            isFocusableInTouchMode = true
            isCursorVisible = true
            highlightColor = Color.parseColor("#404040")

            // Set hardware acceleration for better cursor rendering
            setLayerType(
                    View.LAYER_TYPE_HARDWARE,
                    Paint().apply {
                        color = Color.WHITE // Set cursor color to white
                    }
            )

            // Set hardware acceleration for better cursor rendering
            setLayerType(View.LAYER_TYPE_HARDWARE, null)

            // Make both EditTexts share focus state
            setOnFocusChangeListener { _, hasFocus ->
                if (isRight && hasFocus) {
                    urlEditText.requestFocus()
                }
            }

            // Add text change listener to sync content
            addTextChangedListener(
                    object : TextWatcher {
                        override fun beforeTextChanged(
                                s: CharSequence?,
                                start: Int,
                                count: Int,
                                after: Int
                        ) {}
                        override fun onTextChanged(
                                s: CharSequence?,
                                start: Int,
                                before: Int,
                                count: Int
                        ) {}
                        override fun afterTextChanged(s: Editable?) {}
                    }
            )
        }
    }

    // Set up the bitmap for capturing content
    private fun setupBitmap(width: Int, height: Int) {
        if (width <= 0 || height <= 0) return

        synchronized(bitmapLock) {
            try {
                bitmap?.let { oldBitmap ->
                    if (!oldBitmap.isRecycled) {
                        oldBitmap.recycle()
                    }
                }
                bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error creating bitmap", e)
                bitmap = null
            }
        }
    }

    fun updateLeftEyePosition(xOffset: Float, yOffset: Float, rotationDeg: Float) {

        // Store the translations
        _translationX = yOffset
        _translationY = xOffset

        // If you also want to store rotation in a field:
        _rotationZ = rotationDeg

        leftEyeUIContainer.translationX = yOffset
        leftEyeUIContainer.translationY = xOffset
        leftEyeUIContainer.rotation = rotationDeg

        // Only apply same transformations to full screen overlay when it's actually visible
        // This prevents the video from being positioned incorrectly when fullscreen is activated
        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenOverlayContainer.translationX = yOffset
            fullScreenOverlayContainer.translationY = xOffset
            fullScreenOverlayContainer.rotation = rotationDeg
        } else {
            // Keep at zero when not visible to ensure clean state
            fullScreenOverlayContainer.translationX = 0f
            fullScreenOverlayContainer.translationY = 0f
            fullScreenOverlayContainer.rotation = 0f
        }

        // Pass the fixed screen cursor position to hover detection
        // In anchored mode, the cursor is visually fixed at the center (320, 240)
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)
        val screenX = 320f + containerLocation[0]
        val screenY = 240f + containerLocation[1]

        updateButtonHoverStates(screenX, screenY)

        // Ensure visual cursor scale/visibility is refreshed in anchored mode
        listener?.onCursorPositionChanged(320f, 240f, true)

        // Only do expensive operations occasionally, not every frame
        // The Choreographer already ensures smooth vsync timing
        if (!isRefreshing) {
            post { startRefreshing() }
        }
    }

    // Capture and mirror content to left SurfaceView
    private fun captureLeftEyeContent() {
        if (!isRefreshing) {
            return
        }

        val currentTime = System.currentTimeMillis()
        if (currentTime - lastCaptureTime < MIN_CAPTURE_INTERVAL) {
            return
        }
        lastCaptureTime = currentTime

        try {
            // Check scrollbar visibility periodically (once per second)
            // Skip if in fullscreen mode to save power
            val isFullScreen = fullScreenOverlayContainer.visibility == View.VISIBLE

            if (!isFullScreen && currentTime - lastScrollBarCheckTime > 1000) {
                if (!shouldFreezeScrollBars()) {
                    updateScrollBarsVisibility()
                }
                lastScrollBarCheckTime = currentTime
            }

            // Force cursor refresh if editing - skip in fullscreen
            if (!isFullScreen && _isUrlEditing && urlEditText.isFocused) {
                urlEditText.invalidate()
            }

            // NOTE: PixelCopy + drawBitmapToSurface() removed — BinocularSbsLayout now
            // renders the SBS output directly from the view hierarchy, making the old
            // capture-to-bitmap-then-draw pipeline dead code. Removing it frees ~60 GPU
            // PixelCopy ops/sec and eliminates bitmapLock contention that was starving
            // the audio decoder thread on the X3 Pro (manifesting as periodic stutters).
        } catch (e: Exception) {
            Log.e("MirrorDebug", "Error in refresh tick", e)
            stopRefreshing()
        }
    }

    fun onKeyboardHidden() {
        // Reset views when keyboard is hidden
        post {
            requestLayout()
            invalidate()

            // Force bitmap recreation with new dimensions
            // setupBitmap(webView.width, height - 48)

            // Ensure mirroring is updated
            startRefreshing()
        }
    }

    fun syncKeyboardStates() {
        customKeyboard?.let { Kb ->

            // Force update of the keyboard
            Kb.post {
                Kb.invalidate()
                Kb.requestLayout()
                keyboardContainer.invalidate()
                keyboardContainer.requestLayout()
            }
        }
    }

    // Refresh handling
    private var refreshCount = 0
    private var lastRefreshLogTime = 0L

    private val refreshRunnable =
            object : Runnable {
                override fun run() {
                    refreshCount++

                    // Log every 2 seconds to avoid spam
                    val now = System.currentTimeMillis()
                    if (now - lastRefreshLogTime > 2000) {
                        // DebugLog.d("MirrorDebug", "RefreshLoop running, count=$refreshCount,
                        // isRefreshing=$isRefreshing,
                        // webViewAttached=${webView.isAttachedToWindow},
                        // fsOverlayVisible=${fullScreenOverlayContainer.visibility ==
                        // View.VISIBLE}")
                        lastRefreshLogTime = now
                    }

                    if (isRefreshing) {
                        if (webView.isAttachedToWindow) {
                            captureLeftEyeContent()
                        }
                        refreshHandler.postDelayed(this, refreshInterval)
                    } else {
                        Log.w("MirrorDebug", "RefreshLoop STOPPING! isRefreshing=$isRefreshing")
                        // No need to call stopRefreshing() here as we just stop posting callbacks
                    }
                }
            }

    fun startRefreshing() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    fun stopRefreshing() {
        // REFACTORED: No-op in single viewport mode
        // BinocularSbsLayout handles the rendering - no mirroring needed
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {

        // BinocularSbsLayout gives us the logical viewport size; use actual measured dimensions.
        val eyeWidth = r - l
        val eyeHeight = b - t
        val halfWidth = eyeWidth

        val toggleBarWidth = toggleBarWidthPx
        val navBarHeight = navBarHeightPx

        // Ensure toggle bar is measured correctly
        leftToggleBar.measure(
                MeasureSpec.makeMeasureSpec(toggleBarWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(eyeHeight - navBarHeight, MeasureSpec.EXACTLY)
        )
        if (!isInScrollMode && !isNavBarsHidden) {
            if (leftToggleBar.visibility != View.VISIBLE) {
                leftToggleBar.visibility = View.VISIBLE
            }
        }

        // Ensure navigation bar is measured correctly
        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // Force a layout pass on the container if needed
        if (leftToggleBar.measuredWidth == 0) {
            leftEyeUIContainer.requestLayout()
        }

        val height = b - t
        // Use actual measured height of keyboard if visible, otherwise default
        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160
        // Keyboard width is same regardless of mode (matches original keyboard size)
        val keyboardWidth = halfWidth - toggleBarWidth

        // Position the WebView differently based on scroll mode
        // Shrink the WebView when keyboard is visible so content isn't blocked
        val isKeyboardVisible = keyboardContainer.visibility == View.VISIBLE

        val horizontalReserve = if (horizontalScrollBar.visibility == View.VISIBLE) 20 else 0

        if (isInScrollMode || isNavBarsHidden) {
            val keyboardLimit =
                    if (isKeyboardVisible) {
                        eyeHeight - keyboardHeight // Shrink to fit above keyboard
                    } else {
                        480
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webViewsContainer.measuredHeight
            val adjustedKeyboardLimit = (keyboardLimit - horizontalReserve).coerceAtLeast(0)

            webViewsContainer.layout(
                    0, // No left margin in scroll mode
                    0,
                    0 + webViewsContainer.measuredWidth, // Full width minus margins
                    minOf(adjustedKeyboardLimit, measuredBottom)
            )
        } else {
            val navBarTop = eyeHeight - navBarHeight

            val keyboardLimit =
                    if (isKeyboardVisible) {
                        minOf(navBarTop, eyeHeight - keyboardHeight) // Shrink to fit above keyboard
                    } else {
                        navBarTop // Default bottom for 30px nav bar
                    }
            // Respect proper measurement which accounts for margins (scrollbars)
            val measuredBottom = 0 + webViewsContainer.measuredHeight
            val adjustedKeyboardLimit = (keyboardLimit - horizontalReserve).coerceAtLeast(0)

            webViewsContainer.layout(
                    toggleBarWidth, // Account for toggle bar
                    0,
                    toggleBarWidth +
                            webViewsContainer.measuredWidth, // Standard width + toggle bar offset
                    minOf(adjustedKeyboardLimit, measuredBottom)
            )
        }

        // Calculate available content height based on keyboard visibility
        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    eyeHeight - keyboardHeight
                } else {
                    eyeHeight - navBarHeight
                }

        // Layout the clip parent - hardcoded 640x480
        leftEyeClipParent.layout(
                0, // After toggle bar
                0,
                eyeWidth, // Fixed width for left eye
                eyeHeight
        )

        fullScreenOverlayContainer.layout(
                0, // Relative to leftEyeClipParent
                0,
                halfWidth, // 640px width (matches clip parent)
                eyeHeight
        )

        // REFACTORED: rightEyeView layout no longer needed - single viewport mode
        // rightEyeView.layout(eyeWidth, 0, eyeWidth * 2, eyeHeight)

        // Layout toggle bar - height is eyeHeight minus navBarHeight
        leftToggleBar.layout(0, 0, toggleBarWidth, eyeHeight - navBarHeight)
        //            DebugLog.d("ToggleBarDebug", """
        //        Toggle Bar Layout:
        //        Visibility: ${leftToggleBar.visibility}
        //        Width: $toggleBarWidth
        //        Height: 596
        //        Background: ${leftToggleBar.background}
        //        Parent: ${leftToggleBar.parent?.javaClass?.simpleName}
        //    """.trimIndent())

        val keyboardY = eyeHeight - keyboardHeight
        keyboardContainer.layout(
                toggleBarWidth,
                keyboardY,
                toggleBarWidth + keyboardWidth,
                eyeHeight
        )

        // Position ProgressBar - at bottom in scroll mode, above nav bar otherwise
        val progressBarHeight = 4
        if (isInScrollMode) {
            // In scroll mode, position at very bottom, full width
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - progressBarHeight
                progressBar.layout(0, pbY, halfWidth, eyeHeight)
                progressBar.bringToFront()
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        } else {
            // Normal mode - position above navigation bar
            progressBar.measure(
                    MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(progressBarHeight, MeasureSpec.EXACTLY)
            )
            if (progressBar.visibility == View.VISIBLE) {
                val pbY = eyeHeight - navBarHeight - progressBarHeight
                progressBar.layout(toggleBarWidth, pbY, halfWidth, pbY + progressBarHeight)
            } else {
                progressBar.layout(0, 0, 0, 0)
            }
        }

        // Hide navigation bars
        leftNavigationBar.visibility = View.GONE

        if (keyboardContainer.visibility == View.VISIBLE) {
            // Position keyboards at the bottom
            // In scroll mode, center keyboard (no toggle bar offset)
            val kbLeft =
                    if (isInScrollMode) {
                        (halfWidth - keyboardWidth) / 2 // Center in left half
                    } else {
                        toggleBarWidth
                    }
            keyboardContainer.layout(kbLeft, keyboardY, kbLeft + keyboardWidth, eyeHeight)

            // Hide navigation bars
            leftNavigationBar.visibility = View.GONE

            // Position bookmarks menu if visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                val bookmarksHeight = leftBookmarksView.measuredHeight
                val isEditingAnywhere = _isUrlEditing || leftBookmarksView.isEditing()
                val bookmarksY =
                        if (isEditingAnywhere) {
                            40 // Below URL edit field area / top of screen
                        } else {
                            keyboardY - bookmarksHeight
                        }

                // Constrain bottom to keyboardY to avoid overlapping with keyboard
                val bookmarksBottom =
                        if (isEditingAnywhere) {
                            minOf(bookmarksY + bookmarksHeight, keyboardY)
                        } else {
                            bookmarksY + bookmarksHeight
                        }

                leftBookmarksView.layout(
                        toggleBarWidth,
                        bookmarksY,
                        toggleBarWidth + 480,
                        bookmarksBottom
                )

                leftBookmarksView.bringToFront()
            }

            // Handle edit fields for both URL and bookmark editing
            if (_isUrlEditing || isBookmarkEditing) {
                val editFieldHeight = maxOf(urlFieldMinHeight, urlEditText.measuredHeight)
                val editFieldLeft = keyboardContainer.left.takeIf { it > 0 } ?: toggleBarWidth
                val editFieldRight =
                        keyboardContainer.right.takeIf { it > editFieldLeft }
                                ?: (editFieldLeft + keyboardWidth)

                // Position left edit field only
                urlEditText.apply {
                    layout(editFieldLeft, 0, editFieldRight, editFieldHeight)
                    translationY = (keyboardY - editFieldHeight).toFloat()
                    visibility = View.VISIBLE
                    elevation = 1001f
                }
            }

            // Ensure keyboard containers are on top but below edit fields
            keyboardContainer.elevation = 1000f
        } else {
            // DebugLog.d("EditFieldDebug", "Skipping edit field positioning - conditions not met")

            // Hide keyboard containers
            keyboardContainer.layout(
                    toggleBarWidth,
                    eyeHeight,
                    toggleBarWidth + keyboardWidth,
                    eyeHeight + keyboardHeight
            )

            // Position bookmarks when keyboard is not visible
            if (::leftBookmarksView.isInitialized && leftBookmarksView.visibility == View.VISIBLE) {
                leftBookmarksView.layout(
                        toggleBarWidth,
                        30,
                        toggleBarWidth + 480,
                        eyeHeight - navBarHeight
                )
            }

            // Show navigation bar only in normal mode (hide in scroll mode to avoid overlap)
            if (isInScrollMode) {
                leftNavigationBar.visibility = View.GONE
                leftNavigationBar.layout(0, 0, 0, 0)
            } else {
                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.layout(0, eyeHeight - navBarHeight, halfWidth, eyeHeight)
            }
        }

        // Update bitmap capture when layout changes
        if (changed) {
            post {
                setupBitmap(webView.width, contentHeight)
                startRefreshing()
            }
        }

        // Hide system info bar when disabled or while nav/scroll overlays are hidden.
        updateSystemInfoBarVisibility()
        if (leftSystemInfoView.visibility == View.VISIBLE) {
            // Calculate system info bar position
            val infoBarHeight = 24
            val infoBarY = eyeHeight - navBarHeight - infoBarHeight // Position above nav bar

            // First measure the info views to get their width
            leftSystemInfoView.measure(
                    MeasureSpec.makeMeasureSpec(320, MeasureSpec.AT_MOST),
                    MeasureSpec.makeMeasureSpec(infoBarHeight, MeasureSpec.EXACTLY)
            )

            val infoBarWidth = leftSystemInfoView.measuredWidth
            val leftX =
                    (halfWidth - infoBarWidth) / 2 +
                            toggleBarWidth // Center in left half, account for toggle bar

            // Position the info bars
            leftSystemInfoView.layout(
                    leftX,
                    infoBarY,
                    leftX + infoBarWidth,
                    infoBarY + infoBarHeight
            )
        }

        // Position Dialog Container (Center it in the left view)
        if (dialogContainer.visibility != View.GONE) {
            val dialogWidth = 500

            // Measure the dialog container first if needed
            dialogContainer.measure(
                    MeasureSpec.makeMeasureSpec(dialogWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(eyeHeight, MeasureSpec.AT_MOST)
            )

            val measuredH = dialogContainer.measuredHeight

            val dialogLeft = toggleBarWidth + (keyboardWidth - dialogWidth) / 2

            // Calculate available vertical space, respecting the keyboard if it is visible
            val availableHeight =
                    if (keyboardContainer.visibility == View.VISIBLE) {
                        eyeHeight - keyboardHeight
                    } else {
                        eyeHeight
                    }
            // Center the dialog within the available space
            val dialogTop = (availableHeight - measuredH) / 2

            dialogContainer.layout(
                    dialogLeft,
                    dialogTop,
                    dialogLeft + dialogWidth,
                    dialogTop + measuredH
            )
            dialogContainer.elevation = 2000f
            dialogContainer.bringToFront()
        }

        // Layout maskOverlay to cover left eye only (will be mirrored to right eye)
        maskOverlay.layout(0, 0, halfWidth, height)

        // Layout the unhide button when in scroll mode
        if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) {
            val btnSize = 40
            val btnRight = halfWidth - 8 // 8px margin from right
            val btnBottom = height - 8 // 8px margin from bottom
            btnShowNavBars.layout(btnRight - btnSize, btnBottom - btnSize, btnRight, btnBottom)
            btnShowNavBars.bringToFront()
        }

        // Layout scroll bars for non-anchored mode
        // Eye button size is 40px with 8px margin from bottom/right, so reserve 48px for it
        val eyeButtonSpace =
                if (isInScrollMode && btnShowNavBars.visibility == View.VISIBLE) 48 else 0

        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val hScrollHeight = 20
            val navBarTop =
                    if (leftNavigationBar.visibility == View.VISIBLE) eyeHeight - navBarHeight
                    else eyeHeight
            val hScrollY =
                    if (isInScrollMode) eyeHeight - hScrollHeight
                    else navBarTop - hScrollHeight // Sit right above nav bar

            val leftInset =
                    if (leftToggleBar.visibility == View.VISIBLE) {
                        leftToggleBar.measuredWidth.takeIf { it > 0 } ?: toggleBarWidth
                    } else {
                        0
                    }
            val scrollLeft = leftInset
            var scrollWidth =
                    if (isInScrollMode) halfWidth - leftInset - eyeButtonSpace
                    else halfWidth - leftInset

            // Prevent overlap with vertical scrollbar if visible
            if (verticalScrollBar.visibility == View.VISIBLE) {
                scrollWidth -= 20 // Subtract width of vertical scrollbar
            }

            horizontalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(scrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(hScrollHeight, MeasureSpec.EXACTLY)
            )
            horizontalScrollBar.layout(
                    scrollLeft,
                    hScrollY,
                    scrollLeft + scrollWidth,
                    hScrollY + hScrollHeight
            )
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val vScrollWidth = 20
            val vScrollRight = halfWidth // Align to right edge
            val vScrollTop = 0 // Start from top

            // In scroll mode, stop above eye button. Normal mode, stop at nav bar.
            val vScrollBottom =
                    if (isInScrollMode) eyeHeight - eyeButtonSpace else eyeHeight - navBarHeight
            val vScrollHeight = vScrollBottom - vScrollTop

            verticalScrollBar.measure(
                    MeasureSpec.makeMeasureSpec(vScrollWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(vScrollHeight, MeasureSpec.EXACTLY)
            )
            verticalScrollBar.layout(
                    vScrollRight - vScrollWidth,
                    vScrollTop,
                    vScrollRight,
                    vScrollTop + vScrollHeight
            )
        }

        // Layout the UI container to cover just the left half
        leftEyeUIContainer.layout(0, 0, halfWidth, height)

        if (::chatView.isInitialized &&
                        chatView.visibility == View.VISIBLE &&
                        keyboardContainer.visibility == View.VISIBLE
        ) {
            val chatMargin = 8.dp()
            val availableHeight = (eyeHeight - keyboardHeight - chatMargin).coerceAtLeast(0)
            val baseWidth =
                    chatView.layoutParams.width.takeIf { it > 0 }
                            ?: chatView.measuredWidth.takeIf { it > 0 } ?: 560
            val baseHeight =
                    chatView.layoutParams.height.takeIf { it > 0 }
                            ?: chatView.measuredHeight.takeIf { it > 0 } ?: 420
            val targetWidth = baseWidth.coerceAtMost(halfWidth)
            val targetHeight = baseHeight.coerceAtMost(availableHeight)

            if (targetHeight > 0) {
                chatView.measure(
                        MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
                )
                val left = (halfWidth - targetWidth) / 2
                val bottom = eyeHeight - keyboardHeight - chatMargin
                val top = bottom - chatView.measuredHeight
                chatView.layout(left, top, left + targetWidth, bottom)
            }
        }
    }

    private var sharedConfigListenerRegistered = false

    fun cleanupResources() {
        if (maskWakeLock.isHeld) maskWakeLock.release()
        if (pausedMediaWakeLock.isHeld) pausedMediaWakeLock.release()
        try {
            if (mediaWifiLock?.isHeld == true) mediaWifiLock.release()
        } catch (_: Exception) {}
        if (sharedConfigListenerRegistered) {
            sharedConfigPrefs.unregisterOnSharedPreferenceChangeListener(sharedConfigListener)
            sharedConfigListenerRegistered = false
        }
        stopRefreshing()
        releaseAudioCapture()
        synchronized(bitmapLock) {
            bitmap?.let { currentBitmap ->
                if (!currentBitmap.isRecycled) {
                    currentBitmap.recycle()
                }
            }
            bitmap = null
        }
        System.gc() // Request garbage collection
    }

    fun getCurrentEditText(): String {
        return urlEditText.text.toString()
    }

    fun hideLinkEditing() {
        _isUrlEditing = false
        isBookmarkEditing = false

        urlEditText.apply {
            clearFocus()
            visibility = View.GONE
            elevation = 0f
        }

        post {
            startRefreshing()
            requestLayout()
            invalidate()
        }
    }

    private fun EditText.setOnSelectionChangedListener(listener: (Int, Int) -> Unit) {
        try {
            val field = TextView::class.java.getDeclaredField("mEditor")
            field.isAccessible = true
            val editor = field.get(this)

            val listenerField = editor.javaClass.getDeclaredField("mSelectionChangedListener")
            listenerField.isAccessible = true
            listenerField.set(
                    editor,
                    object : Any() {
                        fun onSelectionChanged(selStart: Int, selEnd: Int) {
                            listener(selStart, selEnd)
                        }
                    }
            )
        } catch (e: Exception) {
            Log.e("DualWebViewGroup", "Error setting selection listener", e)
        }
    }

    fun showInfoBars() {
        updateSystemInfoBarVisibility()
    }

    fun hideInfoBars() {
        leftSystemInfoView.visibility = View.GONE
    }

    private fun Int.dp(): Int = (this * resources.displayMetrics.density).roundToInt()

    private fun isBrowserSystemInfoEnabled(): Boolean =
            sharedConfigPrefs.getBoolean(KEY_BROWSER_SHOW_SYSTEM_INFO, true)

    private fun updateSystemInfoBarVisibility() {
        leftSystemInfoView.visibility =
                if (!isBrowserSystemInfoEnabled() || isInScrollMode || isNavBarsHidden) {
                    View.GONE
                } else {
                    View.VISIBLE
                }
    }

    // Add keyboard mirror handling
    fun setKeyboard(originalKeyboard: CustomKeyboardView) {
        // DebugLog.d("KeyboardDebug", "setKeyboard called with keyboard:
        // ${originalKeyboard.hashCode()}")

        // Clear container
        keyboardContainer.removeAllViews()

        // Clear animations
        keyboardContainer.clearAnimation()
        webView.clearAnimation()
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.clearAnimation()

        // Reset translations
        keyboardContainer.translationY = 0f
        webView.translationY = 0f
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.translationY = 0f

        // Set keyboard
        customKeyboard = originalKeyboard
        customKeyboard?.setAnchoredMode(isAnchored)
        keyboardContainer.addView(
                originalKeyboard,
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT,
                        )
                        .apply { gravity = Gravity.BOTTOM }
        )

        // Explicitly set visibility based on keyboard's current state
        val visibility =
                if (originalKeyboard.visibility == View.VISIBLE) View.VISIBLE else View.GONE
        keyboardContainer.visibility = visibility

        // Hide navigation bars when keyboard is visible
        if (visibility == View.VISIBLE) {
            leftNavigationBar.visibility = View.GONE
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    override fun dispatchDraw(canvas: Canvas) {
        super.dispatchDraw(canvas)
        // Force redraw of toggle buttons
        leftToggleBar.findViewById<View>(R.id.btnModeToggle)?.invalidate()
    }

    private fun getCursorInContainerCoords(): Pair<Float, Float> {
        // Calculate the actual screen position of the cursor first
        val containerLocation = IntArray(2)
        getLocationOnScreen(containerLocation)

        val transX = if (isAnchored) 0f else leftEyeUIContainer.translationX
        val transY = if (isAnchored) 0f else leftEyeUIContainer.translationY

        val visualX = 320f + (lastCursorX - 320f) * uiScale + transX
        val visualY = 240f + (lastCursorY - 240f) * uiScale + transY

        val screenX = visualX + containerLocation[0]
        val screenY = visualY + containerLocation[1]

        return computeAnchoredCoordinates(screenX, screenY)
    }

    private fun computeAnchoredKeyboardCoordinates(): Pair<Float, Float>? {
        val keyboard = keyboardContainer
        if (keyboard.width == 0 || keyboard.height == 0) {
            // DebugLog.d("TouchDebug", "computeAnchoredKeyboardCoordinates: keyboard not laid out")
            return null
        }

        val (adjustedX, adjustedY) = getCursorInContainerCoords()

        val keyboardLocation = IntArray(2)
        keyboard.getLocationOnScreen(keyboardLocation)
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)
        val localXContainer = adjustedX - keyboard.x
        val localYContainer = adjustedY - keyboard.y

        val kbView = customKeyboard ?: return null

        val localX = localXContainer - kbView.x
        val localY = localYContainer - kbView.y

        return Pair(localX, localY)
    }

    private fun computeAnchoredCoordinates(screenX: Float, screenY: Float): Pair<Float, Float> {
        val parent = leftEyeUIContainer.parent as View
        val parentLocation = IntArray(2)
        parent.getLocationOnScreen(parentLocation)

        val relativeX = screenX - parentLocation[0]
        val relativeY = screenY - parentLocation[1]

        val points = floatArrayOf(relativeX, relativeY)

        val inverse = android.graphics.Matrix()
        leftEyeUIContainer.matrix.invert(inverse)
        inverse.mapPoints(points)

        return Pair(points[0], points[1])
    }

    private fun isTouchOnView(view: View, x: Float, y: Float): Boolean {
        return view.visibility == View.VISIBLE &&
                x >= view.left &&
                x <= view.right &&
                y >= view.top &&
                y <= view.bottom
    }

    override fun onInterceptTouchEvent(ev: MotionEvent): Boolean {
        // DebugLog.d("GestureDebug", "DualWebViewGroup onInterceptTouchEvent: ${ev.action}")

        // Let windows overview handle its own touch events
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            return false // Don't intercept, let children handle touches
        }

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            // Allow interactions with menus that are on top
            if (::leftBookmarksView.isInitialized && isTouchOnView(leftBookmarksView, ev.x, ev.y)) {
                return false
            }

            fullScreenTapDetector.onTouchEvent(ev)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            var isOverTarget = false
            val (cursorX, cursorY) = getCursorInContainerCoords()

            // Check Keyboard
            if (keyboardContainer.visibility == View.VISIBLE) {
                val localCoords = computeAnchoredKeyboardCoordinates()
                if (localCoords != null) {
                    val (localX, localY) = localCoords
                    if (localX >= 0 &&
                                    localX <= keyboardContainer.width &&
                                    localY >= 0 &&
                                    localY <= keyboardContainer.height
                    ) {
                        isOverTarget = true
                        anchoredTarget = 1
                    }
                }
            }

            // Check Bookmarks (if not already over keyboard)
            if (!isOverTarget &&
                            ::leftBookmarksView.isInitialized &&
                            leftBookmarksView.visibility == View.VISIBLE
            ) {
                if (cursorX >= leftBookmarksView.left &&
                                cursorX <= leftBookmarksView.right &&
                                cursorY >= leftBookmarksView.top &&
                                cursorY <= leftBookmarksView.bottom
                ) {
                    isOverTarget = true
                    anchoredTarget = 2
                    // DebugLog.d("TouchDebug", "Intercepting anchored tap for bookmarks")
                }
            }

            when (ev.action) {
                MotionEvent.ACTION_DOWN -> {
                    anchoredGestureActive = isOverTarget
                    if (anchoredGestureActive) {
                        anchoredTouchStartX = cursorX
                        anchoredTouchStartY = cursorY
                        lastAnchoredY = cursorY
                        isAnchoredDrag = false
                        // DebugLog.d("TouchDebug", "Intercepting anchored ACTION_DOWN
                        // target=$anchoredTarget")
                        return true
                    }
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) return true
                    if (isOverTarget) {
                        anchoredGestureActive = true
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    if (anchoredGestureActive || isOverTarget) {
                        // DebugLog.d("TouchDebug", "Intercepting anchored ACTION_UP")
                        return true
                    }
                }
                MotionEvent.ACTION_CANCEL -> {
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
            return false
        }

        // Non-anchored keyboard handling
        if (keyboardContainer.visibility == View.VISIBLE && !isAnchored) {
            return true
        }

        // Non-anchored bookmarks handling
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            return true
        }

        return false
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val widthSize = MeasureSpec.getSize(widthMeasureSpec)
        val heightSize = MeasureSpec.getSize(heightMeasureSpec)
        // BinocularSbsLayout already gives us half the screen; use full given width.
        val halfWidth = widthSize
        val navBarHeight = navBarHeightPx
        val toggleBarWidth = toggleBarWidthPx
        val keyboardWidth = halfWidth - toggleBarWidth

        // Measure keyboard container first to get its actual height
        keyboardContainer.measure(
                MeasureSpec.makeMeasureSpec(keyboardWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )

        val keyboardHeight =
                if (keyboardContainer.measuredHeight > 0) keyboardContainer.measuredHeight else 160

        val contentHeight =
                if (keyboardContainer.visibility == View.VISIBLE) {
                    heightSize - keyboardHeight
                } else {
                    heightSize - navBarHeight
                }

        // Measure WebView with different dimensions based on scroll mode
        // FIX: Respect the LayoutParams set by updateScrollBarsVisibility
        val lp = webViewsContainer.layoutParams

        if (isInScrollMode || isNavBarsHidden) {
            val targetWidth = if (lp != null && lp.width > 0) lp.width else 640
            val targetHeight = if (lp != null && lp.height > 0) lp.height else 480

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        } else {
            // Normal Mode
            // Use layout params if available (set by updateScrollBarsVisibility)
            // Default fallback: 640 - toggle bar = width, content height
            val targetWidth = if (lp != null && lp.width > 0) lp.width else (640 - toggleBarWidth)

            // For height in normal mode, we used MATCH_PARENT in updateScrollBarsVisibility
            // usually,
            // but sometimes explicit. If MATCH_PARENT (-1), we use the calculated contentHeight.
            val targetHeight =
                    if (lp != null && lp.height > 0) lp.height
                    else if (contentHeight > 0) contentHeight else 440

            webViewsContainer.measure(
                    MeasureSpec.makeMeasureSpec(targetWidth, MeasureSpec.EXACTLY),
                    MeasureSpec.makeMeasureSpec(targetHeight, MeasureSpec.EXACTLY)
            )
        }

        // REFACTORED: rightEyeView measuring no longer needed - single viewport mode
        // rightEyeView.measure(
        //         MeasureSpec.makeMeasureSpec(halfWidth - toggleBarWidth, MeasureSpec.EXACTLY),
        //         MeasureSpec.makeMeasureSpec(contentHeight, MeasureSpec.EXACTLY)
        // )

        leftNavigationBar.measure(
                MeasureSpec.makeMeasureSpec(halfWidth, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(navBarHeight, MeasureSpec.EXACTLY)
        )

        // keyboardContainer is already measured above, but we can measure it again with EXACTLY if
        // we want to enforce constraints,
        // but UNSPECIFIED allowed it to size itself. Let's stick to the measurement we did.

        fullScreenOverlayContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        maskOverlay.measure(
                MeasureSpec.makeMeasureSpec(widthSize, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure leftEyeUIContainer and its children
        leftEyeUIContainer.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        // Measure windowsOverviewContainer if visible
        windowsOverviewContainer?.let { woc ->
            if (woc.visibility == View.VISIBLE) {
                val containerWidth = 640 - toggleBarWidthPx
                val containerHeight = heightSize - navBarHeightPx
                woc.measure(
                        MeasureSpec.makeMeasureSpec(containerWidth, MeasureSpec.EXACTLY),
                        MeasureSpec.makeMeasureSpec(containerHeight, MeasureSpec.EXACTLY)
                )
            }
        }

        // Measure leftEyeClipParent
        leftEyeClipParent.measure(
                MeasureSpec.makeMeasureSpec(640, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(heightSize, MeasureSpec.EXACTLY)
        )

        setMeasuredDimension(widthSize, heightSize)
    }

    // at class top
    private var downWhen = 0L
    private var downX = 0f
    private var downY = 0f

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val kbVisible = (keyboardContainer.visibility == View.VISIBLE)

        if (fullScreenOverlayContainer.visibility == View.VISIBLE) {
            fullScreenTapDetector.onTouchEvent(event)
            return true
        }

        // Skip anchored gesture handling when in scroll mode - touches should go directly to
        // WebView
        if (isAnchored && !isInScrollMode) {
            // Track velocity for anchored interactions (bookmarks scroll, etc.)
            if (velocityTracker == null) {
                velocityTracker = android.view.VelocityTracker.obtain()
            }
            velocityTracker?.addMovement(event)

            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    if (anchoredGestureActive) return true
                }
                MotionEvent.ACTION_MOVE -> {
                    if (anchoredGestureActive) {
                        val (cursorX, cursorY) = getCursorInContainerCoords()

                        // Check for drag threshold
                        if (!isAnchoredDrag) {
                            val dx = kotlin.math.abs(cursorX - anchoredTouchStartX)
                            val dy = kotlin.math.abs(cursorY - anchoredTouchStartY)
                            if (dx > ANCHORED_TOUCH_SLOP || dy > ANCHORED_TOUCH_SLOP) {
                                isAnchoredDrag = true
                            }
                        }

                        if (isAnchoredDrag && anchoredTarget == 2) { // Bookmarks
                            val deltaY = lastAnchoredY - cursorY
                            if (::leftBookmarksView.isInitialized &&
                                            leftBookmarksView.visibility == View.VISIBLE
                            ) {
                                leftBookmarksView.handleAnchoredSwipe(deltaY)
                            }
                        }

                        lastAnchoredY = cursorY
                        return true
                    }
                }
                MotionEvent.ACTION_UP -> {
                    val wasTracking = anchoredGestureActive
                    anchoredGestureActive = false

                    if (wasTracking) {
                        if (!isAnchoredDrag) {
                            val (cursorX, cursorY) = getCursorInContainerCoords()

                            // Dispatch tap based on target determined at ACTION_DOWN
                            when (anchoredTarget) {
                                1 -> { // Keyboard
                                    // Managed by MainActivity dispatchKeyboardTap
                                }
                                2 -> { // Bookmarks
                                    if (::leftBookmarksView.isInitialized &&
                                                    leftBookmarksView.visibility == View.VISIBLE
                                    ) {
                                        // DebugLog.d("TouchDebug", "Dispatching anchored tap to
                                        // bookmarks")
                                        leftBookmarksView.handleAnchoredTap(
                                                cursorX - leftBookmarksView.left,
                                                cursorY - leftBookmarksView.top
                                        )
                                    }
                                }
                            }
                        } else if (anchoredTarget == 2) {
                            // Anchored Fling for Bookmarks
                            velocityTracker?.computeCurrentVelocity(1000)
                            val velocityY = velocityTracker?.yVelocity ?: 0f
                            // Pass raw velocityY.
                            handleAnchoredFling(velocityY)
                        }
                    }

                    anchoredTarget = 0
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (wasTracking) return true
                }
                MotionEvent.ACTION_CANCEL -> {
                    velocityTracker?.recycle()
                    velocityTracker = null
                    if (anchoredGestureActive) {
                        anchoredGestureActive = false
                        anchoredTarget = 0
                        return true
                    }
                }
            }
        }

        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                downWhen = event.eventTime
                downX = event.x
                downY = event.y
            }
            MotionEvent.ACTION_UP -> {
                val dur = event.eventTime - downWhen
                val travelX = kotlin.math.abs(event.x - downX)
                val travelY = kotlin.math.abs(event.y - downY)
                val wasTap = dur < 300 && travelX < 8 && travelY < 8

                if (wasTap && !isAnchored) {
                    // Check for potential content changes
                    postDelayed({ updateScrollBarsVisibility() }, 500)
                }

                // Handle non-anchored tap for keyboard
                if (kbVisible && !isAnchored && wasTap) {
                    // Focus-driven tap: send the highlighted key
                    customKeyboard?.performFocusedTap()
                    return true
                }

                // Handle non-anchored tap for bookmarks
                if (::leftBookmarksView.isInitialized &&
                                leftBookmarksView.visibility == View.VISIBLE &&
                                !isAnchored &&
                                wasTap
                ) {
                    leftBookmarksView.performFocusedTap()
                    return true
                }
            }
        }

        // Let the keyboard keep handling movement (your current behavior)
        if (kbVisible && !isAnchored) {
            return customKeyboard?.dispatchTouchEvent(event) == true
        }

        // Let the bookmarks view handle movement in non-anchored mode
        if (::leftBookmarksView.isInitialized &&
                        leftBookmarksView.visibility == View.VISIBLE &&
                        !isAnchored
        ) {
            leftBookmarksView.handleDrag(event.x, event.action)
            return true
        }

        return super.onTouchEvent(event)
    }

    fun getKeyboardLocation(location: IntArray) {
        keyboardContainer.getLocationOnScreen(location)
    }

    fun getLogicalKeyboardLocation(location: IntArray) {
        location[0] = keyboardContainer.left
        location[1] = keyboardContainer.top
    }

    fun isPointInBookmarks(screenX: Float, screenY: Float): Boolean {
        if (!::leftBookmarksView.isInitialized || leftBookmarksView.visibility != View.VISIBLE)
                return false

        val bookmarksLocation = IntArray(2)
        leftBookmarksView.getLocationOnScreen(bookmarksLocation)

        return screenX >= bookmarksLocation[0] &&
                screenX <= bookmarksLocation[0] + leftBookmarksView.width &&
                screenY >= bookmarksLocation[1] &&
                screenY <= bookmarksLocation[1] + leftBookmarksView.height
    }

    fun isChatVisible(): Boolean {
        return ::chatView.isInitialized && chatView.visibility == View.VISIBLE
    }

    fun sendTextToChatInput(text: String) {
        if (!isChatVisible()) return
        chatView.sendTextToFocusedInput(text)
    }

    fun sendBackspaceToChatInput() {
        if (!isChatVisible()) return
        chatView.sendBackspaceToFocusedInput()
    }

    fun sendEnterToChatInput() {
        if (!isChatVisible()) return
        chatView.sendEnterToFocusedInput()
    }

    fun isPointInChat(screenX: Float, screenY: Float): Boolean {
        if (!isChatVisible()) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= chatView.left &&
                localX <= chatView.right &&
                localY >= chatView.top &&
                localY <= chatView.bottom
    }

    fun dispatchChatTouchEvent(screenX: Float, screenY: Float) {
        if (!isChatVisible()) return

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        val finalX = localX - chatView.left
        val finalY = localY - chatView.top
        chatView.handleAnchoredTap(finalX, finalY)
    }

    fun isPointInKeyboard(screenX: Float, screenY: Float): Boolean {
        if (keyboardContainer.visibility != View.VISIBLE) return false
        val kbView = customKeyboard ?: return false
        if (kbView.visibility != View.VISIBLE) return false

        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        return localX >= keyboardContainer.left &&
                localX <= keyboardContainer.right &&
                localY >= keyboardContainer.top &&
                localY <= keyboardContainer.bottom
    }

    fun getKeyboardSize(): Pair<Int, Int> {
        return Pair(keyboardContainer.width, keyboardContainer.height)
    }

    // Called from MainActivity when the cursor is over the keyboard
    // Called from MainActivity to dispatch a tap to the custom keyboard
    fun dispatchKeyboardTap(screenX: Float, screenY: Float) {
        val kbView = customKeyboard ?: return
        if (kbView.visibility != View.VISIBLE) return

        val groupLocation = IntArray(2)
        getLocationOnScreen(groupLocation)

        // Translate screen coordinates to be relative to the UI container's screen origin
        // Note: keyboardContainer is a child of leftEyeUIContainer
        val uiLocation = IntArray(2)
        leftEyeUIContainer.getLocationOnScreen(uiLocation)

        val translatedX = screenX - uiLocation[0]
        val translatedY = screenY - uiLocation[1]

        val localX: Float
        val localY: Float

        if (isAnchored) {
            val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
            val cos = Math.cos(rotationRad).toFloat()
            val sin = Math.sin(rotationRad).toFloat()

            // Interaction is already scaled in MainActivity for non-anchored,
            // but in anchored mode screen coordinates are absolute.
            // However, the UI inside the container is logical.
            localX = (translatedX * cos + translatedY * sin) / uiScale
            localY = (-translatedX * sin + translatedY * cos) / uiScale
        } else {
            localX = translatedX / uiScale
            localY = translatedY / uiScale
        }

        // Subtract keyboard's logical position within the container
        val finalX = localX - keyboardContainer.left
        val finalY = localY - keyboardContainer.top

        // DebugLog.d("KeyboardDebug", "Keyboard tap: screen($screenX, $screenY) -> local($finalX,
        // $finalY)")
        kbView.handleAnchoredTap(finalX, finalY)
    }

    fun isDesktopMode(): Boolean {
        return isDesktopMode
    }

    fun setMobileUserAgent(ua: String) {
        mobileUserAgent = ua
        desktopUserAgent = buildDesktopUserAgentFromMobile(ua)
    }

    fun getDesktopUserAgent(): String {
        return desktopUserAgent
    }

    private fun buildDesktopUserAgentFromMobile(mobileUa: String): String {
        val chromeVersion = Regex("""Chrome/([0-9.]+)""").find(mobileUa)?.groupValues?.get(1)
        if (!chromeVersion.isNullOrBlank()) {
            // Use real runtime Chrome version so desktop mode is plausible, not hardcoded/fake.
            return "Mozilla/5.0 (Windows NT 10.0; Win64; x64) " +
                    "AppleWebKit/537.36 (KHTML, like Gecko) " +
                    "Chrome/$chromeVersion Safari/537.36"
        }

        // Fallback: keep runtime UA and strip obvious embedded/mobile markers.
        return mobileUa.replace(Regex(""";\s*wv\b"""), "")
                .replace(Regex("""\sVersion/\d+(\.\d+)*"""), "")
                .replace(Regex("""\sMobile\b"""), "")
                .replace(Regex("""\s{2,}"""), " ")
                .trim()
    }

    fun updateBrowsingMode(isDesktop: Boolean) {
        // DebugLog.d("ModeToggle", "Updating browsing mode to: ${if (isDesktop) "desktop" else
        // "mobile"}")

        isDesktopMode = isDesktop

        // Step 1: Update WebView settings (user agent)
        // If on Netflix, we preserve the current UA (which should be the system default) to prevent
        // DRM errors.
        applyBrowsingModeToWebView(webView, isDesktop)

        // Step 2: Update viewport using JavaScript without forcing a complete reload
        val viewportContent =
                if (isDesktop) "width=1280, initial-scale=0.8"
                else "width=600, initial-scale=1.0, maximum-scale=1.0"

        webView.post {
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

            // Step 3: Soft reload the page by re-navigating to the current URL
            val currentUrl = webView.url
            if (currentUrl != null && currentUrl != "about:blank") {
                // Use loadUrl to "soft reload" and keep browsing history
                webView.loadUrl("javascript:window.location.href = window.location.href")
            }
        }

        // Update toggle button icons
        syncBrowsingModeUi()
    }

    private fun applyBrowsingModeToWebView(targetWebView: WebView, isDesktop: Boolean) {
        val isNetflix = targetWebView.url?.contains("netflix.com") == true
        if (isNetflix) return

        targetWebView.settings.apply {
            userAgentString =
                    if (isDesktop) {
                        desktopUserAgent
                    } else {
                        mobileUserAgent
                    }
            loadWithOverviewMode = true
            useWideViewPort = true
        }
    }

    private fun syncBrowsingModeUi() {
        webView.post {
            val leftButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
            leftButton?.text =
                    context.getString(
                            if (isDesktopMode) R.string.fa_desktop else R.string.fa_mobile_screen
                    )
        }
    }

    private fun loadARDashboard() {
        webView.loadUrl(Constants.DEFAULT_URL)
    }

    // Method to disable text handles
    @SuppressLint("DiscouragedPrivateApi")
    private fun disableTextHandles(editText: EditText) {
        // Don’t allow long-press to start selection
        editText.isLongClickable = false
        editText.setOnLongClickListener { true }

        // Don’t allow selection mode (copy/paste toolbar)
        editText.setTextIsSelectable(false)

        // Block the selection action mode
        editText.customSelectionActionModeCallback =
                object : android.view.ActionMode.Callback {
                    override fun onCreateActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onPrepareActionMode(
                            mode: android.view.ActionMode,
                            menu: android.view.Menu
                    ) = false
                    override fun onActionItemClicked(
                            mode: android.view.ActionMode,
                            item: android.view.MenuItem
                    ) = false
                    override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                }

        // Block the insertion/caret handle action mode (API 23+)
        if (android.os.Build.VERSION.SDK_INT >= 23) {
            editText.customInsertionActionModeCallback =
                    object : android.view.ActionMode.Callback {
                        override fun onCreateActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onPrepareActionMode(
                                mode: android.view.ActionMode,
                                menu: android.view.Menu
                        ) = false
                        override fun onActionItemClicked(
                                mode: android.view.ActionMode,
                                item: android.view.MenuItem
                        ) = false
                        override fun onDestroyActionMode(mode: android.view.ActionMode) {}
                    }
        }

        // Optional: consume double-tap/long-press gestures that can trigger selection on some OEM
        // skins
        editText.setOnTouchListener { _, ev ->
            if (ev.actionMasked == MotionEvent.ACTION_DOWN && ev.eventTime - ev.downTime > 0) {
                // Let simple taps through; block long-press-ish starts if needed
                false
            } else {
                false
            }
        }
    }

    // In DualWebViewGroup.kt
    fun showEditField(initialText: String) {
        urlEditText.apply {
            text.clear()
            append(initialText)
            visibility = View.VISIBLE
            requestFocus()
            setSelection(text.length)
            bringToFront()
            // Add logging to verify state
        }
        // Make sure we're in edit mode
        isBookmarkEditing = true
        keyboardListener?.onShowKeyboard()

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    private fun showButtonClickFeedback(button: View) {
        button.isPressed = true
        // DebugLog.d("buttonFeedbackDebug", "button feedback shown")
        Handler(Looper.getMainLooper())
                .postDelayed({ button.isPressed = false }, buttonFeedbackDuration)
    }

    private fun handleLeftMenuAction(buttonId: Int) {
        if (buttonId != R.id.btnTts) {
            keyboardListener?.onHideKeyboard()
        }

        val button = leftToggleBar.findViewById<View>(buttonId)

        when (buttonId) {
            R.id.btnModeToggle -> {
                button?.let { showButtonClickFeedback(it) }
                isDesktopMode = !isDesktopMode

                // Save preference
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .edit()
                        .putBoolean("isDesktopMode", isDesktopMode)
                        .apply()

                updateBrowsingMode(isDesktopMode)
            }
            R.id.btnYouTube -> {
                button?.let { showButtonClickFeedback(it) }
                loadARDashboard()
            }
            R.id.btnBookmarks -> {
                button?.let { showButtonClickFeedback(it) }
                toggleBookmarks()
            }
            R.id.btnZoomOut -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("out")
            }
            R.id.btnZoomIn -> {
                button?.let { showButtonClickFeedback(it) }
                handleZoomButtonClick("in")
            }
            R.id.btnMask -> {
                button?.let { showButtonClickFeedback(it) }
                maskToggleListener?.onMaskTogglePressed()
            }
            R.id.btnTts -> {
                button?.let { showButtonClickFeedback(it) }
                ttsToggleListener?.onTtsTogglePressed()
            }
        }
    }

    fun hideBookmarkEditing() {
        isBookmarkEditing = false
        urlEditText.apply {
            visibility = View.GONE
            text.clear()
        }

        // Force layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun isBookmarkEditing(): Boolean {
        return isBookmarkEditing
    }

    // Add this method to handle cursor hovering
    private fun updateButtonHoverStates(screenX: Float, screenY: Float) {
        // Clear all states initially
        clearAllHoverStates()

        if (::chatView.isInitialized && chatView.visibility == View.VISIBLE) {
            val uiLocation = IntArray(2)
            leftEyeUIContainer.getLocationOnScreen(uiLocation)

            val translatedX = screenX - uiLocation[0]
            val translatedY = screenY - uiLocation[1]

            val localX: Float
            val localY: Float

            if (isAnchored) {
                val rotationRad = Math.toRadians(leftEyeUIContainer.rotation.toDouble())
                val cos = Math.cos(rotationRad).toFloat()
                val sin = Math.sin(rotationRad).toFloat()
                localX = (translatedX * cos + translatedY * sin) / uiScale
                localY = (-translatedX * sin + translatedY * cos) / uiScale
            } else {
                localX = translatedX / uiScale
                localY = translatedY / uiScale
            }

            val chatLocalX = localX - chatView.left
            val chatLocalY = localY - chatView.top

            if (chatView.updateHoverLocal(chatLocalX, chatLocalY)) {
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check bottom navigation bar buttons ONLY if nav bar is visible
        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.forEach { (_, navButton) ->
                if (isOver(navButton.left, screenX, screenY)) {
                    navButton.isHovered = true
                    navButton.left.isHovered = true
                    navButton.right.isHovered = true
                    customKeyboard?.clearHover() // Clear keyboard hover
                    return // Found the hovered button, stop checking
                }
            }
        }

        // Check left toggle bar buttons
        val toggleBarButtons =
                listOf(
                        Triple(R.id.btnModeToggle, "ModeToggle") { isHoveringModeToggle = true },
                        Triple(R.id.btnYouTube, "Dashboard") { isHoveringDashboardToggle = true },
                        Triple(R.id.btnBookmarks, "Bookmarks") { isHoveringBookmarksMenu = true },
                        Triple(R.id.btnZoomOut, "ZoomOut") { isHoveringZoomOut = true },
                        Triple(R.id.btnZoomIn, "ZoomIn") { isHoveringZoomIn = true },
                        Triple(R.id.btnMask, "Mask") { isHoveringMaskToggle = true },
                        Triple(R.id.btnTts, "TTS") { isHoveringTtsToggle = true }
                )

        for ((buttonId, _, setHoverFlag) in toggleBarButtons) {
            val button = leftToggleBar.findViewById<View>(buttonId)
            if (isOver(button, screenX, screenY)) {
                button?.isHovered = true
                setHoverFlag()
                clearNavigationButtonStates()
                // DebugLog.d("HoverDebug", "Hovering over toggle button: $name")
                customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                return // Found the hovered button, stop checking
            }
        }

        // Check Windows button separately (programmatically created, no resource ID)
        windowsButton?.let { btn ->
            if (isOver(btn, screenX, screenY)) {
                btn.isHovered = true
                isHoveringWindowsToggle = true
                clearNavigationButtonStates()
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check settings window elements if visible
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.btnToggleForceDark,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    val view = menu.findViewById<View>(id)
                    if (isOver(view, screenX, screenY)) {
                        view?.isHovered = true
                        // DebugLog.d("HoverDebug", "Hovering over settings element: $id")
                        customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                        return // Found the hovered element, stop checking
                    }
                }
            }
        }

        // Check active dialog buttons if visible
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                // Dialog structure: Title(0), Message(1), optional Input(2), ButtonContainer(last)
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        val button = container.getChildAt(i)
                        if (isOver(button, screenX, screenY)) {
                            button.isHovered = true
                            // DebugLog.d("HoverDebug", "Hovering over dialog button: $i")
                            customKeyboard?.updateHover(-1f, -1f) // Clear keyboard hover
                            return
                        }
                    }
                }
            }
        }

        // Check windows overview if visible
        if (windowsOverviewContainer?.visibility == View.VISIBLE) {
            val woc = windowsOverviewContainer ?: return

            // Use anchored coordinates if needed
            if (isAnchored) {
                val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

                // Perform hit testing relative to leftEyeUIContainer
                // woc is a child of leftEyeUIContainer
                val wocLeft = woc.left + woc.translationX
                val wocTop = woc.top + woc.translationY

                if (localX >= wocLeft &&
                                localX <= wocLeft + woc.width &&
                                localY >= wocTop &&
                                localY <= wocTop + woc.height
                ) {

                    val container = woc.getChildAt(0) as? LinearLayout ?: return
                    // Container is inside ScrollView woc
                    // local in woc
                    val xInWoc = localX - wocLeft + woc.scrollX
                    val yInWoc = localY - wocTop + woc.scrollY

                    val containerLeft = container.left + container.translationX
                    val containerTop = container.top + container.translationY

                    val xInContainer = xInWoc - containerLeft
                    val yInContainer = yInWoc - containerTop

                    // Helper for checking children
                    for (i in 0 until container.childCount) {
                        val child = container.getChildAt(i)
                        if (xInContainer >= child.left &&
                                        xInContainer <= child.right &&
                                        yInContainer >= child.top &&
                                        yInContainer <= child.bottom
                        ) {

                            if (i == 0) { // Add Button
                                child.isHovered = true
                                hoveredWindowsOverviewItem = child
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }

                            // Rows
                            if (child is ViewGroup) {
                                val xInChild = xInContainer - child.left
                                val yInChild = yInContainer - child.top

                                for (j in 0 until child.childCount) {
                                    val item = child.getChildAt(j)
                                    if (xInChild >= item.left &&
                                                    xInChild <= item.right &&
                                                    yInChild >= item.top &&
                                                    yInChild <= item.bottom
                                    ) {

                                        // Check for delete button first (FontIconView child)
                                        if (item is ViewGroup) {
                                            val xInItem = xInChild - item.left
                                            val yInItem = yInChild - item.top
                                            for (k in 0 until item.childCount) {
                                                val itemChild = item.getChildAt(k)
                                                if (itemChild is FontIconView &&
                                                                xInItem >= itemChild.left &&
                                                                xInItem <= itemChild.right &&
                                                                yInItem >= itemChild.top &&
                                                                yInItem <= itemChild.bottom
                                                ) {
                                                    itemChild.isHovered = true
                                                    hoveredWindowsOverviewItem = itemChild
                                                    customKeyboard?.updateHover(-1f, -1f)
                                                    return
                                                }
                                            }
                                        }

                                        // Set hover on the whole item
                                        item.isHovered = true
                                        hoveredWindowsOverviewItem = item
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }
                        }
                    }
                }
            } else {
                // Non-anchored mode: Use the isOver function with getGlobalVisibleRect
                val container = woc.getChildAt(0) as? LinearLayout ?: return

                // Check all children (add button + rows of window items)
                for (i in 0 until container.childCount) {
                    val child = container.getChildAt(i)

                    // First child (i == 0) is the Add Button
                    if (i == 0 && isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        hoveredWindowsOverviewItem = child
                        customKeyboard?.updateHover(-1f, -1f)
                        return
                    }

                    // Other children are rows containing window items
                    if (child is ViewGroup) {
                        for (j in 0 until child.childCount) {
                            val windowItem = child.getChildAt(j)

                            // Check for delete button first (it's a FontIconView child of the
                            // window item)
                            if (windowItem is ViewGroup) {
                                for (k in 0 until windowItem.childCount) {
                                    val itemChild = windowItem.getChildAt(k)
                                    // Delete button is a FontIconView with the X icon
                                    if (itemChild is FontIconView &&
                                                    isOver(itemChild, screenX, screenY)
                                    ) {
                                        itemChild.isHovered = true
                                        hoveredWindowsOverviewItem = itemChild
                                        customKeyboard?.updateHover(-1f, -1f)
                                        return
                                    }
                                }
                            }

                            // Then check the whole window item
                            if (isOver(windowItem, screenX, screenY)) {
                                windowItem.isHovered = true
                                hoveredWindowsOverviewItem = windowItem
                                customKeyboard?.updateHover(-1f, -1f)
                                return
                            }
                        }
                    }
                }
            }
        }

        // Check bookmarks view if visible
        if (isBookmarksExpanded()) {
            val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

            val finalX = localX - leftBookmarksView.left
            val finalY = localY - leftBookmarksView.top

            if (leftBookmarksView.updateHover(finalX, finalY)) {
                customKeyboard?.updateHoverScreen(-1f, -1f, 1f) // Clear keyboard hover
                return
            }
        }

        // Check scrollbars if visible (UI scale < 0.99f or forced visible)
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            horizontalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + horizontalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + horizontalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until horizontalScrollBar.childCount) {
                    val child = horizontalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == horizontalScrollBar.getChildAt(1)) {
                            if (isOver(hScrollThumb, screenX, screenY)) {
                                hScrollThumb.isHovered = true
                                hScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        if (verticalScrollBar.visibility == View.VISIBLE) {
            val location = IntArray(2)
            verticalScrollBar.getLocationOnScreen(location)
            if (screenX >= location[0] &&
                            screenX <= location[0] + verticalScrollBar.width &&
                            screenY >= location[1] &&
                            screenY <= location[1] + verticalScrollBar.height
            ) {

                // Check children (arrows and track)
                for (i in 0 until verticalScrollBar.childCount) {
                    val child = verticalScrollBar.getChildAt(i)
                    if (isOver(child, screenX, screenY)) {
                        child.isHovered = true
                        child.isActivated = true

                        // If we are over the track container, check the thumb specifically
                        if (child == verticalScrollBar.getChildAt(1)) {
                            if (isOver(vScrollThumb, screenX, screenY)) {
                                vScrollThumb.isHovered = true
                                vScrollThumb.isActivated = true
                            }
                        }
                    }
                }
                customKeyboard?.updateHover(-1f, -1f)
                return
            }
        }

        // Check keyboard elements if visible
        if (keyboardContainer.visibility == View.VISIBLE) {
            val kbView = customKeyboard
            if (kbView != null && kbView.visibility == View.VISIBLE) {
                val uiLocation = IntArray(2)
                leftEyeUIContainer.getLocationOnScreen(uiLocation)

                // Use screen coordinates for keyboard hit testing to avoid drift
                // Pass raw screenX/screenY and let CustomKeyboardView check against actual screen
                // positions
                kbView.updateHoverScreen(screenX, screenY, uiScale)

                // We don't return here because updateHoverScreen will internally check if a key was
                // hit.
                // However, we should check if a key WAS hit to know if we should "consume" the
                // hover event.
                // For now, if the keyboard is visible, we let it process.
                return // Stop checking after keyboard processing
            }
        }
    }

    // Helper function to clear all hover states
    private fun clearAllHoverStates() {
        // Clear toggle button states
        isHoveringModeToggle = false
        isHoveringDashboardToggle = false
        isHoveringBookmarksMenu = false
        isHoveringZoomIn = false
        isHoveringZoomOut = false

        isHoveringMaskToggle = false
        isHoveringTtsToggle = false
        isHoveringWindowsToggle = false

        // Clear windows overview hover
        hoveredWindowsOverviewItem?.isHovered = false
        hoveredWindowsOverviewItem = null

        // Clear visual hover states
        listOf(
                        R.id.btnModeToggle,
                        R.id.btnYouTube,
                        R.id.btnBookmarks,
                        R.id.btnZoomIn,
                        R.id.btnZoomOut,
                        R.id.btnMask,
                        R.id.btnTts
                )
                .forEach { id -> leftToggleBar.findViewById<View>(id)?.isHovered = false }

        // Clear Windows button hover state (programmatically created)
        windowsButton?.isHovered = false

        // Clear settings hover states
        if (isSettingsVisible) {
            settingsMenu?.let { menu ->
                val settingsElements =
                        listOf(
                                R.id.volumeSeekBar,
                                R.id.brightnessSeekBar,
                                R.id.btnToggleForceDark,
                                R.id.smoothnessSeekBar,
                                R.id.screenSizeSeekBar,
                                R.id.btnResetScreenSize,
                                R.id.fontSizeSeekBar,
                                R.id.btnResetFontSize,
                                R.id.btnResetWebpageZoom,
                                R.id.colorWheelView,
                                R.id.btnResetTextColor,
                                R.id.horizontalPosSeekBar,
                                R.id.verticalPosSeekBar,
                                R.id.btnResetPosition,
                                R.id.btnHelp,
                                R.id.btnCloseSettings,
                                R.id.btnGroqApiKey
                        )
                for (id in settingsElements) {
                    menu.findViewById<View>(id)?.isHovered = false
                }
            }
        }

        // Clear dialog button states
        if (dialogContainer.visibility == View.VISIBLE) {
            val dialogView = dialogContainer.getChildAt(0) as? ViewGroup
            dialogView?.let { viewGroup ->
                val btnContainer = viewGroup.getChildAt(viewGroup.childCount - 1) as? ViewGroup
                btnContainer?.let { container ->
                    for (i in 0 until container.childCount) {
                        container.getChildAt(i).isHovered = false
                    }
                }
            }
        }

        // Clear navigation button states
        clearNavigationButtonStates()

        if (::chatView.isInitialized) {
            chatView.clearHover()
        }

        // Clear keyboard hover
        customKeyboard?.updateHoverScreen(-1f, -1f, 1f)

        // Clear scroll bar hover states
        if (horizontalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until horizontalScrollBar.childCount) {
                horizontalScrollBar.getChildAt(i).isHovered = false
                horizontalScrollBar.getChildAt(i).isActivated = false
            }
            hScrollThumb.isHovered = false
            hScrollThumb.isActivated = false
        }
        if (verticalScrollBar.visibility == View.VISIBLE) {
            for (i in 0 until verticalScrollBar.childCount) {
                verticalScrollBar.getChildAt(i).isHovered = false
                verticalScrollBar.getChildAt(i).isActivated = false
            }
            vScrollThumb.isHovered = false
            vScrollThumb.isActivated = false
        }
    }

    // Helper method to check if a point is within any visible scrollbar
    fun isPointInScrollbar(screenX: Float, screenY: Float): Boolean {
        return isOver(horizontalScrollBar, screenX, screenY) ||
                isOver(verticalScrollBar, screenX, screenY)
    }

    // Dispatch touch/click to the appropriate scrollbar element
    fun dispatchScrollbarTouch(screenX: Float, screenY: Float) {
        fun getLocalPoint(container: ViewGroup): Pair<Float, Float>? {
            if (container.visibility != View.VISIBLE) return null
            val rect = android.graphics.Rect()
            if (!container.getGlobalVisibleRect(rect)) return null
            if (screenX < rect.left ||
                            screenX > rect.right ||
                            screenY < rect.top ||
                            screenY > rect.bottom
            ) {
                return null
            }
            val scaleX = if (container.scaleX == 0f) 1f else container.scaleX
            val scaleY = if (container.scaleY == 0f) 1f else container.scaleY
            val localX = (screenX - rect.left) / scaleX
            val localY = (screenY - rect.top) / scaleY
            return localX to localY
        }

        fun dispatchToContainer(container: ViewGroup) {
            val localPoint = getLocalPoint(container) ?: return
            val localX = localPoint.first
            val localY = localPoint.second
            // Check which child is hit
            for (i in 0 until container.childCount) {
                val child = container.getChildAt(i)
                if (localX >= child.left &&
                                localX <= child.right &&
                                localY >= child.top &&
                                localY <= child.bottom
                ) {

                    if (child.hasOnClickListeners()) {
                        child.performClick()
                    } else {
                        // For track/thumb, we need to simulate touch events
                        // The track listener reacts to ACTION_UP
                        val childLocalX = localX - child.left
                        val childLocalY = localY - child.top

                        val downEvent =
                                MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_DOWN,
                                        childLocalX,
                                        childLocalY,
                                        0
                                )
                        child.dispatchTouchEvent(downEvent)
                        downEvent.recycle()

                        val upEvent =
                                MotionEvent.obtain(
                                        SystemClock.uptimeMillis(),
                                        SystemClock.uptimeMillis(),
                                        MotionEvent.ACTION_UP,
                                        childLocalX,
                                        childLocalY,
                                        0
                                )
                        child.dispatchTouchEvent(upEvent)
                        upEvent.recycle()
                    }
                    return
                }
            }
        }

        if (isOver(horizontalScrollBar, screenX, screenY)) {
            dispatchToContainer(horizontalScrollBar)
            return
        }

        if (isOver(verticalScrollBar, screenX, screenY)) {
            dispatchToContainer(verticalScrollBar)
            return
        }
    }

    fun isNavBarVisible(): Boolean {
        // Check both visibility AND scroll mode - in scroll mode, bars are hidden even during fade
        // animation
        return !isInScrollMode && leftNavigationBar.visibility == View.VISIBLE
    }

    fun isToggleBarVisible(): Boolean {
        return leftToggleBar.visibility == View.VISIBLE
    }

    private fun isPointInToggleBarButton(screenX: Float, screenY: Float): Boolean {
        if (leftToggleBar.visibility != View.VISIBLE) return false
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        val buttonIds = listOf(
            R.id.btnModeToggle,
            R.id.btnYouTube,
            R.id.btnBookmarks,
            R.id.btnZoomOut,
            R.id.btnZoomIn,
            R.id.btnMask,
            R.id.btnTts
        )
        if (buttonIds.any { id -> isPointInChild(localX, localY, leftToggleBar, leftToggleBar.findViewById(id)) }) {
            return true
        }
        return windowsButton?.let { isPointInChild(localX, localY, leftToggleBar, it) } == true
    }

    fun isPointInToggleBar(screenX: Float, screenY: Float): Boolean {
        return isPointInToggleBarButton(screenX, screenY)
    }

    fun isPointInNavBar(screenX: Float, screenY: Float): Boolean {
        if (leftNavigationBar.visibility != View.VISIBLE) return false
        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)
        return navButtons.entries.any { isPointInChild(localX, localY, leftNavigationBar, it.value.left) }
    }

    private fun isPointInView(containerX: Float, containerY: Float, view: View?): Boolean {
        if (view == null || view.visibility != View.VISIBLE) return false
        return containerX >= view.left &&
                containerX <= view.right &&
                containerY >= view.top &&
                containerY <= view.bottom
    }

    private fun isPointInChild(
            containerX: Float,
            containerY: Float,
            parent: View,
            child: View?
    ): Boolean {
        if (child == null || parent.visibility != View.VISIBLE || child.visibility != View.VISIBLE)
                return false
        val localX = containerX - parent.left
        val localY = containerY - parent.top
        return localX >= child.left &&
                localX <= child.right &&
                localY >= child.top &&
                localY <= child.bottom
    }

    fun isPointInRestoreButton(x: Float, y: Float): Boolean {
        if (btnShowNavBars.visibility != View.VISIBLE) return false
        val loc = IntArray(2)
        btnShowNavBars.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (btnShowNavBars.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (btnShowNavBars.height * uiScale)
    }

    fun performRestoreButtonClick() {
        if (btnShowNavBars.visibility == View.VISIBLE) {
            btnShowNavBars.performClick()
        }
    }

    fun isWindowsOverviewVisible(): Boolean {
        return windowsOverviewContainer?.visibility == View.VISIBLE
    }

    fun isPointInWindowsOverview(x: Float, y: Float): Boolean {
        val woc = windowsOverviewContainer ?: return false
        if (woc.visibility != View.VISIBLE) return false

        val loc = IntArray(2)
        woc.getLocationOnScreen(loc)
        return x >= loc[0] && x <= loc[0] + woc.width && y >= loc[1] && y <= loc[1] + woc.height
    }

    fun performWindowsOverviewClick() {
        val current = hoveredWindowsOverviewItem
        if (current == null || !current.isAttachedToWindow || !current.isHovered) {
            refreshHoverAtCurrentCursor()
        }

        val item = hoveredWindowsOverviewItem ?: return
        if (!item.isAttachedToWindow || !item.isHovered) return

        showButtonClickFeedback(item)
        item.performClick()
    }

    private fun isOver(button: View?, screenX: Float, screenY: Float): Boolean {
        if (button == null || button.visibility != View.VISIBLE) return false

        // Use getGlobalVisibleRect for accurate screen bounds detection
        val rect = android.graphics.Rect()
        if (!button.getGlobalVisibleRect(rect)) return false

        return screenX >= rect.left &&
                screenX <= rect.right &&
                screenY >= rect.top &&
                screenY <= rect.bottom
    }

    fun handleNavigationClick(screenX: Float, screenY: Float) {
        if (isInScrollMode) return

        val (localX, localY) = computeAnchoredCoordinates(screenX, screenY)

        if (isSettingsVisible && settingsMenu != null) {
            if (isPointInView(localX, localY, settingsMenu)) {
                dispatchSettingsTouchEvent(screenX, screenY)
                return
            }
        }

        if (leftToggleBar.visibility == View.VISIBLE) {
            val toggleBarButtons =
                    listOf(
                            R.id.btnModeToggle,
                            R.id.btnYouTube,
                            R.id.btnBookmarks,
                            R.id.btnZoomOut,
                            R.id.btnZoomIn,
                            R.id.btnMask,
                            R.id.btnTts
                    )

            for (buttonId in toggleBarButtons) {
                val button = leftToggleBar.findViewById<View>(buttonId)
                if (isPointInChild(localX, localY, leftToggleBar, button)) {
                    handleLeftMenuAction(buttonId)
                    return
                }
            }

            windowsButton?.let { btn ->
                if (isPointInChild(localX, localY, leftToggleBar, btn)) {
                    showButtonClickFeedback(btn)
                    toggleWindowMode()
                    return
                }
            }
        }

        if (leftNavigationBar.visibility == View.VISIBLE) {
            navButtons.entries.firstOrNull {
                    isPointInChild(localX, localY, leftNavigationBar, it.value.left)
                }?.let { (key, button) ->
                    triggerNavigationAction(key, button)
                }
        }
    }

    private fun triggerNavigationAction(key: String, button: NavButton) {
        keyboardListener?.onHideKeyboard()
        showButtonClickFeedback(button.left)
        showButtonClickFeedback(button.right)
        if (key == "hide") {
            setNavBarsHidden(true) // Hide nav bars but keep cursor visible
            return
        }
        if (key == "chat") {
            toggleChat()
            return
        }
        navigationListener?.let { listener ->
            when (key) {
                "back" -> listener.onNavigationBackPressed()
                "forward" -> listener.onNavigationForwardPressed()
                "home" -> listener.onHomePressed()
                "link" -> listener.onHyperlinkPressed()
                "settings" -> listener.onSettingsPressed()
                "refresh" -> listener.onRefreshPressed()
                "quit" -> listener.onQuitPressed()
            }
        }
    }

    fun resetPositions() {
        // Reset translations
        _translationX = 0f
        _translationY = 0f
        _rotationZ = 0f

        // Reset translations on views
        leftEyeUIContainer.translationX = 0f
        leftEyeUIContainer.translationY = 0f
        leftEyeUIContainer.rotation = 0f

        // Reset translations on views
        leftEyeClipParent.translationX = 0f
        leftEyeClipParent.translationY = 0f
        leftEyeClipParent.rotation = 0f

        // Also reset fullscreen overlay
        fullScreenOverlayContainer.translationX = 0f
        fullScreenOverlayContainer.translationY = 0f
        fullScreenOverlayContainer.rotation = 0f

        postDelayed(
                {
                    startRefreshing()
                    requestLayout()
                    invalidate()
                },
                100
        )
    }

    private fun handleZoomButtonClick(direction: String) {
        // For ar_nav.html: delegate to the 3D map's own zoom handler
        webView.evaluateJavascript(
                """
        (function() {
            if (window.__arNavZoom) { window.__arNavZoom('$direction'); return; }
            document.body.style.zoom = "${if (direction == "in") currentWebZoom * 1.1f else currentWebZoom * 0.9f}";
        })();
    """,
                null
        )

        // Only update CSS zoom tracking for non-AR pages
        val url = webView.url ?: ""
        if (!url.contains("ar_nav.html")) {
            val zoomFactor = if (direction == "in") 1.1f else 0.9f
            currentWebZoom *= zoomFactor
            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    .edit()
                    .putFloat("webZoomLevel", currentWebZoom)
                    .apply()
        }

        postDelayed(
                {
                    updateScrollBarsVisibility()
                    lastScrollBarCheckTime = System.currentTimeMillis()
                },
                100
        )
    }

    fun refreshBothBookmarks() {
        // Refresh left bookmarks view
        leftBookmarksView.refreshBookmarks()
        leftBookmarksView.visibility = View.VISIBLE
        leftBookmarksView.bringToFront()
        leftBookmarksView.measure(
                MeasureSpec.makeMeasureSpec(420, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
        )
        leftBookmarksView.layout(
                leftBookmarksView.left,
                leftBookmarksView.top,
                leftBookmarksView.left + 480,
                leftBookmarksView.top + leftBookmarksView.measuredHeight
        )

        // Force a layout update
        leftBookmarksView.post {
            leftBookmarksView.requestLayout()
            leftBookmarksView.invalidate()
            // Ensure the mirroring is updated
            startRefreshing()
        }
    }

    // In DualWebViewGroup.kt, add these methods:
    fun startAnchoring() {
        isAnchored = true
        webView.visibility = View.VISIBLE
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.visibility = View.VISIBLE
        startRefreshing()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(true)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(true)
        }

        // Use unbarred anchor icon when anchored
        leftToggleBar.findViewById<FontIconView>(R.id.btnTts)?.text =
                context.getString(R.string.fa_volume_high)
    }

    fun stopAnchoring() {
        isAnchored = false
        resetPositions()

        // Update scrollbars immediately
        updateScrollBarsVisibility()

        // Update keyboard behavior
        customKeyboard?.setAnchoredMode(false)

        // Update bookmarks view mode
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.setAnchoredMode(false)
        }

        leftToggleBar.findViewById<FontIconView>(R.id.btnTts)?.text =
                context.getString(R.string.fa_volume_high)
        webView.visibility = View.VISIBLE
        // REFACTORED: rightEyeView no longer used - single viewport mode
        // rightEyeView.visibility = View.VISIBLE

        updateUiTranslation()

        post {
            startRefreshing()
            invalidate()
        }
    }

    fun setBookmarksView(bookmarksView: BookmarksView) {
        this.leftBookmarksView =
                bookmarksView.apply {
                    val params =
                            MarginLayoutParams(420, LayoutParams.WRAP_CONTENT).apply {
                                leftMargin = toggleBarWidthPx // After toggle bar
                                topMargin = 10 // Move higher up
                            }
                    layoutParams = params
                    elevation = 1000f
                    visibility = View.GONE
                }

        // Remove existing view if present
        (leftBookmarksView.parent as? ViewGroup)?.removeView(leftBookmarksView)

        // Add view to hierarchy
        leftEyeUIContainer.addView(leftBookmarksView)
        leftBookmarksView.bringToFront()

        // Request layout update
        post {
            requestLayout()
            invalidate()
        }
    }

    fun handleAnchoredFling(velocity: Float) {
        if (isBookmarksExpanded()) {
            // No-op for bookmarks in anchored mode (pagination used)
        } else {
            // Forward to general handleFling which handles WebView scroll
            handleFling(velocity)
        }
    }

    fun handleFling(velocityX: Float) {
        // DebugLog.d("Fling Debug", "Fling handled by DualWebViewGroup")

        // First check if bookmarks are visible (Non-Anchored Mode legacy behavior)
        if (leftBookmarksView.visibility == View.VISIBLE && !isAnchored) {
            // DebugLog.d("DualWebViewGroup", "Delegating fling to bookmarks: velocity=$velocityX")

            // Determine direction based on velocity and delegate to both views
            val isForward = velocityX > 0

            // Update both left and right bookmark views to maintain synchronization
            leftBookmarksView.handleFling(isForward)

            // Force layout update to ensure visual sync between views
            post {
                requestLayout()
                invalidate()
            }
            return
        }

        // If bookmarks aren't visible, handle normal scrolling behavior
        // Slow down the velocity for smoother scrolling
        val slowedVelocity = velocityX * 0.15f

        // Handle vertical scrolling
        webView.evaluateJavascript(
                """
            (function() {
                window.scrollBy({
                    top: ${(-slowedVelocity).toInt()},
                    behavior: 'smooth'
                });
            })();
        """,
                null
        )

        // Provide a native scroll backup only if JS execution fails or is slow?
        // Actually, since we want to avoid double-scroll bouncing, relying on JS scrollBy is safer
        // with 'smooth' behavior.
        // However, if we remove this, we rely solely on JS.
        // Let's remove the unconditional native backup to prevent fighting/overshoot.
    }

    private fun initializeToggleButtons() {
        DebugLog.d(
                "ViewDebug",
                """
    Toggle bar parent: ${leftToggleBar.parent?.javaClass?.simpleName}
    Toggle bar children: ${(leftToggleBar as? ViewGroup)?.childCount ?: "Not a ViewGroup"}
    UI Container children count: ${leftEyeUIContainer.childCount}
    UI Container children:
    ${(0 until leftEyeUIContainer.childCount).joinToString("n") { index ->
            val child = leftEyeUIContainer.getChildAt(index)
            "Child $index: ${child.javaClass.simpleName} (${child.hashCode()})"+
                    "n    Location: (${child.x}, ${child.y})"+
                    "n    Size: ${child.width}x${child.height}"+
                    "n    Translation: (${child.translationX}, ${child.translationY})"
        }}
""".trimIndent()
        )

        // Get references to all buttons
        val leftModeToggleButton = leftToggleBar.findViewById<FontIconView>(R.id.btnModeToggle)
        val leftDashboardButton = leftToggleBar.findViewById<FontIconView>(R.id.btnYouTube)
        val leftBookmarksButton = leftToggleBar.findViewById<FontIconView>(R.id.btnBookmarks)
        val leftZoomInButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomIn)
        val leftZoomOutButton = leftToggleBar.findViewById<FontIconView>(R.id.btnZoomOut)
        val leftMaskButton = leftToggleBar.findViewById<FontIconView>(R.id.btnMask)
        val leftTtsButton = leftToggleBar.findViewById<FontIconView>(R.id.btnTts)

        // Create Windows button programmatically since it's not in XML
        val leftWindowsButton =
                FontIconView(context).apply {
                    id = View.generateViewId() // Generate an ID for the view
                    tag = "btnWindows" // Tag for identification
                    configureToggleButton(R.string.fa_window_restore)
                }
        windowsButton = leftWindowsButton // Store reference for hover/click handling

        // Insert it into toggle bar - we need to add it to the layout
        if (leftToggleBar is ViewGroup) {
            // Find where to insert - maybe after dashboard button?
            // Actually, toggle_bar.xml is a LinearLayout (based on usage).
            // We can just addView. But we need to insert it in the correct order visually.
            // XML has: Mode, YouTube, Bookmarks, ZoomOut, ZoomIn, Mask, Anchor.
            // Let's put Windows button after Bookmarks.
            val index = (leftToggleBar as ViewGroup).indexOfChild(leftBookmarksButton) + 1
            if (index > 0) {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton, index)
            } else {
                (leftToggleBar as ViewGroup).addView(leftWindowsButton)
            }
        }

        // Calculate positioning constants
        val iconPadding = 4.dp()
        val orderedButtons =
                listOf(
                        leftModeToggleButton,
                        leftDashboardButton,
                        leftBookmarksButton,
                        leftWindowsButton,
                        leftZoomOutButton,
                        leftZoomInButton,
                        leftMaskButton,
                        leftTtsButton
                )

        orderedButtons.forEach { button ->
            try {
                button?.apply {
                    layoutParams =
                            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
                    visibility = View.VISIBLE
                    background =
                            ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                    // Icon already set via XML text attribute
                    if (id == R.id.btnTts) {
                        text = context.getString(R.string.fa_volume_high)
                    }
                    setPadding(iconPadding, iconPadding, iconPadding, iconPadding)
                    elevation = 4f
                    alpha = 1f
                    isEnabled = true
                    setOnTouchListener { v, _ ->
                        val location = IntArray(2)
                        v.getLocationOnScreen(location)
                        val parentLocation = IntArray(2)
                        leftToggleBar.getLocationOnScreen(parentLocation)

                        false
                    }
                }
            } catch (e: Exception) {
                Log.e("ToggleButton", "Error configuring button", e)
            }
        }

        mapOf(
                        leftModeToggleButton to R.id.btnModeToggle,
                        leftDashboardButton to R.id.btnYouTube,
                        leftBookmarksButton to R.id.btnBookmarks,
                        leftZoomOutButton to R.id.btnZoomOut,
                        leftZoomInButton to R.id.btnZoomIn,
                        leftMaskButton to R.id.btnMask,
                        leftTtsButton to R.id.btnTts
                )
                .forEach { (button, id) -> button?.setOnClickListener { handleLeftMenuAction(id) } }

        leftWindowsButton.setOnClickListener {
            showButtonClickFeedback(leftWindowsButton)
            toggleWindowMode()
        }
    }

    fun isSettingsVisible(): Boolean {
        return isSettingsVisible
    }

    fun hideSettings() {
        if (isSettingsVisible) {
            isSettingsVisible = false
            settingsMenu?.visibility = View.GONE
            settingsScrim?.visibility = View.GONE
        }
    }

    // Reset all overlay UI state - call on app startup
    fun resetUiState() {
        isSettingsVisible = false
        settingsMenu?.visibility = View.GONE
        settingsScrim?.visibility = View.GONE
        if (::leftBookmarksView.isInitialized) {
            leftBookmarksView.visibility = View.GONE
        }
    }

    fun showSettings() {
        // DebugLog.d("SettingsDebug", "showSettings() called, isSettingsVisible:
        // $isSettingsVisible")

        if (settingsMenu == null) {
            settingsMenu =
                    LayoutInflater.from(context)
                            .inflate(R.layout.settings_layout, null, false)
                            .apply {
                                isClickable = false
                                isFocusable = false
                                elevation = 1001f // Even higher elevation than scrim
                            }

            // Add click handler for close button
            settingsMenu?.findViewById<View>(R.id.btnCloseSettings)?.setOnClickListener {
                // DebugLog.d("SettingsDebug", "Close button clicked")
                isSettingsVisible = false
                settingsMenu?.visibility = View.GONE
                settingsScrim?.visibility = View.GONE
                startRefreshing()
            }

            // Add click handler for help button
            settingsMenu?.findViewById<ImageButton>(R.id.btnHelp)?.setOnClickListener {
                // DebugLog.d("SettingsDebug", "Help button clicked")
                showHelpDialog()
            }

            val layoutParams =
                    FrameLayout.LayoutParams(
                                    FrameLayout.LayoutParams.WRAP_CONTENT,
                                    FrameLayout.LayoutParams.WRAP_CONTENT
                            )
                            .apply { gravity = Gravity.CENTER }

            leftEyeUIContainer.addView(settingsMenu, layoutParams)
            settingsMenu?.elevation = 1001f

            // DebugLog.d("SettingsDebug", "Menu added with height:
            // ${settingsMenu?.measuredHeight}")
        }

        // Only initialize seekbars when we are about to SHOW settings (not when closing)
        if (!isSettingsVisible) {
            settingsMenu?.let { menu ->
                // Initialize volume seekbar
                val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
                val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
                volumeSeekBar?.max = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                volumeSeekBar?.progress = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)

                // Initialize brightness seekbar
                val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
                brightnessSeekBar?.max = 100
                val currentBrightness =
                        (context as? Activity)?.window?.attributes?.screenBrightness ?: 0.5f
                brightnessSeekBar?.progress = (currentBrightness * 100).toInt()
                val forceDarkButton = menu.findViewById<Button>(R.id.btnToggleForceDark)
                val forceDarkEnabled =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getBoolean("forceDarkWebEnabled", true)
                forceDarkButton?.text =
                        if (forceDarkEnabled) "Force Dark: On" else "Force Dark: Off"

                // Initialize smoothness seekbar from saved preference
                val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
                val savedSmoothness =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("anchorSmoothness", 40)
                smoothnessSeekBar?.progress = savedSmoothness

                // Initialize screen size seekbar (just update the UI, don't apply scale)
                val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
                val savedScaleProgress =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("uiScaleProgress", 100)
                screenSizeSeekBar?.progress = savedScaleProgress

                // Calculate scale for position slider visibility check only
                val currentScale = 0.25f + (savedScaleProgress / 100f) * 0.75f

                // Initialize position sliders
                val showPosSliders = !isAnchored && currentScale < 0.99f
                val visibility = if (showPosSliders) View.VISIBLE else View.GONE

                menu.findViewById<View>(R.id.settingsPositionLayout)?.visibility = visibility

                menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransXProgress", 50)
                }

                menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)?.apply {
                    progress =
                            context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                    .getInt("uiTransYProgress", 50)
                }

                // Initialize font size seekbar (50% = 50, 100% = 100, 200% = 200, slider is 0-150
                // mapping to 50-200%)
                val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
                val savedFontSize =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("webFontSize", 50) // Default 50 = 100%
                fontSizeSeekBar?.progress = savedFontSize

                // Initialize color buttons with visual background indicators
                // Initialize color wheel with saved color
                menu.findViewById<ColorWheelView>(R.id.colorWheelView)?.apply {
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    try {
                        setColor(Color.parseColor(savedTextColor))
                    } catch (e: Exception) {
                        setColor(Color.WHITE)
                    }
                }

                // Apply saved font settings
                val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                val savedTextColor = getEffectiveWebTextColor(prefs)
                applyWebFontSettings(savedFontSize, savedTextColor)

                // Initialize cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                // Default 50 corresponds to 50%
                val savedSensitivity =
                        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                                .getInt("cursorSensitivity", 50)
                sensitivitySeekBar?.progress = savedSensitivity
            }
        }

        // Toggle visibility state
        isSettingsVisible = !isSettingsVisible

        settingsMenu?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE
        settingsScrim?.visibility = if (isSettingsVisible) View.VISIBLE else View.GONE

        if (isSettingsVisible) {
            settingsScrim?.bringToFront()
            settingsMenu?.bringToFront()

            // Keep the force immediate layout code
            settingsMenu?.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
            )
            settingsMenu?.layout(
                    settingsMenu?.left ?: 0,
                    settingsMenu?.top ?: 0,
                    (settingsMenu?.left ?: 0) + (settingsMenu?.measuredWidth ?: 0),
                    (settingsMenu?.top ?: 0) + (settingsMenu?.measuredHeight ?: 0)
            )
        }

        startRefreshing()
        post {
            requestLayout()
            invalidate()
        }
    }

    fun getSettingsMenuLocation(location: IntArray) {
        settingsMenu?.getLocationOnScreen(location)
    }

    fun getSettingsMenuSize(): Pair<Int, Int> {
        return Pair(settingsMenu?.width ?: 0, settingsMenu?.height ?: 0)
    }

    fun dispatchSettingsTouchEvent(x: Float, y: Float) {
        settingsMenu?.let { menu ->
            // Get locations of all interactive elements
            val volumeSeekBar = menu.findViewById<SeekBar>(R.id.volumeSeekBar)
            val brightnessSeekBar = menu.findViewById<SeekBar>(R.id.brightnessSeekBar)
            val forceDarkButton = menu.findViewById<Button>(R.id.btnToggleForceDark)
            val smoothnessSeekBar = menu.findViewById<SeekBar>(R.id.smoothnessSeekBar)
            val screenSizeSeekBar = menu.findViewById<SeekBar>(R.id.screenSizeSeekBar)
            val horizontalPosSeekBar = menu.findViewById<SeekBar>(R.id.horizontalPosSeekBar)
            val verticalPosSeekBar = menu.findViewById<SeekBar>(R.id.verticalPosSeekBar)
            val closeButton = menu.findViewById<View>(R.id.btnCloseSettings)
            val helpButton = menu.findViewById<ImageButton>(R.id.btnHelp)
            val resetButton = menu.findViewById<Button>(R.id.btnResetPosition)
            val resetScreenSizeButton = menu.findViewById<Button>(R.id.btnResetScreenSize)
            val fontSizeSeekBar = menu.findViewById<SeekBar>(R.id.fontSizeSeekBar)
            val colorWheelView = menu.findViewById<ColorWheelView>(R.id.colorWheelView)
            val resetTextColorButton = menu.findViewById<Button>(R.id.btnResetTextColor)
            val groqKeyButton = menu.findViewById<Button>(R.id.btnGroqApiKey)

            fun getRect(view: View?): Rect? {
                if (view == null || view.visibility != View.VISIBLE) return null
                val rect = Rect()
                return if (view.getGlobalVisibleRect(rect)) rect else null
            }

            fun contains(rect: Rect?, slopPx: Int): Boolean {
                if (rect == null) return false
                return x >= rect.left - slopPx &&
                        x <= rect.right + slopPx &&
                        y >= rect.top - slopPx &&
                        y <= rect.bottom + slopPx
            }

            val menuSlop = (2f * uiScale).roundToInt()
            val sliderSlop = (1f * uiScale).roundToInt()
            val buttonSlop = (3f * uiScale).roundToInt()

            if (contains(getRect(menu), menuSlop)) {
                // Check if click is on volume seekbar
                val volumeRect = getRect(volumeSeekBar)
                if (volumeSeekBar != null && contains(volumeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - volumeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, volumeSeekBar.width.toFloat()) /
                                    volumeSeekBar.width
                    val newProgress = (percentage * volumeSeekBar.max).toInt()

                    // Update volume
                    volumeSeekBar.progress = newProgress
                    (context.getSystemService(Context.AUDIO_SERVICE) as AudioManager).apply {
                        setStreamVolume(
                                AudioManager.STREAM_MUSIC,
                                newProgress,
                                AudioManager.FLAG_SHOW_UI
                        )
                    }

                    // **Play system sound for feedback**
                    playSystemSound(context)

                    // Visual feedback
                    volumeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ volumeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on brightness seekbar
                val brightnessRect = getRect(brightnessSeekBar)
                if (brightnessSeekBar != null && contains(brightnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - brightnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, brightnessSeekBar.width.toFloat()) /
                                    brightnessSeekBar.width
                    val newProgress = (percentage * brightnessSeekBar.max).toInt()

                    // Update brightness
                    brightnessSeekBar.progress = newProgress
                    (context as? Activity)?.window?.attributes =
                            (context as Activity).window.attributes.apply {
                                screenBrightness = newProgress / 100f
                            }

                    // Visual feedback
                    brightnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ brightnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on force dark toggle button
                val forceDarkRect = getRect(forceDarkButton)
                if (forceDarkButton != null && contains(forceDarkRect, buttonSlop)) {
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val currentlyEnabled = prefs.getBoolean("forceDarkWebEnabled", true)
                    val newEnabled = !currentlyEnabled
                    (context as? MainActivity)?.setForceDarkWebEnabled(newEnabled)
                    forceDarkButton.text = if (newEnabled) "Force Dark: On" else "Force Dark: Off"

                    forceDarkButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ forceDarkButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on smoothness seekbar
                val smoothnessRect = getRect(smoothnessSeekBar)
                if (smoothnessSeekBar != null && contains(smoothnessRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - smoothnessRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, smoothnessSeekBar.width.toFloat()) /
                                    smoothnessSeekBar.width
                    val newProgress = (percentage * smoothnessSeekBar.max).toInt()

                    // Update smoothness
                    smoothnessSeekBar.progress = newProgress

                    // Save preference and notify MainActivity
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("anchorSmoothness", newProgress)
                            .apply()

                    // Call MainActivity to update smoothness
                    (context as? MainActivity)?.updateAnchorSmoothness(newProgress)

                    // Visual feedback
                    smoothnessSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ smoothnessSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on cursor sensitivity seekbar
                val sensitivitySeekBar = menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                val sensitivityRect = getRect(sensitivitySeekBar)

                if (sensitivitySeekBar != null && contains(sensitivityRect, sliderSlop)) {
                    // Calculate relative position on seekbar
                    val relativeX = (x - sensitivityRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, sensitivitySeekBar.width.toFloat()) /
                                    sensitivitySeekBar.width
                    val newProgress = (percentage * sensitivitySeekBar.max).toInt()

                    // Update sensitivity and save preference
                    sensitivitySeekBar.progress = newProgress
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", newProgress)
                            .apply()

                    // Notify MainActivity
                    (context as? MainActivity)?.updateCursorSensitivity(newProgress)

                    // Visual feedback
                    sensitivitySeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ sensitivitySeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset sensitivity button
                val resetSensitivityButton =
                        menu.findViewById<Button>(R.id.btnResetCursorSensitivity)
                val resetSensitivityRect = getRect(resetSensitivityButton)

                if (resetSensitivityButton != null && contains(resetSensitivityRect, buttonSlop)) {
                    // Reset to 50%
                    // val sensitivitySeekBar =
                    //        menu.findViewById<SeekBar>(R.id.cursorSensitivitySeekBar)
                    sensitivitySeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("cursorSensitivity", 50)
                            .apply()

                    (context as? MainActivity)?.updateCursorSensitivity(50)

                    // Visual feedback
                    resetSensitivityButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetSensitivityButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on screen size seekbar
                val screenSizeRect = getRect(screenSizeSeekBar)
                if (screenSizeSeekBar != null && contains(screenSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - screenSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, screenSizeSeekBar.width.toFloat()) /
                                    screenSizeSeekBar.width
                    var newProgress = (percentage * screenSizeSeekBar.max).toInt()

                    // Snap to 100% when close (>= 95%)
                    if (newProgress >= 95) {
                        newProgress = 100
                    }

                    // Update screen size
                    screenSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", newProgress)
                            .apply()

                    // Apply scale: 35% (0.35) to 100% (1.0)
                    val scale = 0.35f + (newProgress / 100f) * 0.65f
                    updateUiScale(scale)

                    // Update visibility of position sliders
                    val showPosSliders = !isAnchored && scale < 0.99f
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    val newVisibility = if (showPosSliders) View.VISIBLE else View.GONE

                    if (posLayout?.visibility != newVisibility) {
                        posLayout?.visibility = newVisibility

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Recalculate translation based on new scale
                    updateUiTranslation()

                    // Visual feedback
                    screenSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ screenSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on Groq API Key button
                val groqKeyRect = getRect(groqKeyButton)
                if (groqKeyButton != null && contains(groqKeyRect, buttonSlop)) {

                    // Visual feedback
                    groqKeyButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        groqKeyButton.isPressed = false
                                        // Show dialog
                                        (context as? MainActivity)?.showGroqKeyDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on reset screen size button
                val resetScreenSizeRect = getRect(resetScreenSizeButton)
                if (resetScreenSizeButton != null && contains(resetScreenSizeRect, buttonSlop)) {

                    // Reset screen size to 100%
                    screenSizeSeekBar?.progress = 100

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiScaleProgress", 100)
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    // Apply full scale
                    updateUiScale(1.0f)

                    // Hide position sliders and remeasure
                    val posLayout = menu.findViewById<View>(R.id.settingsPositionLayout)
                    if (posLayout?.visibility != View.GONE) {
                        posLayout?.visibility = View.GONE

                        // Force complete remeasure with UNSPECIFIED to allow width changes
                        menu.measure(
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                                MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                        )
                        menu.layout(
                                menu.left,
                                menu.top,
                                menu.left + menu.measuredWidth,
                                menu.top + menu.measuredHeight
                        )

                        // Invalidate to redraw
                        menu.invalidate()

                        // Also request layout on parent to ensure proper positioning
                        (menu.parent as? View)?.requestLayout()
                    }

                    // Reset position to center
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50
                    updateUiTranslation()

                    // Visual feedback
                    resetScreenSizeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetScreenSizeButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on horizontal pos seekbar
                val horizontalPosRect = getRect(horizontalPosSeekBar)
                if (horizontalPosSeekBar != null &&
                                horizontalPosSeekBar.visibility == View.VISIBLE &&
                                contains(horizontalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - horizontalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, horizontalPosSeekBar.width.toFloat()) /
                                    horizontalPosSeekBar.width
                    val newProgress = (percentage * horizontalPosSeekBar.max).toInt()

                    horizontalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    horizontalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ horizontalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on vertical pos seekbar
                val verticalPosRect = getRect(verticalPosSeekBar)
                if (verticalPosSeekBar != null &&
                                verticalPosSeekBar.visibility == View.VISIBLE &&
                                contains(verticalPosRect, sliderSlop)
                ) {

                    val relativeX = (x - verticalPosRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, verticalPosSeekBar.width.toFloat()) /
                                    verticalPosSeekBar.width
                    val newProgress = (percentage * verticalPosSeekBar.max).toInt()

                    verticalPosSeekBar.progress = newProgress

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransYProgress", newProgress)
                            .apply()

                    updateUiTranslation()

                    verticalPosSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ verticalPosSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset button
                val resetRect = getRect(resetButton)
                if (resetButton != null &&
                                resetButton.visibility == View.VISIBLE &&
                                contains(resetRect, buttonSlop)
                ) {

                    // Reset position progress to 50 (center)
                    horizontalPosSeekBar?.progress = 50
                    verticalPosSeekBar?.progress = 50

                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("uiTransXProgress", 50)
                            .putInt("uiTransYProgress", 50)
                            .apply()

                    updateUiTranslation()

                    // Visual feedback
                    resetButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on help button
                val helpRect = getRect(helpButton)
                if (helpButton != null && contains(helpRect, buttonSlop)) {

                    // Visual feedback
                    helpButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        helpButton.isPressed = false
                                        // Show help dialog
                                        showHelpDialog()
                                    },
                                    100
                            )
                    return
                }

                // Check if click is on Reset Zoom button
                val resetZoomButton = menu.findViewById<Button>(R.id.btnResetFontSize)
                val resetZoomRect = getRect(resetZoomButton)

                if (resetZoomButton != null && contains(resetZoomRect, buttonSlop)) {

                    // Reset font size to 100% (progress 50)
                    fontSizeSeekBar?.progress = 50

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", 50)
                            .apply()

                    // Apply to WebView
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    applyWebFontSettings(50, savedTextColor)

                    // Visual feedback
                    resetZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on Reset Webpage Zoom button
                val resetWebpageZoomButton = menu.findViewById<Button>(R.id.btnResetWebpageZoom)
                val resetWebpageZoomRect = getRect(resetWebpageZoomButton)

                if (resetWebpageZoomButton != null && contains(resetWebpageZoomRect, buttonSlop)) {

                    // Reset webpage zoom to 1.0
                    currentWebZoom = 1.0f

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putFloat("webZoomLevel", currentWebZoom)
                            .apply()

                    webView.evaluateJavascript(
                            """
                        (function() {
                            document.body.style.zoom = "$currentWebZoom";
                        })();
                    """,
                            null
                    )

                    postDelayed(
                            {
                                updateScrollBarsVisibility()
                                lastScrollBarCheckTime = System.currentTimeMillis()
                            },
                            100
                    )

                    // Visual feedback
                    resetWebpageZoomButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetWebpageZoomButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on font size seekbar
                val fontSizeRect = getRect(fontSizeSeekBar)
                if (fontSizeSeekBar != null && contains(fontSizeRect, sliderSlop)) {

                    // Calculate relative position on seekbar
                    val relativeX = (x - fontSizeRect!!.left) / uiScale
                    val percentage =
                            relativeX.coerceIn(0f, fontSizeSeekBar.width.toFloat()) /
                                    fontSizeSeekBar.width
                    val newProgress = (percentage * fontSizeSeekBar.max).toInt()

                    // Update font size
                    fontSizeSeekBar.progress = newProgress

                    // Save preference
                    context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                            .edit()
                            .putInt("webFontSize", newProgress)
                            .apply()

                    // Apply to WebView
                    val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                    val savedTextColor = getEffectiveWebTextColor(prefs)
                    applyWebFontSettings(newProgress, savedTextColor)

                    // Visual feedback
                    fontSizeSeekBar.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ fontSizeSeekBar.isPressed = false }, 100)
                    return
                }

                // Check if click is on color wheel
                val colorWheelRect = getRect(colorWheelView)
                if (colorWheelView != null && contains(colorWheelRect, sliderSlop)) {

                    // Calculate relative position
                    val relativeX = (x - colorWheelRect!!.left) / uiScale
                    val relativeY = (y - colorWheelRect.top) / uiScale

                    val selectedColor =
                            colorWheelView.calculateColorFromCoordinates(relativeX, relativeY)

                    // Update visual indicator
                    colorWheelView.setColor(selectedColor)

                    // Apply color
                    val hexColor = String.format("#%06X", (0xFFFFFF and selectedColor))
                    applyTextColor(hexColor)

                    // Visual feedback
                    colorWheelView.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ colorWheelView.isPressed = false }, 100)
                    return
                }

                // Check if click is on reset text color button
                val resetTextColorRect = getRect(resetTextColorButton)
                if (resetTextColorButton != null && contains(resetTextColorRect, buttonSlop)) {

                    // Reset color to white
                    colorWheelView?.setColor(Color.WHITE)
                    // Reset color to white visually
                    colorWheelView?.setColor(Color.WHITE)
                    applyTextColor(null)

                    // Visual feedback
                    resetTextColorButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed({ resetTextColorButton.isPressed = false }, 100)
                    return
                }

                // Check if click is on close button
                val closeRect = getRect(closeButton)
                if (closeButton != null && contains(closeRect, buttonSlop)) {

                    // Visual feedback
                    closeButton.isPressed = true
                    Handler(Looper.getMainLooper())
                            .postDelayed(
                                    {
                                        closeButton.isPressed = false
                                        // Close settings
                                        isSettingsVisible = false
                                        settingsMenu?.visibility = View.GONE
                                        settingsScrim?.visibility = View.GONE
                                        startRefreshing()
                                    },
                                    100
                            )
                    return
                }
            } else {
                return
            }
        }
    }

    fun playSystemSound(context: Context) {
        val audioManager = context.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        audioManager.playSoundEffect(AudioManager.FX_KEY_CLICK) // Play a standard click sound
    }

    /**
     * Apply font size and text color settings to the WebView via JavaScript injection.
     * @param fontSizeProgress Slider progress (0-150) which maps to 50%-200% font size
     * @param textColor Optional hex color string (e.g., "#FFFFFF")
     */
    private fun applyWebFontSettings(fontSizeProgress: Int, textColor: String?) {
        // Map progress 0-150 to font size 50%-200%
        val fontSizePercent = 50 + fontSizeProgress

        val colorCss =
                if (textColor != null) {
                    "body, body *, p, span, div, h1, h2, h3, h4, h5, h6, a, li, td, th { color: $textColor !important; }"
                } else {
                    ""
                }

        val js =
                """
            (function() {
                var styleId = 'taplink-font-settings';
                var existingStyle = document.getElementById(styleId);
                if (existingStyle) {
                    existingStyle.remove();
                }
                var style = document.createElement('style');
                style.id = styleId;
                style.textContent = 'html { font-size: ${fontSizePercent}% !important; } $colorCss';
                document.head.appendChild(style);
            })();
        """.trimIndent()

        webView.evaluateJavascript(js, null)

        // Update system info bar color
        if (textColor != null) {
            try {
                leftSystemInfoView.setTextColor(Color.parseColor(textColor))
            } catch (e: Exception) {
                Log.e("DualWebViewGroup", "Error updating system info color", e)
            }
        }
    }

    fun getAllWebViews(): List<WebView> {
        return windows.map { it.webView }
    }

    private fun getEffectiveWebTextColor(prefs: android.content.SharedPreferences): String? {
        val overrideEnabled = prefs.getBoolean("webTextColorOverrideEnabled", false)
        return if (overrideEnabled) prefs.getString("webTextColor", null) else null
    }

    /**
     * Apply text color to webpage AND custom UI, then save preference.
     * @param colorHex Hex color string (e.g., "#FFFFFF")
     */
    private fun applyTextColor(colorHex: String?) {
        // Save preference
        context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                .edit()
                .putString("webTextColor", colorHex)
                .putBoolean("webTextColorOverrideEnabled", colorHex != null)
                .apply()

        // Update Custom UI (Settings & Keyboard)
        updateCustomUiColor(colorHex)

        // Get current font size and apply both settings to WebView
        val fontSizeProgress =
                context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
                        .getInt("webFontSize", 50)
        applyWebFontSettings(fontSizeProgress, colorHex)
    }

    /** Update the color of Custom UI elements (Settings Menu and Keyboard) */
    private fun updateCustomUiColor(colorHex: String?) {
        val color =
                if (colorHex != null) {
                    try {
                        Color.parseColor(colorHex)
                    } catch (e: Exception) {
                        Color.WHITE
                    }
                } else {
                    Color.WHITE
                }

        // 1. Update Keyboard
        customKeyboard?.setCustomTextColor(color)

        // 2. Update Settings Menu (Recursively find TextViews/Buttons)
        settingsMenu?.let { menu -> updateViewColorsRecursively(menu, color) }

        // 3. Update Navigation Bar
        if (::leftNavigationBar.isInitialized) {
            updateViewColorsRecursively(leftNavigationBar, color)
        }

        // 4. Update Toggle Bar
        if (::leftToggleBar.isInitialized) {
            updateViewColorsRecursively(leftToggleBar, color)
        }
    }

    private fun updateViewColorsRecursively(view: View, color: Int) {
        if (view is ViewGroup) {
            for (i in 0 until view.childCount) {
                updateViewColorsRecursively(view.getChildAt(i), color)
            }
        } else if (view is TextView) {
            // Apply to TextViews and Buttons (Button is subclass of TextView)
            // But verify it's not one of our special icon views if they shouldn't change
            // (FontIconView IS a TextView, so it will get colored too, which is likely desired)
            view.setTextColor(color)
        }
    }

    /** Re-apply saved font settings to the WebView. Called when a new page loads. */
    fun reapplyWebFontSettings() {
        val prefs = context.getSharedPreferences("TapLinkPrefs", Context.MODE_PRIVATE)
        val fontSizeProgress = prefs.getInt("webFontSize", 50)
        val textColor = getEffectiveWebTextColor(prefs)

        applyWebFontSettings(fontSizeProgress, textColor)

        // Also ensure UI is synced (though this is mostly for initial load)
        updateCustomUiColor(textColor)
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        sharedConfigPrefs.registerOnSharedPreferenceChangeListener(sharedConfigListener)
        sharedConfigListenerRegistered = true
        updateSystemInfoBarVisibility()
        startRefreshing()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        if (sharedConfigListenerRegistered) {
            sharedConfigPrefs.unregisterOnSharedPreferenceChangeListener(sharedConfigListener)
            sharedConfigListenerRegistered = false
        }
        stopRefreshing()
        releaseAudioCapture()
    }

    override fun onWindowVisibilityChanged(visibility: Int) {
        super.onWindowVisibilityChanged(visibility)
        if (visibility == View.VISIBLE) {
            startRefreshing()
        } else {
            stopRefreshing()
        }
    }

    fun isInScrollMode(): Boolean {
        return isInScrollMode
    }

    fun setScrollMode(enabled: Boolean) {
        DebugLog.d(
                "NavBarDebug",
                "setScrollMode: enabled=$enabled, current=$isInScrollMode, navHidden=$isNavBarsHidden"
        )

        if (isInScrollMode == enabled) return
        isInScrollMode = enabled

        if (enabled) {

            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            updateSystemInfoBarVisibility()

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Show force-show button
            btnShowNavBars.visibility = View.VISIBLE
            btnShowNavBars.bringToFront()
            btnShowNavBars.alpha = 0f
            btnShowNavBars.animate().alpha(1.0f).setDuration(200).start()
            btnShowNavBars.requestLayout()
        } else {
            // Only restore UI if the other mode (isNavBarsHidden) is NOT active
            if (!isNavBarsHidden) {
                // Re-enable touch interception and show system info bar
                leftToggleBar.isClickable = true
                leftNavigationBar.isClickable = true
                updateSystemInfoBarVisibility()

                // Then show menus with animation
                leftToggleBar.visibility = View.VISIBLE
                leftToggleBar.alpha = 0f
                leftToggleBar.animate().alpha(1f).setDuration(200).start()

                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.alpha = 0f
                leftNavigationBar.animate().alpha(1f).setDuration(200).start()

                // Hide force-show button
                btnShowNavBars
                        .animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { btnShowNavBars.visibility = View.GONE }
                        .start()
            }
        }

        // Update scrollbars and layout
        updateScrollBarsVisibility()

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    /**
     * Hides or shows the navigation bars without affecting scroll mode. When hidden, cursor remains
     * visible and movable (unlike scroll mode).
     */
    fun setNavBarsHidden(hidden: Boolean) {
        if (isNavBarsHidden == hidden) return
        isNavBarsHidden = hidden

        if (hidden) {
            // Immediately disable touch interception before animating
            leftToggleBar.isClickable = false
            leftNavigationBar.isClickable = false
            updateSystemInfoBarVisibility()

            // Then animate menus away
            leftToggleBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftToggleBar.visibility = View.GONE }
                    .start()

            leftNavigationBar
                    .animate()
                    .alpha(0f)
                    .setDuration(200)
                    .withEndAction { leftNavigationBar.visibility = View.GONE }
                    .start()

            // Show force-show button
            btnShowNavBars.visibility = View.VISIBLE
            btnShowNavBars.bringToFront()
            btnShowNavBars.alpha = 0f
            btnShowNavBars.animate().alpha(1.0f).setDuration(200).start()
            btnShowNavBars.requestLayout()
        } else {
            // Only restore UI if the other mode (isInScrollMode) is NOT active
            if (!isInScrollMode) {
                // Re-enable touch interception and show system info bar
                leftToggleBar.isClickable = true
                leftNavigationBar.isClickable = true
                updateSystemInfoBarVisibility()

                // Then show menus with animation
                leftToggleBar.visibility = View.VISIBLE
                leftToggleBar.alpha = 0f
                leftToggleBar.animate().alpha(1f).setDuration(200).start()

                leftNavigationBar.visibility = View.VISIBLE
                leftNavigationBar.alpha = 0f
                leftNavigationBar.animate().alpha(1f).setDuration(200).start()

                // Hide force-show button
                btnShowNavBars
                        .animate()
                        .alpha(0f)
                        .setDuration(200)
                        .withEndAction { btnShowNavBars.visibility = View.GONE }
                        .start()
            }
        }

        // Update scrollbars and layout
        updateScrollBarsVisibility()

        // Force layout update
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun isNavBarsHidden(): Boolean {
        return isNavBarsHidden
    }

    // Custom Dialog Logic
    fun showAlertDialog(message: String, onConfirm: () -> Unit) {
        showDialog("Alert", message, false, null, { _ -> onConfirm() }, null)
    }

    fun showConfirmDialog(message: String, onConfirm: () -> Unit, onCancel: () -> Unit) {
        showDialog("Confirm", message, false, null, { _ -> onConfirm() }, onCancel)
    }

    fun showPromptDialog(
            message: String,
            defaultValue: String?,
            onConfirm: (String) -> Unit,
            onCancel: () -> Unit
    ) {
        showDialog(
                "Prompt",
                message,
                true,
                defaultValue,
                { text -> onConfirm(text ?: "") },
                onCancel
        )
    }

    fun showHelpDialog(page: Int = 1) {
        val (title, message, hasNext, hasPrev) =
                when (page) {
                    1 ->
                            Quadruple(
                                    "Features: Touch & Menu",
                                    """
                TOUCH GESTURES:
                • Single Tap: Click links, buttons, and focus fields.
                • Double Tap: Go back to the previous page.
                
                TRIPLE TAP (Anchored Mode):
                • Re-centers the screen.
                """.trimIndent(),
                                    true,
                                    false
                            )
                    2 ->
                            Quadruple(
                                    "Features: Screen Modes",
                                    """
                ANCHORED MODE (Anchor Icon):
                • Screen stays fixed in space relative to the world.
                • Smoothness: Controls how rigidly the screen follows tracking.
                
                NON-ANCHORED MODE (Crossed Anchor):
                • Screen is "locked" to your head movement.
                • Screen Position: Shift the display H/V when UI Scale < 100%.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    3 ->
                            Quadruple(
                                    "Features: Display & Tools",
                                    """
                SCROLL MODE (Full Screen Icon):
                • Hides UI for an immersive browsing experience.
                • Restore UI: Tap the transparent "Show" button.
                
                UTILITIES:
                • Volume & Brightness Sliders.
                • Force Dark: Toggle dark rendering for supported webpages.
                • UI Scale: Adjust the global interface size.
                • Web Zoom (+/-): Content zoom level.
                • QR Scanner: Open Dashboard (glasses icon) and tap QR Scanner.

                VOICE / STT:
                • Uses device speech-to-text when supported.
                • Start STT, speak clearly, and text is inserted into the active field.
                • Use scrcpy keyboard to paste your API key into the prompt field.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    4 ->
                            Quadruple(
                                    "Features: Blank Screen Mode",
                                    """
                BLANK SCREEN MODE (Eye Toggle):
                • Blacks out display while media continues playing but allows media controls.
                • Perfect for listening to audio/podcasts.
                • Note: Disables anchored mode while active.
                
                MEDIA CONTROLS (shown when media is playing):
                • Play/Pause: Toggle media playback.
                • Skip Back/Forward: Jump 10 seconds.
                • Unmask (Eye Icon): Exit blank screen mode.
                """.trimIndent(),
                                    true,
                                    true
                            )
                    5 ->
                            Quadruple(
                                    "TapLink AI",
                                    """
                TAPLINK AI (Chat Icon):
                • Open/close with the Chat button on the bottom bar.
                • Requires a Groq API Key (Settings -> Enter Groq API Key).
                • Ask questions or use Summarize to recap the current webpage.
                • Summarize works only when a normal webpage is open.
                • Speak replies: Toggle in chat to read assistant responses aloud.
                """.trimIndent(),
                                    false,
                                    true
                            )
                    else -> return
                }

        val footerButtons = mutableListOf<View>()

        if (hasPrev) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Back"
                        textSize = 14f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page - 1) }
                    }
            )
        }

        if (hasNext) {
            footerButtons.add(
                    Button(context).apply {
                        text = "Next"
                        textSize = 14f
                        setTextColor(Color.parseColor("#4488FF"))
                        setBackgroundColor(Color.TRANSPARENT)
                        setOnClickListener { showHelpDialog(page + 1) }
                    }
            )
        }

        showDialog(
                title = title,
                message = message,
                hasInput = false,
                confirmLabel = "Close",
                dismissOnAnyClick = true,
                additionalButtons = footerButtons
        )
    }

    private data class Quadruple<A, B, C, D>(
            val first: A,
            val second: B,
            val third: C,
            val fourth: D
    )

    private fun showDialog(
            title: String,
            message: String,
            hasInput: Boolean,
            defaultValue: String? = null,
            onConfirm: ((String?) -> Unit)? = null,
            onCancel: (() -> Unit)? = null,
            confirmLabel: String? = "OK",
            dismissOnAnyClick: Boolean = false,
            additionalButtons: List<View> = emptyList()
    ) {
        dialogContainer.removeAllViews()

        // Hide keyboard container initially to avoid overlapping, though we might show it again if
        // input is focused
        keyboardContainer.visibility = View.GONE

        val padding = 16.dp()
        val dialogView =
                LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            500, // Fixed width for consistent look
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                    setPadding(padding, padding, padding, padding)
                    background =
                            GradientDrawable().apply {
                                setColor(Color.parseColor("#202020"))
                                setStroke(2, Color.parseColor("#404040"))
                                cornerRadius = 16f
                            }
                    elevation = 100f
                    isClickable = true
                    isFocusable = true
                }

        // Title
        val titleView =
                TextView(context).apply {
                    text = title
                    textSize = 20f
                    setTypeface(null, android.graphics.Typeface.BOLD)
                    setTextColor(Color.WHITE)
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 16.dp() }
                }
        dialogView.addView(titleView)

        // Message
        val messageView =
                TextView(context).apply {
                    text = message
                    textSize = 16f
                    setTextColor(Color.parseColor("#DDDDDD"))
                    maxLines = 15
                    isVerticalScrollBarEnabled = true
                    movementMethod = ScrollingMovementMethod.getInstance()
                    layoutParams =
                            LinearLayout.LayoutParams(
                                            LinearLayout.LayoutParams.MATCH_PARENT,
                                            LinearLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { bottomMargin = 24.dp() }
                }
        dialogView.addView(messageView)

        var inputField: EditText? = null
        if (hasInput) {
            inputField =
                    EditText(context).apply {
                        setText(defaultValue ?: "")
                        setTextColor(Color.WHITE)
                        textSize = 16f
                        setPadding(16, 16, 16, 16)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#303030"))
                                    cornerRadius = 8f
                                }
                        layoutParams =
                                LinearLayout.LayoutParams(
                                                LinearLayout.LayoutParams.MATCH_PARENT,
                                                LinearLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply { bottomMargin = 24.dp() }

                        // Important: Show custom keyboard on focus
                        setOnFocusChangeListener { _, hasFocus ->
                            if (hasFocus) {
                                keyboardListener?.onShowKeyboard()
                            }
                        }

                        // Allow our custom keyboard to input text here
                        isFocusable = true
                        isFocusableInTouchMode = true
                        setSingleLine()
                    }
            dialogView.addView(inputField)
        }

        // Buttons
        val buttonContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.END
                    layoutParams =
                            LinearLayout.LayoutParams(
                                    LinearLayout.LayoutParams.MATCH_PARENT,
                                    LinearLayout.LayoutParams.WRAP_CONTENT
                            )
                }

        if (onCancel != null) {
            val cancelButton =
                    Button(context).apply {
                        text = "Cancel"
                        textSize = 16f
                        setTextColor(Color.parseColor("#AAAAAA"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onCancel()
                            hideDialog()
                        }
                    }
            buttonContainer.addView(cancelButton)
        }

        additionalButtons.forEach { button ->
            if (button is Button) {
                button.background =
                        ContextCompat.getDrawable(context, R.drawable.nav_button_background)
            }
            buttonContainer.addView(button)
        }

        if (confirmLabel != null) {
            val confirmButton =
                    Button(context).apply {
                        text = confirmLabel
                        textSize = 16f
                        setTextColor(Color.parseColor("#4488FF"))
                        background =
                                ContextCompat.getDrawable(context, R.drawable.nav_button_background)
                        setPadding(24.dp(), 12.dp(), 24.dp(), 12.dp())
                        minWidth = 64.dp()
                        minHeight = 48.dp()
                        setOnClickListener {
                            onConfirm?.invoke(inputField?.text?.toString())
                            hideDialog()
                        }
                    }
            buttonContainer.addView(confirmButton)
        }

        dialogView.addView(buttonContainer)
        dialogContainer.addView(dialogView)
        dialogContainer.visibility = View.VISIBLE
        dialogContainer.bringToFront()
        if (dismissOnAnyClick) {
            dialogContainer.setOnClickListener { hideDialog() }
            // DON'T set listener on dialogView, so clicks inside don't dismiss
        }

        // Ensure rendering updates
        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    fun hideDialog() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Determine whether to show keyboard container again
        if (customKeyboard?.visibility == View.VISIBLE) {
            keyboardContainer.visibility = View.VISIBLE
        }

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    private var toastHandler: Handler? = Handler(Looper.getMainLooper())
    private var toastRunnable: Runnable? = null

    /**
     * Shows a toast message that renders in both eyes.
     * @param message The message to display
     * @param durationMs How long to show the toast (default 2000ms)
     */
    fun showToast(message: String, durationMs: Long = 2000L) {
        // DebugLog.d("Toast", "showToast called with message: $message")
        // Ensure we're on the UI thread
        post {
            // DebugLog.d("Toast", "Inside post block, creating toast view")
            // Cancel any existing toast
            toastRunnable?.let { toastHandler?.removeCallbacks(it) }
            dialogContainer.removeAllViews()

            val padding = 16.dp()
            val toastView =
                    LinearLayout(context).apply {
                        orientation = LinearLayout.HORIZONTAL
                        gravity = Gravity.CENTER
                        layoutParams =
                                FrameLayout.LayoutParams(
                                                FrameLayout.LayoutParams.WRAP_CONTENT,
                                                FrameLayout.LayoutParams.WRAP_CONTENT
                                        )
                                        .apply {
                                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                                            bottomMargin = 64.dp()
                                        }
                        setPadding(padding * 2, padding, padding * 2, padding)
                        background =
                                GradientDrawable().apply {
                                    setColor(Color.parseColor("#E0303030")) // Semi-transparent dark
                                    cornerRadius = 24f
                                }
                        elevation = 100f
                    }

            val messageView =
                    TextView(context).apply {
                        text = message
                        textSize = 16f
                        setTextColor(Color.WHITE)
                        gravity = Gravity.CENTER
                    }
            toastView.addView(messageView)

            // Use a transparent scrim for toast (unlike dialogs which block interaction)
            dialogContainer.setBackgroundColor(Color.TRANSPARENT)
            dialogContainer.addView(toastView)
            dialogContainer.visibility = View.VISIBLE
            dialogContainer.bringToFront()
            dialogContainer.isClickable = false // Allow clicks to pass through

            // DebugLog.d("Toast", "Toast view added, dialogContainer visible:
            // ${dialogContainer.visibility == View.VISIBLE}, child count:
            // ${dialogContainer.childCount}")

            // Ensure rendering updates
            requestLayout()
            invalidate()
            startRefreshing()

            // Auto-dismiss after duration
            toastRunnable = Runnable { hideToast() }
            toastHandler?.postDelayed(toastRunnable!!, durationMs)
        }
    }

    private fun hideToast() {
        dialogContainer.visibility = View.GONE
        dialogContainer.removeAllViews()
        // Restore dialog container background for future dialogs
        dialogContainer.setBackgroundColor(Color.parseColor("#CC000000"))
        dialogContainer.isClickable = true

        post {
            requestLayout()
            invalidate()
            startRefreshing()
        }
    }

    // Helper method to get the current dialog input if any
    fun getDialogInput(): EditText? {
        if (dialogContainer.visibility != View.VISIBLE) return null
        val dialogView = dialogContainer.getChildAt(0) as? ViewGroup ?: return null
        // Scan for EditText
        for (i in 0 until dialogView.childCount) {
            val child = dialogView.getChildAt(i)
            if (child is EditText) return child
        }
        return null
    }

    fun isDialogAction(x: Float, y: Float): Boolean {
        if (dialogContainer.visibility != View.VISIBLE || !dialogContainer.isClickable) return false
        val loc = IntArray(2)
        dialogContainer.getLocationOnScreen(loc)
        return x >= loc[0] &&
                x <= loc[0] + (dialogContainer.width * uiScale) &&
                y >= loc[1] &&
                y <= loc[1] + (dialogContainer.height * uiScale)
    }

    private fun setupMaskOverlayUI() {

        maskNowPlayingText =
                TextView(context).apply {
                    setTextColor(Color.argb(128, 255, 255, 255))
                    textSize = 12f
                    gravity = Gravity.CENTER
                    maxLines = 1
                    ellipsize = TextUtils.TruncateAt.END
                    visibility = View.GONE
                    alpha = 0.5f
                }
        val nowPlayingParams =
                FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.WRAP_CONTENT
                        )
                        .apply {
                            gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                            leftMargin = 56
                            rightMargin = 56
                            bottomMargin = 54
                        }
        maskOverlay.addView(maskNowPlayingText, nowPlayingParams)

        // Unmask button (Bottom Right)
        btnMaskUnmask =
                ImageButton(context).apply {
                    setImageResource(R.drawable.ic_visibility_on)
                    setBackgroundColor(Color.TRANSPARENT)
                    scaleType = ImageView.ScaleType.FIT_CENTER
                    setPadding(8, 8, 8, 8)
                    alpha = 0.5f
                    setOnClickListener { unmaskScreen() }
                }
        val unmaskParams =
                FrameLayout.LayoutParams(40, 40).apply {
                    gravity = Gravity.BOTTOM or Gravity.END
                    rightMargin = 8
                    bottomMargin = 8
                }
        maskOverlay.addView(btnMaskUnmask, unmaskParams)

        // Media Controls Container (Bottom Center)
        maskMediaControlsContainer =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.TRANSPARENT)
                    alpha = 0.5f
                    visibility = View.GONE // Hidden by default until media detected
                }
        val controlsParams =
                FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, 40).apply {
                    gravity = Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL
                    bottomMargin = 8
                }
        maskOverlay.addView(maskMediaControlsContainer, controlsParams)

        // Controls - Order: Prev Track, 10s Back, Play, Pause, 10s Forward, Next Track
        btnMaskPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    // Try to click previous track button (works on YouTube, Spotify, etc.)
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    // Try common previous track selectors
                    var prevBtn = document.querySelector('.ytp-prev-button') ||
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    // Fallback: Skip to beginning
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
            """.trimIndent(),
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                    scheduleTrackChangeRefresh()
                }
        btnMaskPrev =
                createMediaButton(R.string.fa_backward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime -= 10;",
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                }
        btnMaskPlay =
                createMediaButton(R.string.fa_play) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').play();",
                            "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                    )
                    // Immediately update button visibility for responsive UI
                    lastMediaInteractionTime = SystemClock.uptimeMillis()
                    btnMaskPlay.visibility = View.GONE
                    btnMaskPause.visibility = View.VISIBLE
                    maskMediaControlsContainer.requestLayout()
                }
        btnMaskPause =
                createMediaButton(R.string.fa_pause) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').pause();",
                            "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                    )
                    // Immediately update button visibility for responsive UI
                    lastMediaInteractionTime = SystemClock.uptimeMillis()
                    btnMaskPause.visibility = View.GONE
                    btnMaskPlay.visibility = View.VISIBLE
                    maskMediaControlsContainer.requestLayout()
                }
        btnMaskNext =
                createMediaButton(R.string.fa_forward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime += 10;",
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                }
        btnMaskNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    // Try to click next track button (works on YouTube, Spotify, etc.)
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    // Try common next track selectors
                    var nextBtn = document.querySelector('.ytp-next-button') ||
                                  document.querySelector('[aria-label*="next" i]') ||
                                  document.querySelector('[title*="next" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) { nextBtn.click(); return; }
                    // Fallback: Skip to end (triggers autoplay to next)
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = media.duration;
                })();
            """.trimIndent(),
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                    // YouTube SPA navigations take several seconds to update the title.
                    // Schedule aggressive delayed refreshes to catch the new video name.
                    scheduleTrackChangeRefresh()
                }

        btnMaskPause.visibility = View.GONE // Initially show Play

        maskMediaControlsContainer.addView(btnMaskPrevTrack)
        maskMediaControlsContainer.addView(btnMaskPrev)
        maskMediaControlsContainer.addView(btnMaskPlay)
        maskMediaControlsContainer.addView(btnMaskPause)
        maskMediaControlsContainer.addView(btnMaskNext)
        maskMediaControlsContainer.addView(btnMaskNextTrack)

        // ── Audio Visualizer ──
        maskVisualizerView = AudioVisualizerView(context).apply {
            visibility = View.GONE
            alpha = 0.6f
        }
        val vizParams = FrameLayout.LayoutParams(
            FrameLayout.LayoutParams.MATCH_PARENT, 180
        ).apply {
            gravity = Gravity.CENTER
            leftMargin = 40
            rightMargin = 40
        }
        maskOverlay.addView(maskVisualizerView, vizParams)

        // Toggle button — placed to the right of the now-playing text
        btnVisualizerToggle = FontIconView(context).apply {
            setTextColor(Color.WHITE)
            textSize = 16f
            gravity = Gravity.CENTER
            setShadowLayer(8f, 0f, 0f, Color.parseColor("#FF00FF"))
            // Click: true toggle on/off. Theme cycling stays on the visualizer surface itself.
            setOnClickListener {
                val now = SystemClock.uptimeMillis()
                if (now - lastVisualizerToggleTime < 200) return@setOnClickListener
                lastVisualizerToggleTime = now
                if (!isVisualizerVisible) {
                    showVisualizer()
                } else {
                    hideVisualizer()
                }
            }
        }
        updateVisualizerButtonColor()
        val vizToggleParams = FrameLayout.LayoutParams(44, 44).apply {
            gravity = Gravity.BOTTOM or Gravity.END
            rightMargin = 52
            bottomMargin = 46
        }
        maskOverlay.addView(btnVisualizerToggle, vizToggleParams)
    }

    private fun showVisualizer() {
        isVisualizerVisible = true
        maskVisualizerView.visibility = View.VISIBLE
        maskVisualizerView.bringToFront()
        maskVisualizerView.startAnimating()
        startAudioCapture()
        // Bring controls and text back to front
        maskMediaControlsContainer.bringToFront()
        maskNowPlayingText.bringToFront()
        btnVisualizerToggle.bringToFront()
        btnVisualizerToggle.alpha = 1.0f
        updateVisualizerButtonColor()
    }

    private fun hideVisualizer() {
        isVisualizerVisible = false
        stopAudioCapture()
        maskVisualizerView.stopAnimating()
        maskVisualizerView.visibility = View.GONE
        btnVisualizerToggle.alpha = 0.5f
        updateVisualizerButtonColor()
    }

    @SuppressLint("MissingPermission")
    private fun startAudioCapture() {
        // Reuse existing Visualizer if already created — releasing and recreating
        // Visualizer(0) disrupts the audio pipeline and can stop TapRadio playback.
        val existing = audioVisualizer
        if (existing != null) {
            try {
                if (!existing.enabled) existing.enabled = true
                Log.d("AudioViz", "Visualizer re-enabled (reused existing instance)")
                return
            } catch (e: Exception) {
                // Existing instance is dead, release and recreate
                Log.w("AudioViz", "Existing visualizer unusable, recreating: ${e.message}")
                try { existing.release() } catch (_: Exception) {}
                audioVisualizer = null
            }
        }
        try {
            // Session 0 = mix of all audio output
            val viz = Visualizer(0)
            viz.captureSize = Visualizer.getCaptureSizeRange()[1]  // max for best resolution
            viz.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer?, waveform: ByteArray?, samplingRate: Int) {}
                override fun onFftDataCapture(v: Visualizer?, fft: ByteArray?, samplingRate: Int) {
                    if (fft == null) return
                    val buckets = fftMagnitudes.size
                    val fftSize = fft.size / 2  // real/imag pairs
                    val capture = FloatArray(buckets)
                    for (i in 0 until buckets) {
                        val startFrac = (i.toFloat() / buckets.toFloat()).toDouble().pow(1.55).toFloat()
                        val endFrac = ((i + 1).toFloat() / buckets.toFloat()).toDouble().pow(1.55).toFloat()
                        val start = (startFrac * fftSize).toInt().coerceIn(1, maxOf(1, fftSize - 1))
                        val end = (endFrac * fftSize).toInt().coerceIn(start + 1, fftSize)
                        var peakDb = 0f
                        var avgDb = 0f
                        var samples = 0
                        for (bin in start until end) {
                            val reIdx = bin * 2
                            val imIdx = reIdx + 1
                            if (imIdx >= fft.size) break
                            val re = fft[reIdx].toFloat()
                            val im = fft[imIdx].toFloat()
                            val magnitude = kotlin.math.sqrt(re * re + im * im)
                            val db = (20f * kotlin.math.log10(1f + magnitude)).coerceAtLeast(0f)
                            if (db > peakDb) peakDb = db
                            avgDb += db
                            samples++
                        }
                        val meanDb = if (samples > 0) avgDb / samples else 0f
                        val pos = i.toFloat() / (buckets - 1).coerceAtLeast(1)
                        val mixedDb = peakDb * 0.58f + meanDb * 0.42f
                        val normalized = (mixedDb / 44f).coerceIn(0f, 1f)
                        val eqWeight = when {
                            pos < 0.16f -> 0.48f + pos * 0.55f
                            pos < 0.42f -> 0.68f + (pos - 0.16f) * 1.0f
                            pos < 0.76f -> 0.94f + (pos - 0.42f) * 0.95f
                            else -> 1.26f + (pos - 0.76f) * 1.55f
                        }
                        val compressed = normalized.toDouble().pow(0.72).toFloat()
                        capture[i] = (compressed * eqWeight).coerceIn(0f, 1f)
                    }
                    // Write to back buffer, then atomically swap with front buffer
                    for (i in 0 until buckets) {
                        val leftFar = capture[maxOf(0, i - 2)]
                        val left = capture[maxOf(0, i - 1)]
                        val center = capture[i]
                        val right = capture[minOf(buckets - 1, i + 1)]
                        val rightFar = capture[minOf(buckets - 1, i + 2)]
                        val pos = i.toFloat() / (buckets - 1).coerceAtLeast(1)
                        val smoothed = leftFar * 0.08f + left * 0.18f + center * 0.38f + right * 0.22f + rightFar * 0.14f
                        val stereoBalance = when {
                            pos < 0.2f -> 0.72f
                            pos < 0.55f -> 0.82f + (pos - 0.2f) * 0.38f
                            else -> 0.95f + (pos - 0.55f) * 0.55f
                        }
                        fftBackBuffer[i] = (smoothed * stereoBalance).coerceIn(0f, 1f)
                    }
                    // Atomic swap: UI thread sees complete frame or previous frame, never partial
                    val tmp = fftFrontBuffer
                    fftFrontBuffer = fftBackBuffer
                    fftBackBuffer = tmp
                }
            }, Visualizer.getMaxCaptureRate(), false, true)  // waveform=false, fft=true
            viz.enabled = true
            audioVisualizer = viz
            Log.d("AudioViz", "Visualizer capture started, size=${viz.captureSize}")
        } catch (e: Exception) {
            Log.e("AudioViz", "Failed to start Visualizer: ${e.message}")
            // Fall back to random data mode — fftMagnitudes will stay at 0
        }
    }

    private fun stopAudioCapture() {
        // Only disable — don't release. Releasing Visualizer(0) and recreating it
        // disrupts the audio pipeline and can stop TapRadio/media playback.
        try {
            audioVisualizer?.enabled = false
        } catch (_: Exception) {}
        // Zero out both buffers so bars drop to silence
        fftFrontBuffer.fill(0f)
        fftBackBuffer.fill(0f)
    }

    /** Fully release the Visualizer (call on destroy/cleanup only). */
    fun releaseAudioCapture() {
        try {
            audioVisualizer?.enabled = false
            audioVisualizer?.release()
        } catch (_: Exception) {}
        audioVisualizer = null
        fftFrontBuffer.fill(0f)
        fftBackBuffer.fill(0f)
    }

    private fun updatePlaybackWakeLocks() {
        val shouldHoldMaskWakeLock = isScreenMasked
        if (shouldHoldMaskWakeLock) {
            if (!maskWakeLock.isHeld) maskWakeLock.acquire()
        } else if (maskWakeLock.isHeld) {
            maskWakeLock.release()
        }

        // Streaming radio/video needs a steady CPU + Wi-Fi path on these glasses.
        // The device log shows real AudioTrack underruns, so hold playback resources
        // for any active media session, not only when the host Activity is paused.
        val shouldHoldPlaybackWakeLock = isMediaPlaying
        if (shouldHoldPlaybackWakeLock) {
            if (!pausedMediaWakeLock.isHeld) pausedMediaWakeLock.acquire()
            try {
                if (mediaWifiLock?.isHeld == false) mediaWifiLock.acquire()
            } catch (_: Exception) {}
        } else {
            if (pausedMediaWakeLock.isHeld) pausedMediaWakeLock.release()
            try {
                if (mediaWifiLock?.isHeld == true) mediaWifiLock.release()
            } catch (_: Exception) {}
        }
    }

    /** Colorful icon that reflects the current visualizer theme */
    private fun updateVisualizerButtonColor() {
        if (!::btnVisualizerToggle.isInitialized) return
        val themeColor = if (!isVisualizerVisible) {
            Color.argb(160, 180, 120, 255) // Dim purple when off
        } else {
            when (maskVisualizerView.currentTheme) {
                maskVisualizerView.THEME_JAZZ -> Color.parseColor("#FFD700")
                maskVisualizerView.THEME_WAVE        -> Color.parseColor("#00FFFF")
                maskVisualizerView.THEME_PULSE_RING  -> Color.parseColor("#FF006E")
                maskVisualizerView.THEME_SPECTRUM    -> Color.parseColor("#FFAA00")
                maskVisualizerView.THEME_MEDITATIVE  -> Color.parseColor("#7B68EE")
                maskVisualizerView.THEME_BREATHE     -> Color.parseColor("#2E8B8B")
                maskVisualizerView.THEME_TRON             -> Color.parseColor("#00DFFF")
                maskVisualizerView.THEME_CLOSE_ENCOUNTERS -> Color.parseColor("#FF6600")
                else -> Color.WHITE
            }
        }
        btnVisualizerToggle.setTextColor(themeColor)
        btnVisualizerToggle.setShadowLayer(10f, 0f, 0f, themeColor)
        // Use the wave icon since fa_wave_square may not render in FA Solid
        btnVisualizerToggle.setText(R.string.fa_bars_staggered)
    }

    /**
     * Animated audio visualizer view drawn on Canvas.
     * Themes cycle on long-press of the toggle button.
     */
    inner class AudioVisualizerView(context: Context) : View(context) {

        // Theme constants (enum class not allowed inside inner class)
        val THEME_JAZZ = 0  // Jazz band — smoky club stage
        val THEME_WAVE = 1
        val THEME_PULSE_RING = 2
        val THEME_SPECTRUM = 3
        val THEME_MEDITATIVE = 4
        val THEME_BREATHE = 5
        val THEME_TRON = 6
        val THEME_CLOSE_ENCOUNTERS = 7
        private val THEME_COUNT = 8

        // Breathing timer state
        private var breathCycleMs = 0L          // elapsed ms in current cycle
        private var breathInhaleMs = 4000L      // 4s inhale
        private var breathHoldMs = 1000L        // 1s hold
        private var breathExhaleMs = 6000L      // 6s exhale
        private var breathPauseMs = 1000L       // 1s pause
        private val breathTotalMs get() = breathInhaleMs + breathHoldMs + breathExhaleMs + breathPauseMs
        private var lastBreathFrameTime = 0L

        var currentTheme = THEME_JAZZ
            private set
        private val barCount = 32
        private val barHeights = FloatArray(barCount)
        private val targetHeights = FloatArray(barCount)
        private val velocities = FloatArray(barCount)
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var animating = false
        private val random = java.util.Random()
        private var frameCount = 0L
        private val wavePath = android.graphics.Path()

        // Theme color palettes
        private val neonColors = intArrayOf(
            Color.parseColor("#FF00FF"), Color.parseColor("#00FFFF"),
            Color.parseColor("#FF006E"), Color.parseColor("#00FF88"),
            Color.parseColor("#8B5CF6"), Color.parseColor("#06B6D4")
        )
        private val spectrumColors = intArrayOf(
            Color.parseColor("#FF0000"), Color.parseColor("#FF7700"),
            Color.parseColor("#FFFF00"), Color.parseColor("#00FF00"),
            Color.parseColor("#0077FF"), Color.parseColor("#8800FF")
        )

        fun cycleTheme() {
            currentTheme = (currentTheme + 1) % THEME_COUNT
            invalidate()
        }

        private fun applyTheme(theme: Int) {
            currentTheme = ((theme % THEME_COUNT) + THEME_COUNT) % THEME_COUNT
            frameCount = 0L
            breathCycleMs = 0L
            lastBreathFrameTime = 0L
            wavePath.reset()
            paint.reset()
            paint.isAntiAlias = true
            paint.setShadowLayer(0f, 0f, 0f, 0)
            invalidate()
        }

        /** Advance theme sequentially and wrap back to the first theme without hiding. */
        fun cycleThemeOrWrap(): Boolean {
            applyTheme(currentTheme + 1)
            updateVisualizerButtonColor()
            return false
        }

        fun startAnimating() {
            animating = true
            applyTheme(currentTheme)
            postInvalidateOnAnimation()
        }

        fun stopAnimating() {
            animating = false
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            if (!animating) return

            canvas.drawColor(Color.TRANSPARENT, PorterDuff.Mode.CLEAR)
            paint.reset()
            paint.isAntiAlias = true
            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.shader = null
            paint.textAlign = Paint.Align.LEFT
            paint.typeface = android.graphics.Typeface.DEFAULT
            wavePath.reset()

            val w = width.toFloat()
            val h = height.toFloat()
            if (w <= 0 || h <= 0) return

            // Pull real FFT magnitudes from the audio capture
            frameCount++
            for (i in 0 until barCount) {
                targetHeights[i] = fftMagnitudes[i]
            }
            // Smooth interpolation — fast attack, slower decay for punchy response
            for (i in 0 until barCount) {
                val target = targetHeights[i]
                if (target > barHeights[i]) {
                    // Fast attack: snap up quickly to new peaks
                    barHeights[i] = barHeights[i] * 0.3f + target * 0.7f
                } else {
                    // Slower decay: smooth falloff for visual appeal
                    barHeights[i] = barHeights[i] * 0.75f + target * 0.25f
                }
                barHeights[i] = barHeights[i].coerceIn(0.0f, 1f)
            }

            when (currentTheme) {
                THEME_JAZZ -> drawJazzBand(canvas, w, h)
                THEME_WAVE -> drawWave(canvas, w, h)
                THEME_PULSE_RING -> drawPulseRing(canvas, w, h)
                THEME_SPECTRUM -> drawSpectrum(canvas, w, h)
                THEME_MEDITATIVE -> drawMeditative(canvas, w, h)
                THEME_BREATHE -> drawBreathe(canvas, w, h)
                THEME_TRON -> drawTron(canvas, w, h)
                THEME_CLOSE_ENCOUNTERS -> drawCloseEncounters(canvas, w, h)
            }

            if (animating) postInvalidateOnAnimation()
        }

        private fun bandEnergy(startInclusive: Int, endInclusive: Int): Float {
            val safeStart = startInclusive.coerceIn(0, barCount - 1)
            val safeEnd = endInclusive.coerceIn(safeStart, barCount - 1)
            var total = 0f
            var count = 0
            for (i in safeStart..safeEnd) {
                total += barHeights[i]
                count++
            }
            return if (count > 0) total / count else 0f
        }

        private fun remapVisualizerPosition(pos: Float): Float {
            val clamped = pos.coerceIn(0f, 1f)
            val curved = clamped.toDouble().pow(1.18).toFloat()
            return (curved * 0.78f + clamped * 0.22f).coerceIn(0f, 1f)
        }

        private fun bandWindow(centerPos: Float, radius: Int = 2): Float {
            val center = (centerPos.coerceIn(0f, 1f) * (barCount - 1)).toInt().coerceIn(0, barCount - 1)
            var total = 0f
            var weightSum = 0f
            for (offset in -radius..radius) {
                val idx = (center + offset).coerceIn(0, barCount - 1)
                val weight = 1f / (1f + kotlin.math.abs(offset).toFloat())
                total += barHeights[idx] * weight
                weightSum += weight
            }
            return if (weightSum > 0f) total / weightSum else 0f
        }

        private fun measuredAudioAt(pos: Float, lowCut: Float = 0.82f, highBoost: Float = 1.22f): Float {
            val remapped = remapVisualizerPosition(pos)
            val wide = bandWindow(remapped, 3)
            val tight = bandWindow(remapped, 1)
            val blended = (wide * 0.45f + tight * 0.55f).coerceIn(0f, 1f)
            val tonalWeight = when {
                remapped < 0.18f -> lowCut
                remapped < 0.52f -> 0.9f + (remapped - 0.18f) * 0.55f
                else -> 1.02f + (remapped - 0.52f) * ((highBoost - 1.02f) / 0.48f)
            }
            val compressed = blended.toDouble().pow(0.82).toFloat()
            return (compressed * tonalWeight).coerceIn(0f, 1f)
        }

        private fun measuredYOffset(sample: Float, amplitude: Float, floor: Float = 0.28f): Float {
            return (sample - floor) * amplitude
        }

        /**
         * RayNeo Rim Theme — AR glasses frame visualization with
         * cyan/silver rim effects reacting to audio. The outer border glows
         * with bass energy, inner frames are sharp, and flowing energy effects
         * pulse along the edges. Premium futuristic look for the RayNeo X3 Pro.
         */
        /**
         * Jazz Band Theme — Smoky club stage with 5 musicians.
         *
         * Same methodology as orchestra: unit-scale `u` from canvas height,
         * frequency-band-driven animation, musicians freeze when silent.
         * Musicians: drummer (bass), upright bassist (bass+lowMid),
         * pianist (highMid), saxophonist (treble), trumpet (treble+highMid).
         */
        private fun drawJazzBand(canvas: Canvas, w: Float, h: Float) {
            val avgLevel = bandEnergy(0, barCount - 1)
            val bass = bandEnergy(0, 6)
            val lowMid = bandEnergy(7, 14)
            val highMid = bandEnergy(15, 23)
            val treble = bandEnergy(24, 31)
            val active = avgLevel > 0.04f
            val motion = if (active) avgLevel.coerceIn(0f, 1f) else 0f

            // Unit scale — fills glasses frame edge-to-edge
            val u = h * 0.035f
            val stageTop = h * 0.40f
            val stageCenterY = h * 0.62f

            // Time bases (same doubled speed as orchestra)
            val tSlow = frameCount * 0.044f
            val tMed  = frameCount * 0.070f

            // ── Background: dark smoky jazz club ──
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#08040E"),
                    Color.parseColor("#140C1A"),
                    Color.parseColor("#1A0E08")
                ),
                floatArrayOf(0f, 0.5f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Smoky haze — subtle warm fog layers
            if (motion > 0.02f) {
                val hazeAlpha = (motion * 14f).toInt().coerceIn(0, 18)
                paint.color = Color.argb(hazeAlpha, 180, 140, 90)
                canvas.drawRect(0f, h * 0.15f, w, h * 0.55f, paint)
                paint.color = Color.argb(hazeAlpha / 2, 120, 80, 160)
                canvas.drawRect(0f, h * 0.05f, w, h * 0.35f, paint)
            }

            // Stage floor — warm wood tones
            paint.color = Color.argb(85, 160, 100, 40)
            canvas.drawOval(w * 0.02f, stageTop, w * 0.98f, h * 1.05f, paint)
            paint.color = Color.argb(150, 30, 16, 8)
            canvas.drawRect(0f, stageTop + 2f, w, h, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(80, 220, 170, 80)
            paint.strokeWidth = 1.2f
            canvas.drawLine(w * 0.02f, stageTop, w * 0.98f, stageTop, paint)

            // Spotlights — warm amber/gold jazz club lighting
            paint.style = Paint.Style.FILL
            val sA = (25 + motion * 50f).toInt().coerceIn(25, 80)
            paint.color = Color.argb(sA, 255, 200, 80)
            canvas.drawCircle(w * 0.15f, h * 0.06f, 5f + bass * 3f, paint)
            paint.color = Color.argb(sA, 255, 180, 120)
            canvas.drawCircle(w * 0.50f, h * 0.03f, 6f + highMid * 3.5f, paint)
            paint.color = Color.argb(sA, 220, 160, 255)
            canvas.drawCircle(w * 0.85f, h * 0.06f, 5f + treble * 3f, paint)

            // Light cones
            if (motion > 0.05f) {
                paint.color = Color.argb((motion * 14f).toInt().coerceIn(0, 18), 255, 200, 100)
                val cone = android.graphics.Path()
                for (spot in floatArrayOf(0.15f, 0.50f, 0.85f)) {
                    cone.reset()
                    cone.moveTo(w * spot, h * 0.04f)
                    cone.lineTo(w * (spot - 0.07f), stageTop)
                    cone.lineTo(w * (spot + 0.07f), stageTop)
                    cone.close()
                    canvas.drawPath(cone, paint)
                }
            }

            // ═══ Drawing helpers ═══
            // Diverse skin tone palette
            val skinTones = intArrayOf(
                Color.parseColor("#FCDEC0"),  // light
                Color.parseColor("#C68642"),  // medium brown
                Color.parseColor("#8D5524"),  // dark brown
                Color.parseColor("#E0AC69"),  // golden
                Color.parseColor("#503335")   // deep brown
            )
            val headR = u * 1.6f
            val bodyW = u * 2.8f
            val bodyH = u * 8f
            val armW = u * 0.18f + 1.2f

            fun drawHead(hx: Float, hy: Float, r: Float, skinColor: Int = skinTones[0]) {
                paint.style = Paint.Style.FILL
                paint.color = skinColor
                canvas.drawCircle(hx, hy, r, paint)
                // Subtle shadow for depth
                paint.color = Color.argb(45, 20, 12, 8)
                canvas.drawCircle(hx, hy + r * 0.18f, r * 0.82f, paint)
            }

            fun drawTorso(bx: Float, top: Float, bw: Float, bh: Float, color: Int) {
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawRoundRect(bx - bw / 2f, top, bx + bw / 2f, top + bh, bw * 0.28f, bw * 0.28f, paint)
            }

            fun drawLimb(sx: Float, sy: Float, ex: Float, ey: Float, hx: Float, hy: Float, width: Float, color: Int) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = width
                paint.color = color
                canvas.drawLine(sx, sy, ex, ey, paint)
                canvas.drawLine(ex, ey, hx, hy, paint)
            }

            // ═══ Jazz Musicians ═══

            // ── DRUMMER (bass-reactive) ──
            fun drawDrummer(cx: Float, cy: Float, energy: Float, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5f
                val shoulderY = cy - u * 2.4f
                val shoulderL = cx - u * 1.3f
                val shoulderR = cx + u * 1.3f

                val sway = kotlin.math.sin((tSlow + seed).toDouble()).toFloat() * e * u * 0.3f
                drawHead(cx + sway * 0.2f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + sway * 0.1f, torsoTop, bodyW, bodyH, Color.parseColor("#2A1A1A"))

                // Snare drum (center-left)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#C0C0C8")
                canvas.drawOval(cx - u * 2.5f, cy + u * 0.5f, cx + u * 0.5f, cy + u * 2f, paint)
                paint.color = Color.parseColor("#888890")
                canvas.drawOval(cx - u * 2.5f, cy + u * 0.3f, cx + u * 0.5f, cy + u * 0.7f, paint)

                // Hi-hat cymbal (right side)
                paint.color = Color.parseColor("#D4C870")
                paint.alpha = 180
                canvas.drawOval(cx + u * 1.2f, cy - u * 0.8f, cx + u * 3.5f, cy - u * 0.2f, paint)

                // Bass drum (behind, below)
                paint.alpha = 255
                paint.color = Color.parseColor("#3A2020")
                canvas.drawCircle(cx, cy + u * 4f, u * 2.5f, paint)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = u * 0.15f
                paint.color = Color.parseColor("#C0A050")
                canvas.drawCircle(cx, cy + u * 4f, u * 2.5f, paint)

                // Left arm: hitting snare — sharp downbeat motion
                val hitL = kotlin.math.sin((tMed * 2.2f + seed).toDouble()).toFloat()
                val stickSwingL = hitL * e * (u * 0.8f + e * u * 2f)
                val elbowL_x = shoulderL - u * 0.5f
                val elbowL_y = cy - u * 0.4f + stickSwingL * 0.3f
                val handL_x = cx - u * 1.2f
                val handL_y = cy + u * 0.3f + stickSwingL * 0.5f
                drawLimb(shoulderL, shoulderY, elbowL_x, elbowL_y, handL_x, handL_y, armW, skinColor)
                // Drumstick
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#E8D8B0")
                canvas.drawLine(handL_x, handL_y, handL_x - u * 1f, handL_y + u * 1.2f + stickSwingL * 0.3f, paint)

                // Right arm: hitting hi-hat — offset rhythm
                val hitR = kotlin.math.sin((tMed * 2.8f + seed * 1.7f).toDouble()).toFloat()
                val stickSwingR = hitR * e * (u * 0.6f + e * u * 1.8f)
                val elbowR_x = shoulderR + u * 0.6f
                val elbowR_y = cy - u * 0.8f + stickSwingR * 0.25f
                val handR_x = cx + u * 2f
                val handR_y = cy - u * 0.5f + stickSwingR * 0.4f
                drawLimb(shoulderR, shoulderY, elbowR_x, elbowR_y, handR_x, handR_y, armW, skinColor)
                // Drumstick
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#E8D8B0")
                canvas.drawLine(handR_x, handR_y, handR_x + u * 0.6f, handR_y + u * 1f + stickSwingR * 0.2f, paint)
            }

            // ── UPRIGHT BASSIST (bass + lowMid) ──
            fun drawBassist(cx: Float, cy: Float, energy: Float, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5.5f
                val shoulderY = cy - u * 2.8f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f

                val sway = kotlin.math.sin((tSlow * 0.9f + seed).toDouble()).toFloat() * e * u * 0.5f
                drawHead(cx + sway * 0.3f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + sway * 0.12f, torsoTop, bodyW, bodyH, Color.parseColor("#1A2838"))

                // Upright bass body — tall pear shape to the right
                val bx = cx + u * 2f
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#6A3818")
                val bassPath = android.graphics.Path()
                bassPath.moveTo(bx, cy - u * 5f)       // scroll top
                bassPath.lineTo(bx - u * 0.3f, cy - u * 3f) // neck
                bassPath.quadTo(bx - u * 2f, cy - u * 0.5f, bx - u * 1.8f, cy + u * 2f) // upper bout
                bassPath.quadTo(bx, cy + u * 5f, bx + u * 1.8f, cy + u * 2f) // lower bout
                bassPath.quadTo(bx + u * 2f, cy - u * 0.5f, bx + u * 0.3f, cy - u * 3f) // upper bout right
                bassPath.close()
                canvas.drawPath(bassPath, paint)
                // F-holes
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.8f
                paint.color = Color.parseColor("#3A1E0C")
                canvas.drawLine(bx - u * 0.8f, cy - u * 0.2f, bx - u * 0.6f, cy + u * 1.2f, paint)
                canvas.drawLine(bx + u * 0.6f, cy - u * 0.2f, bx + u * 0.8f, cy + u * 1.2f, paint)
                // Strings
                paint.strokeWidth = 0.5f
                paint.color = Color.parseColor("#DDD0B0")
                for (s in -1..1 step 2) {
                    canvas.drawLine(bx + s * u * 0.2f, cy - u * 4.5f, bx + s * u * 0.3f, cy + u * 4f, paint)
                }

                // Left arm: fingering the neck — vibrato
                val vib = kotlin.math.sin((tMed * 2f + seed * 2.5f).toDouble()).toFloat() * e * u * 0.5f
                drawLimb(
                    shoulderL, shoulderY,
                    cx + u * 0.5f, cy - u * 2f,
                    bx - u * 0.1f, cy - u * 2.8f + vib,
                    armW, skinColor
                )

                // Right arm: plucking strings — visible pull motion
                val pluckPhase = kotlin.math.sin((tMed * 1.4f + seed * 1.9f).toDouble()).toFloat()
                val pluck = pluckPhase * e * (u * 0.8f + e * u * 2f)
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 1.8f, cy - u * 0.2f + pluck * 0.2f,
                    bx - u * 0.5f, cy + u * 0.8f + pluck * 0.4f,
                    armW, skinColor
                )
            }

            // ── PIANIST (highMid-reactive) ──
            fun drawPianist(cx: Float, cy: Float, energy: Float, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 4.5f
                val shoulderY = cy - u * 2.2f
                val shoulderL = cx - u * 1.1f
                val shoulderR = cx + u * 1.1f

                val lean = kotlin.math.sin((tSlow * 0.8f + seed).toDouble()).toFloat() * e * u * 0.3f
                drawHead(cx + lean * 0.3f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + lean * 0.12f, torsoTop, bodyW * 0.95f, bodyH * 0.9f, Color.parseColor("#222240"))

                // Piano top / keyboard — horizontal bar in front
                val pianoL = cx - u * 3.5f
                val pianoR = cx + u * 3.5f
                val pianoTop = cy + u * 1f
                val pianoBot = cy + u * 2.5f
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#0A0A0A")
                canvas.drawRoundRect(pianoL, pianoTop, pianoR, pianoBot, u * 0.3f, u * 0.3f, paint)

                // White keys
                val keyCount = 14
                val keyW = (pianoR - pianoL) / keyCount
                paint.color = Color.parseColor("#F0EDE0")
                for (k in 0 until keyCount) {
                    val kx = pianoL + k * keyW + 0.5f
                    canvas.drawRect(kx, pianoTop + u * 0.1f, kx + keyW - 1f, pianoBot - u * 0.1f, paint)
                }
                // Black keys
                paint.color = Color.parseColor("#1A1A1A")
                val blackPattern = intArrayOf(1, 2, 4, 5, 6, 8, 9, 11, 12, 13)
                for (k in blackPattern) {
                    if (k < keyCount) {
                        val kx = pianoL + k * keyW - keyW * 0.15f
                        canvas.drawRect(kx, pianoTop + u * 0.1f, kx + keyW * 0.6f, pianoTop + u * 0.9f, paint)
                    }
                }

                // Arms: both hands play keys — fingers bounce with energy
                val fingerL = kotlin.math.sin((tMed * 2.2f + seed * 1.1f).toDouble()).toFloat() * e * u * 0.7f
                val fingerR = kotlin.math.sin((tMed * 2.6f + seed * 1.8f).toDouble()).toFloat() * e * u * 0.7f

                // Left hand on left side of keyboard
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 1.5f, cy + u * 0.2f + fingerL * 0.2f,
                    cx - u * 2f, pianoTop - u * 0.2f + fingerL * 0.4f,
                    armW, skinColor
                )
                // Right hand on right side
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 1.5f, cy + u * 0.2f + fingerR * 0.2f,
                    cx + u * 2f, pianoTop - u * 0.2f + fingerR * 0.4f,
                    armW, skinColor
                )
            }

            // ── SAXOPHONIST (treble-reactive) ──
            fun drawSaxophonist(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5f
                val headY = torsoTop - headR * 1.1f
                val mouthY = headY + headR * 0.4f  // mouth level on face
                val shoulderY = cy - u * 2.4f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f

                // Sway with feeling
                val sway = kotlin.math.sin((tSlow * 1.2f + seed).toDouble()).toFloat() * e * u * 0.6f
                val headX = cx + sway * 0.35f
                drawHead(headX, headY, headR, skinColor)
                drawTorso(cx + sway * 0.15f, torsoTop, bodyW, bodyH, dress)

                // Saxophone: mouthpiece at face, curves down to bell
                val mpX = headX + u * 0.6f   // mouthpiece at right side of face
                val mpY = mouthY
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = u * 0.4f
                paint.color = Color.parseColor("#D4A030")
                val saxPath = android.graphics.Path()
                saxPath.moveTo(mpX, mpY)  // mouthpiece at face
                saxPath.quadTo(cx + u * 1.8f, cy - u * 1f, cx + u * 1.2f, cy + u * 1.5f) // body curve
                saxPath.quadTo(cx + u * 0.4f, cy + u * 3.5f, cx - u * 0.3f, cy + u * 3f) // bell curve
                canvas.drawPath(saxPath, paint)

                // Mouthpiece nub at face
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#1A1A1A")
                canvas.drawCircle(mpX, mpY, u * 0.18f, paint)

                // Bell opening
                val bellPulse = u * 1.2f + e * u * 0.5f
                paint.color = Color.parseColor("#D4A030")
                canvas.drawCircle(cx - u * 0.3f, cy + u * 3f, bellPulse, paint)
                paint.color = Color.parseColor("#2A1808")
                canvas.drawCircle(cx - u * 0.3f, cy + u * 3f, bellPulse * 0.6f, paint)

                // Keys on saxophone body
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#F0D070")
                val keyPositions = floatArrayOf(0.25f, 0.4f, 0.55f, 0.7f)
                for (kp in keyPositions) {
                    val kx = cx + u * (1.6f - kp * 1.2f)
                    val ky = mpY + (cy + u * 3f - mpY) * kp
                    canvas.drawCircle(kx, ky, u * 0.14f, paint)
                }

                // Left hand: upper keys
                val fingerPhase = kotlin.math.sin((tMed * 1.8f + seed * 1.3f).toDouble()).toFloat()
                val fingerMove = fingerPhase * e * u * 0.6f
                drawLimb(
                    shoulderL, shoulderY,
                    cx + u * 0.2f, cy - u * 1.8f + fingerMove * 0.15f,
                    cx + u * 1.2f, cy - u * 1.5f + fingerMove,
                    armW, skinColor
                )
                // Right hand: lower keys
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 1.2f, cy - u * 0.2f - fingerMove * 0.1f,
                    cx + u * 0.8f, cy + u * 0.8f - fingerMove * 0.4f,
                    armW, skinColor
                )
            }

            // ── TRUMPET PLAYER (treble + highMid) ──
            fun drawTrumpeter(cx: Float, cy: Float, energy: Float, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5f
                val shoulderY = cy - u * 2.4f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f
                val headY = torsoTop - headR * 1.1f
                val mouthY = headY + headR * 0.4f  // mouth level

                val sway = kotlin.math.sin((tSlow * 0.85f + seed).toDouble()).toFloat() * e * u * 0.3f
                drawHead(cx + sway * 0.2f, headY, headR, skinColor)
                drawTorso(cx + sway * 0.1f, torsoTop, bodyW, bodyH, Color.parseColor("#2A2020"))

                // Trumpet: mouthpiece at face, extends outward to bell
                val mouthpieceX = cx + u * 0.8f + sway * 0.2f
                val mouthpieceY = mouthY
                val bellX = cx + u * 4.5f + sway * 0.15f
                val bellY = mouthY + u * 0.3f
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = u * 0.28f
                paint.color = Color.parseColor("#D4A840")
                canvas.drawLine(mouthpieceX, mouthpieceY, bellX, bellY, paint)

                // Mouthpiece nub at face
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#E0C050")
                canvas.drawCircle(mouthpieceX, mouthpieceY, u * 0.15f, paint)

                // Bell at end — pulses with energy
                val bellR = u * 1f + e * u * 0.4f
                paint.color = Color.parseColor("#D4A840")
                canvas.drawCircle(bellX, bellY, bellR, paint)
                paint.color = Color.parseColor("#3A2A10")
                canvas.drawCircle(bellX + u * 0.1f, bellY, bellR * 0.55f, paint)

                // Valve casings (on tube midway)
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#C09830")
                for (v in 0..2) {
                    val t = 0.3f + v * 0.15f
                    val vx = mouthpieceX + (bellX - mouthpieceX) * t
                    val vy = mouthpieceY + (bellY - mouthpieceY) * t
                    canvas.drawRect(vx - u * 0.12f, vy - u * 0.8f, vx + u * 0.12f, vy, paint)
                }

                // Left arm: holds trumpet near valves — finger action
                val valvePhase = kotlin.math.sin((tMed * 2f + seed * 1.5f).toDouble()).toFloat()
                val valveMove = valvePhase * e * u * 0.5f
                val midTrumpetX = mouthpieceX + (bellX - mouthpieceX) * 0.4f
                val midTrumpetY = mouthpieceY + (bellY - mouthpieceY) * 0.4f
                drawLimb(
                    shoulderL, shoulderY,
                    cx + u * 0.5f, shoulderY + u * 1.2f + valveMove * 0.15f,
                    midTrumpetX, midTrumpetY + u * 0.3f + valveMove * 0.3f,
                    armW, skinColor
                )
                // Right arm: supports trumpet further out
                val farTrumpetX = mouthpieceX + (bellX - mouthpieceX) * 0.6f
                val farTrumpetY = mouthpieceY + (bellY - mouthpieceY) * 0.6f
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 2f, shoulderY + u * 0.8f - valveMove * 0.1f,
                    farTrumpetX, farTrumpetY + u * 0.3f + valveMove * 0.2f,
                    armW, skinColor
                )
            }

            // ═══ Place jazz musicians — tight club stage, diverse skin tones ═══
            drawDrummer(w * 0.10f, stageCenterY + u * 0.5f, bass, skinTones[2], 0f)
            drawBassist(w * 0.28f, stageCenterY + u * 0.3f, bass * 0.6f + lowMid * 0.4f, skinTones[0], 1.2f)
            drawPianist(w * 0.50f, stageCenterY + u * 1.5f, highMid, skinTones[3], 2.5f)
            drawSaxophonist(w * 0.72f, stageCenterY + u * 0.2f, treble, Color.parseColor("#2D1530"), skinTones[4], 3.7f)
            drawTrumpeter(w * 0.90f, stageCenterY + u * 0.4f, treble * 0.5f + highMid * 0.5f, skinTones[1], 4.8f)
        }

        /**
         * Classical Orchestra Theme — Musicians on a warm concert stage.
         *
         * ALL figure geometry uses a unit scale `u` derived from canvas
         * height so proportions stay correct regardless of aspect ratio.
         * When audio is present, every musician visibly plays their
         * instrument — bow strokes, finger movement, body sway — all
         * proportional to their frequency band's energy.
         * When silent, every musician holds still at rest position.
         */
        private fun drawWave(canvas: Canvas, w: Float, h: Float) {
            val avgLevel = bandEnergy(0, barCount - 1)
            val bass = bandEnergy(0, 6)
            val lowMid = bandEnergy(7, 14)
            val highMid = bandEnergy(15, 23)
            val treble = bandEnergy(24, 31)
            val active = avgLevel > 0.04f
            val motion = if (active) avgLevel.coerceIn(0f, 1f) else 0f

            // Unit scale: all musician geometry derives from this.
            // Large scale fills the glasses frame edge-to-edge.
            val u = h * 0.035f
            val stageTop = h * 0.42f
            val stageCenterY = h * 0.62f

            // Time bases for musical motion (doubled speed: ~1.5s and ~2.4s cycles)
            val tSlow = frameCount * 0.044f
            val tMed  = frameCount * 0.070f

            // ── Background ──
            paint.style = Paint.Style.FILL
            paint.shader = android.graphics.LinearGradient(
                0f, 0f, 0f, h,
                intArrayOf(
                    Color.parseColor("#06090F"),
                    Color.parseColor("#0E1722"),
                    Color.parseColor("#14100C")
                ),
                floatArrayOf(0f, 0.55f, 1f),
                android.graphics.Shader.TileMode.CLAMP
            )
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            // Stage floor — edge-to-edge for baroque close-up
            paint.color = Color.argb(78, 200, 165, 85)
            canvas.drawOval(w * 0.02f, stageTop, w * 0.98f, h * 1.05f, paint)
            paint.color = Color.argb(140, 24, 16, 8)
            canvas.drawRect(0f, stageTop + 2f, w, h, paint)
            paint.style = Paint.Style.STROKE
            paint.color = Color.argb(90, 240, 200, 130)
            paint.strokeWidth = 1.2f
            canvas.drawLine(w * 0.02f, stageTop, w * 0.98f, stageTop, paint)

            // Stage spots — spread across full width
            paint.style = Paint.Style.FILL
            val sA = (30 + motion * 40f).toInt().coerceIn(30, 75)
            paint.color = Color.argb(sA, 255, 225, 160)
            canvas.drawCircle(w * 0.18f, h * 0.08f, 4f + treble * 2.5f, paint)
            canvas.drawCircle(w * 0.50f, h * 0.04f, 5f + highMid * 3f, paint)
            canvas.drawCircle(w * 0.82f, h * 0.08f, 4f + lowMid * 2.5f, paint)

            // Light cones — wider to cover full stage
            if (motion > 0.05f) {
                paint.color = Color.argb((motion * 18f).toInt().coerceIn(0, 22), 255, 220, 150)
                val cone = android.graphics.Path()
                for (spot in floatArrayOf(0.18f, 0.50f, 0.82f)) {
                    cone.reset()
                    cone.moveTo(w * spot, h * 0.06f)
                    cone.lineTo(w * (spot - 0.08f), stageTop)
                    cone.lineTo(w * (spot + 0.08f), stageTop)
                    cone.close()
                    canvas.drawPath(cone, paint)
                }
            }

            // ═══ Drawing helpers ═══

            // Diverse skin tone palette
            val skinTones = intArrayOf(
                Color.parseColor("#FCDEC0"),  // light
                Color.parseColor("#C68642"),  // medium brown
                Color.parseColor("#8D5524"),  // dark brown
                Color.parseColor("#E0AC69"),  // golden
                Color.parseColor("#503335"),  // deep brown
                Color.parseColor("#D4A574"),  // olive
                Color.parseColor("#A0522D")   // sienna
            )

            fun drawHead(hx: Float, hy: Float, r: Float, skinColor: Int = skinTones[0]) {
                paint.style = Paint.Style.FILL
                paint.color = skinColor
                canvas.drawCircle(hx, hy, r, paint)
                paint.color = Color.argb(45, 20, 12, 8)
                canvas.drawCircle(hx, hy + r * 0.18f, r * 0.82f, paint)
            }

            fun drawTorso(bx: Float, top: Float, bw: Float, bh: Float, color: Int) {
                paint.style = Paint.Style.FILL
                paint.color = color
                canvas.drawRoundRect(bx - bw / 2f, top, bx + bw / 2f, top + bh, bw * 0.28f, bw * 0.28f, paint)
            }

            fun drawLimb(sx: Float, sy: Float, ex: Float, ey: Float, hx: Float, hy: Float, width: Float, color: Int) {
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeWidth = width
                paint.color = color
                canvas.drawLine(sx, sy, ex, ey, paint)
                canvas.drawLine(ex, ey, hx, hy, paint)
            }

            // ═══ Musicians ═══
            val headR = u * 1.6f
            val bodyW = u * 2.8f
            val bodyH = u * 8f
            val armW = u * 0.18f + 1.2f  // arm stroke width

            fun drawViolinist(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5f
                val shoulderY = cy - u * 2.4f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f

                // Gentle sway tied to energy
                val sway = kotlin.math.sin((tSlow + seed).toDouble()).toFloat() * e * u * 0.5f
                drawHead(cx + sway * 0.3f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + sway * 0.12f, torsoTop, bodyW, bodyH, dress)

                // Violin body tucked under chin, left side
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#A96A2A")
                val vl = cx - u * 0.5f
                val vt = cy - u * 1.8f
                canvas.drawOval(vl, vt, vl + u * 2.8f, vt + u * 2.2f, paint)
                // Strings
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.4f
                paint.color = Color.parseColor("#DDD0B0")
                canvas.drawLine(vl + u * 0.7f, vt + u * 0.3f, vl + u * 2.1f, vt + u * 1.9f, paint)

                // Left arm: neck hand — slight vibrato shift
                val vibrato = kotlin.math.sin((tMed * 2.4f + seed * 3f).toDouble()).toFloat() * e * u * 0.4f
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 0.2f, cy - u * 1.4f,
                    cx + u * 0.8f + vibrato * 0.3f, cy - u * 1.6f - vibrato,
                    armW, skinColor
                )

                // Right arm: BOW arm — clear sweeping motion
                // The bow hand moves in an arc; bow angle rotates with it.
                val bowPhase = kotlin.math.sin((tMed + seed * 1.7f).toDouble()).toFloat()
                val bowSwing = bowPhase * e * (u * 1.2f + e * u * 2.5f) // visible motion
                val elbowR_x = shoulderR + u * 0.8f
                val elbowR_y = cy - u * 0.6f + bowSwing * 0.3f
                val handR_x = cx + u * 2f
                val handR_y = cy + u * 0.2f + bowSwing * 0.5f
                drawLimb(shoulderR, shoulderY, elbowR_x, elbowR_y, handR_x, handR_y, armW, skinColor)

                // Bow stick
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#E0D0B0")
                val bowTip_x = cx - u * 1.2f
                val bowTip_y = cy - u * 1.2f + bowSwing * 0.4f
                canvas.drawLine(handR_x, handR_y, bowTip_x, bowTip_y, paint)

            }

            fun drawCellist(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 5.5f
                val shoulderY = cy - u * 2.8f
                val shoulderL = cx - u * 1.2f
                val shoulderR = cx + u * 1.2f

                val sway = kotlin.math.sin((tSlow * 0.8f + seed).toDouble()).toFloat() * e * u * 0.4f
                drawHead(cx + sway * 0.25f, torsoTop - headR * 1.1f, headR, skinColor)
                drawTorso(cx + sway * 0.1f, torsoTop, bodyW * 1.05f, bodyH * 0.95f, dress)

                // Cello body — between knees
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#8E5524")
                canvas.drawRoundRect(
                    cx - u * 1.6f, cy - u * 1.2f,
                    cx + u * 1.6f, cy + u * 4.5f,
                    u * 0.8f, u * 0.8f, paint
                )
                // Neck
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#7A4820")
                canvas.drawLine(cx, cy - u * 3.8f, cx, cy + u * 5f, paint)

                // Left arm: fingering the neck — vibrato motion
                val vib = kotlin.math.sin((tMed * 2f + seed * 2.5f).toDouble()).toFloat() * e * u * 0.5f
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 0.3f, cy - u * 0.5f,
                    cx + u * 0.2f, cy + u * 0.6f - vib,
                    armW, skinColor
                )

                // Right arm: bow across strings — visible sweep
                val bowPhase = kotlin.math.sin((tMed * 0.9f + seed * 2.1f).toDouble()).toFloat()
                val bowSwing = bowPhase * e * (u * 1f + e * u * 2f)
                val eR_x = shoulderR + u * 1f
                val eR_y = cy - u * 0.3f + bowSwing * 0.25f
                val hR_x = cx + u * 1.5f
                val hR_y = cy + u * 0.8f + bowSwing * 0.4f
                drawLimb(shoulderR, shoulderY, eR_x, eR_y, hR_x, hR_y, armW, skinColor)

                // Bow
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1.2f
                paint.color = Color.parseColor("#DDD0B5")
                canvas.drawLine(
                    cx - u * 1.8f, cy + u * 0.5f + bowSwing * 0.3f,
                    cx + u * 2.2f, cy - u * 0.4f - bowSwing * 0.25f,
                    paint
                )
            }

            fun drawWoodwind(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 4.5f
                val shoulderY = cy - u * 2.2f
                val shoulderL = cx - u * 1.1f
                val shoulderR = cx + u * 1.1f

                // Slight rhythmic lean
                val lean = kotlin.math.sin((tSlow * 1.1f + seed).toDouble()).toFloat() * e * u * 0.3f
                drawHead(cx + lean * 0.2f, torsoTop - headR * 1.12f, headR, skinColor)
                drawTorso(cx + lean * 0.1f, torsoTop, bodyW * 0.95f, bodyH * 0.9f, dress)

                // Clarinet: angled down from mouth
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = u * 0.3f
                paint.color = Color.parseColor("#1A1A1A")
                val clTop_x = cx + u * 0.1f
                val clTop_y = cy - u * 2.8f
                val clBot_x = cx + u * 0.6f
                val clBot_y = cy + u * 2.2f
                canvas.drawLine(clTop_x, clTop_y, clBot_x, clBot_y, paint)
                // Keys
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#C8A848")
                for (k in 1..4) {
                    val t = k / 5f
                    val kx = clTop_x + (clBot_x - clTop_x) * t + u * 0.15f
                    val ky = clTop_y + (clBot_y - clTop_y) * t
                    canvas.drawCircle(kx, ky, u * 0.12f, paint)
                }

                // Finger movement: both hands flex on keys, reactive to treble
                val fingerPhase = kotlin.math.sin((tMed * 1.6f + seed * 1.3f).toDouble()).toFloat()
                val fingerMove = fingerPhase * e * u * 0.6f

                // Left hand: upper half of instrument
                drawLimb(
                    shoulderL, shoulderY,
                    cx - u * 0.5f, cy - u * 1.2f + fingerMove * 0.2f,
                    cx + u * 0.15f, cy - u * 1.5f + fingerMove,
                    armW, skinColor
                )
                // Right hand: lower half
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 0.6f, cy - u * 0.4f - fingerMove * 0.15f,
                    cx + u * 0.4f, cy + u * 0.5f - fingerMove * 0.5f,
                    armW, skinColor
                )
            }

            fun drawHornPlayer(cx: Float, cy: Float, energy: Float, dress: Int, skinColor: Int, seed: Float) {
                val e = if (active) energy.coerceIn(0f, 1f) else 0f
                val torsoTop = cy - u * 4.5f
                val shoulderY = cy - u * 2.2f
                val shoulderL = cx - u * 1.1f
                val shoulderR = cx + u * 1.1f
                val headY = torsoTop - headR * 1.1f
                val mouthY = headY + headR * 0.5f  // mouth is lower half of head

                drawHead(cx, headY, headR, skinColor)
                drawTorso(cx, torsoTop, bodyW * 0.97f, bodyH * 0.9f, dress)

                // French horn: mouthpiece at face, bell at lap held by right hand
                val bellCx = cx + u * 1.8f
                val bellCy = cy + u * 0.2f
                val bellR = u * 1.8f + e * u * 0.4f

                // Tubing: from mouth → down to bell (drawn first, behind arms)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = u * 0.22f
                paint.color = Color.parseColor("#D4A84C")
                val tubePath = android.graphics.Path()
                tubePath.moveTo(cx + u * 0.6f, mouthY)  // mouthpiece at face
                tubePath.quadTo(cx + u * 1.8f, cy - u * 1.5f, bellCx - bellR * 0.3f, bellCy - u * 0.5f)
                canvas.drawPath(tubePath, paint)

                // Mouthpiece nub at face
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#E0C050")
                canvas.drawCircle(cx + u * 0.6f, mouthY, u * 0.18f, paint)

                // Bell
                paint.color = Color.parseColor("#C9952E")
                canvas.drawCircle(bellCx, bellCy, bellR, paint)
                // Dark inner bell
                paint.color = Color.parseColor("#3A2A10")
                canvas.drawCircle(bellCx + u * 0.2f, bellCy, bellR * 0.6f, paint)

                // Left arm: valve hand on tubing — visible finger action
                val valvePhase = kotlin.math.sin((tMed * 1.8f + seed * 1.4f).toDouble()).toFloat()
                val valveMove = valvePhase * e * u * 0.5f
                drawLimb(
                    shoulderL, shoulderY,
                    cx + u * 0.3f, cy - u * 0.8f + valveMove * 0.2f,
                    cx + u * 0.8f, cy - u * 0.5f + valveMove,
                    armW, skinColor
                )
                // Right arm: supports bell from below
                drawLimb(
                    shoulderR, shoulderY,
                    cx + u * 1.4f, cy - u * 0.4f,
                    bellCx - u * 0.3f, bellCy + u * 0.8f,
                    armW, skinColor
                )
            }

            fun drawConductor(cx: Float, cy: Float, skinColor: Int) {
                val e = motion
                val torsoTop = cy - u * 5.5f
                val shoulderY = cy - u * 2.8f
                val shoulderL = cx - u * 1.3f
                val shoulderR = cx + u * 1.3f

                val sway = kotlin.math.sin((tSlow * 0.7f).toDouble()).toFloat() * e * u * 0.4f
                drawHead(cx + sway * 0.3f, torsoTop - headR * 1.15f, headR * 1.06f, skinColor)
                drawTorso(cx + sway * 0.12f, torsoTop, bodyW * 1.1f, bodyH * 1.05f, Color.parseColor("#1A1F2A"))

                // Conducting gesture: smooth figure-8 that grows with volume
                val bx = kotlin.math.sin((tSlow * 1.1f).toDouble()).toFloat() * e * (u * 0.8f + e * u * 2.5f)
                val by = kotlin.math.cos((tSlow * 2.2f).toDouble()).toFloat() * e * (u * 0.5f + e * u * 1.8f)

                // Left arm: cue hand
                drawLimb(
                    shoulderL, shoulderY,
                    shoulderL - u * 1f - bx * 0.2f, shoulderY + u * 1.2f - by * 0.25f,
                    shoulderL - u * 1.8f - bx * 0.35f, shoulderY + u * 2.5f - by * 0.4f,
                    armW * 1.05f, skinColor
                )

                // Right arm: baton
                val bhX = shoulderR + u * 1.5f + bx * 0.4f
                val bhY = shoulderY + u * 0.8f - by * 0.5f
                drawLimb(
                    shoulderR, shoulderY,
                    shoulderR + u * 0.8f + bx * 0.2f, shoulderY + u * 0.4f - by * 0.3f,
                    bhX, bhY,
                    armW * 1.05f, skinColor
                )
                // Baton stick
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeWidth = 1f
                paint.color = Color.parseColor("#FFF3D6")
                canvas.drawLine(bhX, bhY, bhX + u * 2f, bhY - u * 1.5f - by * 0.3f, paint)
            }

            // ═══ Place musicians — baroque close seating, edge-to-edge ═══
            // 7 musicians packed tightly across the full frame width
            // Slight Y offsets give depth (front/back row feel)
            drawViolinist(w * 0.06f, stageCenterY + u * 0.3f, highMid, Color.parseColor("#24324A"), skinTones[0], 0f)
            drawViolinist(w * 0.19f, stageCenterY + u * 1.0f, treble, Color.parseColor("#2D1F34"), skinTones[3], 1.3f)
            drawCellist(w * 0.33f, stageCenterY + u * 1.8f, bass, Color.parseColor("#2A2434"), skinTones[5], 0.7f)
            drawConductor(w * 0.50f, stageCenterY - u * 0.4f, skinTones[1])
            drawWoodwind(w * 0.67f, stageCenterY + u * 0.6f, treble, Color.parseColor("#213247"), skinTones[4], 2.1f)
            drawHornPlayer(w * 0.81f, stageCenterY + u * 1.4f, lowMid, Color.parseColor("#342521"), skinTones[6], 3.4f)
            drawCellist(w * 0.94f, stageCenterY + u * 2.0f, bass * 0.8f + lowMid * 0.2f, Color.parseColor("#2B1F22"), skinTones[2], 4.0f)
        }

        private fun drawPulseRing(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(w, h) * 0.45f
            val avgLevel = bandEnergy(0, barCount - 1)
            val bass = bandEnergy(0, 5)
            val innerRadius = maxR * (0.16f + avgLevel * 0.12f)

            paint.style = Paint.Style.STROKE
            paint.strokeCap = Paint.Cap.ROUND
            for (i in 0 until barCount) {
                val pos = i.toFloat() / (barCount - 1).coerceAtLeast(1)
                val sample = measuredAudioAt(pos, lowCut = 0.76f, highBoost = 1.24f)
                val angle = (i.toFloat() / barCount) * 360f
                val spokeLength = maxR * (0.15f + sample * 1.25f)
                val rad = Math.toRadians(angle.toDouble())
                val x1 = cx + innerRadius * kotlin.math.cos(rad).toFloat()
                val y1 = cy + innerRadius * kotlin.math.sin(rad).toFloat()
                val x2 = cx + (innerRadius + spokeLength) * kotlin.math.cos(rad).toFloat()
                val y2 = cy + (innerRadius + spokeLength) * kotlin.math.sin(rad).toFloat()
                paint.strokeWidth = 2.2f + sample * 4.8f
                paint.color = neonColors[i % neonColors.size]
                paint.alpha = (90 + sample * 165).toInt().coerceIn(90, 255)
                paint.setShadowLayer(12f + sample * 12f, 0f, 0f, paint.color)
                canvas.drawLine(x1, y1, x2, y2, paint)

                paint.style = Paint.Style.FILL
                paint.alpha = (110 + sample * 120).toInt().coerceIn(90, 255)
                canvas.drawCircle(x2, y2, 3.2f + sample * 6.6f, paint)
                paint.style = Paint.Style.STROKE
            }

            paint.setShadowLayer(0f, 0f, 0f, 0)
            paint.style = Paint.Style.STROKE
            paint.color = Color.parseColor("#FFFFFF")
            paint.strokeWidth = 2.5f + avgLevel * 2.6f
            paint.alpha = 85
            canvas.drawCircle(cx, cy, innerRadius * (1.0f + bass * 0.18f), paint)
            paint.color = Color.parseColor("#FF4DFF")
            paint.alpha = 60
            paint.strokeWidth = 1.6f + avgLevel * 2.2f
            canvas.drawCircle(cx, cy, innerRadius + maxR * (0.22f + avgLevel * 0.1f), paint)
        }

        private fun drawSpectrum(canvas: Canvas, w: Float, h: Float) {
            val gap = 1f
            val barWidth = (w - gap * (barCount - 1)) / barCount
            paint.style = Paint.Style.FILL
            for (i in 0 until barCount) {
                val barH = barHeights[i] * h * 0.9f
                val x = i * (barWidth + gap)
                // Gradient across spectrum
                val fraction = i.toFloat() / barCount
                val colorIdx = (fraction * (spectrumColors.size - 1)).toInt()
                    .coerceIn(0, spectrumColors.size - 2)
                val mix = (fraction * (spectrumColors.size - 1)) - colorIdx
                paint.color = blendColors(spectrumColors[colorIdx], spectrumColors[colorIdx + 1], mix)
                paint.alpha = (barHeights[i] * 230).toInt().coerceIn(100, 230)
                paint.setShadowLayer(3f, 0f, 0f, paint.color)
                // Mirror: bars grow from center
                val halfBar = barH / 2f
                val cy = h / 2f
                canvas.drawRoundRect(x, cy - halfBar, x + barWidth, cy + halfBar, 2f, 2f, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)
        }

        /**
         * Meditative theme: slow-breathing concentric rings that expand and
         * contract gently, overlaid with drifting luminous particles.
         * Calm indigo / lavender / soft gold palette.
         */
        private fun drawMeditative(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(w, h) * 0.48f
            val breathPhase = (frameCount % 180) / 180f
            val breath = (kotlin.math.sin(breathPhase * Math.PI * 2).toFloat() + 1f) / 2f
            val avgLevel = bandEnergy(0, barCount - 1)
            val bass = bandEnergy(0, 6)
            val mid = bandEnergy(8, 18)
            val treble = bandEnergy(20, 31)

            paint.style = Paint.Style.STROKE
            val ringCount = 5
            for (r in 0 until ringCount) {
                val baseR = maxR * (0.18f + r * 0.16f)
                val phase = (frameCount % 180 + r * 30) / 180f
                val ringBreath = (kotlin.math.sin(phase * Math.PI * 2).toFloat() + 1f) / 2f
                val ringAudio = measuredAudioAt((0.14f + r.toFloat() / ringCount * 0.72f).coerceIn(0f, 1f), lowCut = 0.9f, highBoost = 1.18f)
                val radius = baseR + ringBreath * maxR * 0.06f + ringAudio * maxR * 0.13f
                paint.strokeWidth = 1.4f + ringBreath * 1.2f + ringAudio * 3.4f
                val alpha = (55 + ringBreath * 50 + ringAudio * 90).toInt().coerceIn(55, 220)
                val color = blendColors(
                    Color.parseColor("#4B0082"),
                    Color.parseColor("#B794F4"),
                    (ringBreath * 0.45f + ringAudio * 0.55f).coerceIn(0f, 1f)
                )
                paint.color = color
                paint.alpha = alpha
                canvas.drawCircle(cx, cy, radius, paint)
            }

            paint.style = Paint.Style.FILL
            val particleColors = intArrayOf(
                Color.parseColor("#7B68EE"),
                Color.parseColor("#DDA0DD"),
                Color.parseColor("#FFD700"),
                Color.parseColor("#E6E6FA"),
                Color.parseColor("#87CEEB")
            )
            for (i in 0 until minOf(barCount, 18)) {
                val sample = measuredAudioAt(i.toFloat() / (barCount - 1).coerceAtLeast(1), lowCut = 0.9f, highBoost = 1.2f)
                val angle = (i * 20f + frameCount * (0.16f + sample * 0.5f)) % 360f
                val dist = maxR * (0.12f + sample * 0.74f) + breath * maxR * 0.05f
                val rad = Math.toRadians(angle.toDouble())
                val px = cx + dist * kotlin.math.cos(rad).toFloat()
                val py = cy + dist * kotlin.math.sin(rad).toFloat()
                val size = 2.4f + sample * 7.2f
                paint.color = particleColors[i % particleColors.size]
                paint.alpha = (85 + sample * 155).toInt().coerceIn(60, 235)
                canvas.drawCircle(px, py, size, paint)
                paint.alpha = (paint.alpha * 0.28f).toInt().coerceIn(20, 100)
                canvas.drawCircle(px, py, size * 3.2f, paint)
            }

            paint.style = Paint.Style.FILL
            val orbR = 8f + breath * 5f + avgLevel * 13f
            paint.color = Color.parseColor("#DDA0DD")
            paint.alpha = (105 + breath * 55 + mid * 70).toInt().coerceIn(90, 240)
            canvas.drawCircle(cx, cy, orbR * (2.6f + bass * 0.5f), paint)
            paint.color = Color.parseColor("#FFD9FF")
            paint.alpha = (170 + avgLevel * 65 + treble * 30).toInt().coerceIn(150, 255)
            canvas.drawCircle(cx, cy, orbR * (1f + treble * 0.12f), paint)
        }

        /**
         * TRON (1982) Theme — Retro neon grid with light cycle wall made of
         * spectrum-analyzer bricks, Bit companion as tweeter visualizer, and
         * a Recognizer hovering in the background. Authentic 80s film aesthetic
         * with cyan/orange neon glow on pure black.
         */
        /**
         * TRON (1982) Theme — The Grid comes alive with music.
         *
         * Immersive neon world: perspective grid floor, two light cycles
         * racing and leaving spectrum-analyzer trail walls, a Recognizer
         * patrolling overhead with bass-reactive searchlight, Tron programs
         * throwing identity discs that orbit with treble energy, and Bit
         * companion morphing between YES/NO states.
         *
         * Every visual element is driven by audio frequency bands.
         * When silent, the Grid goes dark — only dim outlines remain.
         */
        private fun drawTron(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val t = frameCount * 0.035f
            val bass = bandEnergy(0, 5)
            val lowMid = bandEnergy(6, 13)
            val highMid = bandEnergy(14, 22)
            val treble = bandEnergy(23, 31)
            val avg = bandEnergy(0, barCount - 1)
            val active = avg > 0.03f

            val cyan = Color.parseColor("#00DFFF")
            val cyanDim = Color.parseColor("#004466")
            val orange = Color.parseColor("#FF6A00")
            val orangeDim = Color.parseColor("#662A00")
            val white = Color.WHITE

            // ── Void background with subtle bass pulse ──
            paint.style = Paint.Style.FILL
            val bgPulse = if (active) (bass * 12f).toInt().coerceIn(0, 15) else 0
            paint.color = Color.rgb(bgPulse, bgPulse / 2, bgPulse)
            canvas.drawRect(0f, 0f, w, h, paint)

            // ── PERSPECTIVE GRID FLOOR ──
            // Grid lines pulse with bass — the floor itself becomes a visualizer
            val horizon = h * 0.38f
            val vanishX = cx
            paint.style = Paint.Style.STROKE

            // Horizontal grid lines with bass-reactive wave distortion
            val hLines = 16
            for (i in 1..hLines) {
                val prog = i.toFloat() / hLines
                val yBase = horizon + (h - horizon) * prog
                val squeeze = prog  // 0 at horizon, 1 at bottom
                val halfW = w * 0.58f * squeeze
                val leftX = vanishX - halfW
                val rightX = vanishX + halfW

                // Bass makes grid lines wave
                val wave = if (active) bass * 3f * kotlin.math.sin((prog * 8f + t * 1.2f).toDouble()).toFloat() else 0f
                paint.strokeWidth = 0.5f + prog * 0.8f
                paint.color = cyan
                paint.alpha = (10 + 50 * prog * (0.3f + avg * 0.7f)).toInt().coerceIn(0, 80)
                paint.setShadowLayer(2f * prog + bass * 3f * prog, 0f, 0f, cyanDim)
                canvas.drawLine(leftX, yBase + wave, rightX, yBase - wave * 0.5f, paint)
            }

            // Vertical converging lines — treble energy makes them brighter
            val vLines = 18
            for (i in -vLines / 2..vLines / 2) {
                val botX = cx + i * w * 0.072f
                val brightness = (1f - kotlin.math.abs(i.toFloat()) / (vLines / 2f))
                paint.strokeWidth = 0.4f + brightness * 0.4f
                paint.color = cyan
                paint.alpha = (8 + 25 * brightness * (0.2f + treble * 0.8f)).toInt().coerceIn(0, 55)
                paint.setShadowLayer(1f, 0f, 0f, cyanDim)
                canvas.drawLine(vanishX + i * 0.8f, horizon, botX, h, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── MCP CONE — rotating wall of colored blocks (Tron arcade game) ──
            // A conical shield of colored bricks that rotate slowly. Each brick
            // is an audio bin — lit bricks pulse with frequency energy, creating
            // a Breakout-style spectrum visualizer shaped like the MCP cone.
            val mcpCx = cx + w * 0.18f
            val mcpTopY = h * 0.02f
            val mcpBotY = h * 0.38f
            val mcpH = mcpBotY - mcpTopY
            val mcpRows = 10
            val mcpColsPerRow = 12
            val mcpRotation = t * 0.4f  // slow rotation

            // MCP face glow at apex (the humanoid face in the cone)
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF2200")
            paint.alpha = (40 + avg * 80).toInt().coerceIn(40, 120)
            paint.setShadowLayer(12f + avg * 10f, 0f, 0f, Color.parseColor("#FF2200"))
            canvas.drawCircle(mcpCx, mcpTopY + mcpH * 0.08f, h * 0.035f + avg * h * 0.015f, paint)

            // Draw the cone blocks — rows get wider toward the bottom (cone shape)
            val mcpBlockColors = intArrayOf(
                Color.parseColor("#FF0040"), Color.parseColor("#FF6600"),
                Color.parseColor("#FFCC00"), Color.parseColor("#00FF66"),
                Color.parseColor("#00CCFF"), Color.parseColor("#6644FF"),
                Color.parseColor("#FF00CC"), Color.parseColor("#FF4400"),
                Color.parseColor("#44FFAA"), Color.parseColor("#FF8800")
            )

            for (row in 0 until mcpRows) {
                val rowProg = (row + 1f) / mcpRows
                val rowY = mcpTopY + mcpH * rowProg
                val rowHalfW = w * 0.04f + w * 0.22f * rowProg  // widens toward bottom
                val brickH = mcpH / mcpRows * 0.85f
                val brickW = (rowHalfW * 2f) / mcpColsPerRow * 0.9f

                for (col in 0 until mcpColsPerRow) {
                    // Rotate column index for spinning effect
                    val rotatedCol = ((col + (mcpRotation * mcpColsPerRow / (2f * Math.PI.toFloat())).toInt()) % mcpColsPerRow + mcpColsPerRow) % mcpColsPerRow
                    val colProg = (col.toFloat() / mcpColsPerRow) - 0.5f
                    val bx = mcpCx + colProg * rowHalfW * 2f

                    // Map to audio bin
                    val binIdx = ((row * mcpColsPerRow + rotatedCol) * barCount / (mcpRows * mcpColsPerRow)).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]

                    if (energy > 0.08f) {
                        // Lit block — color from palette, brightness from energy
                        paint.style = Paint.Style.FILL
                        paint.color = mcpBlockColors[row % mcpBlockColors.size]
                        paint.alpha = (80 + energy * 175).toInt().coerceIn(80, 255)
                        paint.setShadowLayer(2f + energy * 5f, 0f, 0f, mcpBlockColors[row % mcpBlockColors.size])
                        canvas.drawRect(bx, rowY - brickH, bx + brickW, rowY, paint)
                    } else {
                        // Dim outline block
                        paint.style = Paint.Style.STROKE
                        paint.strokeWidth = 0.4f
                        paint.color = Color.parseColor("#220808")
                        paint.alpha = 30
                        paint.setShadowLayer(0f, 0f, 0f, 0)
                        canvas.drawRect(bx, rowY - brickH, bx + brickW, rowY, paint)
                    }
                }
            }

            // Red glow base of MCP cone
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FF2200")
            paint.alpha = (15 + bass * 30).toInt().coerceIn(15, 45)
            paint.setShadowLayer(8f, 0f, 0f, Color.parseColor("#FF2200"))
            canvas.drawRect(mcpCx - w * 0.24f, mcpBotY, mcpCx + w * 0.24f, mcpBotY + 2f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── LIGHT CYCLES — two cycles racing, leaving spectrum walls ──

            // Cyan cycle (left side) — position scrolls with time
            val cycle1X = (w * 0.15f + ((t * 12f) % (w * 0.35f))).coerceIn(w * 0.05f, w * 0.48f)
            val cycle1Y = h * 0.78f
            val cycleH = h * 0.045f

            fun drawLightCycle(px: Float, py: Float, color: Int, dimColor: Int, facing: Float) {
                val cw = cycleH * 2.2f
                val ch = cycleH
                // Cycle body — sleek wedge
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#0A0A14")
                val cp = android.graphics.Path()
                cp.moveTo(px + cw * 0.6f * facing, py)
                cp.lineTo(px + cw * 0.1f * facing, py - ch * 0.7f)
                cp.lineTo(px - cw * 0.5f * facing, py - ch * 0.3f)
                cp.lineTo(px - cw * 0.5f * facing, py + ch * 0.15f)
                cp.close()
                canvas.drawPath(cp, paint)
                // Neon circuit lines on cycle
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = color
                paint.alpha = (160 + avg * 80).toInt().coerceIn(160, 240)
                paint.setShadowLayer(5f, 0f, 0f, color)
                canvas.drawPath(cp, paint)
                // Wheel glow
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.alpha = (180 + avg * 75).toInt().coerceIn(180, 255)
                canvas.drawCircle(px + cw * 0.35f * facing, py, ch * 0.2f, paint)
                canvas.drawCircle(px - cw * 0.35f * facing, py, ch * 0.18f, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)
            }

            drawLightCycle(cycle1X, cycle1Y, cyan, cyanDim, 1f)

            // Orange cycle (right side, going opposite direction)
            val cycle2X = (w * 0.85f - ((t * 10f) % (w * 0.35f))).coerceIn(w * 0.52f, w * 0.95f)
            val cycle2Y = h * 0.82f
            drawLightCycle(cycle2X, cycle2Y, orange, orangeDim, -1f)

            // ── LIGHT CYCLE TRAIL WALLS (spectrum analyzer) ──
            // Cyan trail wall — behind cyan cycle
            val wallCols = 16
            val trailH = h * 0.28f
            val trailBot = cycle1Y + cycleH * 0.15f
            val trailTop = trailBot - trailH
            val trailLeft = w * 0.02f
            val trailRight = cycle1X - cycleH
            if (trailRight > trailLeft + 5f) {
                val colW = (trailRight - trailLeft) / wallCols
                val brickRows = 8
                val brickH = trailH / brickRows
                for (col in 0 until wallCols) {
                    val binIdx = (col * barCount / wallCols).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]
                    val litRows = (energy * brickRows).toInt().coerceIn(0, brickRows)
                    val bx = trailLeft + col * colW

                    for (row in 0 until brickRows) {
                        val by = trailBot - (row + 1) * brickH
                        if (row < litRows) {
                            val rowRatio = row.toFloat() / brickRows
                            paint.style = Paint.Style.FILL
                            paint.color = when {
                                rowRatio < 0.4f -> cyan
                                rowRatio < 0.75f -> Color.parseColor("#55EEFF")
                                else -> white
                            }
                            paint.alpha = (100 + energy * 155).toInt().coerceIn(100, 255)
                            paint.setShadowLayer(2f + energy * 4f, 0f, 0f, cyan)
                            canvas.drawRect(bx + 0.5f, by + 0.5f, bx + colW - 0.5f, by + brickH - 0.5f, paint)
                        } else {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 0.3f
                            paint.color = cyanDim
                            paint.alpha = 12
                            paint.setShadowLayer(0f, 0f, 0f, 0)
                            canvas.drawRect(bx + 0.5f, by + 0.5f, bx + colW - 0.5f, by + brickH - 0.5f, paint)
                        }
                    }
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Orange trail wall — behind orange cycle
            val trailBot2 = cycle2Y + cycleH * 0.15f
            val trailTop2 = trailBot2 - trailH * 0.9f
            val trailLeft2 = cycle2X + cycleH
            val trailRight2 = w * 0.98f
            if (trailRight2 > trailLeft2 + 5f) {
                val colW2 = (trailRight2 - trailLeft2) / wallCols
                val brickRows2 = 8
                val brickH2 = (trailBot2 - trailTop2) / brickRows2
                for (col in 0 until wallCols) {
                    val binIdx = ((wallCols - 1 - col) * barCount / wallCols).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]
                    val litRows = (energy * brickRows2).toInt().coerceIn(0, brickRows2)
                    val bx = trailLeft2 + col * colW2

                    for (row in 0 until brickRows2) {
                        val by = trailBot2 - (row + 1) * brickH2
                        if (row < litRows) {
                            val rowRatio = row.toFloat() / brickRows2
                            paint.style = Paint.Style.FILL
                            paint.color = when {
                                rowRatio < 0.4f -> orange
                                rowRatio < 0.75f -> Color.parseColor("#FFB855")
                                else -> white
                            }
                            paint.alpha = (100 + energy * 155).toInt().coerceIn(100, 255)
                            paint.setShadowLayer(2f + energy * 4f, 0f, 0f, orange)
                            canvas.drawRect(bx + 0.5f, by + 0.5f, bx + colW2 - 0.5f, by + brickH2 - 0.5f, paint)
                        } else {
                            paint.style = Paint.Style.STROKE
                            paint.strokeWidth = 0.3f
                            paint.color = orangeDim
                            paint.alpha = 12
                            paint.setShadowLayer(0f, 0f, 0f, 0)
                            canvas.drawRect(bx + 0.5f, by + 0.5f, bx + colW2 - 0.5f, by + brickH2 - 0.5f, paint)
                        }
                    }
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Neon trail lines at base of walls
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 2f
            paint.color = cyan
            paint.alpha = (100 + avg * 120).toInt().coerceIn(100, 220)
            paint.setShadowLayer(6f, 0f, 0f, cyan)
            canvas.drawLine(trailLeft, trailBot, cycle1X - cycleH * 0.5f, trailBot, paint)
            paint.color = orange
            paint.setShadowLayer(6f, 0f, 0f, orange)
            canvas.drawLine(cycle2X + cycleH * 0.5f, trailBot2, trailRight2, trailBot2, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── TRON PROGRAMS with IDENTITY DISCS (throw & return) ──
            // Discs fly out toward the opponent and boomerang back.
            // Throw distance scales with energy; disc spins as it travels.

            fun drawProgram(px: Float, py: Float, color: Int, throwDir: Float,
                            throwPhase: Float, discEnergy: Float) {
                val u = h * 0.012f
                val headY = py - u * 8f
                // Helmet
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#0A0A14")
                canvas.drawCircle(px, headY, u * 2f, paint)
                // Visor glow
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1.2f
                paint.color = color
                paint.alpha = (120 + discEnergy * 120).toInt().coerceIn(120, 240)
                paint.setShadowLayer(4f, 0f, 0f, color)
                canvas.drawArc(
                    px - u * 1.8f, headY - u * 0.8f,
                    px + u * 1.8f, headY + u * 1f,
                    200f, 140f, false, paint
                )
                // Body
                paint.style = Paint.Style.FILL
                paint.color = Color.parseColor("#080812")
                val body = android.graphics.Path()
                body.moveTo(px, headY + u * 1.5f)
                body.lineTo(px - u * 2.5f, py - u * 2f)
                body.lineTo(px - u * 2f, py + u * 2f)
                body.lineTo(px + u * 2f, py + u * 2f)
                body.lineTo(px + u * 2.5f, py - u * 2f)
                body.close()
                canvas.drawPath(body, paint)
                // Circuit lines
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 0.9f
                paint.color = color
                paint.alpha = (60 + discEnergy * 100).toInt().coerceIn(60, 180)
                paint.setShadowLayer(3f, 0f, 0f, color)
                canvas.drawPath(body, paint)
                canvas.drawLine(px, headY + u * 2f, px, py + u * 1f, paint)
                canvas.drawLine(px - u * 1.5f, py - u * 1.5f, px + u * 1.5f, py - u * 1.5f, paint)

                // ── Identity disc: throw and return boomerang ──
                // throwPhase cycles 0→2π. 0→π = flying out, π→2π = returning.
                // Use a sine curve so the disc smoothly arcs outward and back.
                val phase = throwPhase % (2f * Math.PI.toFloat())
                val outFraction = kotlin.math.sin(phase.toDouble()).toFloat().coerceIn(0f, 1f)
                // Max throw distance scales with energy
                val maxDist = w * 0.18f + discEnergy * w * 0.12f
                val throwDist = outFraction * maxDist
                // Disc arcs upward in parabola as it flies
                val arcHeight = outFraction * (1f - outFraction) * h * 0.15f

                val discX = px + throwDir * throwDist
                val discY = py - u * 4f - arcHeight
                val discR = u * 1.4f + discEnergy * u * 0.6f
                val discSpin = t * 12f  // fast spin

                // Disc trail — fading line from hand to disc position
                if (throwDist > u * 2f && discEnergy > 0.05f) {
                    paint.style = Paint.Style.STROKE
                    paint.strokeWidth = 1f
                    paint.color = color
                    paint.alpha = (discEnergy * 60 * outFraction).toInt().coerceIn(0, 80)
                    paint.setShadowLayer(4f, 0f, 0f, color)
                    val trailPath = android.graphics.Path()
                    trailPath.moveTo(px + throwDir * u * 2f, py - u * 4f)
                    trailPath.quadTo(
                        px + throwDir * throwDist * 0.5f, discY - arcHeight * 0.3f,
                        discX, discY
                    )
                    canvas.drawPath(trailPath, paint)
                }

                // Disc glow
                paint.style = Paint.Style.FILL
                paint.color = color
                paint.alpha = (30 + discEnergy * 60).toInt().coerceIn(30, 90)
                paint.setShadowLayer(discR * 2.5f, 0f, 0f, color)
                canvas.drawCircle(discX, discY, discR * 2f, paint)

                // Disc body (rotated ring appearance via two concentric circles)
                paint.color = Color.parseColor("#E0E8F0")
                paint.alpha = (190 + discEnergy * 65).toInt().coerceIn(190, 255)
                paint.setShadowLayer(6f + discEnergy * 8f, 0f, 0f, color)
                canvas.drawCircle(discX, discY, discR, paint)
                // Inner ring
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 1f
                paint.color = color
                paint.alpha = (200 + discEnergy * 55).toInt().coerceIn(200, 255)
                canvas.drawCircle(discX, discY, discR * 0.6f, paint)
                // Spinning cross-hair on disc
                paint.strokeWidth = 0.6f
                paint.alpha = (150 + discEnergy * 80).toInt().coerceIn(150, 230)
                val cs = kotlin.math.cos(discSpin.toDouble()).toFloat()
                val sn = kotlin.math.sin(discSpin.toDouble()).toFloat()
                canvas.drawLine(discX - cs * discR * 0.5f, discY - sn * discR * 0.5f,
                    discX + cs * discR * 0.5f, discY + sn * discR * 0.5f, paint)
                canvas.drawLine(discX + sn * discR * 0.5f, discY - cs * discR * 0.5f,
                    discX - sn * discR * 0.5f, discY + cs * discR * 0.5f, paint)
                paint.setShadowLayer(0f, 0f, 0f, 0)
            }

            // Cyan program — throws disc rightward toward orange
            val prog1Phase = t * 1.8f + highMid * 2f
            drawProgram(cx - w * 0.14f, h * 0.58f, cyan, 1f, prog1Phase, highMid)

            // Orange program — throws disc leftward toward cyan
            val prog2Phase = t * 1.5f + lowMid * 2f + Math.PI.toFloat() // offset so throws alternate
            drawProgram(cx + w * 0.14f, h * 0.60f, orange, -1f, prog2Phase, lowMid)

            // ── BIT COMPANION ──
            // Floats near top-left, morphs YES(spiky yellow)/NO(angular red)/neutral(cyan)
            val bitCx = w * 0.08f
            val bitCy = h * 0.20f
            val bitBaseR = h * 0.05f
            val bitR = bitBaseR * (0.7f + treble * 0.8f)
            val bitBob = kotlin.math.sin((t * 1.5f).toDouble()).toFloat() * h * 0.012f
            val bitSpin = t * 2.5f

            val bitColor = when {
                treble > 0.55f -> Color.parseColor("#FFEE00") // YES — excited
                treble < 0.12f -> Color.parseColor("#FF2200") // NO — quiet
                else -> cyan
            }
            val vertices = when {
                treble > 0.55f -> 12  // spiky star
                treble < 0.12f -> 4   // angular diamond
                else -> 8             // octagon
            }
            val spike = if (treble > 0.55f) 0.6f else if (treble < 0.12f) 0.3f else 0.1f

            // Glow
            paint.style = Paint.Style.FILL
            paint.color = bitColor
            paint.alpha = (20 + treble * 50).toInt().coerceIn(20, 70)
            paint.setShadowLayer(bitR * 1.8f, 0f, 0f, bitColor)
            canvas.drawCircle(bitCx, bitCy + bitBob, bitR * 1.8f, paint)

            // Body
            val bitPath = android.graphics.Path()
            for (v in 0 until vertices) {
                val angle = (v.toFloat() / vertices) * Math.PI.toFloat() * 2f + bitSpin
                val isOuter = v % 2 == 0
                val vr = if (isOuter) bitR * (1f + spike) else bitR * (0.6f + spike * 0.2f)
                val vx = bitCx + kotlin.math.cos(angle.toDouble()).toFloat() * vr
                val vy = bitCy + bitBob + kotlin.math.sin(angle.toDouble()).toFloat() * vr
                if (v == 0) bitPath.moveTo(vx, vy) else bitPath.lineTo(vx, vy)
            }
            bitPath.close()

            paint.style = Paint.Style.FILL
            paint.color = bitColor
            paint.alpha = (100 + treble * 140).toInt().coerceIn(100, 240)
            paint.setShadowLayer(5f, 0f, 0f, bitColor)
            canvas.drawPath(bitPath, paint)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = white
            paint.alpha = (160 + treble * 80).toInt().coerceIn(160, 240)
            canvas.drawPath(bitPath, paint)

            // Bit eye
            paint.style = Paint.Style.FILL
            paint.color = white
            paint.alpha = (200 + treble * 55).toInt().coerceIn(200, 255)
            canvas.drawCircle(bitCx, bitCy + bitBob, bitBaseR * 0.18f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── HUD overlay ──
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = h * 0.028f
            paint.textAlign = Paint.Align.LEFT
            paint.color = cyan
            paint.alpha = (40 + avg * 50).toInt().coerceIn(40, 90)
            canvas.drawText("END OF LINE", 4f, h - 4f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = orange
            paint.alpha = (40 + avg * 50).toInt().coerceIn(40, 90)
            canvas.drawText("GRID %04d".format((frameCount % 10000).toInt()), w - 4f, h - 4f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
        }

        private fun drawBreathe(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val cy = h / 2f
            val maxR = minOf(w, h) * 0.45f

            // Advance breathing timer
            val now = System.currentTimeMillis()
            if (lastBreathFrameTime > 0) {
                breathCycleMs = (breathCycleMs + (now - lastBreathFrameTime)) % breathTotalMs
            }
            lastBreathFrameTime = now

            // Determine phase and progress (0..1) within that phase
            val elapsed = breathCycleMs
            val (phase, progress) = when {
                elapsed < breathInhaleMs -> "INHALE" to (elapsed.toFloat() / breathInhaleMs)
                elapsed < breathInhaleMs + breathHoldMs -> "HOLD" to ((elapsed - breathInhaleMs).toFloat() / breathHoldMs)
                elapsed < breathInhaleMs + breathHoldMs + breathExhaleMs -> "EXHALE" to ((elapsed - breathInhaleMs - breathHoldMs).toFloat() / breathExhaleMs)
                else -> "REST" to ((elapsed - breathInhaleMs - breathHoldMs - breathExhaleMs).toFloat() / breathPauseMs)
            }

            // Circle size based on breath phase — expands on inhale, shrinks on exhale
            val breathSize = when (phase) {
                "INHALE" -> 0.3f + progress * 0.7f       // grows 0.3 → 1.0
                "HOLD"   -> 1.0f                          // full
                "EXHALE" -> 1.0f - progress * 0.7f        // shrinks 1.0 → 0.3
                else     -> 0.3f                           // resting small
            }

            // Use average audio level from barHeights to tint the color
            val avgLevel = barHeights.average().toFloat().coerceIn(0f, 1f)

            // Color palette: quiet → deep teal, loud → warm amber/coral
            val quietColor = Color.parseColor("#2E8B8B")   // teal
            val midColor = Color.parseColor("#5B9EA6")      // lighter teal
            val warmColor = Color.parseColor("#E8A87C")     // peach
            val hotColor = Color.parseColor("#D4726A")       // coral
            val baseColor = when {
                avgLevel < 0.33f -> blendColors(quietColor, midColor, avgLevel / 0.33f)
                avgLevel < 0.66f -> blendColors(midColor, warmColor, (avgLevel - 0.33f) / 0.33f)
                else -> blendColors(warmColor, hotColor, (avgLevel - 0.66f) / 0.34f)
            }

            // Outer soft glow
            val radius = maxR * breathSize
            paint.style = Paint.Style.FILL
            paint.color = baseColor
            paint.alpha = (40 + avgLevel * 50).toInt()
            paint.setShadowLayer(8f, 0f, 0f, baseColor)
            canvas.drawCircle(cx, cy, radius * 1.4f, paint)

            // Main breathing circle
            paint.alpha = (85 + avgLevel * 85).toInt()
            paint.setShadowLayer(6f, 0f, 0f, baseColor)
            canvas.drawCircle(cx, cy, radius, paint)

            // Inner bright core
            val coreColor = blendColors(baseColor, Color.WHITE, 0.45f)
            paint.color = coreColor
            paint.alpha = (130 + avgLevel * 110).toInt().coerceAtMost(240)
            paint.setShadowLayer(4f, 0f, 0f, Color.WHITE)
            canvas.drawCircle(cx, cy, radius * 0.5f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Breathing guide text
            val label = when (phase) {
                "INHALE" -> "breathe in"
                "HOLD"   -> "hold"
                "EXHALE" -> "breathe out"
                else     -> "rest"
            }
            paint.color = Color.WHITE
            paint.alpha = 200
            paint.textSize = 14f * (w / 400f).coerceIn(0.8f, 1.5f)
            paint.textAlign = Paint.Align.CENTER
            paint.style = Paint.Style.FILL
            paint.setShadowLayer(4f, 0f, 0f, baseColor)
            canvas.drawText(label, cx, cy + radius + paint.textSize * 1.6f, paint)
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // Subtle ring pulse that follows the breath
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.5f + avgLevel * 2f
            paint.color = baseColor
            paint.alpha = (40 + breathSize * 60).toInt()
            canvas.drawCircle(cx, cy, radius * 1.15f, paint)
        }

        /**
         * Close Encounters of the Third Kind theme — Massive mothership
         * hovering overhead with individually-illuminated light panels that
         * flash in different colors, reacting to audio frequencies. Inspired
         * by the "dueling tones" climax at Devils Tower where the mothership
         * communicates through musical tones paired with colored lights.
         * Each panel is its own spectrum bin, flashing independently.
         */
        // Per-panel random state for Close Encounters (deterministic per panel index)
        private val cePanelHues = FloatArray(72) { (it * 137.508f) % 360f }
        private val cePanelPhases = FloatArray(72) { (it * 73.13f) % 1f }
        private val cePanelSpeeds = FloatArray(72) { 0.4f + (it * 31.37f % 1f) * 1.6f }

        private fun drawCloseEncounters(canvas: Canvas, w: Float, h: Float) {
            val cx = w / 2f
            val t = frameCount * 0.018f
            val bassEnergy = bandEnergy(0, 5)
            val midEnergy = bandEnergy(9, 18)
            val trebleEnergy = bandEnergy(22, 31)
            val avgLevel = bandEnergy(0, barCount - 1)

            // ── Deep night sky background ──
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#020408")
            canvas.drawRect(0f, 0f, w, h, paint)

            // Subtle stars
            paint.color = Color.WHITE
            for (s in 0 until 20) {
                val sx = (s * 197.37f + 11f) % w
                val sy = (s * 83.19f + 7f) % (h * 0.35f)
                val twinkle = (0.3f + 0.7f * ((kotlin.math.sin((t * cePanelSpeeds[s % 72] + s * 2.1f).toDouble()).toFloat() + 1f) / 2f))
                paint.alpha = (20 + twinkle * 50).toInt().coerceAtMost(80)
                canvas.drawCircle(sx, sy, 0.6f + twinkle * 0.5f, paint)
            }

            // ── Devils Tower silhouette at bottom ──
            val towerPath = android.graphics.Path()
            val towerBase = h * 0.88f
            towerPath.moveTo(0f, h)
            towerPath.lineTo(0f, towerBase)
            towerPath.lineTo(w * 0.15f, towerBase - h * 0.02f)
            towerPath.lineTo(w * 0.25f, towerBase - h * 0.06f)
            towerPath.lineTo(w * 0.35f, h * 0.58f)
            towerPath.lineTo(w * 0.38f, h * 0.54f)
            towerPath.lineTo(w * 0.42f, h * 0.52f) // Mesa top left
            towerPath.lineTo(w * 0.50f, h * 0.50f) // Mesa peak
            towerPath.lineTo(w * 0.58f, h * 0.52f) // Mesa top right
            towerPath.lineTo(w * 0.62f, h * 0.54f)
            towerPath.lineTo(w * 0.65f, h * 0.58f)
            towerPath.lineTo(w * 0.75f, towerBase - h * 0.06f)
            towerPath.lineTo(w * 0.85f, towerBase - h * 0.02f)
            towerPath.lineTo(w, towerBase)
            towerPath.lineTo(w, h)
            towerPath.close()
            paint.color = Color.parseColor("#080808")
            paint.alpha = 255
            canvas.drawPath(towerPath, paint)

            // ── Mothership — massive dome shape ──
            // The ship sits in the upper portion, overwhelming in scale
            val shipCx = cx
            val shipCy = h * 0.22f
            val shipW = w * 0.75f
            val shipH = h * 0.28f
            val shipBottom = shipCy + shipH * 0.5f

            // Ship hull: dark silhouette with slight structure visible
            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#0C0C14")
            // Main dome (upper half ellipse)
            val hullRect = android.graphics.RectF(
                shipCx - shipW / 2f, shipCy - shipH * 0.55f,
                shipCx + shipW / 2f, shipCy + shipH * 0.45f
            )
            canvas.drawOval(hullRect, paint)

            // Slightly lighter rim to define the edge
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 1.2f
            paint.color = Color.parseColor("#1A1A2A")
            paint.alpha = 60
            canvas.drawOval(hullRect, paint)

            // ── Mothership light panels — the spectacle ──
            // Arranged in concentric rings on the belly of the ship.
            // Each panel independently flashes a random color, driven by
            // different frequency bins. This recreates the "city of lights"
            // effect and the dueling-tones communication sequence.
            val panelColors = intArrayOf(
                Color.parseColor("#FF1111"), // Red
                Color.parseColor("#FF6600"), // Orange
                Color.parseColor("#FFEE00"), // Yellow
                Color.parseColor("#00FF44"), // Green
                Color.parseColor("#0088FF"), // Blue
                Color.parseColor("#FFFFFF"), // White
                Color.parseColor("#FF00AA"), // Magenta
                Color.parseColor("#00FFCC")  // Teal
            )

            val rings = 5
            val totalPanels = 48
            var panelIdx = 0

            for (ring in 0 until rings) {
                val ringRatio = (ring + 1f) / (rings + 1f)
                val ringRadiusX = shipW * 0.42f * ringRatio
                val ringRadiusY = shipH * 0.32f * ringRatio
                val panelsInRing = (6 + ring * 3).coerceAtMost(totalPanels - panelIdx)

                for (p in 0 until panelsInRing) {
                    if (panelIdx >= 72) break
                    val angle = (p.toFloat() / panelsInRing) * Math.PI.toFloat() * 2f +
                                ring * 0.4f + t * 0.15f * cePanelSpeeds[panelIdx % 72]
                    val px = shipCx + kotlin.math.cos(angle.toDouble()).toFloat() * ringRadiusX
                    val py = shipCy + kotlin.math.sin(angle.toDouble()).toFloat() * ringRadiusY * 0.6f

                    // Which frequency bin drives this panel
                    val binIdx = (panelIdx * barCount / totalPanels.coerceAtLeast(1)).coerceIn(0, barCount - 1)
                    val energy = barHeights[binIdx]

                    // Panel flashing: each has its own phase and timing
                    val phase = cePanelPhases[panelIdx % 72]
                    val speed = cePanelSpeeds[panelIdx % 72]
                    val flashCycle = kotlin.math.sin((t * speed * 2f + phase * Math.PI.toFloat() * 2f).toDouble()).toFloat()
                    val flashIntensity = ((flashCycle + 1f) / 2f * energy).coerceIn(0f, 1f)

                    // Pick color — shifts with audio and randomized per panel
                    val colorIdx = ((panelIdx + (frameCount / (8 + panelIdx % 12)).toInt()) % panelColors.size)
                    val panelColor = panelColors[colorIdx]

                    // Panel size varies by ring
                    val panelSize = (2.5f + ring * 0.8f) * (w / 400f).coerceIn(0.8f, 1.5f)

                    if (flashIntensity > 0.08f) {
                        paint.style = Paint.Style.FILL
                        paint.color = panelColor
                        paint.alpha = (60 + flashIntensity * 195).toInt().coerceAtMost(255)
                        val glowR = panelSize * (1f + flashIntensity * 1.2f)
                        paint.setShadowLayer(glowR * 2f, 0f, 0f, panelColor)
                        canvas.drawCircle(px, py, glowR, paint)

                        // Bright core
                        paint.color = Color.WHITE
                        paint.alpha = (flashIntensity * 200).toInt().coerceAtMost(240)
                        paint.setShadowLayer(panelSize, 0f, 0f, panelColor)
                        canvas.drawCircle(px, py, panelSize * 0.4f * flashIntensity, paint)
                    } else {
                        // Dim/off panel — barely visible
                        paint.style = Paint.Style.FILL
                        paint.color = Color.parseColor("#111122")
                        paint.alpha = 30
                        paint.setShadowLayer(0f, 0f, 0f, 0)
                        canvas.drawCircle(px, py, panelSize * 0.5f, paint)
                    }
                    panelIdx++
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Central light hatch — bright scanning beam ──
            // Rotating ring of white light around the central opening
            val hatchR = shipW * 0.06f
            val scanAngle = t * 1.5f
            paint.style = Paint.Style.FILL

            // Hatch glow base
            paint.color = Color.WHITE
            paint.alpha = (30 + avgLevel * 60).toInt().coerceAtMost(100)
            paint.setShadowLayer(hatchR * 2f, 0f, 0f, Color.parseColor("#FFFFCC"))
            canvas.drawCircle(shipCx, shipCy, hatchR * 1.8f, paint)

            // Scanning light sweep (the famous rotating beam)
            val sweepArc = 45f
            for (beam in 0 until 3) {
                val beamAngle = scanAngle * 57.3f + beam * 120f
                paint.color = Color.WHITE
                paint.alpha = (40 + avgLevel * 80).toInt().coerceAtMost(140)
                paint.setShadowLayer(hatchR * 1.5f, 0f, 0f, Color.parseColor("#FFFFDD"))
                val arcRect = android.graphics.RectF(
                    shipCx - hatchR * 2.5f, shipCy - hatchR * 2.5f,
                    shipCx + hatchR * 2.5f, shipCy + hatchR * 2.5f
                )
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = 2f + bassEnergy * 2f
                canvas.drawArc(arcRect, beamAngle, sweepArc, false, paint)
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── Downward light beam from mothership ──
            // Dramatic cone of light illuminating Devils Tower
            val beamTop = shipBottom
            val beamBottom = h * 0.52f
            val beamTopW = shipW * 0.12f
            val beamBotW = shipW * 0.35f
            val beamPath = android.graphics.Path()
            beamPath.moveTo(shipCx - beamTopW / 2f, beamTop)
            beamPath.lineTo(shipCx - beamBotW / 2f, beamBottom)
            beamPath.lineTo(shipCx + beamBotW / 2f, beamBottom)
            beamPath.lineTo(shipCx + beamTopW / 2f, beamTop)
            beamPath.close()

            paint.style = Paint.Style.FILL
            paint.color = Color.parseColor("#FFFFEE")
            paint.alpha = (8 + avgLevel * 25).toInt().coerceAtMost(40)
            canvas.drawPath(beamPath, paint)

            // Beam stripe highlights pulsing with mid frequencies
            paint.color = Color.WHITE
            paint.alpha = (5 + midEnergy * 20).toInt().coerceAtMost(30)
            paint.strokeWidth = 1f
            paint.style = Paint.Style.STROKE
            val stripeCount = 5
            for (s in 0 until stripeCount) {
                val progress = (s + 1f) / (stripeCount + 1f)
                val stripeY = beamTop + (beamBottom - beamTop) * progress
                val stripeHalfW = (beamTopW + (beamBotW - beamTopW) * progress) / 2f
                canvas.drawLine(shipCx - stripeHalfW, stripeY, shipCx + stripeHalfW, stripeY, paint)
            }

            // ── "Dueling tones" visualization ──
            // Musical note blocks at the base that light up with the
            // frequency spectrum — represents the communication sequence
            val toneBarCount = 12
            val toneBarW = w * 0.04f
            val toneBarGap = w * 0.015f
            val toneBarMaxH = h * 0.08f
            val toneStartX = cx - (toneBarCount * (toneBarW + toneBarGap) - toneBarGap) / 2f
            val toneY = h * 0.95f

            val toneColors = intArrayOf(
                Color.parseColor("#FF2200"), Color.parseColor("#FF6600"),
                Color.parseColor("#FFCC00"), Color.parseColor("#FFFF00"),
                Color.parseColor("#00FF44"), Color.parseColor("#00FF88"),
                Color.parseColor("#0088FF"), Color.parseColor("#0044FF"),
                Color.parseColor("#6600FF"), Color.parseColor("#AA00FF"),
                Color.parseColor("#FF00AA"), Color.parseColor("#FF0066")
            )

            for (tb in 0 until toneBarCount) {
                val binIdx = (tb * barCount / toneBarCount).coerceIn(0, barCount - 1)
                val energy = barHeights[binIdx]
                val barH = energy * toneBarMaxH
                val barX = toneStartX + tb * (toneBarW + toneBarGap)

                paint.style = Paint.Style.FILL
                paint.color = toneColors[tb % toneColors.size]
                paint.alpha = (60 + energy * 195).toInt().coerceAtMost(255)
                paint.setShadowLayer(3f + energy * 5f, 0f, 0f, toneColors[tb % toneColors.size])
                canvas.drawRect(barX, toneY - barH, barX + toneBarW, toneY, paint)

                // Bright top cap
                if (energy > 0.1f) {
                    paint.color = Color.WHITE
                    paint.alpha = (energy * 180).toInt().coerceAtMost(200)
                    canvas.drawRect(barX, toneY - barH, barX + toneBarW, toneY - barH + 1.5f, paint)
                }
            }
            paint.setShadowLayer(0f, 0f, 0f, 0)

            // ── HUD text ──
            paint.style = Paint.Style.FILL
            paint.typeface = android.graphics.Typeface.MONOSPACE
            paint.textSize = h * 0.030f
            paint.textAlign = Paint.Align.LEFT
            paint.color = Color.parseColor("#FF6600")
            paint.alpha = 70
            canvas.drawText("CE3K SIGNAL", 6f, h * 0.04f, paint)
            paint.textAlign = Paint.Align.RIGHT
            paint.color = Color.parseColor("#FFCC00")
            paint.alpha = 55
            canvas.drawText("TONE SEQ %.0f".format(frameCount.toFloat()), w - 6f, h * 0.04f, paint)
            paint.typeface = android.graphics.Typeface.DEFAULT
        }

        private fun blendColors(c1: Int, c2: Int, ratio: Float): Int {
            val inv = 1f - ratio
            val r = (Color.red(c1) * inv + Color.red(c2) * ratio).toInt()
            val g = (Color.green(c1) * inv + Color.green(c2) * ratio).toInt()
            val b = (Color.blue(c1) * inv + Color.blue(c2) * ratio).toInt()
            return Color.rgb(r, g, b)
        }
    }

    private fun setupFullScreenControlsUI() {
        // Container for controls (Bottom bar)
        fullScreenControlsContainer =
                FrameLayout(context).apply {
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.MATCH_PARENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.BOTTOM }
                    // No background - just floating buttons
                    setPadding(0, 0, 0, 0)
                    visibility = View.GONE // Hidden by default
                    isClickable = true // Consume clicks
                }

        // Media Controls Container (Center)
        fullScreenMediaControls =
                LinearLayout(context).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.CENTER }
                }
        fullScreenControlsContainer.addView(fullScreenMediaControls)

        // Exit Button (Right)
        btnFsExit =
                FontIconView(context).apply {
                    setText(R.string.fa_compress)
                    setTextColor(Color.WHITE)
                    textSize = 24f
                    setPadding(16, 16, 16, 16)
                    layoutParams =
                            FrameLayout.LayoutParams(
                                            FrameLayout.LayoutParams.WRAP_CONTENT,
                                            FrameLayout.LayoutParams.WRAP_CONTENT
                                    )
                                    .apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL }
                    setOnClickListener {
                        (context as? AppCompatActivity)?.onBackPressedDispatcher?.onBackPressed()
                    }
                }
        fullScreenControlsContainer.addView(btnFsExit)

        // Create Media Buttons (reusing logic from mask controls)
        btnFsPrevTrack =
                createMediaButton(R.string.fa_backward_step) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var prevBtn = document.querySelector('.ytp-prev-button') ||
                                  document.querySelector('[aria-label*="previous" i]') ||
                                  document.querySelector('[title*="previous" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-back"]');
                    if (prevBtn) { prevBtn.click(); return; }
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = 0;
                })();
                """.trimIndent(),
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                }

        btnFsPrev =
                createMediaButton(R.string.fa_backward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime -= 10;",
                            "(function(){ if(window.prevStation){ window.prevStation(); } })();"
                    )
                }

        // Single Play/Pause toggle button
        btnFsPlayPause =
                createMediaButton(R.string.fa_play) {
                    if (isFsPlaying) {
                        // Currently playing, so pause
                        DebugLog.d("FullscreenTouch", "Pause clicked, switching to play icon")
                        val targetWebView = getMediaControlWebView()
                        evaluateMediaControlCommand(
                                targetWebView,
                                "document.querySelector('video, audio').pause();",
                                "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                        )
                        btnFsPlayPause.setText(R.string.fa_play)
                        isFsPlaying = false
                    } else {
                        // Currently paused, so play
                        DebugLog.d("FullscreenTouch", "Play clicked, switching to pause icon")
                        val targetWebView = getMediaControlWebView()
                        evaluateMediaControlCommand(
                                targetWebView,
                                "document.querySelector('video, audio').play();",
                                "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
                        )
                        btnFsPlayPause.setText(R.string.fa_pause)
                        isFsPlaying = true
                    }
                }

        // Sync initial state with actual media state
        if (isMediaPlaying) {
            btnFsPlayPause.setText(R.string.fa_pause)
            isFsPlaying = true
        }

        btnFsNext =
                createMediaButton(R.string.fa_forward) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            "document.querySelector('video, audio').currentTime += 10;",
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                }

        btnFsNextTrack =
                createMediaButton(R.string.fa_forward_step) {
                    val targetWebView = getMediaControlWebView()
                    evaluateMediaControlCommand(
                            targetWebView,
                            """
                (function() {
                    var nextBtn = document.querySelector('.ytp-next-button') ||
                                  document.querySelector('[aria-label*="next" i]') ||
                                  document.querySelector('[title*="next" i]') ||
                                  document.querySelector('button[data-testid="control-button-skip-forward"]');
                    if (nextBtn) { nextBtn.click(); return; }
                    var media = document.querySelector('video, audio');
                    if (media) media.currentTime = media.duration;
                })();
                """.trimIndent(),
                            "(function(){ if(window.nextStation){ window.nextStation(); } })();"
                    )
                }

        fullScreenMediaControls.addView(btnFsPrevTrack)
        fullScreenMediaControls.addView(btnFsPrev)
        fullScreenMediaControls.addView(btnFsPlayPause)
        fullScreenMediaControls.addView(btnFsNext)
        fullScreenMediaControls.addView(btnFsNextTrack)
    }

    private fun createMediaButton(iconRes: Int, onClick: () -> Unit): FontIconView {
        return FontIconView(context).apply {
            setText(iconRes)
            setBackgroundResource(R.drawable.nav_button_background)
            setTextColor(Color.WHITE)
            alpha = 0.5f
            textSize = 18f
            gravity = Gravity.CENTER
            setPadding(8, 8, 8, 8)
            layoutParams =
                    LinearLayout.LayoutParams(40, 40).apply {
                        leftMargin = 4
                        rightMargin = 4
                    }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                onClick()
                scheduleMaskedNowPlayingRefresh()
            }
        }
    }

    fun updateMediaState(isPlaying: Boolean) {
        // DebugLog.d("MediaControls", "updateMediaState called: isPlaying=$isPlaying,
        // isScreenMasked=$isScreenMasked")

        // Ignore updates shortly after manual interaction to prevent race conditions
        if (SystemClock.uptimeMillis() - lastMediaInteractionTime < 500) {
            return
        }

        isMediaPlaying = isPlaying
        if (isPlaying) {
            lastMediaPlayingAt = SystemClock.uptimeMillis()
        }
        updatePlaybackWakeLocks()
        post {
            if (isPlaying) {
                // DebugLog.d("MediaControls", "Setting to playing state")
                btnMaskPlay.visibility = View.GONE
                btnMaskPause.visibility = View.VISIBLE
                maskMediaControlsContainer.visibility = View.VISIBLE
                // DebugLog.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}, parent:
                // ${maskMediaControlsContainer.parent}")
            } else {
                // DebugLog.d("MediaControls", "Setting to paused state")
                btnMaskPlay.visibility = View.VISIBLE
                btnMaskPause.visibility = View.GONE
                // Keep controls visible if we know media exists
                maskMediaControlsContainer.visibility = View.VISIBLE
                // DebugLog.d("MediaControls", "Controls container visibility:
                // ${maskMediaControlsContainer.visibility}")
            }

            refreshMaskedNowPlaying()

            // Update full screen controls as well
            if (::btnFsPlayPause.isInitialized && !suppressFullscreenMediaControls) {
                if (isPlaying) {
                    btnFsPlayPause.setText(R.string.fa_pause)
                    isFsPlaying = true
                } else {
                    btnFsPlayPause.setText(R.string.fa_play)
                    isFsPlaying = false
                }
            }
        }
    }

    fun isMediaPlaying(): Boolean {
        return isMediaPlaying
    }

    fun toggleMediaPlayback() {
        if (isMediaPlaying) {
            pauseMedia()
        } else {
            playMedia()
        }
    }

    private fun isTapRadioWebView(targetWebView: WebView): Boolean {
        return targetWebView.url?.contains("radio.html", ignoreCase = true) == true
    }

    private fun evaluateMediaControlCommand(targetWebView: WebView, fallbackJs: String, tapRadioJs: String? = null) {
        val script = if (tapRadioJs != null && isTapRadioWebView(targetWebView)) tapRadioJs else fallbackJs
        targetWebView.evaluateJavascript(script, null)
    }

    fun playMedia() {
        val webView = getMediaControlWebView()
        evaluateMediaControlCommand(
                webView,
                "var m = document.querySelector('video, audio'); if (m) m.play();",
                "(function(){ if(window.tapRadioNativeResumePlayback){ window.tapRadioNativeResumePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
        )
        updateMediaState(true)
    }

    fun pauseMedia() {
        val webView = getMediaControlWebView()
        evaluateMediaControlCommand(
                webView,
                "var m = document.querySelector('video, audio'); if (m) m.pause();",
                "(function(){ if(window.tapRadioNativePausePlayback){ window.tapRadioNativePausePlayback(); return; } if(window.togglePlay){ window.togglePlay(); } })();"
        )
        updateMediaState(false)
    }

    fun hideMediaControls() {
        post { maskMediaControlsContainer.visibility = View.GONE }
    }

    fun setSuppressFullscreenMediaControls(suppress: Boolean) {
        suppressFullscreenMediaControls = suppress
        post {
            if (::fullScreenMediaControls.isInitialized) {
                fullScreenMediaControls.visibility = if (suppress) View.GONE else View.VISIBLE
            }
        }
    }

    private fun handleMediaStateChanged(sourceWebView: WebView, isPlaying: Boolean) {
        val windowId = windows.firstOrNull { it.webView == sourceWebView }?.id
        if (windowId == null) {
            updateMediaState(isPlaying)
            return
        }

        mediaStateByWindowId[windowId] = isPlaying
        if (isPlaying) {
            mediaLastPlayedAtByWindowId[windowId] = SystemClock.uptimeMillis()
        }

        val anyPlaying = mediaStateByWindowId.values.any { it }
        updateMediaState(anyPlaying)
        refreshMaskedNowPlaying()
    }

    private fun getMediaControlWebView(): WebView {
        val playingIds = mediaStateByWindowId.filterValues { it }.keys
        val targetId =
                if (playingIds.isNotEmpty()) {
                    playingIds.maxByOrNull { mediaLastPlayedAtByWindowId[it] ?: 0L }
                } else {
                    mediaLastPlayedAtByWindowId.keys.maxByOrNull {
                        mediaLastPlayedAtByWindowId[it] ?: 0L
                    }
                }

        return windows.firstOrNull { it.id == targetId }?.webView ?: webView
    }

    fun refreshMaskedNowPlaying() {
        if (!::maskNowPlayingText.isInitialized) return
        post {
            val label = resolveMaskedNowPlayingLabel()
            if (!isScreenMasked || label.isNullOrBlank()) {
                maskNowPlayingText.visibility = View.GONE
            } else {
                maskNowPlayingText.text = label
                maskNowPlayingText.visibility = View.VISIBLE
                maskNowPlayingText.bringToFront()
            }
        }
    }

    private fun scheduleMaskedNowPlayingRefresh() {
        val delays = longArrayOf(120L, 500L, 1200L)
        refreshMaskedNowPlaying()
        delays.forEach { delayMs ->
            postDelayed({ refreshMaskedNowPlaying() }, delayMs)
        }
    }

    /**
     * After a track skip (next/prev), YouTube SPA navigations take several
     * seconds to update document.title.  We probe at multiple intervals and
     * also pull the title directly from the DOM which updates faster.
     */
    private fun scheduleTrackChangeRefresh() {
        val delays = longArrayOf(300L, 800L, 1500L, 2500L, 4000L, 6000L)
        delays.forEach { delayMs ->
            postDelayed({
                refreshMaskedNowPlayingFromJs()
                refreshMaskedNowPlaying()
            }, delayMs)
        }
    }


    private fun getFreshMaskedDomTitle(currentUrl: String? = null): String? {
        val title = lastMaskedDomTitle?.trim().orEmpty()
        if (title.isBlank()) return null
        if (SystemClock.uptimeMillis() - lastMaskedDomTitleAt > maskedDomTitleFreshMs) return null
        if (!currentUrl.isNullOrBlank()) {
            val cachedUrl = lastMaskedDomTitleUrl.orEmpty()
            val sameYoutubeFamily =
                (currentUrl.contains("youtube.com", ignoreCase = true) || currentUrl.contains("youtu.be", ignoreCase = true)) &&
                (cachedUrl.contains("youtube.com", ignoreCase = true) || cachedUrl.contains("youtu.be", ignoreCase = true))
            if (!sameYoutubeFamily) return null
        }
        return title
    }

    /**
     * Use JS to extract the video title directly from the DOM.  On YouTube
     * the element `yt-formatted-string.ytd-watch-metadata` or the
     * `<title>` tag updates before `WebView.getTitle()` reflects it.
     */
    private fun refreshMaskedNowPlayingFromJs() {
        if (!isScreenMasked || !::maskNowPlayingText.isInitialized) return
        val webView = try { getMediaControlWebView() } catch (_: Exception) { return }
        val url = webView.url.orEmpty()
        if (!url.contains("youtube.com", true) && !url.contains("youtu.be", true)) return
        webView.evaluateJavascript(
            """
            (function() {
                var el = document.querySelector('yt-formatted-string.style-scope.ytd-watch-metadata') ||
                         document.querySelector('#info-contents yt-formatted-string') ||
                         document.querySelector('h1.title yt-formatted-string') ||
                         document.querySelector('title');
                if (!el) return '';
                var t = (el.textContent || el.innerText || '').trim();
                t = t.replace(/ - YouTube${'$'}/, '').replace(/ - YouTube Music${'$'}/, '').trim();
                if (!t || /^youtube$/i.test(t) || /^youtube music$/i.test(t)) return '';
                return t;
            })();
            """.trimIndent()
        ) { result ->
            val title = result?.trim('"', ' ') ?: return@evaluateJavascript
            if (title.isNotBlank() && title != "null") {
                post {
                    if (isScreenMasked) {
                        lastMaskedDomTitle = title
                        lastMaskedDomTitleUrl = url
                        lastMaskedDomTitleAt = SystemClock.uptimeMillis()
                        maskNowPlayingText.text = title
                        maskNowPlayingText.visibility = View.VISIBLE
                        maskNowPlayingText.bringToFront()
                    }
                }
            }
        }
    }

    private fun resolveMaskedNowPlayingLabel(): String? {
        val prefs = context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
        val radioPlaying = prefs.getBoolean("tapradio_now_playing_active", false)
        val stationName = prefs.getString("tapradio_now_playing_name", "")?.trim().orEmpty()

        // During track transitions isMediaPlaying can briefly be false;
        // also check recency of last playback so we don't blank the label.
        val recentlyPlaying = isMediaPlaying ||
            (SystemClock.uptimeMillis() - lastMediaPlayingAt < 8000)
        val mediaWebView = try { getMediaControlWebView() } catch (_: Exception) { null }
        val currentUrl = mediaWebView?.url.orEmpty()
        val isYoutube = currentUrl.contains("youtube.com", ignoreCase = true) ||
            currentUrl.contains("youtu.be", ignoreCase = true)
        if (isYoutube) {
            getFreshMaskedDomTitle(currentUrl)?.let { return it }
        }
        if (radioPlaying && stationName.isNotBlank() && !isYoutube) {
            return stationName
        }
        if (!recentlyPlaying || mediaWebView == null) return null
        if (isYoutube) {
            val rawTitle = mediaWebView.title?.trim().orEmpty()
            val cleaned = rawTitle
                .removeSuffix(" - YouTube")
                .removeSuffix(" - YouTube Music")
                .trim()
            return cleaned.takeIf {
                it.isNotBlank() &&
                    !it.equals("YouTube", ignoreCase = true) &&
                    !it.equals("YouTube Music", ignoreCase = true)
            }
        }
        if (radioPlaying && stationName.isNotBlank()) {
            return stationName
        }
        return getFreshMaskedDomTitle(currentUrl)
    }

    fun injectLocation(latitude: Double, longitude: Double) {
        val script =
                """
            (function() {
                // Store the position globally so it persists
                window.__injectedPosition = {
                    coords: {
                        latitude: $latitude,
                        longitude: $longitude,
                        accuracy: 5.0,
                        altitude: null,
                        altitudeAccuracy: null,
                        heading: null,
                        speed: null
                    },
                    timestamp: new Date().getTime()
                };

                // Initialize watcher registry if not present
                if (!window.__geoWatchers) window.__geoWatchers = {};
                if (!window.__geoNextWatchId) window.__geoNextWatchId = 1;

                // Notify all registered watchPosition callbacks with updated position
                var watchers = window.__geoWatchers;
                for (var id in watchers) {
                    if (watchers.hasOwnProperty(id) && typeof watchers[id] === 'function') {
                        try { watchers[id](window.__injectedPosition); } catch(e) {
                            console.warn('[TapLink] watcher ' + id + ' error:', e);
                        }
                    }
                }

                // Only set up the mock geolocation API once
                if (window.__geoMockInstalled) {
                    console.log("[TapLink] Location updated: " + $latitude + ", " + $longitude + " (watchers: " + Object.keys(watchers).length + ")");
                    return;
                }
                window.__geoMockInstalled = true;

                // 1. Mock Permissions API to always return 'granted'
                if (navigator.permissions) {
                    var originalQuery = navigator.permissions.query.bind(navigator.permissions);
                    navigator.permissions.query = function(parameters) {
                        if (parameters.name === 'geolocation') {
                            return Promise.resolve({ state: 'granted', onchange: null });
                        }
                        return originalQuery(parameters);
                    };
                }

                // 2. Override Geolocation API using defineProperty for robustness
                var mockGeolocation = {
                    getCurrentPosition: function(success, error, options) {
                        setTimeout(function() {
                            if (window.__injectedPosition) {
                                success(window.__injectedPosition);
                            } else if (error) {
                                error({code: 2, message: 'Position unavailable'});
                            }
                        }, 10);
                    },
                    watchPosition: function(success, error, options) {
                        var watchId = window.__geoNextWatchId++;
                        window.__geoWatchers[watchId] = success;
                        // Fire immediately with current position
                        setTimeout(function() {
                            if (window.__injectedPosition) {
                                success(window.__injectedPosition);
                            }
                        }, 10);
                        return watchId;
                    },
                    clearWatch: function(id) {
                        delete window.__geoWatchers[id];
                    }
                };

                try {
                    Object.defineProperty(navigator, 'geolocation', {
                        value: mockGeolocation,
                        writable: false,
                        configurable: true
                    });
                } catch (e) {
                    // Fallback if defineProperty fails
                    navigator.geolocation.getCurrentPosition = mockGeolocation.getCurrentPosition;
                    navigator.geolocation.watchPosition = mockGeolocation.watchPosition;
                    navigator.geolocation.clearWatch = mockGeolocation.clearWatch;
                }

                console.log("[TapLink] Location mock installed + injected: " + $latitude + ", " + $longitude);
            })();
        """.trimIndent()

        post { webView.evaluateJavascript(script, null) }
    }

    fun injectPageObservers(targetWebView: WebView) {
        val script =
                """
            (function() {
                if (window.__taplinkReportScroll) {
                    window.__taplinkReportScroll();
                    if (window.__taplinkWarmupScroll) {
                        window.__taplinkWarmupScroll();
                    }
                    return;
                }

                function initTaplinkObservers() {
                    if (window.__observersInjected) return;
                    if (!document.body) {
                        setTimeout(initTaplinkObservers, 50);
                        return;
                    }
                    window.__observersInjected = true;

                    // --- Media Listeners ---
                    function attachMediaListeners(media) {
                        if (media.__listenersAttached) return;
                        media.__listenersAttached = true;
                        
                        const updateState = () => {
                            const allMedia = document.querySelectorAll('video, audio');
                            let anyPlaying = false;
                            for(let i=0; i<allMedia.length; i++) {
                                if(!allMedia[i].paused && !allMedia[i].ended && allMedia[i].readyState > 2) {
                                    anyPlaying = true;
                                    break;
                                }
                            }
                            if (window.MediaInterface) {
                                 window.MediaInterface.onMediaStateChanged(anyPlaying);
                            }
                        };

                        media.addEventListener('play', updateState);
                        media.addEventListener('pause', updateState);
                        media.addEventListener('ended', updateState);
                    }

                    const existingMedia = document.querySelectorAll('video, audio');
                    existingMedia.forEach(attachMediaListeners);

                    // --- Scroll Detection ---
                    let lastScrollTime = 0;
                    let lastScanTime = 0;
                    let cachedScroller = null;
                    let rescanRequested = false;
                    const SCAN_INTERVAL_MS = 1200;
                    const SCROLL_MIN_SIZE = 80;
                    const trackedScrollers = typeof WeakSet !== 'undefined' ? new WeakSet() : new Set();

                    function isRootScrollable(el) {
                        if (!el) return false;
                        return (el.scrollHeight - el.clientHeight) > 1 || (el.scrollWidth - el.clientWidth) > 1;
                    }

                    function isScrollable(el) {
                        if (!el || el.nodeType !== 1 || !el.getBoundingClientRect) return false;
                        const style = window.getComputedStyle(el);
                        const overflowY = style.overflowY;
                        const overflowX = style.overflowX;
                        const scrollY = (overflowY === 'auto' || overflowY === 'scroll' || overflowY === 'overlay') &&
                            (el.scrollHeight - el.clientHeight) > 1;
                        const scrollX = (overflowX === 'auto' || overflowX === 'scroll' || overflowX === 'overlay') &&
                            (el.scrollWidth - el.clientWidth) > 1;
                        if (!(scrollY || scrollX)) return false;
                        return (el.clientHeight > SCROLL_MIN_SIZE || el.clientWidth > SCROLL_MIN_SIZE);
                    }

                    function ensureScrollListener(el) {
                        if (!el || trackedScrollers.has(el)) return;
                        trackedScrollers.add(el);
                        el.addEventListener('scroll', reportScroll, { passive: true });
                    }

                    function collectScrollableElements(root, out) {
                        if (!root || !root.querySelectorAll) return;
                        const elements = root.querySelectorAll('*');
                        for (let i = 0; i < elements.length; i++) {
                            const el = elements[i];
                            if (isScrollable(el)) out.push(el);
                            if (el.shadowRoot) {
                                collectScrollableElements(el.shadowRoot, out);
                            }
                        }
                    }

                    function pickBestScroller(candidates) {
                        let best = null;
                        let bestScore = -1;
                        for (let i = 0; i < candidates.length; i++) {
                            const el = candidates[i];
                            if (!el || !el.getBoundingClientRect) continue;
                            const rect = el.getBoundingClientRect();
                            const width = Math.max(0, Math.min(rect.width, window.innerWidth));
                            const height = Math.max(0, Math.min(rect.height, window.innerHeight));
                            const score = width * height;
                            if (score > bestScore) {
                                bestScore = score;
                                best = el;
                            }
                        }
                        return best;
                    }

                    function findScrollableElement(forceScan) {
                        const now = Date.now();
                        if (cachedScroller && cachedScroller.isConnected === false) {
                            cachedScroller = null;
                        }
                        if (cachedScroller && !isScrollable(cachedScroller) && !isRootScrollable(cachedScroller)) {
                            cachedScroller = null;
                        }

                        const shouldScan = forceScan || !cachedScroller || (now - lastScanTime) >= SCAN_INTERVAL_MS;
                        if (!shouldScan && cachedScroller) {
                            return cachedScroller;
                        }

                        const candidates = [];
                        const rootScroller = document.scrollingElement || document.documentElement || document.body;
                        if (rootScroller && isRootScrollable(rootScroller)) {
                            candidates.push(rootScroller);
                        }

                        collectScrollableElements(document, candidates);

                        const deduped = [];
                        const seen = new Set();
                        for (let i = 0; i < candidates.length; i++) {
                            const el = candidates[i];
                            if (el && !seen.has(el)) {
                                seen.add(el);
                                deduped.push(el);
                            }
                        }

                        for (let i = 0; i < deduped.length; i++) {
                            ensureScrollListener(deduped[i]);
                        }

                        cachedScroller = pickBestScroller(deduped);
                        lastScanTime = now;
                        rescanRequested = false;
                        return cachedScroller;
                    }

                    function pickScrollerFromEvent(event) {
                        if (!event) return null;
                        if (event.composedPath) {
                            const path = event.composedPath();
                            for (let i = 0; i < path.length; i++) {
                                const node = path[i];
                                if (node && node.nodeType === 1) {
                                    const el = node;
                                    if (isScrollable(el) || isRootScrollable(el)) {
                                        ensureScrollListener(el);
                                        return el;
                                    }
                                }
                            }
                        }

                        if (event.target && event.target.nodeType === 1) {
                            const tgt = event.target;
                            if (isScrollable(tgt) || isRootScrollable(tgt)) {
                                ensureScrollListener(tgt);
                                return tgt;
                            }
                        }

                        return null;
                    }

                    function reportScroll(event) {
                        const now = Date.now();
                        // Basic throttle/debounce
                        if (now - lastScrollTime < 16) return;
                        lastScrollTime = now;

                        let scroller = pickScrollerFromEvent(event);
                        if (!scroller) {
                            scroller = findScrollableElement(rescanRequested);
                        }

                        if (!scroller) return;

                        window.__taplinkScrollTarget = scroller;

                        const rootScroller = document.scrollingElement || document.documentElement || document.body;

                        // If it's the root, use window metrics
                        let range, extent, offset, hRange, hExtent, hOffset;

                        if (scroller === rootScroller || scroller === document.documentElement || scroller === document.body) {
                            const docEl = document.documentElement;
                            range = docEl.scrollHeight;
                            extent = window.innerHeight;
                            offset = window.scrollY;

                            hRange = docEl.scrollWidth;
                            hExtent = window.innerWidth;
                            hOffset = window.scrollX;
                        } else {
                            range = scroller.scrollHeight;
                            extent = scroller.clientHeight;
                            offset = scroller.scrollTop;

                            hRange = scroller.scrollWidth;
                            hExtent = scroller.clientWidth;
                            hOffset = scroller.scrollLeft;
                        }

                        if (window.MediaInterface) {
                            window.MediaInterface.updateScrollMetrics(
                                Math.round(hRange),
                                Math.round(hExtent),
                                Math.round(hOffset),
                                Math.round(range),
                                Math.round(extent),
                                Math.round(offset)
                            );
                        }
                    }

                    function warmupScrollReports() {
                        if (window.__taplinkWarmupActive) return;
                        window.__taplinkWarmupActive = true;
                        const delays = [0, 120, 300, 600, 1000, 1500, 2000];
                        for (let i = 0; i < delays.length; i++) {
                            const delay = delays[i];
                            setTimeout(function() {
                                rescanRequested = true;
                                reportScroll();
                                if (i === delays.length - 1) {
                                    window.__taplinkWarmupActive = false;
                                }
                            }, delay);
                        }
                    }

                    window.__taplinkReportScroll = reportScroll;
                    window.__taplinkWarmupScroll = warmupScrollReports;

                    let reportTimer = null;
                    function scheduleReport() {
                        if (reportTimer !== null) return;
                        reportTimer = setTimeout(function() {
                            reportTimer = null;
                            reportScroll();
                        }, 250);
                    }

                    // Global capture listeners for scrollable activity
                    window.addEventListener('scroll', scheduleReport, { capture: true, passive: true });
                    window.addEventListener('wheel', scheduleReport, { capture: true, passive: true });
                    window.addEventListener('touchmove', scheduleReport, { capture: true, passive: true });

                    // Also check on resize
                    window.addEventListener('resize', scheduleReport);
                    document.addEventListener('DOMContentLoaded', scheduleReport, { passive: true });

                    // --- Mutation Observer ---
                    const observer = new MutationObserver(function(mutations) {
                        mutations.forEach(function(mutation) {
                            mutation.addedNodes.forEach(function(node) {
                                if (node.nodeName === 'VIDEO' || node.nodeName === 'AUDIO') {
                                    attachMediaListeners(node);
                                } else if (node.querySelectorAll) {
                                    node.querySelectorAll('video, audio').forEach(attachMediaListeners);
                                }
                            });
                        });
                        // Also re-check scroll on mutations
                        rescanRequested = true;
                        scheduleReport();
                    });
                    
                    observer.observe(document.body, { childList: true, subtree: true });

                    // Immediate metrics after setup
                    reportScroll();
                    warmupScrollReports();
                }

                initTaplinkObservers();
            })();
        """.trimIndent()

        targetWebView.evaluateJavascript(script, null)
    }

    // ── Media file interception for TapInsight media player ──
    companion object {
        private val MEDIA_TEXT_EXTS = setOf("txt","md","log","csv","json","xml","html","htm","rtf","ini","cfg","conf","yaml","yml","toml")
        private val MEDIA_AUDIO_EXTS = setOf("mp3","wav","ogg","m4a","aac","flac","wma","opus")
        private val MEDIA_VIDEO_EXTS = setOf("mp4","webm","mkv","avi","mov","m4v","ogv","3gp")
    }

    private fun interceptMediaUrl(view: android.webkit.WebView, url: String): Boolean {
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
        val encodedUrl = java.net.URLEncoder.encode(url, "UTF-8")
        val encodedTitle = java.net.URLEncoder.encode(title, "UTF-8")
        val srtUrl = if (mediaType == "video") {
            java.net.URLEncoder.encode(path.substringBeforeLast('.') + ".srt", "UTF-8")
        } else ""

        // Read media player preferences from companion app settings
        val vcPrefs = context.getSharedPreferences("visionclaw_prefs", android.content.Context.MODE_PRIVATE)
        val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
        val underline = vcPrefs.getBoolean("media_tts_underline", true)
        val mediaParams = buildString {
            if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
            if (!underline) append("&underline=false")
        }

        val playerUrl = "file:///android_asset/media_player.html" +
            "?url=$encodedUrl&type=$mediaType&title=$encodedTitle" +
            (if (srtUrl.isNotBlank()) "&srt=$srtUrl" else "") +
            mediaParams
        DebugLog.d("MediaPlayer", "DualWebView intercepted $mediaType ($ext): $url")
        view.loadUrl(playerUrl)
        return true
    }

    private class MediaInterface(
            private val parent: DualWebViewGroup,
            private val sourceWebView: WebView
    ) {
        @android.webkit.JavascriptInterface
        fun onMediaStateChanged(isPlaying: Boolean) {
            // Run on UI thread to update UI
            parent.post { parent.handleMediaStateChanged(sourceWebView, isPlaying) }
        }

        @android.webkit.JavascriptInterface
        fun updateScrollMetrics(
                rangeX: Int,
                extentX: Int,
                offsetX: Int,
                rangeY: Int,
                extentY: Int,
                offsetY: Int
        ) {
            parent.post {
                parent.updateExternalScrollMetrics(
                        rangeX,
                        extentX,
                        offsetX,
                        rangeY,
                        extentY,
                        offsetY
                )
            }
        }

        // ── Pending text for the pull-based media player approach ──
        @Volatile private var pendingReaderText: String? = null

        @android.webkit.JavascriptInterface
        fun getPendingText(): String {
            val text = pendingReaderText ?: ""
            pendingReaderText = null
            return text
        }

        @android.webkit.JavascriptInterface
        fun openTextReader(text: String, title: String) {
            android.util.Log.d("MediaInterface", "openTextReader: ${text.length} chars, title=$title")
            if (text.isBlank()) return
            pendingReaderText = text
            parent.post {
                val vcPrefs = parent.context.getSharedPreferences("visionclaw_prefs", Context.MODE_PRIVATE)
                val voiceName = vcPrefs.getString("media_tts_voice", "") ?: ""
                val underline = vcPrefs.getBoolean("media_tts_underline", true)
                val encodedTitle = java.net.URLEncoder.encode(title.take(200), "UTF-8")
                val mediaParams = buildString {
                    if (voiceName.isNotBlank()) append("&voice=${java.net.URLEncoder.encode(voiceName, "UTF-8")}")
                    if (!underline) append("&underline=false")
                }
                val playerUrl = "file:///android_asset/media_player.html?type=text&title=$encodedTitle$mediaParams"
                sourceWebView.loadUrl(playerUrl)
            }
        }
    }
}
