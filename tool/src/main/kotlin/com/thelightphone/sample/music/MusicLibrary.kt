package com.thelightphone.sample.music

import android.net.Uri
import android.os.ParcelFileDescriptor
import com.thelightphone.sdk.LightAudioLibrary
import com.thelightphone.sdk.LightAudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext

/**
 * Loads the device's audio library from MediaStore (via the SDK's
 * [LightAudioLibrary]) and holds the result as observable state. A process-level
 * singleton so the library survives navigation and is scanned at most once per
 * launch. Also the single owner of the [LightAudioLibrary] handle that
 * [MusicPlayer] uses to open track descriptors.
 */
object MusicLibrary {

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks: StateFlow<List<Track>> = _tracks.asStateFlow()

    private val _loading = MutableStateFlow(false)
    val loading: StateFlow<Boolean> = _loading.asStateFlow()

    @Volatile
    private var loaded = false

    @Volatile
    private var audioLibrary: LightAudioLibrary? = null

    /** Loads the library the first time only; subsequent calls are no-ops. */
    suspend fun ensureLoaded(library: LightAudioLibrary) {
        audioLibrary = library
        if (loaded) return
        reload(library)
    }

    /** Re-queries MediaStore, replacing the current library. */
    suspend fun reload(library: LightAudioLibrary): Unit = withContext(Dispatchers.IO) {
        audioLibrary = library
        _loading.value = true
        try {
            _tracks.value = library.queryTracks()
                .map { it.toTrack() }
                .sortedWith(
                    compareBy<Track>(
                        { it.artist.lowercase() },
                        { it.album.lowercase() },
                        { it.trackNumber },
                        { it.title.lowercase() },
                    ),
                )
            loaded = true
        } finally {
            _loading.value = false
        }
    }

    /** Opens a read-only descriptor for a track's content URI (used by MusicPlayer). */
    fun openFd(uri: Uri): ParcelFileDescriptor? = audioLibrary?.openFileDescriptor(uri)

    private fun LightAudioTrack.toTrack(): Track = Track(
        uri = uri,
        title = title.orUnknown("Untitled"),
        artist = artist.orUnknown(UNKNOWN_ARTIST),
        album = album.orUnknown(UNKNOWN_ALBUM),
        genre = genre.orUnknown(UNKNOWN_GENRE),
        trackNumber = trackNumber,
        durationMs = durationMs,
    )

    // MediaStore stores missing artist/album as the literal "<unknown>".
    private fun String?.orUnknown(fallback: String): String {
        val trimmed = this?.trim()
        return if (trimmed.isNullOrEmpty() || trimmed == "<unknown>") fallback else trimmed
    }
}
