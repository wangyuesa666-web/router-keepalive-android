package com.fft.routerkeepalive.data

import android.content.Context
import android.content.SharedPreferences
import com.fft.routerkeepalive.domain.AppSettings
import com.fft.routerkeepalive.domain.DEFAULT_SETTINGS
import com.fft.routerkeepalive.domain.DiagnosticLog
import com.fft.routerkeepalive.domain.defaultSettings
import com.fft.routerkeepalive.domain.normalizeSsids

/**
 * Persistence, ported 1:1 from the HarmonyOS `SettingsStore` (preferences kit).
 *
 * The SharedPreferences file name, key names and the `\u001f` list separator are kept identical to
 * the ArkTS implementation so the on-disk shape stays the same.
 */
class SettingsStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(STORE_NAME, Context.MODE_PRIVATE)

    fun loadSettings(): AppSettings {
        return AppSettings(
            enabled = prefs.getBoolean("enabled", DEFAULT_SETTINGS.ENABLED),
            targetSsids = normalizeSsids(
                (prefs.getString("targetSsids", "") ?: "").split('\u001f')
            ),
            scope = prefs.getString("scope", DEFAULT_SETTINGS.SCOPE) ?: DEFAULT_SETTINGS.SCOPE,
            intervalSeconds = prefs.getInt("intervalSeconds", DEFAULT_SETTINGS.INTERVAL_SECONDS),
            primaryUrl = prefs.getString("primaryUrl", DEFAULT_SETTINGS.PRIMARY_URL)
                ?: DEFAULT_SETTINGS.PRIMARY_URL,
            fallbackUrl = prefs.getString("fallbackUrl", DEFAULT_SETTINGS.FALLBACK_URL)
                ?: DEFAULT_SETTINGS.FALLBACK_URL,
            timeoutSeconds = prefs.getInt("timeoutSeconds", DEFAULT_SETTINGS.TIMEOUT_SECONDS),
            retryRounds = prefs.getInt("retryRounds", DEFAULT_SETTINGS.RETRY_ROUNDS),
            notifyOnFailure = prefs.getBoolean("notifyOnFailure", DEFAULT_SETTINGS.NOTIFY_ON_FAILURE)
        )
    }

    fun saveSettings(settings: AppSettings) {
        prefs.edit()
            .putBoolean("enabled", settings.enabled)
            .putString("targetSsids", normalizeSsids(settings.targetSsids).joinToString("\u001f"))
            .putString("scope", settings.scope)
            .putInt("intervalSeconds", settings.intervalSeconds)
            .putString("primaryUrl", settings.primaryUrl)
            .putString("fallbackUrl", settings.fallbackUrl)
            .putInt("timeoutSeconds", settings.timeoutSeconds)
            .putInt("retryRounds", settings.retryRounds)
            .putBoolean("notifyOnFailure", settings.notifyOnFailure)
            .apply()
    }

    fun loadDiagnostics(): DiagnosticLog =
        DiagnosticLog.deserialize(prefs.getString("diagnostics", "") ?: "")

    fun saveDiagnostics(log: DiagnosticLog) {
        prefs.edit().putString("diagnostics", log.serialize()).apply()
    }

    fun defaultsKeepingSsids(current: AppSettings): AppSettings =
        defaultSettings().apply { targetSsids = current.targetSsids.toList() }

    /** Theme preference; the HarmonyOS build keeps this in PersistentStorage. */
    fun loadThemeMode(): String = prefs.getString("themeMode", "system") ?: "system"

    /**
     * Committed synchronously, unlike the other writes. `AppCompatDelegate` recreates the Activity
     * as soon as the night mode changes and the recreated Activity reads this value in onCreate;
     * an async apply() can lose that race and leave the old palette on screen.
     */
    fun saveThemeMode(mode: String) {
        prefs.edit().putString("themeMode", mode).commit()
    }

    private companion object {
        const val STORE_NAME = "router_keepalive"
    }
}
