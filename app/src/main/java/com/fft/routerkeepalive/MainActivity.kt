package com.fft.routerkeepalive

import android.Manifest
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.view.animation.Interpolator
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.core.view.updatePadding
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavController
import androidx.navigation.fragment.NavHostFragment
import com.fft.routerkeepalive.databinding.ActivityMainBinding
import com.fft.routerkeepalive.service.KeepAliveController
import com.fft.routerkeepalive.ui.DockMotionPolicy
import com.fft.routerkeepalive.ui.KeepAliveViewModel
import com.fft.routerkeepalive.ui.LiquidHeaderPage
import com.fft.routerkeepalive.ui.LiquidHeaderPolicy
import com.fft.routerkeepalive.ui.RouterColorMode
import com.fft.routerkeepalive.ui.RouterUiPolicy
import com.fft.routerkeepalive.ui.attachPressFeedback
import com.google.android.material.snackbar.Snackbar
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * App shell, ported 1:1 from the HarmonyOS `pages/Index.ets` (2026-10-09 final build).
 *
 * Structure mapping:
 * - ArkUI `Stack` + 180deg gradient -> `FrameLayout` with `bg_page_gradient`
 * - ArkUI `LiquidHeader` -> the pinned glass header (card colour, hairline bottom border,
 *   height/title/subtitle interpolated by `LiquidHeaderPolicy` on scroll)
 * - ArkUI `HdsTabs` + `DockTab` -> the floating 112x52 dock with a 320ms fast-out-slow-in
 *   crossfade between the medium and bold label, exactly as `DockMotionPolicy` describes
 * - ArkUI `NavPathStack` push/pop -> Navigation `navigate()` with predictive back
 */
