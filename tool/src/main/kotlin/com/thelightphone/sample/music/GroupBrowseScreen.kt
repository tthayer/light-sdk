package com.thelightphone.sample.music

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.gridUnitsAsDp

/** The three ways the classic menu groups a library. */
enum class BrowseKind(val title: String) {
    Artist("Artists"),
    Album("Albums"),
    Genre("Genres"),
}

/**
 * Lists the distinct groups (artists / albums / genres) for [tracks]. Selecting
 * a group drills into a [SongListScreen] filtered to that group.
 */
class GroupBrowseScreen(
    sealedActivity: SealedLightActivity,
    private val kind: BrowseKind,
    private val tracks: List<Track>,
) : SimpleLightScreen<Unit>(sealedActivity) {

    private fun groups(): List<String> = when (kind) {
        BrowseKind.Artist -> tracks.artists()
        BrowseKind.Album -> tracks.albums()
        BrowseKind.Genre -> tracks.genres()
    }

    private fun tracksIn(group: String): List<Track> = when (kind) {
        BrowseKind.Artist -> tracks.filter { it.artist == group }
        BrowseKind.Album -> tracks.filter { it.album == group }
        BrowseKind.Genre -> tracks.filter { it.genre == group }
    }

    @Composable
    override fun Content() {
        MusicScaffold(title = kind.title, onBack = { goBack() }) {
            LightScrollView(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1f.gridUnitsAsDp()),
            ) {
                groups().forEach { group ->
                    MenuRow(
                        label = group,
                        onClick = {
                            navigateTo({ SongListScreen(it, group, tracksIn(group)) })
                        },
                    )
                }
            }
        }
    }
}
