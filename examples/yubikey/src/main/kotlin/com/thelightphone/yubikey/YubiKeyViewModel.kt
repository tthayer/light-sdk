package com.thelightphone.yubikey

import androidx.lifecycle.viewModelScope
import com.thelightphone.sdk.LightViewModel
import com.thelightphone.sdk.SimpleLightScreen
import com.thelightphone.sdk.security.LightSecurityKey
import com.thelightphone.sdk.security.OathCode
import com.thelightphone.sdk.shared.LightResult
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** UI state for the YubiKey reader screen. */
sealed interface YubiKeyUiState {
    /** Idle: NFC reader is armed; waiting for a tap (or a USB READ). */
    data object Idle : YubiKeyUiState

    /** A USB read is in progress. */
    data object Reading : YubiKeyUiState

    /** Codes read successfully. [codes] is empty if the key has no credentials. */
    data class Loaded(val codes: List<OathCode>) : YubiKeyUiState

    /** The read failed. */
    data class Failed(val message: String) : YubiKeyUiState
}

class YubiKeyViewModel(private val securityKey: LightSecurityKey) : LightViewModel<Unit>() {

    private enum class Source { USB, NFC }

    private val _state = MutableStateFlow<YubiKeyUiState>(YubiKeyUiState.Idle)
    val state: StateFlow<YubiKeyUiState> = _state.asStateFlow()

    private val _demoMode = MutableStateFlow(false)
    val demoMode: StateFlow<Boolean> = _demoMode.asStateFlow()

    private var readJob: Job? = null
    private var autoRefreshJob: Job? = null

    /** True when a USB key is already plugged in (lets the UI tailor its prompt). */
    fun usbConnected(): Boolean = securityKey.hasUsbKey()

    override fun onScreenShow(screen: SimpleLightScreen<Unit>) {
        // NFC: arm reader mode so a tap auto-reads (the platform never grabs the tag).
        securityKey.startNfcReaderMode { result -> deliver(result, Source.NFC) }
        // USB: if a key is already plugged in, read immediately without a button press.
        if (_demoMode.value || securityKey.hasUsbKey()) read()
    }

    override fun onScreenHide(screen: SimpleLightScreen<Unit>) = stopBackgroundWork()

    override fun onAppPause() = stopBackgroundWork()

    private fun stopBackgroundWork() {
        securityKey.stopNfcReaderMode()
        autoRefreshJob?.cancel()
        readJob?.cancel()
    }

    fun toggleDemoMode() {
        _demoMode.value = !_demoMode.value
        securityKey.demoMode = _demoMode.value
        autoRefreshJob?.cancel()
        _state.value = YubiKeyUiState.Idle
        if (_demoMode.value) read()
    }

    /** READ button: reads a plugged-in USB key (or the demo card). NFC is tap-driven. */
    fun read() {
        if (readJob?.isActive == true) return
        _state.value = YubiKeyUiState.Reading
        readJob = viewModelScope.launch { deliver(securityKey.readCodes(), Source.USB) }
    }

    private fun deliver(result: LightResult<List<OathCode>>, source: Source) {
        autoRefreshJob?.cancel()
        _state.value = when (result) {
            is LightResult.Success -> YubiKeyUiState.Loaded(result.data)
            is LightResult.Error -> YubiKeyUiState.Failed(result.extra ?: "Could not read key")
        }
        // A USB key (or the demo card) stays available, so keep the codes fresh:
        // re-read just after the soonest code's window rolls over. NFC codes
        // can't auto-refresh (the key has left the field) — user taps again.
        if (source == Source.USB && result is LightResult.Success) {
            scheduleAutoRefresh(result.data)
        }
    }

    private fun scheduleAutoRefresh(codes: List<OathCode>) {
        val soonestExpiry = codes.filter { it.hasValue }.minOfOrNull { it.validUntilEpochSeconds } ?: return
        autoRefreshJob = viewModelScope.launch {
            val now = System.currentTimeMillis() / 1000
            delay(((soonestExpiry - now).coerceAtLeast(0) + 1) * 1000)
            if (!(_demoMode.value || securityKey.hasUsbKey())) return@launch // key removed
            when (val refreshed = securityKey.readCodes()) {
                is LightResult.Success -> {
                    _state.value = YubiKeyUiState.Loaded(refreshed.data)
                    scheduleAutoRefresh(refreshed.data) // loop for the next window
                }
                is LightResult.Error -> Unit // leave current codes; they'll show expired
            }
        }
    }

    override fun onBackPressed(): Boolean {
        if (_state.value is YubiKeyUiState.Reading) {
            cancelRead()
            return true
        }
        return false
    }

    fun cancelRead() {
        readJob?.cancel()
        autoRefreshJob?.cancel()
        readJob = null
        _state.value = YubiKeyUiState.Idle
    }
}
