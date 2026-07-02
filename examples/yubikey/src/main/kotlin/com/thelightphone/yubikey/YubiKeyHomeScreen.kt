package com.thelightphone.yubikey

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextAlign
import com.thelightphone.sdk.InitialScreen
import com.thelightphone.sdk.LightScreen
import com.thelightphone.sdk.SealedLightActivity
import com.thelightphone.sdk.security.OathCode
import com.thelightphone.sdk.ui.LightBarButton
import com.thelightphone.sdk.ui.LightBottomBar
import com.thelightphone.sdk.ui.LightScrollView
import com.thelightphone.sdk.ui.LightText
import com.thelightphone.sdk.ui.LightTextVariant
import com.thelightphone.sdk.ui.LightTheme
import com.thelightphone.sdk.ui.LightThemeController
import com.thelightphone.sdk.ui.LightThemeTokens
import com.thelightphone.sdk.ui.LightTopBar
import com.thelightphone.sdk.ui.LightTopBarCenter
import com.thelightphone.sdk.ui.gridUnitsAsDp
import kotlinx.coroutines.delay

@InitialScreen
class YubiKeyHomeScreen(sealedActivity: SealedLightActivity) :
    LightScreen<Unit, YubiKeyViewModel>(sealedActivity) {

    override val viewModelClass: Class<YubiKeyViewModel>
        get() = YubiKeyViewModel::class.java

    override fun createViewModel() = YubiKeyViewModel(lightContext.securityKey)

    @Composable
    override fun Content() {
        val themeColors by LightThemeController.colors.collectAsState()
        val state by viewModel.state.collectAsState()
        val demoMode by viewModel.demoMode.collectAsState()

        LightTheme(colors = themeColors) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(LightThemeTokens.colors.background),
            ) {
                LightTopBar(
                    center = LightTopBarCenter.Text(if (demoMode) "YubiKey (demo)" else "YubiKey"),
                    modifier = Modifier.padding(bottom = 1f.gridUnitsAsDp()),
                )

                Box(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth(),
                    contentAlignment = Alignment.Center,
                ) {
                    when (val s = state) {
                        is YubiKeyUiState.Idle -> CenteredMessage(
                            if (viewModel.usbConnected()) {
                                "YubiKey plugged in.\nTap READ to show your codes."
                            } else {
                                "Hold your YubiKey flat to the back of the phone to show your codes."
                            }
                        )
                        is YubiKeyUiState.Reading -> CenteredMessage("Reading…")
                        is YubiKeyUiState.Failed -> CenteredMessage(s.message)
                        is YubiKeyUiState.Loaded ->
                            if (s.codes.isEmpty()) {
                                CenteredMessage("No accounts on this key.")
                            } else {
                                CodeList(s.codes)
                            }
                    }
                }

                LightBottomBar(bottomBarButtons(state, demoMode))
            }
        }
    }

    private fun bottomBarButtons(state: YubiKeyUiState, demoMode: Boolean): List<LightBarButton> =
        when (state) {
            is YubiKeyUiState.Reading -> listOf(
                LightBarButton.Text(text = "CANCEL", onClick = viewModel::cancelRead)
            )
            else -> listOf(
                LightBarButton.Text(text = "READ", onClick = viewModel::read),
                LightBarButton.Text(
                    text = if (demoMode) "DEMO: ON" else "DEMO: OFF",
                    onClick = viewModel::toggleDemoMode,
                ),
            )
        }

    @Composable
    private fun CenteredMessage(text: String) {
        LightText(
            text = text,
            variant = LightTextVariant.Copy,
            align = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 1.5f.gridUnitsAsDp()),
        )
    }

    @Composable
    private fun CodeList(codes: List<OathCode>) {
        // Tick once a second so countdowns and expiry update live.
        var nowSeconds by remember { mutableLongStateOf(System.currentTimeMillis() / 1000) }
        LaunchedEffect(codes) {
            while (true) {
                nowSeconds = System.currentTimeMillis() / 1000
                delay(1000)
            }
        }

        LightScrollView(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 1f.gridUnitsAsDp()),
        ) {
            codes.forEach { code ->
                CodeRow(code = code, nowSeconds = nowSeconds)
            }
        }
    }

    @Composable
    private fun CodeRow(code: OathCode, nowSeconds: Long) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 0.75f.gridUnitsAsDp()),
            verticalArrangement = Arrangement.spacedBy(0.25f.gridUnitsAsDp()),
        ) {
            LightText(text = code.credential.label, variant = LightTextVariant.Fine, lighten = true)

            val value = code.value
            when {
                value != null -> {
                    val remaining = (code.validUntilEpochSeconds - nowSeconds).coerceAtLeast(0)
                    val expired = remaining <= 0
                    LightText(
                        text = if (expired) "— — —" else spaced(value),
                        variant = LightTextVariant.Subtitle,
                        monospace = true,
                        lighten = expired,
                    )
                    LightText(
                        text = if (expired) "expired — present key again" else "resets in ${remaining}s",
                        variant = LightTextVariant.Copy,
                        lighten = true,
                    )
                }
                code.requiresTouch -> LightText(
                    text = "touch your key to reveal",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                )
                else -> LightText(
                    text = "HOTP — not supported yet",
                    variant = LightTextVariant.Copy,
                    lighten = true,
                )
            }
        }
    }

    /** "123456" -> "123 456" for readability; leaves other lengths untouched. */
    private fun spaced(code: String): String {
        if (code.length < 6 || code.length > 8) return code
        val mid = code.length / 2
        return code.substring(0, mid) + " " + code.substring(mid)
    }
}
