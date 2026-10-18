package com.fft.routerkeepalive

import com.fft.routerkeepalive.domain.AppSettings
import com.fft.routerkeepalive.domain.BackgroundTaskAvailability
import com.fft.routerkeepalive.domain.BackgroundTaskSnapshot
import com.fft.routerkeepalive.domain.DiagnosticLog
import com.fft.routerkeepalive.domain.DiagnosticRecord
import com.fft.routerkeepalive.domain.FailureTracker
import com.fft.routerkeepalive.domain.ProbeCategory
import com.fft.routerkeepalive.domain.ProbeResult
import com.fft.routerkeepalive.domain.RunScope
import com.fft.routerkeepalive.domain.RuntimePresentation.backgroundCancelMessage
import com.fft.routerkeepalive.domain.RuntimePresentation.dataTransferActivityProgress
import com.fft.routerkeepalive.domain.RuntimePresentation.effectiveKeepAliveStatus
import com.fft.routerkeepalive.domain.RuntimePresentation.shouldAcceptProbeResult
import com.fft.routerkeepalive.domain.RuntimePresentation.shouldDisablePreferenceAfterBackgroundCancel
import com.fft.routerkeepalive.domain.RuntimePresentation.targetWifiWaitingReason
import com.fft.routerkeepalive.domain.SystemBarTheme
import com.fft.routerkeepalive.domain.WifiConnectivity
import com.fft.routerkeepalive.domain.backgroundTaskAvailability
import com.fft.routerkeepalive.domain.combineProbeResults
import com.fft.routerkeepalive.domain.defaultSettings
import com.fft.routerkeepalive.domain.formatRecordLine
import com.fft.routerkeepalive.domain.normalizeSsid
import com.fft.routerkeepalive.domain.recentRecords
import com.fft.routerkeepalive.domain.ssidListToText
import com.fft.routerkeepalive.domain.ssidTextToList
import com.fft.routerkeepalive.domain.validateSettings
import com.fft.routerkeepalive.ui.DockMaterial
import com.fft.routerkeepalive.ui.DockMotionPolicy
import com.fft.routerkeepalive.ui.LiquidHeaderPage
import com.fft.routerkeepalive.ui.LiquidHeaderPolicy
import com.fft.routerkeepalive.ui.RouterColorMode
import com.fft.routerkeepalive.ui.RouterLayout
import com.fft.routerkeepalive.ui.RouterPalette
import com.fft.routerkeepalive.ui.RouterTheme
import com.fft.routerkeepalive.ui.RouterUiPolicy
import com.fft.routerkeepalive.ui.RouterWindowClass
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Kotlin mirror of the HarmonyOS hypium suite in `entry/src/test/domain/Domain.test.ets`.
 * Every assertion is the same, so the two platforms fail together if behaviour diverges.
 */
class DomainTest {

    private fun defaults(): AppSettings = defaultSettings()

    @Test
    fun providesSafeDefaultsAndValidatesConfiguredRanges() {
        val d = defaults()
        assertFalse(d.enabled)
        assertEquals(60, d.intervalSeconds)
        assertEquals(8, d.timeoutSeconds)
        assertEquals(3, d.retryRounds)
        // The 2026-10-09 HarmonyOS build defaults to ANY_WIFI so probing works out of the box.
        assertEquals(RunScope.ANY_WIFI, d.scope)
        assertEquals(0, validateSettings(d).size)

        val invalid = d.copy(
            intervalSeconds = 14,
            timeoutSeconds = 31,
            retryRounds = 0,
            primaryUrl = "http://example.com"
        )
        assertEquals(4, validateSettings(invalid).size)
    }

    @Test
    fun normalizesQuotedWhitespaceSsidsAndAppliesAllRunScopes() {
        assertEquals("Dorm WiFi", normalizeSsid("  \"Dorm WiFi\"  "))
        assertTrue(RunScope.shouldRun(RunScope.TARGET_WIFI, true, "Dorm WiFi", listOf("\"Dorm WiFi\"")))
        assertFalse(RunScope.shouldRun(RunScope.TARGET_WIFI, true, "Other", listOf("Dorm WiFi")))
        assertTrue(RunScope.shouldRun(RunScope.ANY_WIFI, true, "Other", emptyList()))
        assertFalse(RunScope.shouldRun(RunScope.MANUAL, true, "Dorm WiFi", listOf("Dorm WiFi")))
    }

