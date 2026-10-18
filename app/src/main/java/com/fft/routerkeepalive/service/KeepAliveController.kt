package com.fft.routerkeepalive.service

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.util.Log
import com.fft.routerkeepalive.data.SettingsStore
import com.fft.routerkeepalive.domain.AppSettings
import com.fft.routerkeepalive.domain.DiagnosticLog
import com.fft.routerkeepalive.domain.DiagnosticRecord
import com.fft.routerkeepalive.domain.FailureTracker
import com.fft.routerkeepalive.domain.ProbeCategory
import com.fft.routerkeepalive.domain.ProbeResult
import com.fft.routerkeepalive.domain.RunScope
import com.fft.routerkeepalive.domain.RuntimePresentation.backgroundCancelMessage
import com.fft.routerkeepalive.domain.RuntimePresentation.effectiveKeepAliveStatus
import com.fft.routerkeepalive.domain.RuntimePresentation.shouldAcceptProbeResult
import com.fft.routerkeepalive.domain.RuntimePresentation.shouldDisablePreferenceAfterBackgroundCancel
import com.fft.routerkeepalive.domain.RuntimePresentation.targetWifiWaitingReason
import com.fft.routerkeepalive.domain.cloneSettings
import com.fft.routerkeepalive.domain.combineProbeResults
import com.fft.routerkeepalive.domain.defaultSettings
import com.fft.routerkeepalive.domain.validateSettings
import com.fft.routerkeepalive.network.ProbeClient
import com.fft.routerkeepalive.network.WifiMonitor
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Keep-alive controller, ported 1:1 from the HarmonyOS `KeepAliveController`.
 *
 * Platform mapping:
 * - HarmonyOS `startBackgroundRunning(['dataTransfer'])` -> Android foreground service
 *   (`KeepAliveService`, type `dataSync`) + a persistent low-importance notification.
 * - HarmonyOS `setInterval` schedule -> a coroutine loop in the service, cancelled on stop.
 * - HarmonyOS `backgroundTaskManager.getAllContinuousTasks` evidence -> Android checks that the
 *   foreground service is actually running before reporting a healthy state.
 *
 * Everything else -- settings validation, gating, failure tracking, diagnostic records and the
 * privacy constraints -- is identical to the ArkTS implementation.
 */
object KeepAliveController {

    private const val TAG = "RouterKeepAlive"

    object Status {
        const val OFF = "已关闭"
        const val WAITING_WIFI = "等待符合条件的 Wi-Fi"
        const val RUNNING = "运行正常"
        const val CHECKING = "正在探测"
        const val AUTH_SUSPECTED = "疑似认证失效"
        const val PERMISSION_LIMITED = "后台权限受限"
    }

    var settings: AppSettings = defaultSettings()
        private set
    var status: String = Status.OFF
        private set
    var currentSsid: String = ""
        private set
    var wifiAvailable: Boolean = false
        private set
    var lastSuccessAt: Long = 0
        private set
    var consecutiveFailures: Int = 0
        private set
    var diagnostics: DiagnosticLog = DiagnosticLog()
        private set
    var lastMessage: String = ""
        private set
    var initialized: Boolean = false
        private set
    /** True while the foreground service is alive, i.e. the Android analogue of `backgroundRunning`. */
    var backgroundRunning: Boolean = false
        private set

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    data class Snapshot(
        val settings: AppSettings = defaultSettings(),
        val status: String = Status.OFF,
        val displayStatus: String = Status.OFF,
        val currentSsid: String = "",
        val wifiAvailable: Boolean = false,
        val lastSuccessAt: Long = 0,
        val consecutiveFailures: Int = 0,
        val diagnostics: List<DiagnosticRecord> = emptyList(),
        val lastMessage: String = "",
        val waitingReason: String = ""
    )

    private lateinit var appContext: Context
    private lateinit var store: SettingsStore
    // Holds only the application context, so this process-lifetime singleton cannot leak an Activity.
    private var wifiMonitor: WifiMonitor? = null
    private lateinit var notifications: NotificationService
    private val probeClient = ProbeClient()
    private val failureTracker = FailureTracker(3)
    private var probing = false
    private var completedProbeCount = 0
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var scheduleJob: Job? = null

    fun initialize(context: Context) {
        if (initialized) {
            return
        }
        appContext = context.applicationContext
        store = SettingsStore(appContext)
        notifications = NotificationService(appContext)
        notifications.ensureChannels()
        settings = store.loadSettings()
        diagnostics = store.loadDiagnostics()
        wifiMonitor = WifiMonitor(appContext) { connected, ssid -> onWifiChanged(connected, ssid) }
        scope.launch {
            if (settings.enabled) {
                start()
            }
            initialized = true
            publishSnapshot()
        }
    }

