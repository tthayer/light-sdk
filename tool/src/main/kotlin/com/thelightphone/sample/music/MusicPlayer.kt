package com.thelightphone.sample.music

import android.media.AudioAttributes
import android.media.MediaPlayer
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import android.os.ParcelFileDescriptor
import java.io.IOException

/**
 * Process-level playback engine wrapping a single [MediaPlayer]. Lives above the
 * per-screen ViewModel lifecycle so audio keeps playing as the user navigates.
 * All UI observes [state]; controls call the engine directly.
 *
 * Not thread-safe by design: every entry point is invoked from the Compose main
 * thread, and the position ticker also runs on [Dispatchers.Main].
 */
object MusicPlayer {

    private const val TAG = "MusicPlayer"
    private const val RESTART_THRESHOLD_MS = 3_000
    private const val TICK_INTERVAL_MS = 500L

    private val _state = MutableStateFlow(PlaybackState())
    val state: StateFlow<PlaybackState> = _state.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var ticker: Job? = null

    private var player: MediaPlayer? = null
    // Kept open for the lifetime of the current player: MediaPlayer reads through
    // this descriptor and it must outlive prepare/playback.
    private var currentPfd: ParcelFileDescriptor? = null
    private var queue: List<Track> = emptyList()

    // Playback order: indices into [queue]. Sequential, or a shuffled permutation.
    private var order: List<Int> = emptyList()
    private var orderPos: Int = 0
    private var shuffle: Boolean = false
    private var failuresThisStart: Int = 0

    /** Plays [tracks], beginning at [startIndex] (or a fresh shuffle of the set). */
    fun play(tracks: List<Track>, startIndex: Int, shuffle: Boolean = false) {
        if (tracks.isEmpty()) return
        queue = tracks
        this.shuffle = shuffle
        val safeStart = startIndex.coerceIn(0, tracks.lastIndex)
        order = if (shuffle) shuffledOrder(tracks.size, safeStart) else tracks.indices.toList()
        orderPos = if (shuffle) 0 else safeStart
        failuresThisStart = 0
        startCurrent()
    }

    /** Shuffle-plays the entire supplied set from the main menu. */
    fun shuffleAll(tracks: List<Track>) = play(tracks, startIndex = 0, shuffle = true)

    fun togglePlayPause() {
        val mp = player
        if (mp == null) {
            if (queue.isNotEmpty()) startCurrent()
            return
        }
        if (isPlayingSafely()) {
            runCatching { mp.pause() }
            stopTicker()
        } else {
            runCatching { mp.start() }
            startTicker()
        }
        publish()
    }

    fun next() {
        failuresThisStart = 0
        if (orderPos < order.lastIndex) {
            orderPos++
            startCurrent()
        } else {
            finishQueue()
        }
    }

    fun previous() {
        val mp = player
        // Restart the current track if we're past the grace window (iPod behavior).
        if (mp != null && positionSafely(mp) > RESTART_THRESHOLD_MS) {
            runCatching { mp.seekTo(0) }
            publish()
            return
        }
        failuresThisStart = 0
        if (orderPos > 0) {
            orderPos--
            startCurrent()
        } else {
            runCatching { mp?.seekTo(0) }
            publish()
        }
    }

    fun seekTo(positionMs: Int) {
        runCatching { player?.seekTo(positionMs.coerceAtLeast(0)) }
        publish()
    }

    /** Re-derives the play order in place, keeping the current track selected. */
    fun toggleShuffle() {
        if (queue.isEmpty()) return
        val currentQueueIndex = order.getOrNull(orderPos) ?: return
        shuffle = !shuffle
        order = if (shuffle) shuffledOrder(queue.size, currentQueueIndex) else queue.indices.toList()
        orderPos = order.indexOf(currentQueueIndex).coerceAtLeast(0)
        publish()
    }

    private fun shuffledOrder(size: Int, firstQueueIndex: Int): List<Int> {
        val rest = (0 until size).filter { it != firstQueueIndex }.shuffled()
        return listOf(firstQueueIndex) + rest
    }

    private fun currentTrack(): Track? = order.getOrNull(orderPos)?.let { queue.getOrNull(it) }

    private fun startCurrent() {
        val track = currentTrack() ?: return
        releasePlayer()

        val mp = MediaPlayer()
        player = mp
        try {
            // Open the MediaStore content URI and feed MediaPlayer the raw
            // FileDescriptor. (A FileDescriptor also sidesteps the emulator
            // NuPlayer error -38 seen with path-based data sources.)
            val pfd = MusicLibrary.openFd(track.uri)
                ?: throw IOException("could not open ${track.uri}")
            currentPfd = pfd
            mp.setDataSource(pfd.fileDescriptor)
            mp.setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            mp.setOnCompletionListener { onCompletion() }
            mp.setOnErrorListener { _, what, extra ->
                Log.e(TAG, "MediaPlayer error what=$what extra=$extra for ${track.uri}")
                skipAfterError()
                true
            }
            // Local files: prepare synchronously so a failure surfaces as a
            // descriptive exception instead of a bare async error code.
            mp.prepare()
            failuresThisStart = 0
            mp.start()
            startTicker()
            publish()
        } catch (e: Exception) {
            Log.e(TAG, "Failed to start ${track.uri}", e)
            skipAfterError()
        }
    }

    private fun skipAfterError() {
        releasePlayer()
        failuresThisStart++
        // Give up once we've failed more times than there are tracks to try.
        if (failuresThisStart > queue.size) {
            failuresThisStart = 0
            finishQueue()
            return
        }
        if (orderPos < order.lastIndex) {
            orderPos++
            startCurrent()
        } else {
            finishQueue()
        }
    }

    private fun onCompletion() {
        if (orderPos < order.lastIndex) {
            orderPos++
            startCurrent()
        } else {
            finishQueue()
        }
    }

    private fun finishQueue() {
        stopTicker()
        runCatching { player?.seekTo(0) }
        _state.update { it.copy(isPlaying = false, positionMs = 0) }
    }

    private fun startTicker() {
        stopTicker()
        ticker = scope.launch {
            while (isActive) {
                publish()
                delay(TICK_INTERVAL_MS)
            }
        }
    }

    private fun stopTicker() {
        ticker?.cancel()
        ticker = null
    }

    private fun isPlayingSafely(): Boolean =
        runCatching { player?.isPlaying == true }.getOrDefault(false)

    private fun positionSafely(mp: MediaPlayer): Int =
        runCatching { mp.currentPosition }.getOrDefault(0)

    private fun publish() {
        val mp = player
        val track = currentTrack()
        _state.update {
            it.copy(
                current = track,
                isPlaying = isPlayingSafely(),
                positionMs = mp?.let { p -> positionSafely(p) } ?: 0,
                durationMs = mp?.let { p -> runCatching { p.duration }.getOrDefault(-1) }
                    ?.takeIf { d -> d > 0 }
                    ?: track?.durationMs?.toInt()
                    ?: 0,
                shuffle = shuffle,
                queueSize = queue.size,
                queuePosition = orderPos,
            )
        }
    }

    private fun releasePlayer() {
        stopTicker()
        player?.let { mp ->
            runCatching { mp.reset() }
            runCatching { mp.release() }
        }
        player = null
        currentPfd?.let { runCatching { it.close() } }
        currentPfd = null
    }
}
