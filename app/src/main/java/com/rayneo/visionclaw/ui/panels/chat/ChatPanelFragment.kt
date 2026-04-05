package com.rayneo.visionclaw.ui.panels.chat

import android.util.Log
import android.animation.ValueAnimator
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.BatteryManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Patterns
import android.view.LayoutInflater
import android.view.Surface
import android.view.TextureView
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.Preview
import androidx.core.content.ContextCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.rayneo.visionclaw.R
import com.rayneo.visionclaw.core.model.ChatMessage
import com.rayneo.visionclaw.ui.MainViewModel
import com.rayneo.visionclaw.ui.VoiceOscilloscopeView
import com.rayneo.visionclaw.ui.panels.TrackpadPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.launch
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import android.view.animation.AccelerateDecelerateInterpolator
import org.json.JSONArray
import org.json.JSONObject

/**
 * Chat card list for the RayNeo X3 Pro AR display.
 *
 * ## Scroll & Focus Design (third-generation approach)
 *
 * Previous attempts used a [LinearSnapHelper] subclass that conflicted
 * with the trackpad-driven card navigation.  The X3 Pro has **no touch
 * scrolling** — all input comes from the trackpad via [onTrackpadScroll].
 * Therefore a SnapHelper (designed for finger flings) is the wrong tool.
 *
 * This version uses a purely index-driven approach:
 *
 *  1. [focusedCardIndex] is the single source of truth.
 *  2. [onTrackpadScroll] increments / decrements the index.
 *  3. [scrollToFocused] performs a 2-step center lock:
 *     - coarse jump with `scrollToPositionWithOffset(...)`
 *     - precise correction by measuring child-center vs. anchor-center.
 *  4. After centering, [applyFocusVisuals] scales / fades / glows every
 *     visible child.
 *
 * No SnapHelper.  No scroll-listener-driven focus inference.  No
 * coordinate math that can drift out of sync with the platform.
 */
class ChatPanelFragment : Fragment(), TrackpadPanel {
    private companion object {
        private const val SWIPE_STEP_LOCK_MS = 250L
        private const val CARD_NAV_MIN_DELTA = 0.35f
        private const val FAST_SWIPE_DELTA = 6.0f
        private const val SWIPE_RELEASE_RESET_MS = 280L
        private const val TAP_SETTLE_DELAY_MS = 150L
        private const val TAP_GUARD_VELOCITY_THRESHOLD_PX_PER_MS = 10f
        private const val TAP_GUARD_BLOCK_MS = 180L

        // ── Pop-out visual spec ──────────────────────────────────────────
        private const val CARD_HEIGHT_DP = 220f
        private const val CARD_FOCUS_SCALE = 1.15f
        private const val CARD_FOCUS_ALPHA = 1.0f
        private const val CARD_FOCUS_Z = 12f
        private const val CARD_UNFOCUSED_SCALE = 0.75f
        private const val CARD_UNFOCUSED_ALPHA = 0.15f
        private const val CARD_UNFOCUSED_Z = 0f
        private const val CARD_FOCUS_ANIM_MS = 200L
        private const val CARD_FOCUS_GLOW_PX = 3
        private const val CARD_FOCUS_GLOW_CORNER_DP = 14f
        private const val CARD_FOCUS_GLOW_COLOR = 0xFF00FFFF.toInt()
        private const val READER_SCROLL_SCALE = 48f
    }

    private inner class DiscreteCarouselManager(context: Context) :
        LinearLayoutManager(context, RecyclerView.VERTICAL, false) {
        override fun canScrollVertically(): Boolean = false

        fun scrollToFocus(position: Int) {
            scrollToPositionWithOffset(position, computeFocusOffsetPx())
        }
    }

    interface CoreEyeSurfaceListener {
        fun onSurfaceAvailable()
        fun onSurfaceDestroyed()
    }

    interface CardActionListener {
        fun onAssistantRequested()
    }

    enum class FocusedTapResult {
        OPENED_URL,
        ACTIVATE_ASSISTANT,
        IGNORED
    }

    enum class ConnectionStatus {
        IDLE,
        CONNECTING,
        GEMINI_CONNECTED,
        TOOLS_READY,
        ERROR
    }

    private val viewModel: MainViewModel by activityViewModels()

    private lateinit var root: View
    private lateinit var hudContainer: LinearLayout
    private lateinit var hudTime: TextView
    private lateinit var hudCalendar: TextView
    private lateinit var hudTasks: TextView
    private lateinit var hudNews: TextView
    private lateinit var hudCalendarCard: LinearLayout
    private lateinit var hudTasksCard: LinearLayout
    private lateinit var hudNewsCard: LinearLayout
    private lateinit var hudBatteryIcon: ImageView
    private lateinit var hudBatteryText: TextView
    private lateinit var hudAqiText: TextView
    private lateinit var hudRadioText: TextView
    private lateinit var hudHeartbeatText: TextView
    private lateinit var hudConnectionDot: View
    private lateinit var hudConnectionText: TextView
    private lateinit var chatRecycler: RecyclerView
    private lateinit var chatStreamIndicator: TextView
    private lateinit var readerOverlay: FrameLayout
    private lateinit var readerScroll: NestedScrollView
    private lateinit var readerText: TextView
    private lateinit var inlineOscilloscope: VoiceOscilloscopeView
    private lateinit var coreEyeContainer: FrameLayout
    private lateinit var coreEyePreviewTexture: TextureView
    private lateinit var coreEyeRing: View
    private lateinit var coreEyeIdleIcon: ImageView

    private val adapter = ChatAdapter(
        onUrlTapped = { url -> viewModel.openUrl(url) },
        onAssistantRequested = { cardActionListener?.onAssistantRequested() }
    )
    private lateinit var layoutManager: DiscreteCarouselManager

    private var renderedAssistantMessages: List<ChatMessage> = emptyList()
    private var lastMessageFingerprint = 0
    private var lastRenderedCardCount = 0
    private var accumulatedSwipeDeltaY = 0f
    private var swipeStepConsumed = false
    private var focusedCardIndex: Int = RecyclerView.NO_POSITION
        set(value) {
            field = value
            // Keep the adapter in sync so that onBindViewHolder can apply
            // correct focused/unfocused alpha for every card — including the
            // New Chat sentinel — the instant it is bound.
            adapter.focusedPosition = value
        }
    private var suppressFirstCollectorFocus = false
    private var tapBlockedUntilSnap = false
    private var swipeLockUntilMs = 0L
    private var hudModeEnabled = false
    private var readerCardUrl: String? = null          // URL of the card currently in reader mode
    private var lastReaderTapMs = 0L                   // for double-tap detection in reader mode
    private val DOUBLE_TAP_THRESHOLD_MS = 400L
    private val pendingUrlOpenRunnable = Runnable {
        val url = readerCardUrl ?: return@Runnable
        viewModel.openUrl(url)
        readerCardUrl = null
        exitReaderMode(animated = false)
    }
    private var readerModeActive = false
    private var isSettled = true
    private var lastScrollSampleMs = 0L
    private var velocityTapBlockUntilMs = 0L

    private var coreEyeEnabled = false
    private var coreEyeStreaming = false
    private var coreEyeSurfaceReady = false
    private var coreEyePulseAnimator: ValueAnimator? = null
    private var coreEyeSurfaceListener: CoreEyeSurfaceListener? = null
    private var cardActionListener: CardActionListener? = null
    private var externalCalendarSummary: String? = null
    private var externalTasksSummary: String? = null
    private var externalNewsSummary: String? = null
    private var externalAirQualityState: MainViewModel.AirQualityHudState? = null
    private var externalRadioState: MainViewModel.RadioHudState? = null

    private val uiHandler = Handler(Looper.getMainLooper())
    private val coreEyeStreamTimeoutRunnable = Runnable {
        if (readerModeActive) {
            return@Runnable
        }
        if (coreEyeEnabled) {
            setCoreEyeStreamingVisuals(active = false)
        }
    }
    private val hudWarmupSyncRunnable = Runnable {
        if (!isAdded || !this::hudContainer.isInitialized) return@Runnable
        viewModel.refreshHudUpcomingCalendar(force = true)
        viewModel.refreshHudTasks(force = true)
        viewModel.refreshHudAirQuality(force = true)
        renderHudSnapshot()
    }
    private val swipeReleaseResetRunnable = Runnable {
        // Reset one-swipe session state after a short idle pause.
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = false
        isSettled = true
    }

