package com.thelightphone.sample.music

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.ui.LightIcon
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable

/** The iPod "Now Playing" screen: track info, scrubber, and transport controls. */
class NowPlayingScreen(sealedActivity: SealedLightActivity) : SimpleLightScreen<Unit>(sealedActivity) {

    @Composable
    override fun Content() {
        val playback by MusicPlayer.state.collectAsState()
        val track = playback.current

        MusicScaffold(title = "Now Playing", onBack = { goBack() }) {
            if (track == null) {
                Box(
                    modifier = Modifier.weight(1f).fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    LightText(
                        text = "Nothing playing",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                }
                return@MusicScaffold
            }

            // Track metadata, vertically centered in the free space.
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                if (playback.queueSize > 0) {
                    LightText(
                        text = "${playback.queuePosition + 1} of ${playback.queueSize}",
                        variant = LightTextVariant.Detail,
                        lighten = true,
                        modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                    )
                }
                LightText(
                    text = track.title,
                    variant = LightTextVariant.Heading,
                    align = TextAlign.Center,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                LightText(
                    text = track.artist,
                    variant = LightTextVariant.Copy,
                    align = TextAlign.Center,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = track.album,
                    variant = LightTextVariant.Detail,
                    align = TextAlign.Center,
                    lighten = true,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            // Scrubber + time readouts.
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 1.5f.gridUnitsAsDp()),
            ) {
                Scrubber(
                    positionMs = playback.positionMs,
                    durationMs = playback.durationMs,
                    onSeek = { MusicPlayer.seekTo(it) },
                )
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 0.5f.gridUnitsAsDp()),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    LightText(text = formatTime(playback.positionMs), variant = LightTextVariant.Detail, lighten = true)
                    val remaining = (playback.durationMs - playback.positionMs).coerceAtLeast(0)
                    LightText(text = "-${formatTime(remaining)}", variant = LightTextVariant.Detail, lighten = true)
                }
            }

            // Transport controls.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 1f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(
                    icon = LightIcons.REWIND,
                    size = 3f,
                    modifier = Modifier.lightClickable { MusicPlayer.previous() },
                )
                LightIcon(
                    icon = if (playback.isPlaying) LightIcons.PAUSE else LightIcons.PLAY,
                    size = 4f,
                    modifier = Modifier.lightClickable { MusicPlayer.togglePlayPause() },
                )
                LightIcon(
                    icon = LightIcons.FAST_FORWARD,
                    size = 3f,
                    modifier = Modifier.lightClickable { MusicPlayer.next() },
                )
            }

            // Shuffle toggle.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .lightClickable { MusicPlayer.toggleShuffle() }
                    .padding(bottom = 1f.gridUnitsAsDp()),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                LightIcon(
                    icon = LightIcons.SHUFFLE,
                    size = 1.5f,
                    modifier = Modifier.padding(end = 0.5f.gridUnitsAsDp()),
                )
                LightText(
                    text = if (playback.shuffle) "Shuffle On" else "Shuffle Off",
                    variant = LightTextVariant.Detail,
                    lighten = !playback.shuffle,
                )
            }
        }
    }

    @Composable
    private fun Scrubber(
        positionMs: Int,
        durationMs: Int,
        onSeek: (Int) -> Unit,
    ) {
        val fraction = if (durationMs > 0) {
            (positionMs.toFloat() / durationMs).coerceIn(0f, 1f)
        } else {
            0f
        }
        val trackColor = LightThemeTokens.colors.contentSecondary
        val fillColor = LightThemeTokens.colors.content

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxWidth()
                .height(1.5f.gridUnitsAsDp()),
        ) {
            val widthPx = constraints.maxWidth.toFloat()

            fun seekToX(x: Float) {
                if (durationMs <= 0 || widthPx <= 0f) return
                onSeek(((x / widthPx).coerceIn(0f, 1f) * durationMs).toInt())
            }

            // Full-height transparent gesture layer for a comfortable hit target.
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .fillMaxHeight()
                    .pointerInput(durationMs, widthPx) {
                        detectTapGestures { offset -> seekToX(offset.x) }
                    }
                    .pointerInput(durationMs, widthPx) {
                        detectHorizontalDragGestures { change, _ -> seekToX(change.position.x) }
                    },
                contentAlignment = Alignment.CenterStart,
            ) {
                // Background rail.
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .background(trackColor),
                )
                // Elapsed fill.
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(2.dp)
                        .background(fillColor),
                )
            }
        }
    }
}
