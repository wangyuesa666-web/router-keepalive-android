package com.fft.routerkeepalive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import com.fft.routerkeepalive.R
import com.fft.routerkeepalive.databinding.FragmentKeepAliveBinding
import com.fft.routerkeepalive.domain.DiagnosticRecord
import com.fft.routerkeepalive.domain.formatRecordLine
import com.fft.routerkeepalive.service.KeepAliveController
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch

/**
 * Keep-alive tab, ported 1:1 from the ArkUI `KeepAliveContent` builder (2026-10-09 build):
 * StatusCard, DiagnosticsCard and GuidanceCard in the same order with the same text.
 */
class KeepAliveFragment : TabFragment() {

    private var _binding: FragmentKeepAliveBinding? = null
    private val binding get() = _binding!!
    private val viewModel: KeepAliveViewModel by activityViewModels()

    override val tabScroll: NestedScrollView get() = binding.tabScroll
    override val headerPage = LiquidHeaderPage.KEEP_ALIVE
    override fun usesWideHeader(): Boolean =
        RouterUiPolicy.homeUsesColumns(
            RouterUiPolicy.windowClass(
                resources.displayMetrics.widthPixels / resources.displayMetrics.density,
                resources.displayMetrics.heightPixels / resources.displayMetrics.density
            )
        )

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentKeepAliveBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.buttonTestNow.setOnClickListener {
            viewModel.runNow()
        }
        binding.buttonViewAll.setOnClickListener {
            findNavController().navigate(R.id.diagnosticsFragment)
        }
        // iOS-style press feedback on the two always-present buttons.
        binding.buttonTestNow.attachPressFeedback()
        binding.buttonViewAll.attachPressFeedback()

        // The glass light has to be painted after inflation: a MaterialCardView ignores
        // `android:background`, so the layered drawable route cannot reach it.
        binding.root.applyGlassSheen()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }

        // No message observer on this tab: `viewModel.message` is a StateFlow, so returning here
        // from the settings tab replays the last save result and raises a second Snackbar for a
        // confirmation the user already read. Only the settings tab consumes it, inline.
    }

    private fun render(state: KeepAliveUiState) {
        renderStatus(state)
        renderWifi(state)
        renderLastSuccess(state)
        renderFailures(state)
        renderWaitingReason(state)
        renderPreview(state.previewDiagnostics)
    }

    /** The ArkUI status Text: 23sp bold, colour from `statusColor()`. */
    private fun renderStatus(state: KeepAliveUiState) {
        binding.textStatus.text = state.displayStatus
        val colorRes = when (state.displayStatus) {
            KeepAliveController.Status.AUTH_SUSPECTED -> R.color.status_danger
            KeepAliveController.Status.RUNNING -> R.color.status_running
            KeepAliveController.Status.WAITING_WIFI -> R.color.status_waiting
            else -> R.color.status_warning
        }
        binding.textStatus.setTextColor(ContextCompat.getColor(requireContext(), colorRes))
    }

    private fun renderWifi(state: KeepAliveUiState) {
        binding.textWifi.text = when {
            !state.wifiAvailable -> getString(R.string.wifi_unavailable)
            state.currentSsid.isNotEmpty() -> state.currentSsid
            else -> getString(R.string.wifi_unnamed)
        }
    }

    private fun renderLastSuccess(state: KeepAliveUiState) {
        binding.textLastSuccess.text = if (state.lastSuccessAt > 0) {
            SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
                .format(Date(state.lastSuccessAt))
        } else {
            getString(R.string.last_success_none)
        }
    }

    private fun renderFailures(state: KeepAliveUiState) {
        binding.textFailures.text = state.consecutiveFailures.toString()
    }

    private fun renderWaitingReason(state: KeepAliveUiState) {
        binding.textWaitingReason.isVisible = state.waitingReason.isNotEmpty()
        binding.textWaitingReason.text = state.waitingReason
    }

    /** DiagnosticsCard preview: the ArkUI inset panel holding the 3 newest lines. */
    private fun renderPreview(records: List<DiagnosticRecord>) {
        val container = binding.diagnosticsPreview
        container.removeAllViews()
        if (records.isEmpty()) {
            container.addView(
                layoutInflater.inflate(R.layout.item_diagnostic_empty, container, false)
            )
            return
        }
        // ArkUI: Column({ space: 4 }) { ForEach(lines) { Text(line).fontSize(12) } }
        records.forEachIndexed { index, record ->
            val line = layoutInflater.inflate(R.layout.item_diagnostic, container, false)
            (line as TextView).text = formatRecordLine(record)
            if (index > 0) {
                (line.layoutParams as LinearLayout.LayoutParams).topMargin = dp(4f)
            }
            container.addView(line)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}