    // ══════════════════════════════════════════════════════════════════════
    // Lifecycle
    // ══════════════════════════════════════════════════════════════════════

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_chat_panel, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        root = view
        hudContainer = view.findViewById(R.id.hudContainer)
        hudTime = view.findViewById(R.id.hudTime)
        hudCalendar = view.findViewById(R.id.hudCalendar)
        hudTasks = view.findViewById(R.id.hudTasks)
        hudNews = view.findViewById(R.id.hudNews)
        hudCalendarCard = view.findViewById(R.id.hudCalendarCard)
        hudTasksCard = view.findViewById(R.id.hudTasksCard)
        hudNewsCard = view.findViewById(R.id.hudNewsCard)
        hudBatteryIcon = view.findViewById(R.id.hudBatteryIcon)
        hudBatteryText = view.findViewById(R.id.hudBatteryText)
        hudAqiText = view.findViewById(R.id.hudAqiText)
        hudRadioText = view.findViewById(R.id.hudRadioText)
        hudHeartbeatText = view.findViewById(R.id.hudHeartbeatText)
        hudConnectionDot = view.findViewById(R.id.hudConnectionDot)
        hudConnectionText = view.findViewById(R.id.hudConnectionText)
        chatRecycler = view.findViewById(R.id.chatRecycler)
        chatStreamIndicator = view.findViewById(R.id.chatStreamIndicator)
        readerOverlay = view.findViewById(R.id.readerOverlay)
        readerScroll = view.findViewById(R.id.readerScroll)
        readerText = view.findViewById(R.id.readerText)
        inlineOscilloscope = view.findViewById(R.id.chatInlineOscilloscope)
        coreEyeContainer = view.findViewById(R.id.coreEyeContainer)
        coreEyePreviewTexture = view.findViewById(R.id.coreEyePreviewTexture)
        coreEyeRing = view.findViewById(R.id.coreEyeRing)
        coreEyeIdleIcon = view.findViewById(R.id.coreEyeIdleIcon)

        applyHudCardOrder()
        renderHudSnapshot()
        configureCoreEyeView()
        refreshBatteryStatusHud()
        setConnectionStatus(ConnectionStatus.IDLE)
        inlineOscilloscope.setCenterCutoutRadiusDp(52f)
        // Compute the cutout fraction from real pixel positions after layout.
        // Camera is centered on the 20% guideline; the oscilloscope has 12dp
        // side margins.  fraction = (cameraCenterPx − oscLeftPx) / oscWidthPx
        root.post {
            val parentW = root.width.toFloat()
            if (parentW <= 0f) return@post
            // Camera center is at exactly 10% of parent (centered on guideline)
            val camCenterPx = parentW * 0.10f
            // Oscilloscope has 12dp start margin
            val density = resources.displayMetrics.density
            val oscLeftPx = 12f * density
            val oscWidthPx = parentW - 24f * density   // 12dp margin each side
            if (oscWidthPx > 0f) {
                val fraction = (camCenterPx - oscLeftPx) / oscWidthPx
                inlineOscilloscope.setCutoutCenterFraction(fraction.coerceIn(0f, 1f))
            }
        }

        layoutManager = DiscreteCarouselManager(requireContext())
        chatRecycler.layoutManager = layoutManager
        chatRecycler.adapter = adapter
        chatRecycler.setHasFixedSize(false)
        chatRecycler.clipChildren = false
        chatRecycler.itemAnimator = null
        // Disable direct touch scrolling; only trackpad gestures navigate cards.
        chatRecycler.isNestedScrollingEnabled = false
        chatRecycler.setOnTouchListener { _, _ -> true }
        readerOverlay.visibility = View.GONE

