package com.fft.routerkeepalive.ui

import android.app.Application
import androidx.appcompat.app.AppCompatDelegate
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.fft.routerkeepalive.data.SettingsStore
import com.fft.routerkeepalive.domain.AppSettings
import com.fft.routerkeepalive.domain.DiagnosticRecord
import com.fft.routerkeepalive.domain.cloneSettings
import com.fft.routerkeepalive.domain.defaultSettings
import com.fft.routerkeepalive.domain.recentRecords
import com.fft.routerkeepalive.domain.ssidListToText
import com.fft.routerkeepalive.domain.ssidTextToList
import com.fft.routerkeepalive.service.KeepAliveController
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/**
 * UI state for both tabs.
 *
 * [settings] is the CONTROLLER's committed settings (what is actually running), while [draft] is
 * what the settings form is currently showing. The ArkUI page makes the same split: its `@State
 * settings` is an editable copy that `saveSettings()` pushes into the controller. Keeping the two
 * apart is what lets a user type a half-finished URL or an out-of-range number without the
 * background state emission overwriting the field.
 */
data class KeepAliveUiState(
    val settings: AppSettings = defaultSettings(),
    val draft: AppSettings = defaultSettings(),
    val displayStatus: String = KeepAliveController.Status.OFF,
    val currentSsid: String = "",
    val wifiAvailable: Boolean = false,
    val lastSuccessAt: Long = 0,
    val consecutiveFailures: Int = 0,
    val waitingReason: String = "",
    val lastMessage: String = "",
    val diagnostics: List<DiagnosticRecord> = emptyList(),
    /** The raw SSID text as typed, so commas and spacing survive until save. */
    val ssidText: String = ""
) {
    val previewDiagnostics: List<DiagnosticRecord> get() = recentRecords(diagnostics, 3)
    val allDiagnostics: List<DiagnosticRecord> get() = recentRecords(diagnostics, 100)
}

class KeepAliveViewModel(application: Application) : AndroidViewModel(application) {

    private val store = SettingsStore(application)

    private val _state = MutableStateFlow(KeepAliveUiState())
    val state: StateFlow<KeepAliveUiState> = _state.asStateFlow()

    /** Theme mode, persisted separately like the ArkUI PersistentStorage `routerTheme`. */
    private val _themeMode = MutableStateFlow(store.loadThemeMode())
    val themeMode: StateFlow<String> = _themeMode.asStateFlow()

    private val _message = MutableStateFlow("")
    val message: StateFlow<String> = _message.asStateFlow()

    /** Set once the user has typed into the form, so later snapshots never clobber the draft. */
    private var draftDirty = false

    init {
        viewModelScope.launch {
            KeepAliveController.snapshot.collect { snapshot ->
                val current = _state.value
                _state.value = current.copy(
                    settings = snapshot.settings,
                    // Refresh the draft from the controller only while the user has not touched it.
                    draft = if (draftDirty) current.draft else cloneSettings(snapshot.settings),
                    ssidText = if (draftDirty) {
                        current.ssidText
                    } else {
                        ssidListToText(snapshot.settings.targetSsids)
                    },
                    displayStatus = snapshot.displayStatus,
                    currentSsid = snapshot.currentSsid,
                    wifiAvailable = snapshot.wifiAvailable,
                    lastSuccessAt = snapshot.lastSuccessAt,
                    consecutiveFailures = snapshot.consecutiveFailures,
                    waitingReason = snapshot.waitingReason,
                    lastMessage = snapshot.lastMessage,
                    diagnostics = snapshot.diagnostics
                )
            }
        }
    }

    // ---- Keep-alive tab ----------------------------------------------------------------

    fun setEnabled(enabled: Boolean) {
        KeepAliveController.setEnabled(enabled)
    }

    fun runNow() {
        KeepAliveController.runNow(true)
    }

    fun copyDiagnostics() {
        KeepAliveController.copyDiagnostics()
    }

    fun clearDiagnostics() {
        KeepAliveController.clearDiagnostics()
    }

    // ---- Settings draft ----------------------------------------------------------------

    /**
     * Applies [block] to the local draft only. Nothing is persisted and nothing is validated until
     * the user presses a save button, which is the ArkUI behaviour: its `@State settings` is edited
     * freely and `saveSettings()` is the single commit point.
     */
    private fun editDraft(block: (AppSettings) -> Unit) {
        val current = _state.value
        val updated = cloneSettings(current.draft)
        block(updated)
        draftDirty = true
        _state.value = current.copy(draft = updated)
    }

    fun setScope(scope: String) = editDraft { it.scope = scope }

    fun setSsidText(text: String) {
        draftDirty = true
        _state.value = _state.value.copy(ssidText = text)
    }

    fun setInterval(seconds: Int) = editDraft { it.intervalSeconds = seconds }

    fun setTimeout(seconds: Int) = editDraft { it.timeoutSeconds = seconds }

    fun setRetryRounds(rounds: Int) = editDraft { it.retryRounds = rounds }

    fun setPrimaryUrl(url: String) = editDraft { it.primaryUrl = url }

    fun setFallbackUrl(url: String) = editDraft { it.fallbackUrl = url }

    fun setNotifyOnFailure(enabled: Boolean) = editDraft { it.notifyOnFailure = enabled }

    /** ArkUI `saveSettings()`: commit the basic draft (scope, SSIDs, interval). */
    fun saveBasic() {
        commit(_state.value.draft.copy(targetSsids = ssidTextToList(_state.value.ssidText)))
    }

    /** ArkUI `saveSettings()`: commit the advanced draft (URLs, timeout, retries, notify). */
    fun saveAdvanced() {
        commit(_state.value.draft)
    }

    /**
     * ArkUI `saveSettings()`: commit the draft through the controller and report any errors.
     *
     * A successful save deliberately emits no message. The confirmation used to be a Snackbar, but
     * it duplicated the effect the user just triggered (the value they typed is now in effect) and
     * it covered the dock, which is what you tap to leave the page — so the success path is silent.
     * Failures still report, because there the user has to act.
     */
    private fun commit(updated: AppSettings) {
        KeepAliveController.updateSettings(updated) { errors ->
            if (errors.isEmpty()) {
                draftDirty = false
                _message.value = ""
            } else {
                // Keep the draft so the user can fix the offending field, like the ArkUI page
                // which leaves @State settings untouched when validation fails.
                _message.value = errors.joinToString("；")
            }
        }
    }

    /** ArkUI `restoreDefaults()`: reset to the defaults, keeping the target SSIDs. */
    fun restoreDefaults() {
        draftDirty = false
        _state.value = _state.value.copy(ssidText = "")
        KeepAliveController.restoreDefaults()
    }

    // ---- Appearance ---------------------------------------------------------------------

    /**
     * Persists the theme choice and publishes it. MainActivity collects [themeMode] and is the one
     * that applies it, because only an Activity can repaint the window: it calls
     * `AppCompatDelegate.setDefaultNightMode()` and recreates itself. The ArkUI page does the
     * equivalent inline in `onThemeModeChanged` via `abilityContext.setColorMode()`.
     */
    fun setThemeMode(mode: String) {
        if (_themeMode.value == mode) return
        _themeMode.value = mode
        store.saveThemeMode(mode)
    }
}
