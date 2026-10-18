package com.fft.routerkeepalive.domain

/** Ported 1:1 from the HarmonyOS `RuntimePresentation`. */
object RuntimePresentation {

    fun targetWifiWaitingReason(
        scope: String,
        wifiAvailable: Boolean,
        currentSsid: String,
        targets: List<String>,
        detail: String
    ): String {
        if (scope != RunScope.TARGET_WIFI) {
            return ""
        }
        if (!wifiAvailable) {
            return "当前没有可用的 Wi-Fi 网络。$detail"
        }
        if (normalizeSsid(currentSsid).isEmpty()) {
            return "读不到当前 Wi-Fi 名称，\"仅目标 Wi-Fi\" 无法匹配目标列表；可改用\"任意 Wi-Fi\"。$detail"
        }
        if (targets.isEmpty()) {
            return "当前 Wi-Fi「$currentSsid」已就绪，但目标列表为空：请填写目标 Wi-Fi 名称，或把运行范围改为\"任意 Wi-Fi\"。"
        }
        if (RunScope.shouldRun(scope, wifiAvailable, currentSsid, targets)) {
            return ""
        }
        return "当前 Wi-Fi「$currentSsid」不在目标列表（${targets.joinToString("、")}）中，因此不发起探测。"
    }

    fun backgroundCancelMessage(reason: Int): String = when (reason) {
        1 -> "后台长时任务已取消（系统返回原因 1）。该编号也可能表示持续任务通知被移除，不代表你手动关闭了保活。"
        2 -> "后台长时任务已由系统取消；退到后台或锁屏后无法继续探测。请检查后台运行设置。"
        else -> "后台长时任务已取消（原因 $reason）；退到后台或锁屏后无法继续探测。"
    }

    /**
     * The HarmonyOS background-cancel callback cannot prove that the user flipped the master switch,
     * so the preference is never auto-disabled.
     */
    fun shouldDisablePreferenceAfterBackgroundCancel(reason: Int): Boolean = false

    fun dataTransferActivityProgress(completedProbeCount: Int): Int {
        val safeCount = maxOf(0, completedProbeCount)
        return safeCount % 99 + 1
    }

    fun dataTransferLiveViewTypeCode(): Int = 8

    fun effectiveKeepAliveStatus(probeStatus: String, enabled: Boolean, backgroundRunning: Boolean): String =
        if (enabled && !backgroundRunning) "后台权限受限" else probeStatus

    fun shouldAcceptProbeResult(enabled: Boolean, backgroundRunning: Boolean, manual: Boolean): Boolean =
        enabled && (manual || backgroundRunning)
}

object BackgroundTaskAvailability {
    const val MISSING: String = "MISSING"
    const val ACTIVE: String = "ACTIVE"
    const val SUSPENDED: String = "SUSPENDED"
}

data class BackgroundTaskSnapshot(
    val abilityName: String,
    val suspendState: Boolean
)

fun backgroundTaskAvailability(tasks: List<BackgroundTaskSnapshot>): String {
    val ownTask = tasks.firstOrNull { it.abilityName == "EntryAbility" }
        ?: return BackgroundTaskAvailability.MISSING
    return if (ownTask.suspendState) BackgroundTaskAvailability.SUSPENDED else BackgroundTaskAvailability.ACTIVE
}

/** Ported 1:1 from the HarmonyOS `WifiConnectivity`. */
object WifiConnectivity {
    fun reconcile(reportedAvailable: Boolean, ssid: String): Boolean =
        reportedAvailable || normalizeSsid(ssid).isNotEmpty()
}

object SystemBarTheme {
    fun contentColor(dark: Boolean): String = if (dark) "#FFF4EFF7" else "#FF173A30"
}