    @Test
    fun treatsAndroidSsidPlaceholdersAsUnreadable() {
        // WifiInfo.getSsid() returns these literals when the platform withholds the real name.
        assertEquals("", normalizeSsid("<unknown ssid>"))
        assertEquals("", normalizeSsid("\"<unknown ssid>\""))
        assertEquals("", normalizeSsid("unknown ssid"))
        assertEquals("", normalizeSsid("0x"))
        assertEquals("", normalizeSsid("<none>"))
        assertEquals("", normalizeSsid(""))
        assertEquals("", normalizeSsid("   "))
        // A real name still survives.
        assertEquals("AndroidWifi", normalizeSsid("\"AndroidWifi\""))
        assertEquals("CMCC-TGyH-5G", normalizeSsid("CMCC-TGyH-5G"))
    }

    @Test
    fun showsTargetWifiWaitingReasonOnlyWhenAutomaticProbingIsGated() {
        assertEquals(
            "",
            targetWifiWaitingReason(
                RunScope.TARGET_WIFI, true, "CMCC-TGyH-5G", listOf("CMCC-TGyH-5G"), ""
            )
        )
        assertTrue(
            targetWifiWaitingReason(
                RunScope.TARGET_WIFI, true, "Other", listOf("CMCC-TGyH-5G"), ""
            ).contains("不在目标列表")
        )
        assertEquals(
            "",
            targetWifiWaitingReason(
                RunScope.ANY_WIFI, true, "Other", listOf("CMCC-TGyH-5G"), ""
            )
        )
    }

    @Test
    fun doesNotAttributeAmbiguousBackgroundCancellationToTheUser() {
        assertFalse(backgroundCancelMessage(1).contains("用户取消"))
        assertTrue(backgroundCancelMessage(1).contains("通知"))
        assertTrue(backgroundCancelMessage(2).contains("系统取消"))
        assertFalse(shouldDisablePreferenceAfterBackgroundCancel(1))
        assertFalse(shouldDisablePreferenceAfterBackgroundCancel(2))
        assertEquals("后台权限受限", effectiveKeepAliveStatus("运行正常", true, false))
        assertEquals("运行正常", effectiveKeepAliveStatus("运行正常", true, true))
    }

    @Test
    fun keepsDataTransferActivityPulseActive() {
        assertEquals(1, dataTransferActivityProgress(0))
        assertEquals(2, dataTransferActivityProgress(1))
        assertEquals(99, dataTransferActivityProgress(98))
        assertEquals(1, dataTransferActivityProgress(99))
        assertEquals(1, dataTransferActivityProgress(-5))
    }

    @Test
    fun discardsAutomaticResultWhenBackgroundTaskEndsDuringTheRequest() {
        assertTrue(shouldAcceptProbeResult(true, true, false))
        assertFalse(shouldAcceptProbeResult(true, false, false))
        assertFalse(shouldAcceptProbeResult(false, false, false))
        assertTrue(shouldAcceptProbeResult(true, false, true))
    }

    @Test
    fun convertsSsidEditingTextWithoutDuplicatesOrUnstableOrdering() {
        val ssids = ssidTextToList(" Dorm WiFi, ,\"Guest\",Dorm WiFi, Lab ")
        assertEquals(3, ssids.size)
        assertEquals("Dorm WiFi", ssids[0])
        assertEquals("Guest", ssids[1])
        assertEquals("Lab", ssids[2])
        assertEquals("Dorm WiFi, Guest, Lab", ssidListToText(ssids))
        assertEquals(0, ssidTextToList("").size)
    }

    @Test
    fun runsAnyWifiOnAvailabilityAloneBecauseSsidMayBeUnreadable() {
        assertTrue(RunScope.shouldRun(RunScope.ANY_WIFI, true, "", emptyList()))
        assertTrue(RunScope.shouldRun(RunScope.ANY_WIFI, true, "", listOf("Dorm WiFi")))
        assertFalse(RunScope.shouldRun(RunScope.ANY_WIFI, false, "Dorm WiFi", emptyList()))
        assertFalse(RunScope.shouldRun(RunScope.TARGET_WIFI, true, "", listOf("Dorm WiFi")))
        assertFalse(RunScope.shouldRun(RunScope.TARGET_WIFI, false, "Dorm WiFi", listOf("Dorm WiFi")))
    }

