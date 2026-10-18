package com.fft.routerkeepalive.ui

/**
 * Ported 1:1 from the HarmonyOS `DockMotionPolicy` (added in the 2026-10-09 final build).
 *
 * Drives the dock tab-switch animation: a 320ms fast-out-slow-in curve, with an active frame-rate
 * range capped at 144Hz and an idle range that releases the boost.
 */
object DockMotionPolicy {

    data class FrameRateRange(val min: Int, val expected: Int, val max: Int)

    fun durationMs(): Long = 320

    fun curve(): String = "fast-out-slow-in"

    /** Picks the highest supported rate up to 144Hz, defaulting to 120Hz. */
    fun activeFrameRate(supportedRates: List<Int> = emptyList()): FrameRateRange {
        var peak = 0
        supportedRates.forEach { rate ->
            if (rate > peak && rate <= 144) {
                peak = rate
            }
        }
        if (peak <= 0) {
            peak = 120
        }
        return FrameRateRange(min = minOf(60, peak), expected = peak, max = peak)
    }

    /** Idle range: no boost. */
    fun idleFrameRate(): FrameRateRange = FrameRateRange(min = 0, expected = 0, max = 0)
}