class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: KeepAliveViewModel by viewModels()
    private lateinit var navController: NavController
    private val store by lazy { com.fft.routerkeepalive.data.SettingsStore(applicationContext) }

    private val requestNotificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    /** Re-reads the SSID once granted, so the target-SSID scope starts working immediately. */
    private val requestWifiPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) {
            KeepAliveController.refreshWifiNow()
        }

    /** Dock animation curve and duration, from DockMotionPolicy. */
    private val dockInterpolator: Interpolator = AccelerateDecelerateInterpolator()
    private val dockDurationMs = DockMotionPolicy.durationMs()

    private var selectedTab = 0
    private var safeTopPx = 0
    private var safeBottomPx = 0
    private var safeLeftPx = 0
    private var safeRightPx = 0
    private var viewportWidth = 390f
    private var viewportHeight = 844f
    private var reduceMotion = false

    /** The palette currently on screen, so a theme change can be detected and repainted. */
    private var appliedDark: Boolean = false

    /** The theme mode last handed to [applyThemeMode], to avoid reacting to our own emission. */
    private var appliedThemeMode: String = ""

    override fun onCreate(savedInstanceState: Bundle?) {
        // Apply the persisted theme before the content view so the first frame uses the right
        // palette. The value is read here rather than from the ViewModel because the ViewModel is
        // not constructed until after super.onCreate(). `appliedDark` seeds the "already on
        // screen" baseline so this first call never triggers a recreate loop.
        appliedDark = currentDark()
        appliedThemeMode = store.loadThemeMode()
        applyThemeMode(appliedThemeMode)

        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowCompat.getInsetsController(window, binding.root).apply {
            systemBarsBehavior =
                androidx.core.view.WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }

        val navHost = supportFragmentManager.findFragmentById(R.id.nav_host) as NavHostFragment
        navController = navHost.navController

        // Keep the dock and the header in sync when the system back gesture changes destination.
        navController.addOnDestinationChangedListener { _, destination, _ ->
            val tab = if (destination.id == R.id.settingsFragment) 1 else 0
            if (tab != selectedTab) {
                selectedTab = tab
            }
            applyDockSelected(binding.tabKeepAlive, tab == 0)
            applyDockSelected(binding.tabSettings, tab == 1)
            binding.header.isVisible = destination.id != R.id.diagnosticsFragment
            binding.dock.isVisible = destination.id != R.id.diagnosticsFragment
        }

        // The controller owns the settings store, the diagnostics log and the Wi-Fi monitor, so it
        // must be initialized before any UI action can reach setEnabled/runNow/updateSettings.
        KeepAliveController.initialize(applicationContext)

        binding.switchMaster.setOnCheckedChangeListener { _, value ->
            viewModel.setEnabled(value)
        }

        // Press feedback: the dock and the master switch are the two always-visible controls, so
        // they get the iOS-style sink-and-fade. The dock also keeps its rounded ripple (bg_dock_tab)
        // which is clipped to the pill, so the two effects do not fight.
        binding.switchMaster.attachPressFeedback()
        binding.tabKeepAlive.setOnClickListener { selectTab(0) }
        binding.tabSettings.setOnClickListener { selectTab(1) }
        binding.tabKeepAlive.attachPressFeedback()
        binding.tabSettings.attachPressFeedback()

        installInsetsListener()
        observeState()
        applySystemBarAppearance()
        requestNotificationPermissionIfNeeded()
        requestWifiPermissionIfNeeded()
    }

    /**
     * ArkUI `applyAppColorMode()` calls `abilityContext.setColorMode()`, which repaints the whole
     * ability immediately. The Android equivalent is `AppCompatDelegate.setDefaultNightMode()`,
     * but it only repaints if the Activity is recreated — and this Activity declares `uiMode` in
     * `configChanges` (so rotation and font-scale changes do not restart it), which means the
     * night-mode change arrives as `onConfigurationChanged` and nothing repaints.
     *
     * Selecting a theme is a rare, explicit user action, so `recreate()` here is both cheap and
     * the only way to get the immediate feedback the ArkUI version gives. It is skipped when the
     * resolved palette is already the one on screen, which is what happens on the first call from
     * onCreate.
     */
    private fun applyThemeMode(mode: String) {
        AppCompatDelegate.setDefaultNightMode(
            when (RouterUiPolicy.colorModeForTheme(mode)) {
                RouterColorMode.DARK -> AppCompatDelegate.MODE_NIGHT_YES
                RouterColorMode.LIGHT -> AppCompatDelegate.MODE_NIGHT_NO
                else -> AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM
            }
        )
        val dark = RouterUiPolicy.resolveDark(mode, systemDark())
        if (dark != appliedDark) {
            appliedDark = dark
            recreate()
        }
    }

    /** True when the platform is currently in night mode. */
    private fun systemDark(): Boolean =
        resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES

    private fun currentDark(): Boolean =
        RouterUiPolicy.resolveDark(store.loadThemeMode(), systemDark())

    private fun requestNotificationPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            val granted = ContextCompat.checkSelfPermission(
                this, Manifest.permission.POST_NOTIFICATIONS
            ) == PackageManager.PERMISSION_GRANTED
            if (!granted) {
                requestNotificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
    }

    /**
     * The HarmonyOS `GET_WIFI_INFO` grant has no runtime prompt, but on Android 13+ reading the
     * SSID needs `NEARBY_WIFI_DEVICES`. Without it `WifiInfo.getSsid()` returns the `<unknown ssid>`
     * placeholder, so the "仅目标 Wi-Fi" scope could never match a network.
     */
    private fun requestWifiPermissionIfNeeded() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        val granted = ContextCompat.checkSelfPermission(
            this, Manifest.permission.NEARBY_WIFI_DEVICES
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            requestWifiPermission.launch(Manifest.permission.NEARBY_WIFI_DEVICES)
        }
    }

    // ---- Window insets ----------------------------------------------------------------

    /**
     * Mirrors `EntryAbility.refreshAvoidAreas()`: the system, cutout and navigation-indicator
     * areas are merged and then used for the header padding and the dock bottom margin.
     */
    private fun installInsetsListener() {
        ViewCompat.setOnApplyWindowInsetsListener(binding.root) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            safeTopPx = bars.top
            safeBottomPx = bars.bottom
            safeLeftPx = bars.left
            safeRightPx = bars.right
            renderChrome()
            insets
        }
        binding.root.post {
            viewportWidth = binding.root.width / resources.displayMetrics.density
            viewportHeight = binding.root.height / resources.displayMetrics.density
            renderChrome()
        }
    }

    private fun safeTop(): Float = safeTopPx / resources.displayMetrics.density
    private fun safeBottom(): Float = safeBottomPx / resources.displayMetrics.density
    private fun safeLeft(): Float = safeLeftPx / resources.displayMetrics.density
    private fun safeRight(): Float = safeRightPx / resources.displayMetrics.density

    // ---- Scroll-driven liquid header --------------------------------------------------

    /**
     * Mirrors `onDidScroll` -> `refreshHeaderProgress`. Each tab Fragment owns its own scroll
     * container, so it reports its offset here; the header geometry, title size and subtitle size
     * are all recomputed from the same LiquidHeaderPolicy the ArkUI page uses.
     */
    fun onTabScrolled(scrollYDp: Float) {
        renderHeaderProgress(scrollYDp)
    }

    private fun renderHeaderProgress(scrollYDp: Float) {
        val page = if (selectedTab == 0) LiquidHeaderPage.KEEP_ALIVE else LiquidHeaderPage.SETTINGS
        val progress = LiquidHeaderPolicy.progress(scrollYDp, reduceMotion)
        val wide = if (page == LiquidHeaderPage.KEEP_ALIVE) {
            RouterUiPolicy.homeUsesColumns(RouterUiPolicy.windowClass(viewportWidth, viewportHeight))
        } else {
            RouterUiPolicy.settingsUsesColumns(RouterUiPolicy.windowClass(viewportWidth, viewportHeight))
        }

        val heightDp = LiquidHeaderPolicy.surfaceHeight(page, progress, wide, safeTop())
        binding.header.layoutParams = binding.header.layoutParams.apply {
            height = dp(heightDp)
        }
        binding.headerTitle.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            LiquidHeaderPolicy.titleSize(page, progress)
        )
        // The ArkUI header title is `FontWeight.Bold`; `setTextSize` only changes the size and
        // leaves whatever typeface is set, but the size is refreshed on every scroll tick, so the
        // weight is pinned here too rather than relying on the layout attribute surviving.
        binding.headerTitle.setTypeface(binding.headerTitle.typeface, android.graphics.Typeface.BOLD)
        binding.headerSubtitle.setTextSize(
            android.util.TypedValue.COMPLEX_UNIT_SP,
            LiquidHeaderPolicy.lerp(13f, 11f, progress)
        )
        // The 2026-10-09 build keeps the header square, so only the border/scrim changes.
        binding.header.isActivated = progress > 0.5f
    }

    // ---- Dock -------------------------------------------------------------------------

    /**
     * Mirrors `HdsTabs.onChange` + `animateDockSelection`: switch the page, then crossfade the
     * dock label from medium/secondary to bold/accent over 320ms fast-out-slow-in.
     */
    private fun selectTab(index: Int) {
        if (selectedTab == index) return
        selectedTab = index
        navigateToTab(index)
        animateDockSelection(index)
    }

    private fun navigateToTab(index: Int) {
        val destination = if (index == 0) R.id.keepAliveFragment else R.id.settingsFragment
        if (navController.currentDestination?.id == destination) {
            return
        }
        // The two tabs are siblings: launching with a singleTop-style option keeps the back stack
        // shallow so back from settings lands on the keep-alive tab, like HdsTabs.
        val options = androidx.navigation.NavOptions.Builder()
            .setLaunchSingleTop(true)
            .setRestoreState(true)
            .setPopUpTo(R.id.keepAliveFragment, inclusive = false, saveState = true)
            .build()
        navController.navigate(destination, null, options)
    }

    private fun animateDockSelection(targetIndex: Int) {
        val target = if (targetIndex == 0) binding.tabKeepAlive else binding.tabSettings
        val other = if (targetIndex == 0) binding.tabSettings else binding.tabKeepAlive

        if (reduceMotion) {
            // Reduced motion snaps straight to the final state, like the ArkUI branch.
            applyDockSelected(target, selected = true)
            applyDockSelected(other, selected = false)
            return
        }

        target.animate().cancel()
        other.animate().cancel()
        target.animate()
            .alpha(1f)
            .setDuration(dockDurationMs)
            .setInterpolator(dockInterpolator)
            .start()
        other.animate()
            .alpha(0.6f)
            .setDuration(dockDurationMs)
            .setInterpolator(dockInterpolator)
            .start()
        applyDockSelected(target, selected = true)
        applyDockSelected(other, selected = false)
    }

    /**
     * The ArkUI dock cross-fades two stacked labels (Bold/accent for the active tab, Medium/
     * secondaryText for the inactive one). Here the state is carried by `isSelected`, which the
     * `bg_dock_tab` selector turns into a translucent accent wash clipped to the pill radius, plus
     * the text colour selector. No alpha fade: dimming the whole tab would also dim the highlight
     * and read as a disabled control rather than an unselected one.
     */
    private fun applyDockSelected(view: View, selected: Boolean) {
        view.isSelected = selected
        view.jumpDrawablesToCurrentState()
    }

    // ---- Chrome -----------------------------------------------------------------------

    /** Header padding, dock margins and content top inset, all from RouterUiPolicy. */
    private fun renderChrome() {
        val windowClass = RouterUiPolicy.windowClass(viewportWidth, viewportHeight)
        val horizontal = dp(
            RouterUiPolicy.contentInset(
                safeLeft(),
                RouterUiPolicy.horizontalPadding(windowClass).toFloat()
            )
        )
        val horizontalRight = dp(
            RouterUiPolicy.contentInset(
                safeRight(),
                RouterUiPolicy.horizontalPadding(windowClass).toFloat()
            )
        )
        binding.header.setPadding(
            horizontal,
            dp(safeTop()),
            horizontalRight,
            dp(8f)
        )

        val dockParams = binding.dock.layoutParams as android.view.ViewGroup.MarginLayoutParams
        dockParams.setMargins(
            dp(16f), 0, dp(16f),
            dp(safeBottom() + RouterUiPolicy.dockExtraMargin(viewportHeight))
        )
        binding.dock.layoutParams = dockParams

        renderHeaderProgress(0f)
        applyDockSelected(binding.tabKeepAlive, selectedTab == 0)
        applyDockSelected(binding.tabSettings, selectedTab == 1)
    }

    // ---- State ------------------------------------------------------------------------

    private fun observeState() {
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    viewModel.state.collect { state ->
                        renderHeader(state.displayStatus, state.settings.enabled)
                    }
                }
                launch {
                    viewModel.message.collect { message ->
                        if (message.isNotEmpty()) {
                            Snackbar.make(binding.root, message, Snackbar.LENGTH_SHORT).show()
                        }
                    }
                }
                // The ArkUI page keeps a 1s timer to refresh "最近成功"; only the text nodes are
                // touched, matching ArkUI's incremental diff over @State.
                launch {
                    while (true) {
                        delay(1000)
                        renderHeader(
                            viewModel.state.value.displayStatus,
                            viewModel.state.value.settings.enabled
                        )
                    }
                }
                launch {
                    viewModel.themeMode.collect { mode ->
                        // Reacts to a theme picked in the settings tab. `applyThemeMode` recreates
                        // the Activity when the resolved palette actually changes, which is what
                        // repaints every Material colour; the system-bar icons are refreshed in
                        // both branches, because a theme switch changes which icon colour is
                        // legible even when the palette itself is unchanged (follow-system).
                        if (mode != appliedThemeMode) {
                            appliedThemeMode = mode
                            applyThemeMode(mode)
                        }
                        applySystemBarAppearance()
                    }
                }
            }
        }
    }

    private fun renderHeader(displayStatus: String, enabled: Boolean) {
        binding.headerSubtitle.setText(
            if (enabled) R.string.keepalive_on_subtitle else R.string.keepalive_off_subtitle
        )
        if (binding.switchMaster.isChecked != enabled) {
            binding.switchMaster.isChecked = enabled
        }
        // The diagnostics page carries its own back arrow and title, so hide the tab header.
        val onDiagnostics = navController.currentDestination?.id == R.id.diagnosticsFragment
        binding.header.isVisible = !onDiagnostics
        binding.dock.isVisible = !onDiagnostics
    }

    private fun applySystemBarAppearance() {
        val dark = RouterUiPolicy.resolveDark(
            viewModel.themeMode.value ?: "system",
            (resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK) ==
                android.content.res.Configuration.UI_MODE_NIGHT_YES
        )
        val controller = WindowCompat.getInsetsController(window, binding.root)
        controller.isAppearanceLightStatusBars = !dark
        controller.isAppearanceLightNavigationBars = !dark
    }

    override fun onSupportNavigateUp(): Boolean = navController.navigateUp()

    private fun dp(value: Float): Int = (value * resources.displayMetrics.density + 0.5f).toInt()

    private companion object {
        const val PREFS = "router_keepalive"
        const val KEY_THEME = "themeMode"
    }
}
