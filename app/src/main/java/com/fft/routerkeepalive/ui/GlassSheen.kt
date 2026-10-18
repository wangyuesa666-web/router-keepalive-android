package com.fft.routerkeepalive.ui

import android.graphics.drawable.GradientDrawable
import android.view.View
import androidx.core.content.ContextCompat
import com.google.android.material.card.MaterialCardView
import com.fft.routerkeepalive.R

/**
 * Applies the "liquid glass" sheen to a card.
 *
 * Why this exists rather than a layered background drawable: `MaterialCardView` paints its own
 * `MaterialShapeDrawable` and ignores `android:background` entirely, so a layer-list of glass
 * colours set on the view is silently dropped and the card falls back to the theme's default
 * surface — which in the light theme is `#FEF7FF`, a pink-white that is visibly off the HarmonyOS
 * palette and reads as a dirty edge around every card. The flat fill therefore has to come from
 * `cardBackgroundColor` (see the Card style) and the light has to be painted on top.
 *
 * What is painted is the three things that give a glass pane its materiality:
 *   1. a specular highlight along the top edge, fading downward — light catching the surface;
 *   2. a bevelled rim, brighter than the flat border it sits on;
 *   3. a soft return light along the bottom edge, so the pane looks suspended rather than pasted.
 *
 * The overlay is a separate view pinned to the card's bounds, so it never interferes with the
 * card's own ripple, stroke or corner radius, and it is non-interactive.
 */
object GlassSheen {

    /** Marks the overlay so repeated calls replace it instead of stacking another one. */
    private const val TAG = "glass_sheen"

    /**
     * Adds the sheen to [card]. Idempotent: calling it twice on the same card replaces the
     * previous overlay rather than stacking another one.
     */
    fun applyTo(card: MaterialCardView) {
        val existing = card.findViewWithTag<View>(TAG) ?: View(card.context).also {
            it.tag = TAG
            it.isClickable = false
            it.isFocusable = false
        }
        existing.background = buildSheen(card)
        if (existing.parent == null) {
            card.addView(
                existing,
                android.view.ViewGroup.LayoutParams(
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                    android.view.ViewGroup.LayoutParams.MATCH_PARENT
                )
            )
        }
    }

    private fun buildSheen(card: MaterialCardView): GradientDrawable {
        // Colours resolve through the card's own theme, so the light/dark variants in
        // values/glass.xml and values-night/glass.xml are picked up automatically.
        val top = ContextCompat.getColor(card.context, R.color.glass_sheen_top)
        val mid = ContextCompat.getColor(card.context, R.color.glass_sheen_mid)
        val rim = ContextCompat.getColor(card.context, R.color.glass_sheen_rim)

        // Top-down specular: bright at the top edge, gone by the middle.
        val sheen = GradientDrawable(
            GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(top, mid, android.graphics.Color.TRANSPARENT, android.graphics.Color.TRANSPARENT)
        )
        sheen.cornerRadius = card.radius
        sheen.setStroke(1, rim)
        return sheen
    }
}

/**
 * Walks [root]'s view tree and applies the glass sheen to every card it contains.
 *
 * This is a top-level extension rather than a member of `TabFragment` so the diagnostics page can
 * use it too: it is not a tab, but its content card needs the same light.
 */
fun View.applyGlassSheen() {
    if (this is MaterialCardView) {
        GlassSheen.applyTo(this)
    }
    if (this is android.view.ViewGroup) {
        for (i in 0 until childCount) {
            getChildAt(i).applyGlassSheen()
        }
    }
}