    @Test
    fun treatsSuccessfullyReadSsidAsProofThatWifiIsAvailable() {
        assertTrue(WifiConnectivity.reconcile(false, "iPhone Air"))
        assertFalse(WifiConnectivity.reconcile(false, ""))
        assertTrue(WifiConnectivity.reconcile(true, ""))
    }

    @Test
    fun usesReadableSystemBarColorsForBothThemes() {
        assertEquals("#FFF4EFF7", SystemBarTheme.contentColor(true))
        assertEquals("#FF173A30", SystemBarTheme.contentColor(false))
    }

    @Test
    fun choosesSingleOrDoubleColumnLayoutsFromWidthAndAspectRatio() {
        assertEquals(RouterLayout.SINGLE_COLUMN, RouterUiPolicy.layoutFor(390f, 844f))
        assertEquals(RouterLayout.SINGLE_COLUMN, RouterUiPolicy.layoutFor(700f, 1000f))
        assertEquals(RouterLayout.DOUBLE_COLUMN, RouterUiPolicy.layoutFor(700f, 700f))
        assertEquals(RouterLayout.DOUBLE_COLUMN, RouterUiPolicy.layoutFor(1280f, 800f))
        assertEquals(RouterLayout.SINGLE_COLUMN, RouterUiPolicy.layoutFor(1280f, 1920f))
    }

    @Test
    fun classifiesWindowsFromUsableWidthAndAspectRatio() {
        assertEquals(RouterWindowClass.PORTRAIT_STACK, RouterUiPolicy.windowClass(599f, 400f))
        assertEquals(RouterWindowClass.MEDIUM_LANDSCAPE, RouterUiPolicy.windowClass(600f, 500f))
        assertEquals(RouterWindowClass.MEDIUM_LANDSCAPE, RouterUiPolicy.windowClass(839f, 700f))
        assertEquals(RouterWindowClass.EXPANDED_LANDSCAPE, RouterUiPolicy.windowClass(840f, 700f))
        assertEquals(RouterWindowClass.EXPANDED_LANDSCAPE, RouterUiPolicy.windowClass(1279f, 800f))
        assertEquals(RouterWindowClass.ULTRA_WIDE, RouterUiPolicy.windowClass(1280f, 800f))
        assertEquals(RouterWindowClass.EXPANDED_LANDSCAPE, RouterUiPolicy.windowClass(1000f, 1190f))
        assertEquals(RouterWindowClass.PORTRAIT_STACK, RouterUiPolicy.windowClass(1000f, 1200f))
    }

    @Test
    fun usesPageSpecificColumnsAndConstrainsUltraWideContent() {
        assertFalse(RouterUiPolicy.homeUsesColumns(RouterWindowClass.PORTRAIT_STACK))
        assertTrue(RouterUiPolicy.homeUsesColumns(RouterWindowClass.MEDIUM_LANDSCAPE))
        assertFalse(RouterUiPolicy.settingsUsesColumns(RouterWindowClass.MEDIUM_LANDSCAPE))
        assertTrue(RouterUiPolicy.settingsUsesColumns(RouterWindowClass.EXPANDED_LANDSCAPE))
        assertEquals(1240, RouterUiPolicy.contentMaxWidth(RouterWindowClass.EXPANDED_LANDSCAPE))
        assertEquals(1240, RouterUiPolicy.contentMaxWidth(RouterWindowClass.ULTRA_WIDE))
        assertEquals(18, RouterUiPolicy.horizontalPadding(RouterWindowClass.PORTRAIT_STACK))
        assertEquals(24, RouterUiPolicy.horizontalPadding(RouterWindowClass.ULTRA_WIDE))
        assertEquals(16, RouterUiPolicy.dockExtraMargin(519f))
        assertEquals(24, RouterUiPolicy.dockExtraMargin(520f))
    }