        chatRecycler.addOnScrollListener(
            object : RecyclerView.OnScrollListener() {
                override fun onScrolled(recyclerView: RecyclerView, dx: Int, dy: Int) {
                    applyFocusVisuals(animate = false)
                }

                override fun onScrollStateChanged(recyclerView: RecyclerView, newState: Int) {
                    if (newState == RecyclerView.SCROLL_STATE_IDLE) {
                        applyFocusVisuals(animate = false)
                        accumulatedSwipeDeltaY = 0f
                        swipeStepConsumed = false
                        tapBlockedUntilSnap = false
                    }
                }
            }
        )
        chatRecycler.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ ->
            // Keep focus visuals synchronized when layout changes after manual
            // index jumps (scrollToPositionWithOffset) and adapter rebinding.
            applyFocusVisuals(animate = false)
        }
        // Catch cards that scroll back into view from the RecyclerView cache
        // (these are re-attached without onBindViewHolder, so they keep stale
        // alpha from when they were last focused).
        chatRecycler.addOnChildAttachStateChangeListener(
            object : RecyclerView.OnChildAttachStateChangeListener {
                override fun onChildViewAttachedToWindow(view: View) {
                    applyFocusVisuals(animate = false)
                }
                override fun onChildViewDetachedFromWindow(view: View) {}
            }
        )
        chatRecycler.post {
            focusedCardIndex = adapter.getLastContentPosition()
            snapFocusedCard()
            applyFocusVisuals(animate = false)
        }

        viewModel.hydrateAssistantHistory()
        bindInitialHistoryFromSnapshot()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.messages.collect { messages ->
                        val assistantMessages = messages.filterNot { it.fromUser }
                        renderedAssistantMessages = assistantMessages
                        adapter.submitMessages(assistantMessages)
                        val fingerprint = assistantMessages.joinToString("|") { it.text }.hashCode()
                        if (suppressFirstCollectorFocus) {
                            suppressFirstCollectorFocus = false
                            lastMessageFingerprint = fingerprint
                            focusedCardIndex = adapter.getLastContentPosition()
                            snapFocusedCard()
                        } else if (fingerprint != lastMessageFingerprint) {
                            val oldFingerprint = lastMessageFingerprint
                            lastMessageFingerprint = fingerprint
                            // Only auto-focus the latest card if a NEW message was added
                            // (card count changed). If only existing card text was updated
                            // (streaming chunk), update the card content but don't hijack
                            // the user's navigation — they may be browsing other cards.
                            val latestPos = adapter.getLatestMessagePosition()
                            if (assistantMessages.size != lastRenderedCardCount) {
                                lastRenderedCardCount = assistantMessages.size
                                focusCard(latestPos, animate = true)
                            } else {
                                // Streaming text update — refresh the card at latestPos
                                // but keep the user's current focus position.
                                adapter.notifyItemChanged(latestPos)
                                // Also update reader mode if it's open on the streaming card
                                if (readerModeActive && focusedCardIndex == latestPos) {
                                    val updatedText = adapter.getCardText(latestPos)?.trim().orEmpty()
                                    if (updatedText.isNotBlank()) {
                                        readerText.text = updatedText
                                    }
                                }
                            }
                        } else {
                            snapFocusedCard()
                        }
                    }
                }
                launch {
                    viewModel.calendarSummary.collect { summary ->
                        renderCalendarSummary(summary)
                    }
                }
                launch {
                    viewModel.tasksSummary.collect { summary ->
                        renderTasksSummary(summary)
                    }
                }
                launch {
                    viewModel.newsSummary.collect { summary ->
                        renderNewsSummary(summary)
                    }
                }
                launch {
                    viewModel.airQualitySummary.collect { state ->
                        renderAirQualityState(state)
                    }
                }
                launch {
                    viewModel.radioSummary.collect { state ->
                        renderRadioState(state)
                    }
                }
                launch {
                    val formatter = SimpleDateFormat("EEE MMM dd • HH:mm:ss", Locale.US)
                    while (true) {
                        hudTime.text = formatter.format(Date())
                        refreshBatteryStatusHud()
                        delay(1000)
                    }
                }
                launch {
                    while (true) {
                        renderHudSnapshot()
                        delay(3000)
                    }
                }
            }
        }
        view.post { renderHudSnapshot() }
    }

    override fun onResume() {
        super.onResume()
        if (this::hudContainer.isInitialized) {
            renderHudSnapshot()
        }
        uiHandler.removeCallbacks(hudWarmupSyncRunnable)
        uiHandler.postDelayed(hudWarmupSyncRunnable, 1500L)
        uiHandler.postDelayed(hudWarmupSyncRunnable, 5000L)
    }

    override fun onDestroyView() {
        if (coreEyeSurfaceReady) {
            coreEyeSurfaceListener?.onSurfaceDestroyed()
        }
        coreEyeSurfaceReady = false
        coreEyePreviewTexture.surfaceTextureListener = null
        uiHandler.removeCallbacks(coreEyeStreamTimeoutRunnable)
        uiHandler.removeCallbacks(hudWarmupSyncRunnable)
        uiHandler.removeCallbacks(swipeReleaseResetRunnable)
        chatStreamIndicator.animate().cancel()
        coreEyePulseAnimator?.cancel()
        coreEyePulseAnimator = null
        super.onDestroyView()
    }

    // ══════════════════════════════════════════════════════════════════════
    // Trackpad input (TrackpadPanel)
    // ══════════════════════════════════════════════════════════════════════

    override fun onTrackpadPan(deltaX: Float, deltaY: Float): Boolean {
        if (hudModeEnabled) return true
        return onTrackpadScroll(deltaY)
    }

    override fun onTrackpadScroll(deltaY: Float): Boolean {
        if (hudModeEnabled) return true
        if (readerModeActive) {
            val step = (deltaY * READER_SCROLL_SCALE).toInt()
            if (step != 0) {
                readerScroll.smoothScrollBy(0, step)
            }
            return true
        }
        val now = SystemClock.uptimeMillis()
        if (now < swipeLockUntilMs) return true
        if (abs(deltaY) < 0.01f) return true
        if (adapter.itemCount <= 0) return true
        isSettled = false
        if (lastScrollSampleMs > 0L) {
            val dtMs = (now - lastScrollSampleMs).coerceAtLeast(1L).toFloat()
            val velocity = abs(deltaY) / dtMs
            if (velocity > TAP_GUARD_VELOCITY_THRESHOLD_PX_PER_MS) {
                velocityTapBlockUntilMs = now + TAP_GUARD_BLOCK_MS
            }
        }
        lastScrollSampleMs = now
        accumulatedSwipeDeltaY += deltaY
        uiHandler.removeCallbacks(swipeReleaseResetRunnable)
        uiHandler.postDelayed(
            swipeReleaseResetRunnable,
            maxOf(SWIPE_RELEASE_RESET_MS, TAP_SETTLE_DELAY_MS)
        )
        if (swipeStepConsumed) return true
        if (abs(accumulatedSwipeDeltaY) < CARD_NAV_MIN_DELTA) return true
        if (abs(accumulatedSwipeDeltaY) >= FAST_SWIPE_DELTA) tapBlockedUntilSnap = true

        val direction = if (accumulatedSwipeDeltaY > 0f) 1 else -1
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = true
        val firstContent = adapter.getFirstContentPosition()
        val lastContent = adapter.getLastContentPosition()
        val current = coerceFocusedIndex()
        val next = (current + direction).coerceIn(firstContent, lastContent)
        if (next == current) return true
        swipeLockUntilMs = now + SWIPE_STEP_LOCK_MS
        playNavigationTick()
        focusCard(next, animate = true)
        return true
    }

    override fun onTrackpadSelect(): Boolean {
        return handleFocusedCardTap() != FocusedTapResult.ACTIVATE_ASSISTANT
    }

    fun handleFocusedCardTap(): FocusedTapResult {
        if (hudModeEnabled) return FocusedTapResult.IGNORED

        // ── Check if the focused card is "New Chat" BEFORE applying scroll/settle
        //    guards — activating the assistant should always respond on first tap.
        val earlyIdx = coerceFocusedIndex()
        val isNewChat = earlyIdx < 0 || adapter.isNewChatCard(earlyIdx)
        if (isNewChat) {
            // Clear any lingering scroll/snap state so we don't block next time
            tapBlockedUntilSnap = false
            isSettled = true
            return FocusedTapResult.ACTIVATE_ASSISTANT
        }

        val now = SystemClock.uptimeMillis()
        if (!isSettled || now < velocityTapBlockUntilMs) {
            return FocusedTapResult.IGNORED
        }

        // ── Reader mode is active ──
        // Double-tap is handled externally by cyclePanelViaDoubleTap() →
        // exitReaderModeFromOutside(), so only single-tap arrives here.
        if (readerModeActive) {
            val expandedText = readerText.text?.toString().orEmpty()
            val url = inferBrowserUrlFromCardText(expandedText)
                ?: readerCardUrl
                ?: buildGenericSearchUrl(expandedText)
            if (!url.isNullOrBlank()) {
                viewModel.openUrl(url)
                readerCardUrl = null
                exitReaderMode(animated = false)
                return FocusedTapResult.OPENED_URL
            }
            return FocusedTapResult.IGNORED
        }

        if (chatRecycler.scrollState != RecyclerView.SCROLL_STATE_IDLE) {
            return FocusedTapResult.IGNORED
        }
        if (tapBlockedUntilSnap) {
            snapFocusedCard()
            tapBlockedUntilSnap = false
            return FocusedTapResult.IGNORED
        }
        val idx = coerceFocusedIndex()
        if (idx < 0) return FocusedTapResult.ACTIVATE_ASSISTANT
        if (adapter.isNewChatCard(idx)) return FocusedTapResult.ACTIVATE_ASSISTANT

        val cardText = adapter.getCardText(idx)?.trim().orEmpty()
        val resolvedUrl = inferBrowserUrlFromCardText(cardText) ?: adapter.getCardUrl(idx)
        // ── Always expand the focused card first. A second tap while expanded
        //    opens the relevant browser target, if any. ──
        readerCardUrl = resolvedUrl
        lastReaderTapMs = 0L
        return if (enterReaderMode(idx, animated = true)) {
            FocusedTapResult.IGNORED
        } else {
            readerCardUrl = null
            FocusedTapResult.ACTIVATE_ASSISTANT
        }
    }

    fun autoFocusLatestAssistantUrl() {
        if (adapter.itemCount <= 0) return
        val start = adapter.getLatestMessagePosition()
        val first = adapter.getFirstContentPosition()
        val urlPos = (start downTo first).firstOrNull {
            val text = adapter.getCardText(it).orEmpty()
            inferBrowserUrlFromCardText(text) != null || adapter.getCardUrl(it) != null
        }
        focusCard(urlPos ?: start, animate = true)
    }

    private fun inferBrowserUrlFromCardText(text: String): String? {
        val trimmed = text.trim()
        if (trimmed.isBlank()) return null
        Log.d("ARNav", "inferBrowserUrlFromCardText: input='${trimmed.take(200)}'")


        Patterns.WEB_URL.matcher(trimmed).run {
            if (find()) {
                val raw = group().orEmpty().trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
                if (raw.isNotBlank()) {
                    return if (raw.startsWith("http://") || raw.startsWith("https://")) raw else "https://$raw"
                }
            }
        }

        val lines = trimmed.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .toList()
        if (lines.isEmpty()) return null

        val explicitMapCard = lines.any {
            it.startsWith("Maps:", ignoreCase = true) ||
                it.startsWith("Directions:", ignoreCase = true) ||
                it.startsWith("From:", ignoreCase = true) ||
                it.startsWith("To:", ignoreCase = true)
        }
        val numberedPlacesCard = looksLikeNumberedPlacesCard(trimmed, lines)
        val mapResultCard = explicitMapCard || numberedPlacesCard

        // Check for explicit "From:" / "To:" lines (from google_routes tool results)
        val fromLine = lines.firstOrNull { it.startsWith("From:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.trimEnd('.', ',', ';')
        val toLine = lines.firstOrNull { it.startsWith("To:", ignoreCase = true) }
            ?.substringAfter(":")?.trim()?.trimEnd('.', ',', ';')
        if (!toLine.isNullOrBlank()) {
            val originAddr = fromLine?.takeIf { it.isNotBlank() }
            // Try strict address regex first; fall back to raw To: text
            // (Google Maps can geocode place names, cities, and landmarks)
            val destination = extractAddressFromCardText(toLine) ?: toLine
            val searchTopic = extractSearchTopicFromCardText(trimmed, listOf(destination))
            val url = buildDrivingDirectionsUrl(destination, originAddr, searchTopic)
            Log.d("ARNav", "  → From/To detected: origin='${originAddr ?: "GPS"}' dest='$destination' search='${searchTopic ?: ""}' URL: ${url.take(200)}")
            return url
        }

        val extractedAddresses = extractAddressesFromCardText(trimmed)
        Log.d("ARNav", "  extractedAddresses=${extractedAddresses.size}: ${extractedAddresses.joinToString(" | ") { it.take(60) }}")
        when {
            extractedAddresses.size == 1 -> {
                val address = extractedAddresses.first()
                val searchTopic = extractSearchTopicFromCardText(trimmed, extractedAddresses)
                if (mapResultCard) {
                    val url = buildDrivingDirectionsUrl(address, searchQuery = searchTopic)
                    Log.d("ARNav", "  → single address map URL: ${url.take(200)} search='${searchTopic ?: ""}'")
                    return url
                }
                val searchQuery = buildSearchQueryFromCardText(trimmed, lines, extractedAddresses)
                val url = buildGenericSearchUrl(searchQuery)
                Log.d("ARNav", "  → single address search URL: ${url?.take(200)} query='${searchQuery.take(120)}'")
                return url
            }
            extractedAddresses.size > 1 -> {
                if (mapResultCard) {
                    // Extract name+address pairs from numbered place lines and build
                    // a waypoint URL so each location gets its own pin on the map.
                    val namedLocations = extractNamedLocationsFromCard(lines, extractedAddresses)
                    val url = buildMultiPinMapsUrl(namedLocations, extractedAddresses)
                    Log.d("ARNav", "  → multi pin URL (${namedLocations.size} locations): ${url.take(200)}")
                    return url
                }
                val searchQuery = buildSearchQueryFromCardText(trimmed, lines, extractedAddresses)
                val url = buildGenericSearchUrl(searchQuery)
                Log.d("ARNav", "  → multi address search URL: ${url?.take(200)} query='${searchQuery.take(120)}'")
                return url
            }
        }

        extractDestinationQueryFromCardText(trimmed)?.let { destination ->
            val url = buildGenericSearchUrl(destination)
            Log.d("ARNav", "  → destination query search URL: ${url?.take(200)}")
            return url
        }

        val mapLike = trimmed.contains("open now", ignoreCase = true) ||
            trimmed.contains("closed", ignoreCase = true) ||
            trimmed.contains("currently closed", ignoreCase = true) ||
            trimmed.contains("currently open", ignoreCase = true) ||
            trimmed.contains("nearby alternatives", ignoreCase = true) ||
            trimmed.contains("eta:", ignoreCase = true) ||
            trimmed.contains("from:", ignoreCase = true) ||
            trimmed.contains("to:", ignoreCase = true) ||
            trimmed.contains("located at", ignoreCase = true) ||
            trimmed.contains("address:", ignoreCase = true) ||
            trimmed.contains("drive", ignoreCase = true) ||
            trimmed.contains("walk", ignoreCase = true) ||
            trimmed.contains("transit", ignoreCase = true) ||
            trimmed.contains("parking", ignoreCase = true) ||
            Regex("""\b\d{1,5}\s+[A-Za-z0-9.\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed) ||
            Regex("""\b(?:restaurant|cafe|coffee|shop|store|market|pharmacy|gas station|hotel|bar|bakery|hospital|parking)\b""", RegexOption.IGNORE_CASE)
                .containsMatchIn(trimmed)

        val usefulLines = lines
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("ETA:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true) ||
                    it.startsWith("Directions:", ignoreCase = true)
            }
            .map { sanitizeMapCardLine(it) }
            .filter { it.isNotBlank() }
            .take(if (mapLike) 2 else 3)

        val query = usefulLines.joinToString(" ").trim()
        if (query.isBlank()) {
            return buildGenericSearchUrl(sanitizeMapCardLine(trimmed).take(280).ifBlank { trimmed })
        }
        return buildGenericSearchUrl(query)
    }

    private fun buildDrivingDirectionsUrl(
        destination: String,
        originAddress: String? = null,
        searchQuery: String? = null
    ): String {
        val encodedDestination = URLEncoder.encode(destination, StandardCharsets.UTF_8.toString())
        val encodedSearch = searchQuery?.takeIf { it.isNotBlank() }
            ?.let { URLEncoder.encode(it.take(280), StandardCharsets.UTF_8.toString()) }
        // Prefer an explicit text origin (from user voice command) over GPS coords
        val explicitOrigin = if (!originAddress.isNullOrBlank()) {
            originAddress
        } else {
            viewModel.getDeviceLocationContext()
                ?.takeIf { it.latitude != 0.0 || it.longitude != 0.0 }
                ?.let { context ->
                    "${"%.6f".format(Locale.US, context.latitude)},${"%.6f".format(Locale.US, context.longitude)}"
                }
        }

        val searchSuffix = encodedSearch?.let { "&taplink_query=$it" }.orEmpty()
        return if (explicitOrigin != null) {
            val encodedOrigin = URLEncoder.encode(explicitOrigin, StandardCharsets.UTF_8.toString())
            Log.d("ARNav", "buildDrivingDirectionsUrl: origin='$explicitOrigin' dest='$destination' search='${searchQuery ?: ""}'")
            "https://www.google.com/maps/dir/?api=1&origin=$encodedOrigin&destination=$encodedDestination&travelmode=driving$searchSuffix"
        } else {
            Log.d("ARNav", "buildDrivingDirectionsUrl: no origin, dest='$destination' search='${searchQuery ?: ""}'")
            "https://www.google.com/maps/dir/?api=1&destination=$encodedDestination&travelmode=driving$searchSuffix"
        }
    }

    private fun buildGoogleMapsSearchUrl(query: String): String {
        val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8.toString())
        return "https://www.google.com/maps/search/?api=1&query=$encoded"
    }

    /**
     * Extract "Name, Address" pairs from numbered card lines like:
     *   1. Joe's Pizza — ★4.5 (120) — Open Now — 123 Main St, Boston
     * Returns a list of "Joe's Pizza, 123 Main St, Boston" strings.
     */
    private fun extractNamedLocationsFromCard(
        lines: List<String>,
        addresses: List<String>
    ): List<String> {
        val result = mutableListOf<String>()
        val addressLower = addresses.map { it.lowercase(Locale.US) }

        for (line in lines) {
            // Match numbered lines:  "1. Name — stuff — stuff — Address"
            val trimmed = line.trim()
            val nameMatch = Regex("""^\d+\.\s+(.+)""").find(trimmed) ?: continue
            val content = nameMatch.groupValues[1]

            // Split by " — " separator used in formatPlace()
            val parts = content.split(" — ", " - ").map { it.trim() }
            if (parts.isEmpty()) continue

            val placeName = parts.first().trim()
            // Find which address belongs to this line
            val lineAddr = addresses.firstOrNull { addr ->
                trimmed.contains(addr, ignoreCase = true)
            }

            if (lineAddr != null && placeName.isNotBlank()) {
                result.add("$placeName, $lineAddr")
            } else if (placeName.isNotBlank()) {
                // No address found on this line — use name alone as search term
                result.add(placeName)
            }
        }
        return result
    }

    /**
     * Build a URL to our custom multi_pin_map.html that shows ONLY the given
     * locations as numbered pins with a persistent, clickable legend panel.
     * Locations are passed as a JSON array in the query string.
     */
    private fun buildMultiPinMapsUrl(
        namedLocations: List<String>,
        fallbackAddresses: List<String>
    ): String {
        val apiKey = viewModel.preferences.googleMapsApiKey
        // Build a JSON array of {name, address} objects
        val jsonArray = JSONArray()
        if (namedLocations.isNotEmpty()) {
            for (loc in namedLocations) {
                val obj = JSONObject()
                // "Joe's Pizza, 123 Main St, Boston" → name="Joe's Pizza", address="123 Main St, Boston"
                val commaIdx = loc.indexOf(',')
                if (commaIdx > 0) {
                    obj.put("name", loc.substring(0, commaIdx).trim())
                    obj.put("address", loc.substring(commaIdx + 1).trim())
                } else {
                    obj.put("name", loc.trim())
                }
                jsonArray.put(obj)
            }
        } else {
            for (addr in fallbackAddresses) {
                val obj = JSONObject()
                obj.put("name", addr)
                obj.put("address", addr)
                jsonArray.put(obj)
            }
        }
        val encodedLocations = URLEncoder.encode(jsonArray.toString(), StandardCharsets.UTF_8.toString())
        val encodedKey = URLEncoder.encode(apiKey, StandardCharsets.UTF_8.toString())
        // Pass user GPS so the map centers near the user while geocoding runs
        val loc = viewModel.getDeviceLocationContext()
        val latParam = loc?.latitude ?: 0.0
        val lngParam = loc?.longitude ?: 0.0
        return "file:///android_asset/multi_pin_map.html?gkey=$encodedKey&lat=$latParam&lng=$lngParam&locations=$encodedLocations"
    }

    private fun sanitizeMapCardLine(text: String): String {
        var value = text.trim()
            .removePrefix("→")
            .removePrefix("-")
            .replace(Regex("""^\d+\.\s*"""), "")
            .replace(Regex("""\s+—\s+★[0-9.]+(?:\s+\(\d+\))?"""), "")
            .trim()

        extractAddressFromCardText(value)?.let { return it }

        val patterns = listOf(
            Regex("""\baddress:\s*(.+)""", RegexOption.IGNORE_CASE),
            Regex("""(?:is\s+)?(?:located|location)\s+at\s+(.+)""", RegexOption.IGNORE_CASE),
            Regex("""\bis\s+at\s+(.+)""", RegexOption.IGNORE_CASE)
        )
        for (pattern in patterns) {
            val match = pattern.find(value)
            if (match != null) {
                value = match.groupValues[1].trim()
                break
            }
        }

        value = value
            .replace(Regex("""\b(?:currently\s+)?(?:open\s*now|openow|closed|closednow)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:clear|cloudy|overcast|rain|showers|fog|drizzle|snow|storm)\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\bAQI\s*\d+.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b\d{1,3}°\s*[FC]\b.*$""", RegexOption.IGNORE_CASE), "")
            .replace(Regex("""\b(?:walk|drive|transit|eta|parking)\b.*$""", RegexOption.IGNORE_CASE), "")

        listOf(" — ", " - ", " | ", ". ").forEach { separator ->
            val idx = value.indexOf(separator)
            if (idx > 5) value = value.substring(0, idx)
        }

        return value.trim().trimEnd('.', ',', ';', ':')
    }

    private fun looksLikeNumberedPlacesCard(
        fullText: String,
        lines: List<String>
    ): Boolean {
        val hasAddress = extractAddressesFromCardText(fullText).isNotEmpty()
        if (!hasAddress) return false

        val numberedLines = lines.filter { Regex("""^\d+\.\s+""").containsMatchIn(it) }
        if (numberedLines.isEmpty()) return false

        val numberedLinesWithAddresses = numberedLines.count { line ->
            extractAddressFromCardText(line) != null
        }

        return numberedLinesWithAddresses > 0 &&
            (numberedLines.size > 1 || fullText.contains("Nearby alternatives", ignoreCase = true))
    }

    private fun extractDestinationQueryFromCardText(text: String): String? {
        val normalizedLines = text.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("ETA:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true) ||
                    it.startsWith("Directions:", ignoreCase = true)
            }
            .toList()
        if (normalizedLines.isEmpty()) return null

        extractAddressFromCardText(text)?.let { return it }
        normalizedLines.firstNotNullOfOrNull { line -> extractAddressFromCardText(line) }?.let { return it }

        val labelled = normalizedLines.firstNotNullOfOrNull { line ->
            listOf(
                Regex("""\baddress:\s*(.+)""", RegexOption.IGNORE_CASE),
                Regex("""(?:is\s+)?(?:located|location)\s+at\s+(.+)""", RegexOption.IGNORE_CASE),
                Regex("""\bis\s+at\s+(.+)""", RegexOption.IGNORE_CASE)
            ).firstNotNullOfOrNull { pattern ->
                pattern.find(line)?.groupValues?.getOrNull(1)?.trim()
            }
        }
        labelled?.let {
            val cleaned = sanitizeMapCardLine(it)
            if (cleaned.isNotBlank()) return cleaned
        }

        val firstUseful = normalizedLines
            .map { sanitizeMapCardLine(it) }
            .firstOrNull { it.isNotBlank() }
            ?.take(280)

        return firstUseful?.takeIf { it.isNotBlank() }
    }

    private fun extractAddressesFromCardText(text: String): List<String> {
        val addressRegex = Regex(
            """\b\d{1,5}\s+[A-Za-z0-9.'#\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b(?:,\s*[A-Za-z .'-]+){0,3}""",
            RegexOption.IGNORE_CASE
        )
        return addressRegex.findAll(text)
            .map { it.value.trim().trimEnd('.', ',', ';', ':') }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase(Locale.US) }
            .toList()
    }

    private fun buildSearchQueryFromCardText(
        fullText: String,
        lines: List<String>,
        addresses: List<String>
    ): String {
        val pieces = linkedSetOf<String>()
        inferPlaceCategoryFromText(fullText)?.let { pieces += it }

        val joined = lines.joinToString(" ") { it.trim() }
            .replace(Regex("""\s+"""), " ")
            .trim()

        Regex(
            """([A-Z][A-Za-z'&.-]*(?:\s+(?:[A-Z][A-Za-z'&.-]*|&|and|of|the))+?)\s+(?:at\s+\d{1,5}|,\s*with|is\s+(?:open|closed)|—)"""
        ).findAll(joined).forEach { match ->
            val name = match.groupValues[1].trim().trim(',', '.', ';', ':')
            if (name.length > 2 && !name.startsWith("A search", ignoreCase = true)) {
                pieces += name
            }
        }

        Regex("""(?:include|consider)\s+(.+?)(?:\.|$)""", RegexOption.IGNORE_CASE)
            .find(joined)
            ?.groupValues
            ?.getOrNull(1)
            ?.trim()
            ?.takeIf { it.isNotBlank() }
            ?.let { pieces += it }

        extractSearchTopicFromCardText(fullText, addresses)
            ?.takeIf {
                !it.startsWith("A search", ignoreCase = true) &&
                    !it.startsWith("A stellar pursuit", ignoreCase = true)
            }
            ?.let { pieces += it }

        val scrubbed = joined
            .replace(Regex("""(?i)^a\s+(?:search\s+reveals[^.]*|stellar\s+pursuit!?\s*)"""), "")
            .replace(Regex("""(?i)tap the card to see full map details\.?"""), "")
            .replace(Regex("""(?i)wishing you[^.]*\.?"""), "")
            .replace(Regex("""(?i)the weather[^.]*\.?"""), "")
            .replace(Regex("""(?i)other nearby alternatives include\s*"""), "")
            .replace(Regex("""(?i)with a stellar\s+[0-9.]+\s+rating,?"""), "")
            .replace(Regex("""(?i),?\s*is\s+open\w*\s+and\s+just\s+a?\s*\d+[- ]minute[^.]*\.?"""), " ")
            .replace(Regex("""(?i)\d+[- ]minute\s+(?:walk|drive|transit)"""), "")
            .replace(Regex("""(?i)(?:open\s*now|openow|closed|currently open|currently closed)"""), "")
            .replace(Regex("""\d{1,3}°\s*[FC]"""), "")
            .replace(Regex("""\s+"""), " ")
            .trim(' ', ',', '.', ';', ':')

        if (pieces.isEmpty() && scrubbed.isNotBlank()) {
            pieces += scrubbed
        }

        return pieces.joinToString(" ")
            .replace(Regex("""\s+"""), " ")
            .trim()
            .take(280)
            .ifBlank { sanitizeMapCardLine(fullText).take(280).ifBlank { fullText.take(280) } }
    }

    private fun buildMultiAddressMapsQuery(
        fullText: String,
        lines: List<String>,
        addresses: List<String>
    ): String {
        inferPlaceCategoryFromText(fullText)?.let { category ->
            return "$category near me"
        }

        val candidate = lines.firstNotNullOfOrNull { line ->
            val cleaned = sanitizeMapCardLine(line)
            cleaned.takeIf {
                it.isNotBlank() &&
                    addresses.none { address -> address.equals(cleaned, ignoreCase = true) } &&
                    !it.startsWith("maps:", ignoreCase = true) &&
                    !it.startsWith("eta:", ignoreCase = true)
            }
        }

        return candidate?.take(280)
            ?: sanitizeMapCardLine(fullText).take(280)
    }

    private fun extractSearchTopicFromCardText(
        fullText: String,
        addresses: List<String> = extractAddressesFromCardText(fullText)
    ): String? {
        val lines = fullText.lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .filterNot {
                it.startsWith("Maps:", ignoreCase = true) ||
                    it.startsWith("ETA:", ignoreCase = true) ||
                    it.startsWith("Nearby alternatives", ignoreCase = true) ||
                    it.startsWith("Directions:", ignoreCase = true)
            }
            .toList()

        val candidate = lines.firstNotNullOfOrNull { line ->
            val cleaned = sanitizeMapCardLine(line)
            cleaned.takeIf {
                it.isNotBlank() &&
                    addresses.none { address -> address.equals(cleaned, ignoreCase = true) } &&
                    extractAddressFromCardText(it) == null
            }
        }

        return candidate?.take(280)
            ?: inferPlaceCategoryFromText(fullText)?.let { "$it near me" }
    }

    private fun inferPlaceCategoryFromText(text: String): String? {
        val lower = text.lowercase(Locale.US)
        val categories = listOf(
            "coffee shop",
            "cafe",
            "restaurant",
            "bakery",
            "bar",
            "pharmacy",
            "gas station",
            "grocery store",
            "supermarket",
            "hotel",
            "parking"
        )
        return categories.firstOrNull { lower.contains(it) }
    }

    private fun extractAddressFromCardText(text: String): String? {
        val addressRegex = Regex(
            """\b\d{1,5}\s+[A-Za-z0-9.'#\- ]+\s(?:St|Street|Ave|Avenue|Blvd|Boulevard|Rd|Road|Dr|Drive|Ln|Lane|Way|Pl|Place|Ct|Court|Pkwy|Parkway|Ter|Terrace)\b(?:,\s*[A-Za-z .'-]+){0,3}""",
            RegexOption.IGNORE_CASE
        )
        return addressRegex.find(text)
            ?.value
            ?.trim()
            ?.trimEnd('.', ',', ';', ':')
            ?.takeIf { it.isNotBlank() }
    }

    private fun buildGenericSearchUrl(text: String): String? {
        val cleaned = text
            .lineSequence()
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .joinToString("\n")
            .trim()
        if (cleaned.isBlank()) return null
        val encoded = URLEncoder.encode(cleaned.take(1000), StandardCharsets.UTF_8.toString())
        return "https://www.google.com/search?q=$encoded"
    }

    fun focusNewChatCard(animate: Boolean = true) {
        if (adapter.itemCount <= 0) return
        if (readerModeActive) {
            exitReaderMode(animated = false)
        }
        focusCard(adapter.getLastContentPosition(), animate = animate)
    }

    fun clearFocus() {
        if (readerModeActive) {
            exitReaderMode(animated = false)
        }
        focusedCardIndex = RecyclerView.NO_POSITION
        accumulatedSwipeDeltaY = 0f
        swipeStepConsumed = false
        isSettled = true
        lastScrollSampleMs = 0L
        velocityTapBlockUntilMs = 0L
    }

    override fun onTextInputFromHold(text: String): Boolean = false

    override fun onHeadYaw(yawDegrees: Float) {
        root.translationX = (yawDegrees * 1.25f).coerceIn(-40f, 40f)
    }

    override fun getReadableText(): String {
        val messages = renderedAssistantMessages.takeLast(8)
        return messages.joinToString("\n") { "Assistant: ${it.text}" }
    }

    fun isReaderModeActive(): Boolean = readerModeActive

    /** Called from MainActivity when a double-tap should close reader mode
     *  instead of cycling panels / launching TapBrowser. */
    fun exitReaderModeFromOutside() {
        uiHandler.removeCallbacks(pendingUrlOpenRunnable)
        lastReaderTapMs = 0L
        readerCardUrl = null
        exitReaderMode(animated = true)
        view?.post { focusNewChatCard(animate = true) }
    }

    // ── OpenClaw heartbeat scrolling text under the clock ──────────

    private val hideHeartbeatRunnable = Runnable {
        if (!isAdded || !::hudHeartbeatText.isInitialized) return@Runnable
        hudHeartbeatText.animate()
            .alpha(0f)
            .setDuration(300L)
            .withEndAction {
                hudHeartbeatText.visibility = View.GONE
                hudHeartbeatText.isSelected = false
            }
            .start()
    }

    /**
     * Show an OpenClaw heartbeat status message as scrolling marquee text
     * directly under the clock. Auto-hides after [displayMs] (default 6 s).
     * Pass 0 for persistent display (no auto-hide).
     * Pass null or blank to hide immediately.
     */
    fun showHeartbeat(message: String?, displayMs: Long = 6_000L) {
        if (!isAdded || !::hudHeartbeatText.isInitialized) return
        uiHandler.removeCallbacks(hideHeartbeatRunnable)

        if (message.isNullOrBlank()) {
            hideHeartbeatRunnable.run()
            return
        }

        val prefix = "\u2764\uFE0F "   // ❤️ heart prefix
        hudHeartbeatText.text = "$prefix$message"
        hudHeartbeatText.visibility = View.VISIBLE
        hudHeartbeatText.animate().cancel()
        hudHeartbeatText.alpha = 1f
        // Marquee requires isSelected = true to scroll
        hudHeartbeatText.isSelected = true

        // displayMs == 0 means persistent (no auto-hide); used for idle status ticker
        if (displayMs > 0L) {
            uiHandler.postDelayed(hideHeartbeatRunnable, displayMs)
        }
    }

    /** Hide the heartbeat bar immediately. */
    fun hideHeartbeat() {
        showHeartbeat(null)
    }

    fun setStreamActiveIndicator(active: Boolean) {
        if (!this::chatStreamIndicator.isInitialized) return
        if (!active) {
            chatStreamIndicator.animate().cancel()
            chatStreamIndicator.alpha = 1f
            chatStreamIndicator.visibility = View.GONE
            return
        }
        // Skip if already pulsing — avoids restarting the animation loop needlessly
        if (chatStreamIndicator.visibility == View.VISIBLE) return
        chatStreamIndicator.visibility = View.VISIBLE
        chatStreamIndicator.alpha = 1f
        chatStreamIndicator.animate()
            .alpha(0.28f)
            .setDuration(520L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction {
                if (!isAdded || !this::chatStreamIndicator.isInitialized || chatStreamIndicator.visibility != View.VISIBLE) {
                    return@withEndAction
                }
                chatStreamIndicator.animate()
                    .alpha(1f)
                    .setDuration(520L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .withEndAction {
                        if (chatStreamIndicator.visibility == View.VISIBLE) {
                            setStreamActiveIndicator(true)
                        }
                    }
                    .start()
            }
            .start()
    }

    private fun enterReaderMode(position: Int, animated: Boolean): Boolean {
        val text = adapter.getCardText(position)?.trim().orEmpty()
        if (text.isBlank()) return false
        readerModeActive = true
        readerText.text = text
        readerScroll.scrollTo(0, 0)
        readerOverlay.visibility = View.VISIBLE
        hudContainer.visibility = View.GONE
        setStreamActiveIndicator(false)
        hideVoiceOscilloscope()
        coreEyeContainer.visibility = View.GONE
        chatRecycler.alpha = 0.20f
        applyFocusVisuals(animate = false)
        if (!animated) {
            readerOverlay.alpha = 1f
            readerOverlay.scaleX = 1f
            readerOverlay.scaleY = 1f
            return true
        }
        readerOverlay.alpha = 0f
        readerOverlay.scaleX = 0.96f
        readerOverlay.scaleY = 0.96f
        readerOverlay.animate()
            .alpha(1f)
            .scaleX(1f)
            .scaleY(1f)
            .setDuration(300L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
        return true
    }

    private fun exitReaderMode(animated: Boolean) {
        if (!readerModeActive) return
        readerModeActive = false
        readerCardUrl = null
        uiHandler.removeCallbacks(pendingUrlOpenRunnable)
        val finalize: () -> Unit = {
            readerOverlay.visibility = View.GONE
            readerOverlay.alpha = 1f
            readerOverlay.scaleX = 1f
            readerOverlay.scaleY = 1f
            hudContainer.visibility = View.VISIBLE
            chatRecycler.alpha = 1f
            if (!hudModeEnabled) {
                coreEyeContainer.visibility = if (coreEyeEnabled) View.VISIBLE else View.GONE
            }
            snapFocusedCard()
        }
        if (!animated) {
            finalize()
            return
        }
        readerOverlay.animate().cancel()
        readerOverlay.animate()
            .alpha(0f)
            .scaleX(0.97f)
            .scaleY(0.97f)
            .setDuration(250L)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .withEndAction(finalize)
            .start()
    }

    // ══════════════════════════════════════════════════════════════════════
    // HUD
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Reorder the HUD info cards (calendar, tasks, news) inside [hudContainer]
     * based on the user's preferred display order stored in preferences.
     * The first two children of hudContainer (time/battery row and connection row)
     * are kept in place; only the card wrappers are reordered.
     */
    private fun applyHudCardOrder() {
        val orderStr = viewModel.preferences.hudDisplayOrder
        val cardMap = mapOf(
            "calendar" to hudCalendarCard,
            "tasks" to hudTasksCard,
            "news" to hudNewsCard
        )
        // Remove all card views from hudContainer
        cardMap.values.forEach { hudContainer.removeView(it) }
        // Re-add in preferred order
        val orderedKeys = orderStr.split(",").map { it.trim() }.filter { it in cardMap }
        // Append any missing keys (in case prefs are incomplete)
        val allKeys = orderedKeys + cardMap.keys.filter { it !in orderedKeys }
        for (key in allKeys) {
            cardMap[key]?.let { hudContainer.addView(it) }
        }
        renderHudSnapshot()
    }

    private fun renderHudSnapshot() {
        val calendarSummary = externalCalendarSummary ?: viewModel.calendarSummary.value
        val tasksSummary = externalTasksSummary ?: viewModel.tasksSummary.value
        val newsSummary = externalNewsSummary ?: viewModel.newsSummary.value
        val airQualityState = externalAirQualityState ?: viewModel.airQualitySummary.value
        val radioState = externalRadioState ?: viewModel.radioSummary.value
        renderCalendarSummary(calendarSummary)
        renderTasksSummary(tasksSummary)
        renderNewsSummary(newsSummary)
        renderAirQualityState(airQualityState)
        renderRadioState(radioState)
    }

    private fun renderCalendarSummary(summary: String) {
        if (viewModel.preferences.hudShowCalendar && summary.isNotBlank()) {
            hudCalendar.text = summary
            hudCalendarCard.visibility = View.VISIBLE
        } else {
            hudCalendarCard.visibility = View.GONE
        }
    }

    private fun renderTasksSummary(summary: String) {
        if (viewModel.preferences.hudShowTasks && summary.isNotBlank()) {
            hudTasks.text = summary
            hudTasksCard.visibility = View.VISIBLE
        } else {
            hudTasksCard.visibility = View.GONE
        }
    }

    private fun renderNewsSummary(summary: String) {
        if (viewModel.preferences.hudShowNews && summary.isNotBlank()) {
            hudNews.text = summary
            hudNewsCard.visibility = View.VISIBLE
        } else {
            hudNewsCard.visibility = View.GONE
        }
    }

    private fun renderAirQualityState(state: MainViewModel.AirQualityHudState?) {
        if (state == null || state.text.isBlank()) {
            hudAqiText.visibility = View.GONE
        } else {
            hudAqiText.text = state.text
            hudAqiText.setTextColor(colorForAqi(state.aqi))
            hudAqiText.visibility = View.VISIBLE
        }
    }

    private fun renderRadioState(state: MainViewModel.RadioHudState?) {
        val stationName = state?.stationName?.trim().orEmpty()
        if (state == null || !state.playing || stationName.isBlank()) {
            hudRadioText.visibility = View.GONE
            return
        }
        hudRadioText.text = stationName
        hudRadioText.visibility = View.VISIBLE
    }

    fun isHudModeEnabled(): Boolean = hudModeEnabled

    fun setHudModeEnabled(enabled: Boolean) {
        if (!this::chatRecycler.isInitialized) return
        if (hudModeEnabled == enabled) return
        if (enabled && readerModeActive) {
            exitReaderMode(animated = false)
        }
        hudModeEnabled = enabled
        hudContainer.visibility = View.VISIBLE
        chatRecycler.visibility = if (enabled) View.GONE else View.VISIBLE
        if (enabled) {
            setStreamActiveIndicator(false)
        }
        if (enabled) {
            hideVoiceOscilloscope()
            coreEyeContainer.visibility = View.GONE
            coreEyeContainer.alpha = 1f
        } else {
            coreEyeContainer.visibility = if (coreEyeEnabled) View.VISIBLE else View.GONE
            snapFocusedCard()
        }
    }

    fun syncHudSnapshot(
        calendarSummary: String,
        tasksSummary: String,
        newsSummary: String,
        airQualityState: MainViewModel.AirQualityHudState?,
        radioState: MainViewModel.RadioHudState? = null
    ) {
        if (!isAdded || !this::hudContainer.isInitialized) return
        externalCalendarSummary = calendarSummary
        externalTasksSummary = tasksSummary
        externalNewsSummary = newsSummary
        externalAirQualityState = airQualityState
        externalRadioState = radioState
        renderCalendarSummary(calendarSummary)
        renderTasksSummary(tasksSummary)
        renderNewsSummary(newsSummary)
        renderAirQualityState(airQualityState)
        renderRadioState(radioState ?: viewModel.radioSummary.value)
    }

    fun setConnectionStatus(status: ConnectionStatus) {
        if (!isAdded || !::hudConnectionDot.isInitialized || !::hudConnectionText.isInitialized) return
        val (label, color, alpha) = when (status) {
            ConnectionStatus.IDLE -> Triple("—", 0xB3FFFFFF.toInt(), 0.72f)
            ConnectionStatus.CONNECTING -> Triple("…", 0xFFFFC857.toInt(), 0.95f)
            ConnectionStatus.GEMINI_CONNECTED -> Triple("G", 0xFF00E676.toInt(), 1f)
            ConnectionStatus.TOOLS_READY -> Triple("G", 0xFF00E676.toInt(), 1f)
            ConnectionStatus.ERROR -> Triple("ERR", 0xFFFF5B5B.toInt(), 1f)
        }
        hudConnectionText.text = label
        hudConnectionText.setTextColor(color)
        hudConnectionText.alpha = alpha

        val dot = (hudConnectionDot.background as? GradientDrawable) ?: GradientDrawable().apply {
            shape = GradientDrawable.OVAL
        }
        dot.setColor(color)
        hudConnectionDot.background = dot
        hudConnectionDot.alpha = alpha
    }

    // ══════════════════════════════════════════════════════════════════════
    // CoreEye camera PIP
    // ══════════════════════════════════════════════════════════════════════

    fun setCoreEyeSurfaceListener(listener: CoreEyeSurfaceListener?) {
        coreEyeSurfaceListener = listener
        if (listener != null && coreEyeSurfaceReady) {
            listener.onSurfaceAvailable()
        }
    }

    fun setCardActionListener(listener: CardActionListener?) {
        cardActionListener = listener
    }

    fun isCoreEyeSurfaceReady(): Boolean = coreEyeSurfaceReady

    fun getCoreEyeSurfaceProvider(): Preview.SurfaceProvider? {
        if (!this::coreEyePreviewTexture.isInitialized || !coreEyeSurfaceReady) return null
        val executor = ContextCompat.getMainExecutor(requireContext())
        return Preview.SurfaceProvider { request ->
            val texture = coreEyePreviewTexture.surfaceTexture
            if (texture == null || !coreEyeSurfaceReady) {
                request.willNotProvideSurface()
                return@SurfaceProvider
            }
            texture.setDefaultBufferSize(request.resolution.width, request.resolution.height)
            val surface = Surface(texture)
            request.provideSurface(surface, executor) { surface.release() }
        }
    }

    fun setCoreEyeCaptureEnabled(enabled: Boolean) {
        coreEyeEnabled = enabled
        if (!enabled) {
            uiHandler.removeCallbacks(coreEyeStreamTimeoutRunnable)
            setCoreEyeStreamingVisuals(active = false)
        }
        if (!hudModeEnabled) {
            coreEyeContainer.visibility = if (enabled) View.VISIBLE else View.GONE
        }
        coreEyeIdleIcon.visibility = if (enabled) View.GONE else View.VISIBLE
    }

    fun onCoreEyeFrameStreamed() {
        if (!coreEyeEnabled) return
        setCoreEyeStreamingVisuals(active = true)
        uiHandler.removeCallbacks(coreEyeStreamTimeoutRunnable)
        uiHandler.postDelayed(coreEyeStreamTimeoutRunnable, 1500L)
    }

    // ══════════════════════════════════════════════════════════════════════
    // Oscilloscope
    // ══════════════════════════════════════════════════════════════════════

    fun pushVoiceOscilloscope(level: Float, color: Int) {
        if (!::inlineOscilloscope.isInitialized) return
        inlineOscilloscope.visibility = View.VISIBLE
        inlineOscilloscope.pushLevel(level, color)
    }

    fun hideVoiceOscilloscope() {
        if (!::inlineOscilloscope.isInitialized) return
        inlineOscilloscope.stop()
        inlineOscilloscope.visibility = View.GONE
    }

    // ══════════════════════════════════════════════════════════════════════
    // Focus management — pure index-driven, NO SnapHelper
    // ══════════════════════════════════════════════════════════════════════

    /**
     * Returns [focusedCardIndex] clamped to valid content range.
     * If it was out of range, it is corrected in place.
     */
    private fun coerceFocusedIndex(): Int {
        val first = adapter.getFirstContentPosition()
        val last = adapter.getLastContentPosition()
        if (focusedCardIndex in first..last) return focusedCardIndex
        focusedCardIndex = last
        return focusedCardIndex
    }

    /**
     * Calculate the exact offset needed so the focused card center aligns with
     * the vertical center of the RecyclerView's visible content area (the region
     * between paddingTop and paddingBottom).  This keeps cards inside the
     * recycler bounds and prevents them from overlapping the HUD above.
     */
    private fun computeFocusOffsetPx(): Int {
        if (!this::chatRecycler.isInitialized) return 0
        val density = resources.displayMetrics.density
        val cardHeightPx = (CARD_HEIGHT_DP * density).toInt()
        val visibleHeight = chatRecycler.height - chatRecycler.paddingTop - chatRecycler.paddingBottom
        val centerOffset = (visibleHeight - cardHeightPx) / 2
        return centerOffset.coerceAtLeast(0)
    }

    /** Set [focusedCardIndex] and place that card at the fixed focus offset. */
    private fun focusCard(position: Int, animate: Boolean) {
        val previous = focusedCardIndex
        val first = adapter.getFirstContentPosition()
        val last = adapter.getLastContentPosition()
        focusedCardIndex = position.coerceIn(first, last)
        if (previous != RecyclerView.NO_POSITION && previous != focusedCardIndex && adapter.isContentPosition(previous)) {
            adapter.notifyItemChanged(previous)
        }
        if (adapter.isContentPosition(focusedCardIndex)) {
            adapter.notifyItemChanged(focusedCardIndex)
        }
        layoutManager.scrollToFocus(focusedCardIndex)
        chatRecycler.post {
            applyFocusVisuals(animate = animate)
            // Second pass after layout settles to catch cards that became
            // visible only after the scroll animation completed.
            chatRecycler.postDelayed({ applyFocusVisuals(animate = false) }, 250L)
        }
        tapBlockedUntilSnap = false
    }

    private fun snapFocusedCard(): Boolean {
        if (adapter.itemCount <= 0) return false
        val previous = focusedCardIndex
        focusedCardIndex = coerceFocusedIndex()
        if (previous != RecyclerView.NO_POSITION && previous != focusedCardIndex && adapter.isContentPosition(previous)) {
            adapter.notifyItemChanged(previous)
        }
        if (adapter.isContentPosition(focusedCardIndex)) {
            adapter.notifyItemChanged(focusedCardIndex)
        }
        layoutManager.scrollToFocus(focusedCardIndex)
        chatRecycler.post {
            applyFocusVisuals(animate = false)
            chatRecycler.postDelayed({ applyFocusVisuals(animate = false) }, 250L)
        }
        return true
    }

    /**
     * Walk every visible child and apply focused / unfocused styling.
     *
     * Uses [RecyclerView.getChildAt] instead of the LayoutManager's
     * findFirst/LastVisibleItemPosition so that views re-attached from
     * the RecyclerView cache (which are NOT rebound) are always caught.
     */
    private fun applyFocusVisuals(animate: Boolean) {
        if (!this::chatRecycler.isInitialized) return
        for (i in 0 until chatRecycler.childCount) {
            val child = chatRecycler.getChildAt(i) ?: continue
            val position = chatRecycler.getChildAdapterPosition(child)
            if (position == RecyclerView.NO_POSITION) continue
            if (!adapter.isContentPosition(position)) continue
            val focusTarget = child.findViewById<View>(R.id.messageBubble) ?: child
            val focused = position == focusedCardIndex
            applyFocusGlow(focusTarget, focused && !readerModeActive)

            val targetScale = if (focused) CARD_FOCUS_SCALE else CARD_UNFOCUSED_SCALE
            val targetAlpha = if (focused) CARD_FOCUS_ALPHA else CARD_UNFOCUSED_ALPHA
            val targetZ = if (focused) CARD_FOCUS_Z else CARD_UNFOCUSED_Z
            setCardState(focusTarget, targetScale, targetAlpha, targetZ, animate)
        }
    }

    private fun setCardState(
        child: View,
        scale: Float,
        alpha: Float,
        z: Float,
        animate: Boolean
    ) {
        if (!animate) {
            child.scaleX = scale
            child.scaleY = scale
            child.alpha = alpha
            child.translationZ = z
            return
        }
        val sameState =
            abs(child.scaleX - scale) < 0.01f &&
                abs(child.alpha - alpha) < 0.01f &&
                abs(child.translationZ - z) < 0.01f
        if (sameState) return

        child.animate().cancel()
        child.animate()
            .scaleX(scale)
            .scaleY(scale)
            .alpha(alpha)
            .translationZ(z)
            .setDuration(CARD_FOCUS_ANIM_MS)
            .setInterpolator(AccelerateDecelerateInterpolator())
            .start()
    }

    private fun applyFocusGlow(target: View, focused: Boolean) {
        if (!focused) {
            if (target.foreground != null) target.foreground = null
            return
        }
        val density = target.resources.displayMetrics.density
        val glow = (target.foreground as? GradientDrawable) ?: GradientDrawable()
        glow.shape = GradientDrawable.RECTANGLE
        glow.cornerRadius = CARD_FOCUS_GLOW_CORNER_DP * density
        glow.setColor(Color.TRANSPARENT)
        glow.setStroke(CARD_FOCUS_GLOW_PX, CARD_FOCUS_GLOW_COLOR)
        target.foreground = glow
    }

    // ══════════════════════════════════════════════════════════════════════
    // Startup binding
    // ══════════════════════════════════════════════════════════════════════

    private fun bindInitialHistoryFromSnapshot() {
        val initialCards = viewModel.getAssistantCardsSnapshot()
        renderedAssistantMessages = initialCards
        adapter.submitMessages(initialCards)
        lastMessageFingerprint = initialCards.joinToString("|") { it.text }.hashCode()
        suppressFirstCollectorFocus = true
        focusedCardIndex = adapter.getLastContentPosition()
        snapFocusedCard()
    }

    private fun playNavigationTick() {
        val audioManager = context?.getSystemService(Context.AUDIO_SERVICE) as? AudioManager ?: return
        runCatching {
            audioManager.playSoundEffect(AudioManager.FX_KEYPRESS_STANDARD, 0.5f)
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CoreEye surface management
    // ══════════════════════════════════════════════════════════════════════

    private fun configureCoreEyeView() {
        coreEyeContainer.visibility = View.GONE
        coreEyePreviewTexture.surfaceTextureListener =
            object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) {
                    coreEyeSurfaceReady = true
                    coreEyeSurfaceListener?.onSurfaceAvailable()
                }

                override fun onSurfaceTextureSizeChanged(
                    surface: android.graphics.SurfaceTexture,
                    width: Int,
                    height: Int
                ) = Unit

                override fun onSurfaceTextureDestroyed(
                    surface: android.graphics.SurfaceTexture
                ): Boolean {
                    coreEyeSurfaceReady = false
                    coreEyeSurfaceListener?.onSurfaceDestroyed()
                    return true
                }

                override fun onSurfaceTextureUpdated(
                    surface: android.graphics.SurfaceTexture
                ) = Unit
            }
        if (coreEyePreviewTexture.isAvailable) {
            coreEyeSurfaceReady = true
            coreEyeSurfaceListener?.onSurfaceAvailable()
        }
        coreEyeRing.visibility = View.GONE
        coreEyeIdleIcon.visibility = View.VISIBLE
        coreEyePreviewTexture.alpha = 1f
    }

    // ══════════════════════════════════════════════════════════════════════
    // Battery HUD
    // ══════════════════════════════════════════════════════════════════════

    private fun refreshBatteryStatusHud() {
        if (!isAdded || !::hudBatteryText.isInitialized || !::hudBatteryIcon.isInitialized) return
        val batteryIntent = runCatching {
            requireContext().registerReceiver(
                null,
                IntentFilter(Intent.ACTION_BATTERY_CHANGED)
            )
        }.getOrNull() ?: return

        val level = batteryIntent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = batteryIntent.getIntExtra(BatteryManager.EXTRA_SCALE, 100).coerceAtLeast(1)
        val status = batteryIntent.getIntExtra(
            BatteryManager.EXTRA_STATUS,
            BatteryManager.BATTERY_STATUS_UNKNOWN
        )
        val charging =
            status == BatteryManager.BATTERY_STATUS_CHARGING ||
                status == BatteryManager.BATTERY_STATUS_FULL

        val percent = if (level >= 0) ((level * 100f) / scale).toInt().coerceIn(0, 100) else -1
        hudBatteryText.text = if (percent >= 0) "$percent%" else "--%"

        val tint = when {
            charging -> 0xFF00FFFF.toInt()
            percent in 0..15 -> 0xFFFF5B5B.toInt()
            percent in 16..35 -> 0xFFFFB14A.toInt()
            else -> 0xFFFFFFFF.toInt()
        }
        hudBatteryIcon.alpha = if (percent >= 0) 1f else 0.5f
        hudBatteryText.alpha = if (percent >= 0) 1f else 0.5f
        hudBatteryIcon.setColorFilter(tint)
        hudBatteryText.setTextColor(tint)
    }

    private fun colorForAqi(aqi: Int?): Int {
        val value = aqi ?: return 0xCCFFFFFF.toInt()
        return when {
            value <= 50 -> 0xFF00E676.toInt()
            value <= 100 -> 0xFFFFEB3B.toInt()
            value <= 150 -> 0xFFFF9800.toInt()
            value <= 200 -> 0xFFFF5B5B.toInt()
            value <= 300 -> 0xFFBA68C8.toInt()
            else -> 0xFF8E24AA.toInt()
        }
    }

    // ══════════════════════════════════════════════════════════════════════
    // CoreEye streaming ring animation
    // ══════════════════════════════════════════════════════════════════════

    private fun setCoreEyeStreamingVisuals(active: Boolean) {
        if (coreEyeStreaming == active) return
        coreEyeStreaming = active
        if (active) {
            coreEyeRing.visibility = View.VISIBLE
            if (coreEyePulseAnimator == null) {
                coreEyePulseAnimator = ValueAnimator.ofFloat(0.4f, 1f).apply {
                    duration = 720L
                    repeatMode = ValueAnimator.REVERSE
                    repeatCount = ValueAnimator.INFINITE
                    addUpdateListener { anim ->
                        coreEyeRing.alpha = anim.animatedValue as Float
                    }
                }
            }
            coreEyePulseAnimator?.start()
        } else {
            coreEyePulseAnimator?.cancel()
            coreEyeRing.alpha = 0f
            coreEyeRing.visibility = View.GONE
        }
    }
}
