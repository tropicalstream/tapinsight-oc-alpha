package com.rayneo.visionclaw.core.audio

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Build
import android.os.SystemClock
import android.util.Log
import kotlin.math.abs
import kotlin.math.max

/**
 * Streams Gemini Live PCM chunks directly to device audio using AudioTrack.
 */
class GeminiAudioPlayer(context: Context) {

    /** Callback invoked on a background thread once the AudioTrack buffer has drained. */
    var onDrainComplete: (() -> Unit)? = null

    companion object {
        private const val TAG = "GeminiAudioPlayer"
        private const val DEFAULT_SAMPLE_RATE = 24_000
    }

    private val appContext = context.applicationContext
    private val audioManager = appContext.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    private val lock = Any()
    private var audioTrack: AudioTrack? = null
    private var trackSampleRate = 0
    private var loggedPlaybackStart = false
    private var hasAudioFocus = false
    private var focusRequest: AudioFocusRequest? = null
    /** Set to true when the server turn is complete; the drain thread watches this. */
    @Volatile private var drainPending = false
    private var drainThread: Thread? = null
    @Volatile private var lastOutputLevel = 0f
    @Volatile private var lastOutputAtMs = 0L

    private val focusChangeListener = AudioManager.OnAudioFocusChangeListener { change ->
        if (change <= AudioManager.AUDIOFOCUS_LOSS) {
            synchronized(lock) {
                hasAudioFocus = false
                runCatching { audioTrack?.pause() }
            }
        }
    }

    fun playChunk(
        mimeType: String,
        data: ByteArray,
        muted: Boolean,
        volume: Float
    ) {
        if (data.isEmpty() || muted) return
        val sampleRate = parseSampleRate(mimeType) ?: DEFAULT_SAMPLE_RATE

        synchronized(lock) {
            requestAudioFocusLocked()
            val track = ensureTrackLocked(sampleRate) ?: return
            val safeVolume = volume.coerceIn(0f, 1f)
            runCatching { track.setVolume(safeVolume) }
            if (track.playState != AudioTrack.PLAYSTATE_PLAYING) {
                runCatching { track.play() }
            }
            val written = track.write(data, 0, data.size, AudioTrack.WRITE_BLOCKING)
            lastOutputLevel = calculatePcm16Level(data)
            lastOutputAtMs = SystemClock.uptimeMillis()
            if (!loggedPlaybackStart && written > 0) {
                loggedPlaybackStart = true
                Log.d(TAG, "Streaming Gemini audio sampleRate=$sampleRate bytes=$written")
            }
            if (written < 0) {
                Log.w(TAG, "AudioTrack write failed: $written")
            }
        }
    }

    /**
     * Called when the server signals turn-complete.  Starts a background thread
     * that waits for the AudioTrack to finish playing all buffered audio, then
     * invokes [onDrainComplete] so the caller can arm the silence watchdog only
     * after the user has heard every word.
     */
    fun notifyTurnComplete() {
        drainPending = true
        if (drainThread?.isAlive == true) return  // already watching
        drainThread = Thread({
            try {
                // Poll AudioTrack head position until it stops advancing,
                // meaning all buffered data has been played.
                var stallCount = 0
                var lastHead = -1
                while (drainPending) {
                    val head = synchronized(lock) {
                        audioTrack?.playbackHeadPosition ?: -1
                    }
                    if (head < 0) break   // track gone
                    if (head == lastHead) {
                        stallCount++
                        // 6 × 50ms = 300ms of no advancement → drained
                        if (stallCount >= 6) break
                    } else {
                        stallCount = 0
                        lastHead = head
                    }
                    Thread.sleep(50)
                }
            } catch (_: InterruptedException) { }
            drainPending = false
            Log.d(TAG, "Audio drain complete — invoking callback")
            onDrainComplete?.invoke()
        }, "GeminiAudioDrain").also { it.isDaemon = true; it.start() }
    }

    /** Cancel any pending drain watcher (e.g. when a new turn starts). */
    fun cancelDrain() {
        drainPending = false
    }

    fun currentOutputLevel(): Float = lastOutputLevel

    fun isActivelySpeaking(windowMs: Long = 350L): Boolean {
        return SystemClock.uptimeMillis() - lastOutputAtMs <= windowMs && lastOutputLevel > 0.01f
    }

    fun stopAndFlush() {
        drainPending = false
        synchronized(lock) {
            val track = audioTrack ?: return
            runCatching { track.pause() }
            runCatching { track.flush() }
            loggedPlaybackStart = false
            lastOutputLevel = 0f
            abandonAudioFocusLocked()
        }
    }

    fun release() {
        drainPending = false
        synchronized(lock) {
            val track = audioTrack
            audioTrack = null
            trackSampleRate = 0
            loggedPlaybackStart = false
            lastOutputLevel = 0f
            runCatching { track?.pause() }
            runCatching { track?.flush() }
            runCatching { track?.release() }
            abandonAudioFocusLocked()
        }
    }

    private fun ensureTrackLocked(sampleRate: Int): AudioTrack? {
        val existing = audioTrack
        if (existing != null &&
            trackSampleRate == sampleRate &&
            existing.state == AudioTrack.STATE_INITIALIZED
        ) {
            return existing
        }

        runCatching {
            existing?.pause()
            existing?.flush()
            existing?.release()
        }

        val minBuffer = AudioTrack.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (minBuffer <= 0) {
            Log.w(TAG, "Invalid AudioTrack minBufferSize: $minBuffer")
            audioTrack = null
            trackSampleRate = 0
            return null
        }

        val track = runCatching {
            AudioTrack.Builder()
                .setAudioAttributes(
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_MEDIA)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                        .build()
                )
                .setAudioFormat(
                    AudioFormat.Builder()
                        .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                        .setSampleRate(sampleRate)
                        .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                        .build()
                )
                .setBufferSizeInBytes(max(minBuffer * 2, 4096))
                .setTransferMode(AudioTrack.MODE_STREAM)
                .build()
        }.getOrNull()

        if (track == null || track.state != AudioTrack.STATE_INITIALIZED) {
            Log.w(TAG, "Failed to initialize AudioTrack for sampleRate=$sampleRate")
            runCatching { track?.release() }
            audioTrack = null
            trackSampleRate = 0
            loggedPlaybackStart = false
            return null
        }

        audioTrack = track
        trackSampleRate = sampleRate
        loggedPlaybackStart = false
        return track
    }

    private fun parseSampleRate(mimeType: String): Int? {
        val match = Regex("""rate=(\d+)""").find(mimeType)
        return match?.groupValues?.getOrNull(1)?.toIntOrNull()
    }

    private fun calculatePcm16Level(data: ByteArray): Float {
        if (data.size < 2) return 0f
        var peak = 0
        var i = 0
        while (i + 1 < data.size) {
            val sample = ((data[i + 1].toInt() shl 8) or (data[i].toInt() and 0xFF)).toShort().toInt()
            peak = max(peak, abs(sample))
            i += 2
        }
        return (peak / 32767f).coerceIn(0f, 1f)
    }

    private fun requestAudioFocusLocked() {
        if (hasAudioFocus) return
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
        if (!hasAudioFocus) {
            Log.w(TAG, "Audio focus not granted for Gemini playback")
        }
    }

    private fun abandonAudioFocusLocked() {
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
