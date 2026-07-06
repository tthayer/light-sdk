package com.thelightphone.sample.music

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp

/**
 * A flat, ordered list of songs. Selecting one starts the whole list as the
 * playback queue at that index and opens Now Playing. The currently playing
 * track is marked with a speaker icon.
 */
class SongListScreen(
    sealedActivity: SealedLightActivity,
    private val title: String,
    private val tracks: List<Track>,
) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val playback by MusicPlayer.state.collectAsState()

        MusicScaffold(title = title, onBack = { goBack() }) {
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                tracks.forEachIndexed { index, track ->
                    TrackRow(
                        title = track.title,
                        secondary = track.artist,
                        isCurrent = playback.current?.uri == track.uri,
                        onClick = {
                            MusicPlayer.play(tracks, startIndex = index)
                            navigateTo({ NowPlayingScreen(it) })
                        },
                    )
                }
            }
        }
    }
}
