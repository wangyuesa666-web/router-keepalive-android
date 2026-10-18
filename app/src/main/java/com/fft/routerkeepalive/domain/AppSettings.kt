package com.fft.routerkeepalive.domain

/**
 * Run scope, ported 1:1 from the HarmonyOS `RunScope` class.
 *
 * See the HarmonyOS note: "any Wi-Fi" must depend only on Wi-Fi availability, never on the SSID
 * being non-empty, because Android 10+ hides the SSID of randomisation networks and some OEM builds
 * hide it entirely for third-party apps.
 */
object RunScope {
    const val TARGET_WIFI: String = "TARGET_WIFI"
    const val ANY_WIFI: String = "ANY_WIFI"
    const val MANUAL: String = "MANUAL"

    fun shouldRun(scope: String, wifiAvailable: Boolean, currentSsid: String, targets: List<String>): Boolean {
        if (scope == MANUAL || !wifiAvailable) {
            return false
        }
        if (scope == ANY_WIFI) {
            return true
        }
        val normalizedCurrent: String = normalizeSsid(currentSsid)
        if (normalizedCurrent.isEmpty()) {
            return false
        }
        return targets.any { normalizeSsid(it) == normalizedCurrent }
    }
}

/** Application settings. Same fields, defaults and ordering as the HarmonyOS `AppSettings`. */
data class AppSettings(
    var enabled: Boolean = DEFAULT_SETTINGS.ENABLED,
    var targetSsids: List<String> = emptyList(),
    var scope: String = DEFAULT_SETTINGS.SCOPE,
    var intervalSeconds: Int = DEFAULT_SETTINGS.INTERVAL_SECONDS,
    var primaryUrl: String = DEFAULT_SETTINGS.PRIMARY_URL,
    var fallbackUrl: String = DEFAULT_SETTINGS.FALLBACK_URL,
    var timeoutSeconds: Int = DEFAULT_SETTINGS.TIMEOUT_SECONDS,
    var retryRounds: Int = DEFAULT_SETTINGS.RETRY_ROUNDS,
    var notifyOnFailure: Boolean = DEFAULT_SETTINGS.NOTIFY_ON_FAILURE
)

object DEFAULT_SETTINGS {
    const val ENABLED = false
    // The 2026-10-09 final HarmonyOS build switched the default run scope to ANY_WIFI so the app
    // probes on any Wi-Fi out of the box instead of waiting for a target SSID to be configured.
    const val SCOPE = RunScope.ANY_WIFI
    const val INTERVAL_SECONDS = 60
    const val PRIMARY_URL = "https://connectivitycheck.platform.hicloud.com/generate_204"
    const val FALLBACK_URL = "https://cp.cloudflare.com/generate_204"
    const val TIMEOUT_SECONDS = 8
    const val RETRY_ROUNDS = 3
    const val NOTIFY_ON_FAILURE = true
}

private val DEFAULT_SETTINGS_INSTANCE = AppSettings(
    enabled = DEFAULT_SETTINGS.ENABLED,
    targetSsids = emptyList(),
    scope = DEFAULT_SETTINGS.SCOPE,
    intervalSeconds = DEFAULT_SETTINGS.INTERVAL_SECONDS,
    primaryUrl = DEFAULT_SETTINGS.PRIMARY_URL,
    fallbackUrl = DEFAULT_SETTINGS.FALLBACK_URL,
    timeoutSeconds = DEFAULT_SETTINGS.TIMEOUT_SECONDS,
    retryRounds = DEFAULT_SETTINGS.RETRY_ROUNDS,
    notifyOnFailure = DEFAULT_SETTINGS.NOTIFY_ON_FAILURE
)

/** Same as `cloneSettings` in ArkTS: a deep-enough copy that mutation cannot leak between callers. */
fun cloneSettings(settings: AppSettings): AppSettings = settings.copy(targetSsids = settings.targetSsids.toList())

/**
 * Placeholders Android returns instead of a real SSID. `WifiInfo.getSsid()` yields the literal
 * string `<unknown ssid>` when the platform withholds the name (randomised MAC networks, some
 * OEM builds, or when location is disabled on API 27+). Treat them as "unreadable" so the UI falls
 * back to the same message the HarmonyOS build shows.
 */
private val UNKNOWN_SSID_MARKERS = setOf(
    "<unknown ssid>",
    "unknown ssid",
    "0x",
    "<none>",
    ""
)

fun normalizeSsid(value: String): String {
    var normalized = value.trim()
    if (normalized.length >= 2 && normalized.startsWith("\"") && normalized.endsWith("\"")) {
        normalized = normalized.substring(1, normalized.length - 1).trim()
    }
    if (normalized.lowercase() in UNKNOWN_SSID_MARKERS) {
        return ""
    }
    return normalized
}

fun normalizeSsids(values: List<String>): List<String> {
    val result = LinkedHashSet<String>()
    values.forEach { value ->
        val normalized = normalizeSsid(value)
        if (normalized.isNotEmpty() && !result.contains(normalized)) {
            result.add(normalized)
        }
    }
    return result.toList()
}

fun ssidTextToList(text: String): List<String> = normalizeSsids(text.split(','))

fun ssidListToText(values: List<String>): String = normalizeSsids(values).joinToString(", ")

private fun validHttps(url: String): Boolean {
    val trimmed = url.trim()
    return trimmed.startsWith("https://") && trimmed.length > 8
}

fun validateSettings(settings: AppSettings): List<String> {
    val errors = mutableListOf<String>()
    if (settings.intervalSeconds < 15 || settings.intervalSeconds > 900) {
        errors.add("探测间隔必须为 15 至 900 秒")
    }
    if (settings.timeoutSeconds < 2 || settings.timeoutSeconds > 30) {
        errors.add("超时必须为 2 至 30 秒")
    }
    if (settings.retryRounds < 1 || settings.retryRounds > 10) {
        errors.add("每轮尝试次数必须为 1 至 10")
    }
    if (!validHttps(settings.primaryUrl) || !validHttps(settings.fallbackUrl)) {
        errors.add("主地址和备用地址必须使用 HTTPS")
    }
    return errors
}

/** `defaultSettings()` returns a fresh, mutable instance on every call, matching ArkTS clone semantics. */
fun defaultSettings(): AppSettings = AppSettings(
    enabled = DEFAULT_SETTINGS.ENABLED,
    targetSsids = emptyList(),
    scope = DEFAULT_SETTINGS.SCOPE,
    intervalSeconds = DEFAULT_SETTINGS.INTERVAL_SECONDS,
    primaryUrl = DEFAULT_SETTINGS.PRIMARY_URL,
    fallbackUrl = DEFAULT_SETTINGS.FALLBACK_URL,
    timeoutSeconds = DEFAULT_SETTINGS.TIMEOUT_SECONDS,
    retryRounds = DEFAULT_SETTINGS.RETRY_ROUNDS,
    notifyOnFailure = DEFAULT_SETTINGS.NOTIFY_ON_FAILURE
)