    fun setEnabled(enabled: Boolean) {
        val updated = cloneSettings(settings)
        updated.enabled = enabled
        settings = updated
        persistSettings()
        if (enabled) start() else stop()
        publishSnapshot()
    }

    fun updateSettings(updated: AppSettings, onResult: (List<String>) -> Unit = {}) {
        scope.launch {
            val errors = validateSettings(updated)
            if (errors.isNotEmpty()) {
                onResult(errors)
                return@launch
            }
            settings = cloneSettings(updated)
            persistSettings()
            if (settings.enabled && backgroundRunning) {
                schedule()
                runNow(false)
            }
            onResult(emptyList())
        }
    }

    fun restoreDefaults() {
        scope.launch {
            settings = store.defaultsKeepingSsids(settings)
            persistSettings()
            stop()
            publishSnapshot()
        }
    }

    /** Called by `KeepAliveService.onCreate`; mirrors HarmonyOS `onContinuousTaskActive`. */
    fun onServiceStarted() {
        backgroundRunning = true
        lastMessage = ""
        completedProbeCount = 0
        notifications.updateKeepAliveActivity(completedProbeCount)
        schedule()
        runNow(false)
        publishSnapshot()
    }

    /** Called by `KeepAliveService.onDestroy`; mirrors the HarmonyOS task-cancel callback. */
    fun onServiceStopped(reason: Int) {
        backgroundRunning = false
        cancelSchedule()
        status = Status.PERMISSION_LIMITED
        lastMessage = backgroundCancelMessage(reason)
        if (shouldDisablePreferenceAfterBackgroundCancel(reason)) {
            val disabled = cloneSettings(settings)
            disabled.enabled = false
            settings = disabled
            persistSettings()
            wifiMonitor?.stop()
            status = Status.OFF
        }
        Log.w(TAG, "continuous task cancelled: reason=$reason")
        publishSnapshot()
    }

    /** Called by `KeepAliveService` when the OS foreground-service type is unavailable. */
    fun onServiceUnavailable(code: Int, message: String) {
        backgroundRunning = false
        cancelSchedule()
        status = Status.PERMISSION_LIMITED
        lastMessage = "系统未允许持续任务：$code $message"
        Log.e(TAG, "startForeground failed: code=$code message=$message")
        publishSnapshot()
    }

    fun start() {
        // initialize() is called from MainActivity.onCreate, but a UI event can theoretically
        // arrive first (e.g. a restored switch state). Guard instead of crashing on lateinit.
        if (!this::appContext.isInitialized) {
            Log.w(TAG, "start() before initialize(); ignored")
            return
        }
        KeepAliveService.start(appContext)
        wifiMonitor?.start()
    }

    fun stop() {
        if (!this::appContext.isInitialized) {
            Log.w(TAG, "stop() before initialize(); ignored")
            return
        }
        KeepAliveService.stop(appContext)
        cancelSchedule()
        wifiMonitor?.stop()
        backgroundRunning = false
        completedProbeCount = 0
        status = Status.OFF
        notifications.clearFailure()
        notifications.cancelKeepAlive()
        publishSnapshot()
    }

    fun runNow(manual: Boolean = true) {
        scope.launch {
            if (probing || !settings.enabled) {
                Log.i(TAG, "runNow skipped: probing=$probing enabled=${settings.enabled}")
                return@launch
            }
            if (!manual && !backgroundRunning) {
                return@launch
            }
            if (!shouldProbe(manual)) {
                Log.i(TAG, "runNow gated: scope=${settings.scope} ssid=[$currentSsid] manual=$manual")
                status = Status.WAITING_WIFI
                publishSnapshot()
                return@launch
            }
            probing = true
            status = Status.CHECKING
            publishSnapshot()
            var combined = ProbeResult(
                success = false,
                category = ProbeCategory.ALL_ENDPOINTS_FAILED,
                durationMs = 0.0
            )
            val wifiNetwork = currentWifiNetwork()
            for (attempt in 0 until settings.retryRounds) {
                val primary = withContext(Dispatchers.IO) {
                    probeClient.probe(settings.primaryUrl, settings.timeoutSeconds, wifiNetwork)
                }
                if (primary.success) {
                    combined = primary
                    break
                }
                val fallback = withContext(Dispatchers.IO) {
                    probeClient.probe(settings.fallbackUrl, settings.timeoutSeconds, wifiNetwork)
                }
                combined = combineProbeResults(primary, fallback)
                if (combined.success) {
                    break
                }
            }
            probing = false
            if (!shouldAcceptProbeResult(settings.enabled, backgroundRunning, manual)) {
                return@launch
            }
            Log.i(
                TAG,
                "probe round: category=${combined.category} success=${combined.success} durationMs=${combined.durationMs}"
            )
            diagnostics.append(
                DiagnosticRecord(
                    timestamp = System.currentTimeMillis(),
                    category = combined.category,
                    durationMs = combined.durationMs
                )
            )
            if (combined.success) {
                lastSuccessAt = System.currentTimeMillis()
                status = Status.RUNNING
                failureTracker.recordRound(true)
                consecutiveFailures = 0
                notifications.clearFailure()
            } else {
                val shouldAlert = failureTracker.recordRound(false)
                consecutiveFailures = failureTracker.consecutiveFailedRounds
                status = if (consecutiveFailures >= 3) Status.AUTH_SUSPECTED else Status.WAITING_WIFI
                if (shouldAlert && settings.notifyOnFailure) {
                    notifications.showFailure(consecutiveFailures)
                }
            }
            completedProbeCount++
            notifications.updateKeepAliveActivity(
                completedProbeCount,
                combined.success,
                combined.durationMs
            )
            persistDiagnostics()
            publishSnapshot()
        }
    }

