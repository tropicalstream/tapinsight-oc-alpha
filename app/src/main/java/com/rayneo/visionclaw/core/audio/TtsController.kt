package com.rayneo.visionclaw.core.audio

import android.content.Context
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.speech.tts.UtteranceProgressListener
import android.speech.tts.TextToSpeech
import android.util.Log
import com.rayneo.visionclaw.core.storage.AppPreferences
import java.util.Locale

class TtsController(
    context: Context,
    private val preferences: AppPreferences
) {

    companion object {
        private const val TAG = "TtsController"
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private var tts: TextToSpeech? = null
    private var ready = false
    private var initializing = false
    private var pendingUtterance: String? = null
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null
    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= AudioManager.AUDIOFOCUS_LOSS) {
            stop()
            abandonAudioFocus()
        }
    }

    init {
        initTts()
    }

    /**
     * Speak text aloud. Set [force] to true for voice-session audio that should
     * always play regardless of the auto-read preference.
     */
    fun speak(text: String, force: Boolean = false) {
        if (preferences.ttsMuted || text.isBlank()) return
        if (!force && !preferences.ttsAutoRead) return
        if (!requestAudioFocus()) {
            Log.w(TAG, "Audio focus not granted for TTS")
            return
        }
        if (!ready || tts == null) {
            pendingUtterance = text
            initTts()
            return
        }
        speakInternal(text)
    }

    private fun speakInternal(text: String) {
        // Apply user-configured speech rate (0 or negative = default 1.0)
        val rate = preferences.ttsSpeechRate
        tts?.setSpeechRate(if (rate > 0f) rate else 1.0f)

        val params = Bundle().apply {
            putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, preferences.ttsVolume)
            putInt(TextToSpeech.Engine.KEY_PARAM_STREAM, AudioManager.STREAM_MUSIC)
        }

        val result = tts?.speak(text, TextToSpeech.QUEUE_FLUSH, params, "panel_readout")
        if (result != TextToSpeech.SUCCESS) {
            Log.w(TAG, "tts.speak failed; reinitializing")
            pendingUtterance = text
            initTts(force = true)
        }
    }

    fun stop() {
        tts?.stop()
    }

    fun isSpeaking(): Boolean = tts?.isSpeaking == true

    fun shutdown() {
        tts?.stop()
        tts?.shutdown()
        tts = null
        ready = false
        pendingUtterance = null
        initializing = false
        abandonAudioFocus()
    }

    private fun initTts(force: Boolean = false) {
        if (initializing) return
        if (!force && tts != null && ready) return
        initializing = true
        if (force) {
            runCatching { tts?.stop() }
            runCatching { tts?.shutdown() }
            tts = null
            ready = false
        }

        tts = TextToSpeech(appContext) { status ->
            initializing = false
            ready = status == TextToSpeech.SUCCESS
            if (!ready) {
                Log.w(TAG, "TTS init failed")
                return@TextToSpeech
            }
            tts?.language = Locale.US
            tts?.setOnUtteranceProgressListener(
                object : UtteranceProgressListener() {
                    override fun onStart(utteranceId: String?) = Unit
                    override fun onDone(utteranceId: String?) {
                        abandonAudioFocus()
                    }
                    override fun onError(utteranceId: String?) {
                        abandonAudioFocus()
                    }
                }
            )
            pendingUtterance?.let { queued ->
                pendingUtterance = null
                speakInternal(queued)
            }
        }
    }

    private fun requestAudioFocus(): Boolean {
        if (hasAudioFocus) return true
        val result =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val req =
                    focusRequest
                        ?: AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                            .setAudioAttributes(
                                AudioAttributes.Builder()
                                    .setUsage(AudioAttributes.USAGE_MEDIA)
                                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                                    .build()
                            )
                            .setOnAudioFocusChangeListener(focusChangeListener)
                            .build()
                            .also { focusRequest = it }
                audioManager.requestAudioFocus(req)
            } else {
                @Suppress("DEPRECATION")
                audioManager.requestAudioFocus(
                    focusChangeListener,
                    AudioManager.STREAM_MUSIC,
                    AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                )
            }
        hasAudioFocus = result == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
        return hasAudioFocus
    }

    private fun abandonAudioFocus() {
        if (!hasAudioFocus) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            focusRequest?.let { audioManager.abandonAudioFocusRequest(it) }
        } else {
            @Suppress("DEPRECATION")
            audioManager.abandonAudioFocus(focusChangeListener)
        }
        hasAudioFocus = false
    }
}