    @Test
    fun mergesDynamicAvoidAreasAndKeepsVisualInsetWhenSystemReportsZero() {
        assertEquals(28f, RouterUiPolicy.mergeAvoidInsets(0f, 28f, 16f))
        assertEquals(0f, RouterUiPolicy.mergeAvoidInsets(-4f, 0f, 0f))
        assertEquals(16f, RouterUiPolicy.contentInset(0f, 16f))
        assertEquals(40f, RouterUiPolicy.contentInset(24f, 16f))
        assertEquals(18f, RouterUiPolicy.contentInset(-3f, 18f))
    }

    @Test
    fun clampsLiquidHeaderCollapseProgressTo40vpRange() {
        assertEquals(0f, LiquidHeaderPolicy.progress(-8f))
        assertEquals(0f, LiquidHeaderPolicy.progress(0f))
        assertEquals(0.5f, LiquidHeaderPolicy.progress(20f))
        assertEquals(1f, LiquidHeaderPolicy.progress(40f))
        assertEquals(1f, LiquidHeaderPolicy.progress(80f))
    }

    @Test
    fun integratesLiquidHeaderSurfaceWithStatusBar() {
        assertEquals(0f, LiquidHeaderPolicy.topRadius())
        // The 2026-10-09 build dropped the rounded bottom corners: bottomRadius() is 0 for all
        // progress values, leaving only the hairline bottom border.
        assertEquals(0f, LiquidHeaderPolicy.bottomRadius(0f))
        assertEquals(0f, LiquidHeaderPolicy.bottomRadius(1f))
        assertEquals(
            104f,
            LiquidHeaderPolicy.surfaceHeight(LiquidHeaderPage.SETTINGS, 0f, false, 36f)
        )
        assertEquals(
            118f,
            LiquidHeaderPolicy.contentTopInset(LiquidHeaderPage.SETTINGS, false, 36f)
        )
    }

    @Test
    fun interpolatesKeepAliveAndSettingsHeaderMetrics() {
        assertEquals(88f, LiquidHeaderPolicy.height(LiquidHeaderPage.KEEP_ALIVE, 0f, false))
        assertEquals(56f, LiquidHeaderPolicy.height(LiquidHeaderPage.KEEP_ALIVE, 1f, false))
        assertEquals(72f, LiquidHeaderPolicy.height(LiquidHeaderPage.SETTINGS, 0f, true))
        assertEquals(52f, LiquidHeaderPolicy.height(LiquidHeaderPage.SETTINGS, 1f, true))
        assertEquals(28f, LiquidHeaderPolicy.titleSize(LiquidHeaderPage.KEEP_ALIVE, 0f))
        assertEquals(20f, LiquidHeaderPolicy.titleSize(LiquidHeaderPage.KEEP_ALIVE, 1f))
    }

    @Test
    fun keepsHeaderGeometryFiniteForInvalidInput() {
        assertEquals(0f, LiquidHeaderPolicy.progress(Float.NaN))
        assertEquals(10f, LiquidHeaderPolicy.lerp(10f, 20f, Float.NaN))
        assertEquals(20f, LiquidHeaderPolicy.lerp(10f, 20f, 2f))
    }

    @Test
    fun snapsCollapseStateWhenReducedMotionIsEnabled() {
        assertEquals(0f, LiquidHeaderPolicy.progress(19f, true))
        assertEquals(1f, LiquidHeaderPolicy.progress(20f, true))
    }

    @Test
    fun keepsWideHeadersSlightlyTallerWhilePreservingCollapse() {
        assertEquals(92f, LiquidHeaderPolicy.height(LiquidHeaderPage.KEEP_ALIVE, 0f, true))
        assertEquals(60f, LiquidHeaderPolicy.height(LiquidHeaderPage.KEEP_ALIVE, 1f, true))
        assertEquals(68f, LiquidHeaderPolicy.height(LiquidHeaderPage.SETTINGS, 0f, false))
        assertEquals(50f, LiquidHeaderPolicy.height(LiquidHeaderPage.SETTINGS, 1f, false))
        assertEquals(102f, LiquidHeaderPolicy.contentTopInset(LiquidHeaderPage.KEEP_ALIVE, false, 0f))
    }

