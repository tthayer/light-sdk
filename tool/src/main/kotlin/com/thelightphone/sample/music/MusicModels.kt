package com.thelightphone.sample.music

import android.net.Uri

/** A single playable track from the device's MediaStore audio library. */
data class Track(
    val uri: Uri,
    val title: String,
    val artist: String,
    val album: String,
    val genre: String,
    val trackNumber: Int,
    val durationMs: Long,
)

/** Snapshot of the shared [MusicPlayer] engine, observed by every screen. */
data class PlaybackState(
    val current: Track? = null,
    val isPlaying: Boolean = false,
    val positionMs: Int = 0,
    val durationMs: Int = 0,
    val shuffle: Boolean = false,
    val queueSize: Int = 0,
    val queuePosition: Int = 0,
)

const val UNKNOWN_ARTIST = "Unknown Artist"
const val UNKNOWN_ALBUM = "Unknown Album"
const val UNKNOWN_GENRE = "Unknown Genre"

private val caseInsensitive = String.CASE_INSENSITIVE_ORDER

/** Distinct artist names, alphabetized case-insensitively. */
fun List<Track>.artists(): List<String> =
    map { it.artist }.distinct().sortedWith(caseInsensitive)

/** Distinct album names, alphabetized case-insensitively. */
fun List<Track>.albums(): List<String> =
    map { it.album }.distinct().sortedWith(caseInsensitive)

/** Distinct genre names, alphabetized case-insensitively. */
fun List<Track>.genres(): List<String> =
    map { it.genre }.distinct().sortedWith(caseInsensitive)