    fun copyDiagnostics() {
        scope.launch {
            try {
                val clipboard = appContext.getSystemService(Context.CLIPBOARD_SERVICE)
                    as android.content.ClipboardManager
                clipboard.setPrimaryClip(
                    android.content.ClipData.newPlainText("router-keepalive", diagnostics.serialize())
                )
                lastMessage = "诊断记录已复制"
            } catch (_: Exception) {
                lastMessage = "复制失败，请检查剪贴板权限"
            }
            publishSnapshot()
        }
    }

    fun clearDiagnostics() {
        scope.launch {
            diagnostics.clear()
            persistDiagnostics()
            lastMessage = "诊断记录已清空"
            publishSnapshot()
        }
    }

    private fun shouldProbe(manual: Boolean = false): Boolean {
        if (settings.scope == RunScope.MANUAL) {
            return manual
        }
        return RunScope.shouldRun(settings.scope, wifiAvailable, currentSsid, settings.targetSsids)
    }

    private fun onWifiChanged(available: Boolean, ssid: String) {
        wifiAvailable = available
        currentSsid = if (available) ssid else ""
        if (!settings.enabled || !backgroundRunning) {
            publishSnapshot()
            return
        }
        if (!shouldProbe(false)) {
            status = Status.WAITING_WIFI
            publishSnapshot()
            return
        }
        runNow(false)
    }

    /**
     * Re-reads the SSID after the nearby-Wi-Fi permission was granted. On Android 13+ the SSID is
     * unreadable before that grant, so the startup read can legitimately come back empty; this
     * gives the UI a way to recover as soon as the user allows it.
     */
    fun refreshWifiNow() {
        if (!this::appContext.isInitialized) return
        val monitor = wifiMonitor ?: WifiMonitor(appContext) { connected, ssid ->
            onWifiChanged(connected, ssid)
        }.also { wifiMonitor = it }
        monitor.start()
        monitor.readSsid()
    }

    /**
     * Target-mode waiting reason. Read by the UI on every refresh (never stored in `lastMessage`)
     * so a `lastMessage` assignment in the startup flow cannot erase it.
     */
    fun waitingReason(): String {
        if (!settings.enabled || !backgroundRunning) {
            return ""
        }
        val detail = wifiMonitor?.diagnostic ?: ""
        return targetWifiWaitingReason(
            settings.scope, wifiAvailable, currentSsid, settings.targetSsids, detail
        )
    }

    fun displayStatus(): String = effectiveKeepAliveStatus(status, settings.enabled, backgroundRunning)

    /** The Wi-Fi `Network`, so probes are pinned to Wi-Fi and never fall back to mobile data. */
    private fun currentWifiNetwork(): Network? {
        return try {
            val manager = appContext.getSystemService(Context.CONNECTIVITY_SERVICE)
                as ConnectivityManager
            val network = manager.activeNetwork ?: return null
            val capabilities = manager.getNetworkCapabilities(network) ?: return null
            if (capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) network else null
        } catch (_: Exception) {
            null
        }
    }

    private fun schedule() {
        cancelSchedule()
        scheduleJob = scope.launch {
            while (settings.enabled && backgroundRunning) {
                delay(settings.intervalSeconds * 1000L)
                runNow(false)
            }
        }
    }

    private fun cancelSchedule() {
        scheduleJob?.cancel()
        scheduleJob = null
    }

    private fun persistSettings() {
        scope.launch { store.saveSettings(settings) }
    }

    private fun persistDiagnostics() {
        scope.launch { store.saveDiagnostics(diagnostics) }
    }

    private fun publishSnapshot() {
        _snapshot.value = Snapshot(
            settings = cloneSettings(settings),
            status = status,
            displayStatus = displayStatus(),
            currentSsid = currentSsid,
            wifiAvailable = wifiAvailable,
            lastSuccessAt = lastSuccessAt,
            consecutiveFailures = consecutiveFailures,
            diagnostics = diagnostics.records.toList(),
            lastMessage = lastMessage,
            waitingReason = waitingReason()
        )
    }
}
