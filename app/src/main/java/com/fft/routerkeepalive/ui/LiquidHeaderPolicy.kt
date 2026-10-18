package com.fft.routerkeepalive.ui

enum class LiquidHeaderPage(val key: String) {
    KEEP_ALIVE("keepAlive"),
    SETTINGS("settings")
}

/** Ported 1:1 from the HarmonyOS `LiquidHeaderPolicy`. */
object LiquidHeaderPolicy {
    const val COLLAPSE_DISTANCE: Float = 40f

    fun progress(scrollY: Float, reduceMotion: Boolean = false): Float {
        if (scrollY.isNaN() || scrollY.isInfinite()) {
            return 0f
        }
        val progress = (scrollY / COLLAPSE_DISTANCE).coerceIn(0f, 1f)
        return if (reduceMotion) (if (progress >= 0.5f) 1f else 0f) else progress
    }

    fun lerp(expanded: Float, collapsed: Float, progress: Float): Float {
        val safeProgress = if (progress.isFinite()) progress.coerceIn(0f, 1f) else 0f
        return expanded + (collapsed - expanded) * safeProgress
    }

    fun height(page: LiquidHeaderPage, progress: Float, wide: Boolean): Float =
        if (page == LiquidHeaderPage.KEEP_ALIVE) {
            lerp(if (wide) 92f else 88f, if (wide) 60f else 56f, progress)
        } else {
            lerp(if (wide) 72f else 68f, if (wide) 52f else 50f, progress)
        }

    fun titleSize(page: LiquidHeaderPage, progress: Float): Float =
        lerp(
            if (page == LiquidHeaderPage.KEEP_ALIVE) 28f else 26f,
            if (page == LiquidHeaderPage.KEEP_ALIVE) 20f else 19f,
            progress
        )

    fun topRadius(): Float = 0f

    /**
     * The 2026-10-09 HarmonyOS build removed the rounded bottom corners: the header is now a flat
     * bar with only a hairline bottom border.
     */
    fun bottomRadius(progress: Float): Float = 0f

    fun surfaceHeight(page: LiquidHeaderPage, progress: Float, wide: Boolean, safeTop: Float): Float =
        maxOf(0f, safeTop) + height(page, progress, wide)

    fun contentTopInset(page: LiquidHeaderPage, wide: Boolean, safeTop: Float): Float =
        surfaceHeight(page, 0f, wide, safeTop) + 14f
}
