package com.rayneo.visionclaw.ui.panels.chat

import android.graphics.Color
import android.text.SpannableString
import android.text.Spanned
import android.text.method.LinkMovementMethod
import android.text.style.ForegroundColorSpan
import android.util.Patterns
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.rayneo.visionclaw.R
import com.rayneo.visionclaw.core.model.ChatMessage

class ChatAdapter(
    private val onUrlTapped: (String) -> Unit,
    private val onAssistantRequested: () -> Unit
) : RecyclerView.Adapter<RecyclerView.ViewHolder>() {

    companion object {
        private const val MAX_HISTORY_CARDS = 20
        private const val CARD_HEIGHT_DP = 220f

        const val VIEW_TYPE_CHAT_CARD = 1
        const val VIEW_TYPE_SENTINEL_CARD = 2
    }

    sealed class CardItem {
        data class MessageCard(val message: ChatMessage) : CardItem()
        data object NewChatCard : CardItem()
    }

    private data class UrlMatch(
        val raw: String,
        val normalized: String,
        val start: Int,
        val end: Int
    )

    private val chatHistory = mutableListOf<ChatMessage>()

    /**
     * The adapter-level focused position.  Updated by the fragment whenever
     * [focusedCardIndex] changes.  Used during [onBindViewHolder] to apply
     * an initial focused / unfocused alpha so that newly-bound cards
     * (including the New Chat sentinel) are *always* correctly dimmed,
     * even when the fragment's post-layout [applyFocusVisuals] hasn't
     * run yet.
     */
    var focusedPosition: Int = RecyclerView.NO_POSITION

    fun submitMessages(messages: List<ChatMessage>) {
        chatHistory.clear()
        chatHistory += messages.takeLast(MAX_HISTORY_CARDS)
        notifyDataSetChanged()
    }

    fun getFirstContentPosition(): Int = 0

    /** The sentinel is always the final position. */
    fun getLastContentPosition(): Int = chatHistory.size

    fun getLatestMessagePosition(): Int {
        return if (chatHistory.isEmpty()) getLastContentPosition() else chatHistory.size - 1
    }

    fun isContentPosition(position: Int): Boolean {
        return position in getFirstContentPosition()..getLastContentPosition()
    }

    private fun cardItemForPosition(position: Int): CardItem {
        return if (isNewChatCard(position)) {
            CardItem.NewChatCard
        } else {
            val messageIndex = position.coerceIn(0, chatHistory.lastIndex)
            CardItem.MessageCard(chatHistory[messageIndex])
        }
    }

    fun isNewChatCard(position: Int): Boolean {
        return position == getLastContentPosition()
    }

    fun getCardUrl(position: Int): String? {
        if (!isContentPosition(position) || isNewChatCard(position)) return null
        if (position !in chatHistory.indices) return null
        return findUrls(chatHistory[position].text).firstOrNull()?.normalized
    }

    fun getCardText(position: Int): String? {
        if (!isContentPosition(position) || isNewChatCard(position)) return null
        if (position !in chatHistory.indices) return null
        return chatHistory[position].text
    }

    override fun getItemCount(): Int = chatHistory.size + 1

    override fun getItemViewType(position: Int): Int {
        return if (isNewChatCard(position)) VIEW_TYPE_SENTINEL_CARD else VIEW_TYPE_CHAT_CARD
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RecyclerView.ViewHolder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_chat_message, parent, false)
        return ChatViewHolder(view)
    }

    override fun onBindViewHolder(holder: RecyclerView.ViewHolder, position: Int) {
        when (holder) {
            is ChatViewHolder -> {
                holder.bind(cardItemForPosition(position), onUrlTapped)
                // Apply initial focused / unfocused alpha so that every card
                // — including the New Chat sentinel — is correctly dimmed the
                // instant it appears, before the fragment's applyFocusVisuals
                // can run its post-layout pass.
                val focused = position == focusedPosition
                val bubble = holder.itemView.findViewById<View>(R.id.messageBubble)
                if (bubble != null) {
                    bubble.alpha = if (focused) 1.0f else 0.15f
                    bubble.scaleX = if (focused) 1.15f else 0.75f
                    bubble.scaleY = if (focused) 1.15f else 0.75f
                }
            }
        }
    }

    inner class ChatViewHolder(itemView: View) : RecyclerView.ViewHolder(itemView) {
        private val rowRoot: LinearLayout = itemView.findViewById(R.id.messageRow)
        private val bubble: LinearLayout = itemView.findViewById(R.id.messageBubble)
        private val messageText: TextView = itemView.findViewById(R.id.messageText)
        private val messageScrollView: ScrollView = itemView.findViewById(R.id.messageScrollView)
        private val launchCard: LinearLayout = itemView.findViewById(R.id.launchCard)
        private val launchCardUrl: TextView = itemView.findViewById(R.id.launchCardUrl)

        fun bind(item: CardItem, onUrlTapped: (String) -> Unit) {
            when (item) {
                is CardItem.NewChatCard -> bindNewChatCard()
                is CardItem.MessageCard -> bindMessageCard(item.message, onUrlTapped)
            }
        }

        private fun bindNewChatCard() {
            rowRoot.gravity = Gravity.CENTER_HORIZONTAL
            centerBubble()

            setUniformCardHeight()
            bubble.minimumHeight = 0
            bubble.gravity = Gravity.CENTER
            bubble.setBackgroundResource(R.drawable.bg_chat_bubble_assistant)

            messageText.movementMethod = null
            messageText.gravity = Gravity.CENTER
            messageText.text = "New Chat"
            messageText.setTextColor(Color.parseColor("#FFFFFFFF"))

            launchCard.visibility = View.GONE
            launchCard.setOnClickListener(null)
            itemView.setOnClickListener { onAssistantRequested() }
        }

        private fun bindMessageCard(message: ChatMessage, onUrlTapped: (String) -> Unit) {
            val isUser = message.fromUser
            rowRoot.gravity = Gravity.CENTER_HORIZONTAL
            centerBubble()

            setUniformCardHeight()
            bubble.minimumHeight = 0
            bubble.gravity = Gravity.NO_GRAVITY
            bubble.setBackgroundResource(
                if (isUser) R.drawable.bg_chat_bubble_user else R.drawable.bg_chat_bubble_assistant
            )

            val urls = findUrls(message.text)
            messageText.movementMethod = LinkMovementMethod.getInstance()
            messageText.highlightColor = Color.TRANSPARENT
            messageText.gravity = Gravity.START
            messageText.text = buildLinkedText(message.text, urls, onUrlTapped)

            val launchUrl = urls.firstOrNull()
            if (!isUser && launchUrl != null) {
                launchCard.visibility = View.VISIBLE
                launchCardUrl.text = launchUrl.raw
                // No direct click listeners — taps route through
                // handleFocusedCardTap() which handles expand → open → close flow.
                launchCard.setOnClickListener(null)
                itemView.setOnClickListener(null)
            } else {
                launchCard.visibility = View.GONE
                launchCard.setOnClickListener(null)
                itemView.setOnClickListener(null)
            }
        }

        private fun centerBubble() {
            val params = bubble.layoutParams as? LinearLayout.LayoutParams ?: return
            if (params.gravity == Gravity.CENTER_HORIZONTAL) return
            params.gravity = Gravity.CENTER_HORIZONTAL
            bubble.layoutParams = params
        }

        private fun dpToPx(dp: Float): Int {
            return (dp * itemView.resources.displayMetrics.density).toInt()
        }

        private fun setUniformCardHeight() {
            val targetHeightPx = dpToPx(CARD_HEIGHT_DP)
            val params = rowRoot.layoutParams
            if (params != null && params.height != targetHeightPx) {
                params.height = targetHeightPx
                rowRoot.layoutParams = params
            }
            rowRoot.minimumHeight = targetHeightPx
        }

        private fun buildLinkedText(
            text: String,
            urls: List<UrlMatch>,
            onUrlTapped: (String) -> Unit
        ): SpannableString {
            val spannable = SpannableString(text)
            urls.forEach { match ->
                // Highlight URLs in cyan but don't attach click listeners —
                // taps route through handleFocusedCardTap() for expand/open flow.
                spannable.setSpan(
                    ForegroundColorSpan(Color.parseColor("#00FFFF")),
                    match.start,
                    match.end,
                    Spanned.SPAN_EXCLUSIVE_EXCLUSIVE
                )
            }
            return spannable
        }
    }

    private fun findUrls(text: String): List<UrlMatch> {
        val matches = ArrayList<UrlMatch>()
        val matcher = Patterns.WEB_URL.matcher(text)
        while (matcher.find()) {
            val start = matcher.start()
            val end = matcher.end()
            val raw = text.substring(start, end)
            matches += UrlMatch(
                raw = raw,
                normalized = normalizeUrl(raw),
                start = start,
                end = end
            )
        }
        return matches
    }

    private fun normalizeUrl(raw: String): String {
        val sanitized = raw.trim().trimEnd('.', ',', ';', ':', ')', ']', '}', '!', '?')
        return if (sanitized.startsWith("http://") || sanitized.startsWith("https://")) {
            sanitized
        } else {
            "https://$sanitized"
        }
    }
}
