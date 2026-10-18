package com.fft.routerkeepalive.ui

import android.view.MotionEvent
import android.view.View
import android.view.animation.DecelerateInterpolator

/**
 * iOS-style press feedback.
 *
 * A plain ripple says "this was pressed" but feels flat on glass: the ripple is drawn *inside* the
 * view and reads as a change of colour, not as the surface reacting to your finger. On a glass
 * material the physical metaphor is that the surface gives way and catches the light differently
 * as it presses down. So a press here does two things at once:
 *
 *  1. the view scales down a few percent, so it appears to sink toward the page;
 *  2. it fades slightly, because a pressed glass surface catches less light than a resting one.
 *
 * Both are restored on release *or* cancel, so an interrupted touch (a drag out of the button, a
 * system gesture) never leaves a view stuck in the pressed state. Durations are short — 110ms in,
 * 160ms out — so rapid tab switching never feels laggy.
 *
 * The resting alpha is captured on the way down and restored on the way up rather than being reset
 * to 1f: several views in this app run at a fractional alpha (the collapsed advanced section, the
 * inactive dock labels), and forcing them opaque would punch a bright hole through the glass.
 */
private const val PRESS_SCALE = 0.97f
private const val PRESS_ALPHA_FACTOR = 0.88f
private const val PRESS_DURATION = 110L
private const val RELEASE_DURATION = 160L

/** Resting alpha, captured at ACTION_DOWN so the release can restore exactly what was there. */
private val restingAlpha = java.util.WeakHashMap<View, Float>()

/**
 * Attaches press feedback. Safe to call repeatedly: it replaces any previous listener instead of
 * stacking one.
 */
fun View.attachPressFeedback() {
    setOnTouchListener { view, event ->
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> view.pressDown()
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.pressUp()
        }
        // Returning false preserves normal click handling and any ripple already attached.
        false
    }
}

private fun View.pressDown() {
    animate().cancel()
    restingAlpha[this] = alpha
    val pressed = alpha * PRESS_ALPHA_FACTOR
    animate()
        .scaleX(PRESS_SCALE)
        .scaleY(PRESS_SCALE)
        .alpha(pressed)
        .setDuration(PRESS_DURATION)
        .setInterpolator(DecelerateInterpolator())
        .start()
}

private fun View.pressUp() {
    animate().cancel()
    animate()
        .scaleX(1f)
        .scaleY(1f)
        .alpha(restingAlpha.remove(this) ?: alpha)
        .setDuration(RELEASE_DURATION)
        .setInterpolator(DecelerateInterpolator())
        .start()
}
