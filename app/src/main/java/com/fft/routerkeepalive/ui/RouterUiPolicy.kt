package com.fft.routerkeepalive.ui

import android.content.res.Configuration
import android.graphics.Rect
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat

/** Ported 1:1 from the HarmonyOS `RouterWindowClass` / `RouterLayout` enums. */
enum class RouterWindowClass {
    PORTRAIT_STACK,
    MEDIUM_LANDSCAPE,
    EXPANDED_LANDSCAPE,
    ULTRA_WIDE;

    companion object {
        fun fromDp(widthDp: Float, heightDp: Float): RouterWindowClass {
            val safeWidth = maxOf(widthDp, 1f)
            if (safeWidth < 600 || heightDp / safeWidth >= 1.2f) {
                return PORTRAIT_STACK
            }
            if (safeWidth < 840) {
                return MEDIUM_LANDSCAPE
            }
            if (safeWidth < 1280) {
                return EXPANDED_LANDSCAPE
            }
            return ULTRA_WIDE
        }
    }
}

enum class RouterLayout {
    SINGLE_COLUMN,
    DOUBLE_COLUMN;

    companion object {
        fun forSize(widthDp: Float, heightDp: Float): RouterLayout {
            if (heightDp / maxOf(widthDp, 1f) >= 1.2f) return SINGLE_COLUMN
            if (widthDp < 600) return SINGLE_COLUMN
            if (widthDp >= 840) return DOUBLE_COLUMN
            return if (widthDp / maxOf(heightDp, 1f) >= 0.85f) DOUBLE_COLUMN else SINGLE_COLUMN
        }
    }
}

enum class DockMaterial { IMMERSIVE, FALLBACK }

enum class RouterColorMode(val value: Int) {
    FOLLOW_SYSTEM(Configuration.UI_MODE_NIGHT_UNDEFINED),
    DARK(Configuration.UI_MODE_NIGHT_YES),
    LIGHT(Configuration.UI_MODE_NIGHT_NO)
}

/** Ported 1:1 from the HarmonyOS `RouterUiPolicy`, plus the Android window-inset helpers. */
object RouterUiPolicy {

    fun mergeAvoidInsets(firstVp: Float, secondVp: Float, thirdVp: Float = 0f): Float =
        maxOf(0f, firstVp, secondVp, thirdVp)

    fun contentInset(avoidVp: Float, visualPaddingVp: Float): Float =
        maxOf(0f, avoidVp) + maxOf(0f, visualPaddingVp)

    fun windowClass(widthVp: Float, heightVp: Float): RouterWindowClass =
        RouterWindowClass.fromDp(widthVp, heightVp)

    fun homeUsesColumns(windowClass: RouterWindowClass): Boolean =
        windowClass != RouterWindowClass.PORTRAIT_STACK

    fun settingsUsesColumns(windowClass: RouterWindowClass): Boolean =
        windowClass == RouterWindowClass.EXPANDED_LANDSCAPE || windowClass == RouterWindowClass.ULTRA_WIDE

    fun contentMaxWidth(windowClass: RouterWindowClass): Int = 1240

    fun horizontalPadding(windowClass: RouterWindowClass): Int =
        if (windowClass == RouterWindowClass.ULTRA_WIDE) 24 else 18

    fun dockExtraMargin(heightVp: Float): Int = if (heightVp < 520) 16 else 24

    fun layoutFor(widthVp: Float, heightVp: Float): RouterLayout =
        RouterLayout.forSize(widthVp, heightVp)

    fun resolveDark(mode: String, systemDark: Boolean): Boolean =
        mode == "dark" || (mode == "system" && systemDark)

    fun colorModeForTheme(mode: String): RouterColorMode = when (mode) {
        "dark" -> RouterColorMode.DARK
        "light" -> RouterColorMode.LIGHT
        else -> RouterColorMode.FOLLOW_SYSTEM
    }

    fun shouldRefreshSystemChrome(mode: String): Boolean = mode == "system"

    fun dockMaterial(immersiveAvailable: Boolean): DockMaterial =
        if (immersiveAvailable) DockMaterial.IMMERSIVE else DockMaterial.FALLBACK

    // ---- Android-side window metrics ------------------------------------------------

    /** Usable width/height in dp, mirroring the ArkUI viewport the Harmony build measures. */
    fun usableSizeDp(view: View): Pair<Float, Float> {
        val rect = usableRectPx(view)
        val density = view.resources.displayMetrics.density
        return (rect.width() / density) to (rect.height() / density)
    }

    fun usableRectPx(view: View): Rect {
        val insets = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
        if (insets == null) {
            return Rect(0, 0, view.width, view.height)
        }
        return Rect(insets.left, insets.top, view.width - insets.right, view.height - insets.bottom)
    }

    fun safeTopPx(view: View): Int = safeInsets(view).top

    fun safeBottomPx(view: View): Int = safeInsets(view).bottom

    fun safeLeftPx(view: View): Int = safeInsets(view).left

    fun safeRightPx(view: View): Int = safeInsets(view).right

    private fun safeInsets(view: View): androidx.core.graphics.Insets {
        val compat = ViewCompat.getRootWindowInsets(view)
            ?.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            ?: return androidx.core.graphics.Insets.NONE
        return compat
    }
}
