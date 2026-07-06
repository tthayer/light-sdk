package com.thelightphone.sample.music

import android.Manifest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightAudioLibrary
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.audioLibrary
import com.thelightphone.sdk.checkPermission
import com.thelightphone.sdk.rememberPermissionRequestLauncher
import com.thelightphone.sdk.useMediaVolumeKeys
import com.thelightphone.sdk.shared.LightServiceMethod
import com.thelightphone.sdk.shared.getOrNull
import com.thelightphone.sdk.ui.LightIcons
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.gridUnitsAsDp
import com.thelightphone.sdk.ui.lightClickable
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

private val MUSIC_PERMISSION = Manifest.permission.READ_MEDIA_AUDIO

enum class MusicAccess { Unknown, Granted, Denied, Blocked }

class MusicHomeViewModel(private val audioLibrary: LightAudioLibrary) : LightViewModel<Unit>() {

    val tracks = MusicLibrary.tracks
    val loading = MusicLibrary.loading

    private val _access = MutableStateFlow(MusicAccess.Unknown)
    val access: StateFlow<MusicAccess> = _access.asStateFlow()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        super.onScreenShow(screen)
        // Re-checked on every show so returning from the permission prompt picks
        // up a fresh grant and loads the library.
        refresh()
    }

    private fun refresh() {
        viewModelScope.launch {
            // The MediaStore query is the real gate — it returns empty without the
            // OS permission — so re-query whenever we have nothing yet.
            if (MusicLibrary.tracks.value.isEmpty()) {
                MusicLibrary.reload(audioLibrary)
            }
            if (MusicLibrary.tracks.value.isNotEmpty()) {
                _access.value = MusicAccess.Granted
                return@launch
            }
            // Still empty: ask the server why so we can prompt appropriately.
            _access.value = when (checkPermission(MUSIC_PERMISSION).getOrNull()?.permissionResult) {
                LightServiceMethod.GetPermission.Result.Granted -> MusicAccess.Granted
                LightServiceMethod.GetPermission.Result.BlockedByServer -> MusicAccess.Blocked
                else -> MusicAccess.Denied
            }
        }
    }
}

@InitialScreen
class MusicHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, MusicHomeViewModel>(sealedActivity) {

    override val viewModelClass: Class<MusicHomeViewModel>
        get() = MusicHomeViewModel::class.java

    override fun createViewModel(): MusicHomeViewModel =
        MusicHomeViewModel(lightContext.audioLibrary)

    override fun willShow() {
        super.willShow()
        // Hardware volume keys should control playback volume throughout the tool.
        lightContext.useMediaVolumeKeys()
    }

    @Composable
    override fun Content() {
        val access by viewModel.access.collectAsState()
        val tracks by viewModel.tracks.collectAsState()
        val loading by viewModel.loading.collectAsState()
        val playback by MusicPlayer.state.collectAsState()
        val permissionLauncher = rememberPermissionRequestLauncher(MUSIC_PERMISSION)

        MusicScaffold(title = "Music", onBack = null) {
            when (access) {
                MusicAccess.Unknown -> CenteredMessage("Loading…")

                MusicAccess.Denied -> PermissionPrompt(
                    message = "Music needs permission to read the songs on your phone.",
                    actionLabel = "Allow access",
                    onAction = { permissionLauncher?.launch() },
                )

                MusicAccess.Blocked -> CenteredMessage(
                    "Music access is turned off for this tool.",
                )

                MusicAccess.Granted -> when {
                    loading && tracks.isEmpty() -> CenteredMessage("Scanning library…")

                    tracks.isEmpty() -> CenteredMessage("No music found on this phone.")

                    else -> LightScrollView(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxWidth()
                            .padding(horizontal = 1f.gridUnitsAsDp()),
                    ) {
                        MenuRow(
                            label = "Artists",
                            onClick = { navigateTo({ GroupBrowseScreen(it, BrowseKind.Artist, tracks) }) },
                        )
                        MenuRow(
                            label = "Albums",
                            onClick = { navigateTo({ GroupBrowseScreen(it, BrowseKind.Album, tracks) }) },
                        )
                        MenuRow(
                            label = "Genres",
                            onClick = { navigateTo({ GroupBrowseScreen(it, BrowseKind.Genre, tracks) }) },
                        )
                        MenuRow(
                            label = "Songs",
                            onClick = { navigateTo({ SongListScreen(it, "Songs", tracks) }) },
                        )
                        MenuRow(
                            label = "Shuffle Songs",
                            leadingIcon = LightIcons.SHUFFLE,
                            onClick = {
                                MusicPlayer.shuffleAll(tracks)
                                navigateTo({ NowPlayingScreen(it) })
                            },
                        )
                        if (playback.current != null) {
                            MenuRow(
                                label = "Now Playing",
                                leadingIcon = LightIcons.MEDIA,
                                onClick = { navigateTo({ NowPlayingScreen(it) }) },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.CenteredMessage(message: String) {
        Box(
            modifier = Modifier.weight(1f).fillMaxSize(),
            contentAlignment = Alignment.Center,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                lighten = true,
                modifier = Modifier.padding(horizontal = 2f.gridUnitsAsDp()),
            )
        }
    }

    @Composable
    private fun androidx.compose.foundation.layout.ColumnScope.PermissionPrompt(
        message: String,
        actionLabel: String,
        onAction: () -> Unit,
    ) {
        Column(
            modifier = Modifier.weight(1f).fillMaxSize().padding(horizontal = 2f.gridUnitsAsDp()),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            LightText(
                text = message,
                variant = LightTextVariant.Copy,
                align = TextAlign.Center,
                lighten = true,
            )
            LightText(
                text = actionLabel,
                variant = LightTextVariant.Button,
                align = TextAlign.Center,
                modifier = Modifier
                    .padding(top = 1.5f.gridUnitsAsDp())
                    .lightClickable(onClick = onAction),
            )
        }
    }
}
