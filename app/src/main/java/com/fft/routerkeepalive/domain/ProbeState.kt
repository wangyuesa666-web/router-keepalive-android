package com.fft.routerkeepalive.domain

data class ProbeResult(
    val success: Boolean,
    val category: String,
    val durationMs: Double,
    val statusCode: Int? = null
)

object ProbeCategory {
    const val SUCCESS: String = "SUCCESS"
    const val TIMEOUT: String = "TIMEOUT"
    const val DNS: String = "DNS"
    const val TLS: String = "TLS"
    const val HTTP: String = "HTTP"
    const val REDIRECT: String = "REDIRECT"
    const val NETWORK_UNAVAILABLE: String = "NETWORK_UNAVAILABLE"
    const val ALL_ENDPOINTS_FAILED: String = "ALL_ENDPOINTS_FAILED"
    const val UNKNOWN: String = "UNKNOWN"
}

fun combineProbeResults(primary: ProbeResult, fallback: ProbeResult): ProbeResult {
    if (primary.success) {
        return primary
    }
    if (fallback.success) {
        return fallback
    }
    return ProbeResult(
        success = false,
        category = ProbeCategory.ALL_ENDPOINTS_FAILED,
        durationMs = primary.durationMs + fallback.durationMs
    )
}

/**
 * Counts consecutive fully failed rounds and fires the alert exactly once per streak,
 * ported 1:1 from the HarmonyOS `FailureTracker`.
 */
class FailureTracker(private val threshold: Int = 3) {
    var consecutiveFailedRounds: Int = 0
        private set
    private var alertIssued: Boolean = false

    /** Returns true exactly when this round crossed the threshold and no alert was sent yet. */
    fun recordRound(success: Boolean): Boolean {
        if (success) {
            consecutiveFailedRounds = 0
            alertIssued = false
            return false
        }
        consecutiveFailedRounds++
        if (consecutiveFailedRounds >= threshold && !alertIssued) {
            alertIssued = true
            return true
        }
        return false
    }
}