    @Test
    fun drivesDockMotionWithTheHarmonyCurveAndFrameRates() {
        assertEquals(320L, DockMotionPolicy.durationMs())
        assertEquals("fast-out-slow-in", DockMotionPolicy.curve())
        // Picks the highest supported rate up to 144Hz, matching the ArkTS loop exactly.
        assertEquals(120, DockMotionPolicy.activeFrameRate().expected)
        assertEquals(120, DockMotionPolicy.activeFrameRate(emptyList()).expected)
        assertEquals(120, DockMotionPolicy.activeFrameRate(listOf(60, 90, 120)).expected)
        assertEquals(144, DockMotionPolicy.activeFrameRate(listOf(60, 144, 165)).expected)
        assertEquals(60, DockMotionPolicy.activeFrameRate(listOf(30, 60)).expected)
        assertEquals(90, DockMotionPolicy.activeFrameRate(listOf(30, 60, 90)).expected)
        // min is capped at 60 by Math.min(60, peak).
        assertEquals(60, DockMotionPolicy.activeFrameRate(listOf(30, 60)).min)
        // Idle releases the boost entirely.
        assertEquals(0, DockMotionPolicy.idleFrameRate().expected)
        assertEquals(0, DockMotionPolicy.idleFrameRate().max)
    }

    @Test
    fun resolvesExplicitAndSystemThemesWithoutMixingPalettes() {
        assertTrue(RouterUiPolicy.resolveDark("dark", false))
        assertFalse(RouterUiPolicy.resolveDark("light", true))
        assertTrue(RouterUiPolicy.resolveDark("system", true))
        assertFalse(RouterUiPolicy.resolveDark("system", false))
        assertTrue(RouterUiPolicy.shouldRefreshSystemChrome("system"))
        assertFalse(RouterUiPolicy.shouldRefreshSystemChrome("light"))
        assertFalse(RouterUiPolicy.shouldRefreshSystemChrome("dark"))
        assertEquals(RouterColorMode.FOLLOW_SYSTEM, RouterUiPolicy.colorModeForTheme("system"))
        assertEquals(RouterColorMode.LIGHT, RouterUiPolicy.colorModeForTheme("light"))
        assertEquals(RouterColorMode.DARK, RouterUiPolicy.colorModeForTheme("dark"))
    }

    @Test
    fun fallsBackWhenImmersiveDockMaterialUnavailable() {
        assertEquals(DockMaterial.IMMERSIVE, RouterUiPolicy.dockMaterial(true))
        assertEquals(DockMaterial.FALLBACK, RouterUiPolicy.dockMaterial(false))
    }

    @Test
    fun keepsLightAndDarkPalettesReadableAndVisuallyDistinct() {
        val light: RouterPalette = RouterTheme.palette(false)
        val dark: RouterPalette = RouterTheme.palette(true)
        assertEquals("#173A30", light.primaryText)
        assertEquals("#4C685E", light.secondaryText)
        assertEquals("#176149", light.accent)
        assertEquals("#F4EFF7", dark.primaryText)
        assertEquals("#C4B9CD", dark.secondaryText)
        assertEquals("#BDA5F2", dark.accent)
        assertEquals("#E6176149", light.actionSurface)
        assertEquals("#E6BDA5F2", dark.actionSurface)
        assertEquals("#176149", light.dockLight)
        assertEquals("#BDA5F2", dark.dockLight)
        assertEquals("#F7FFFFFF", light.controlSurface)
        assertEquals("#8FAFC9BA", light.controlBorder)
        assertEquals("#E61E1827", dark.controlSurface)
        assertEquals("#8A756782", dark.controlBorder)
        assertFalse(light.controlSurface == light.card)
        assertFalse(dark.controlSurface == dark.card)
        assertEquals(light.accent, light.controlFocus)
        assertEquals(dark.accent, dark.controlFocus)
        assertEquals(dark.accent, dark.normalStatus)
        assertFalse(dark.normalStatus == light.accent)
        assertFalse(dark.danger == dark.accent)
    }

    @Test
    fun parsesPaletteColorsIncludingAlpha() {
        // #RRGGBB without alpha is treated as fully opaque, like ArkUI does.
        assertEquals(-1, RouterTheme.parseColor("#FFFFFFFF"))
        assertEquals(0xFF176149.toInt(), RouterTheme.parseColor("#176149"))
        assertEquals(0xE6176149.toInt(), RouterTheme.parseColor("#E6176149"))
        // #RGB shorthand expands and becomes opaque.
        assertEquals(0xFFFFFFFF.toInt(), RouterTheme.parseColor("#FFF"))
    }

