package com.fft.routerkeepalive.ui

import android.os.Bundle
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import com.google.android.material.card.MaterialCardView
import com.fft.routerkeepalive.MainActivity

/**
 * Shared plumbing for the two tab pages.
 *
 * Both tabs are the ArkUI `TabContent` of an `HdsTabs`: a full-height `Scroll` whose top padding is
 * `LiquidHeaderPolicy.contentTopInset(...)` (the header height plus 14) and whose scroll offset
 * drives the collapsing header. On Android the header lives in the Activity, so this class reports
 * the offset back through [MainActivity.onTabScrolled] and applies the safe-area-aware padding that
 * the ArkUI builder computes inline.
 */
abstract class TabFragment : Fragment() {

    /** The fragment's scrolling container; every tab layout exposes it as `tab_scroll`. */
    protected abstract val tabScroll: NestedScrollView

    /** True for the keep-alive tab, which uses the larger header metrics. */
    protected abstract val headerPage: LiquidHeaderPage

    /** True when the current window should use the wider header metrics. */
    protected abstract fun usesWideHeader(): Boolean

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tabScroll.setOnScrollChangeListener { _, _, scrollY, _, _ ->
            (activity as? MainActivity)?.onTabScrolled(
                scrollY / resources.displayMetrics.density
            )
        }
        applyTabInsets()
    }

    /**
     * ArkUI: `Scroll { Row { Column.padding({ top: contentTopInset(page, wide, safeTop),
     * left: contentInset(safeLeft, hPad), right: ..., bottom: safeBottom + 96 }) } }`.
     *
     * Read from the Activity content root because that is where the window-insets dispatch is
     * consumed; a listener on this Fragment's own root would never fire.
     */
    private fun applyTabInsets() {
        val contentRoot = requireActivity().findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            val safeTop = bars.top / density
            val safeBottom = bars.bottom / density
            val safeLeft = bars.left / density
            val safeRight = bars.right / density

            val metrics = resources.displayMetrics
            val windowClass = RouterUiPolicy.windowClass(
                metrics.widthPixels / density, metrics.heightPixels / density
            )
            val horizontal = RouterUiPolicy.horizontalPadding(windowClass).toFloat()

            val padding = _bindingScroll() ?: return@setOnApplyWindowInsetsListener insets
            padding.setPadding(
                dp(RouterUiPolicy.contentInset(safeLeft, horizontal)),
                dp(
                    LiquidHeaderPolicy.contentTopInset(
                        headerPage, usesWideHeader(), safeTop
                    )
                ),
                dp(RouterUiPolicy.contentInset(safeRight, horizontal)),
                dp(safeBottom + 96f)
            )
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    /** The scroll view, or null once the view hierarchy is gone. */
    private fun _bindingScroll(): NestedScrollView? =
        if (isAdded && view != null) tabScroll else null

    protected fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}