    @Test
    fun alertsOnlyAfterThreeCompleteFailedRoundsAndResetsAfterSuccess() {
        val tracker = FailureTracker(3)
        assertFalse(tracker.recordRound(false))
        assertFalse(tracker.recordRound(false))
        assertTrue(tracker.recordRound(false))
        assertEquals(3, tracker.consecutiveFailedRounds)
        assertFalse(tracker.recordRound(true))
        assertEquals(0, tracker.consecutiveFailedRounds)
    }

    @Test
    fun combinesPrimaryAndFallbackResultsWithoutHidingBothFailures() {
        val timeout = ProbeResult(false, ProbeCategory.TIMEOUT, 8000.0)
        val ok = ProbeResult(true, ProbeCategory.SUCCESS, 120.0)
        val tls = ProbeResult(false, ProbeCategory.TLS, 50.0)
        assertTrue(combineProbeResults(timeout, ok).success)
        val failed = combineProbeResults(timeout, tls)
        assertFalse(failed.success)
        assertEquals(ProbeCategory.ALL_ENDPOINTS_FAILED, failed.category)
    }

    @Test
    fun capsDiagnosticsAt100AndStoresOnlySafeFields() {
        val log = DiagnosticLog()
        for (i in 0 until 105) {
            log.append(DiagnosticRecord(i.toLong(), ProbeCategory.SUCCESS, i.toDouble()))
        }
        assertEquals(100, log.records.size)
        assertEquals(5L, log.records[0].timestamp)
        val serialized = log.serialize()
        assertFalse(serialized.contains("https://"))
        assertFalse(serialized.contains("password"))
        assertEquals(100, DiagnosticLog.deserialize(serialized).records.size)
    }

    @Test
    fun rendersDiagnosticLinesAndListsNewestRecordsFirst() {
        val calendar = java.util.Calendar.getInstance().apply {
            set(2026, 9, 5, 8, 5, 3)
        }
        val timestamp = calendar.timeInMillis
        val success = DiagnosticRecord(timestamp, ProbeCategory.SUCCESS, 218.0)
        assertEquals("08:05:03 · SUCCESS · 218 ms", formatRecordLine(success))

        val markup = DiagnosticRecord(timestamp, "SUCCESS<script>ALERT</script>", 41.6)
        assertEquals("08:05:03 · SUCCESSALERT · 42 ms", formatRecordLine(markup))
        assertFalse(
            formatRecordLine(DiagnosticRecord(timestamp, "https://dorm.example/login", 12.0))
                .contains("http")
        )
        assertEquals(
            "08:05:03 · UNKNOWN · 0 ms",
            formatRecordLine(DiagnosticRecord(timestamp, "", -5.0))
        )

        val records =
            (0 until 25).map { DiagnosticRecord(it.toLong(), ProbeCategory.SUCCESS, it.toDouble()) }
        val recent = recentRecords(records, 20)
        assertEquals(20, recent.size)
        assertEquals(24L, recent[0].timestamp)
        assertEquals(5L, recent[19].timestamp)
        assertEquals(0L, records[0].timestamp)
        assertEquals(25, records.size)
        assertEquals(0, recentRecords(records, 0).size)
        assertEquals(25, recentRecords(records, 40).size)
    }

    @Test
    fun doesNotTreatVerificationErrorAsActiveBackgroundTaskWithoutEvidence() {
        assertEquals(BackgroundTaskAvailability.MISSING, backgroundTaskAvailability(emptyList()))
        assertEquals(
            BackgroundTaskAvailability.MISSING,
            backgroundTaskAvailability(listOf(BackgroundTaskSnapshot("OtherAbility", false)))
        )
        assertEquals(
            BackgroundTaskAvailability.ACTIVE,
            backgroundTaskAvailability(listOf(BackgroundTaskSnapshot("EntryAbility", false)))
        )
        assertEquals(
            BackgroundTaskAvailability.SUSPENDED,
            backgroundTaskAvailability(listOf(BackgroundTaskSnapshot("EntryAbility", true)))
        )
    }
}
