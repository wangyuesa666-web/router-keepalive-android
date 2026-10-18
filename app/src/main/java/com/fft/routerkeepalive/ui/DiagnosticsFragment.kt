package com.fft.routerkeepalive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.isVisible
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.fft.routerkeepalive.R
import com.fft.routerkeepalive.databinding.FragmentDiagnosticsBinding
import com.fft.routerkeepalive.domain.DiagnosticRecord
import com.fft.routerkeepalive.domain.formatRecordLine
import kotlinx.coroutines.launch

/**
 * Diagnostics detail, ported 1:1 from the ArkUI `DiagnosticsDetail` NavDestination: the full
 * 100-record window, newest first, plus copy and clear.
 */
class DiagnosticsFragment : Fragment() {

    private var _binding: FragmentDiagnosticsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: KeepAliveViewModel by activityViewModels()

    private val adapter = DiagnosticAdapter()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentDiagnosticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        applyPageInsets()
        binding.buttonBack.setOnClickListener {
            findNavController().navigateUp()
        }
        binding.recyclerDiagnostics.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerDiagnostics.adapter = adapter

        // The glass light has to be painted after inflation: a MaterialCardView ignores
        // `android:background`, so the layered drawable route cannot reach it.
        binding.root.applyGlassSheen()

        binding.buttonBack.attachPressFeedback()
        binding.buttonCopy.attachPressFeedback()
        binding.buttonClear.attachPressFeedback()

        // No Snackbar for copy/clear: the system clipboard overlay already confirms the copy, and a
        // clear is visible instantly because the list is replaced by the empty state. A Snackbar
        // would also float over the two buttons it is confirming.
        binding.buttonCopy.setOnClickListener { viewModel.copyDiagnostics() }
        binding.buttonClear.setOnClickListener { viewModel.clearDiagnostics() }

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state ->
                    val records = state.allDiagnostics
                    adapter.submitList(records)
                    binding.textEmpty.isVisible = records.isEmpty()
                    binding.recyclerDiagnostics.isVisible = records.isNotEmpty()
                }
            }
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }

    /**
     * ArkUI: `.padding({ top: contentInset(safeTop, 16), left/right: contentInset(safeX, padding) })`.
     *
     * The insets are read from the Activity's content root, because that is where MainActivity's
     * own listener consumes the window insets dispatch; a listener on this Fragment's root would
     * never fire. The padding is reapplied on every inset change so a rotation or a cutout change
     * is picked up.
     */
    private fun applyPageInsets() {
        val contentRoot = requireActivity().findViewById<View>(android.R.id.content)
        ViewCompat.setOnApplyWindowInsetsListener(contentRoot) { _, insets ->
            val bars = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout()
            )
            val density = resources.displayMetrics.density
            val windowClass = RouterUiPolicy.windowClass(
                viewportWidthDp(), viewportHeightDp()
            )
            val horizontal = RouterUiPolicy.horizontalPadding(windowClass).toFloat()
            _binding?.detailRoot?.setPadding(
                dp(RouterUiPolicy.contentInset(bars.left / density, horizontal)),
                dp(RouterUiPolicy.contentInset(bars.top / density, 16f)),
                dp(RouterUiPolicy.contentInset(bars.right / density, horizontal)),
                dp(bars.bottom / density + 24f)
            )
            insets
        }
        ViewCompat.requestApplyInsets(contentRoot)
    }

    private fun viewportWidthDp(): Float =
        resources.displayMetrics.widthPixels / resources.displayMetrics.density

    private fun viewportHeightDp(): Float =
        resources.displayMetrics.heightPixels / resources.displayMetrics.density

    private fun dp(value: Float): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()

    /** Row view holder: one left-aligned line, exactly as the ArkUI ForEach renders it. */
    private class RowHolder(view: View) : RecyclerView.ViewHolder(view) {
        private val line: android.widget.TextView = view.findViewById(R.id.text_line)

        fun bind(record: DiagnosticRecord) {
            line.text = formatRecordLine(record)
        }
    }

    private class DiagnosticAdapter :
        ListAdapter<DiagnosticRecord, RowHolder>(DIFF) {

        override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): RowHolder {
            val view = LayoutInflater.from(parent.context)
                .inflate(R.layout.item_diagnostic, parent, false)
            return RowHolder(view)
        }

        override fun onBindViewHolder(holder: RowHolder, position: Int) {
            holder.bind(getItem(position))
        }

        private companion object {
            val DIFF = object : DiffUtil.ItemCallback<DiagnosticRecord>() {
                override fun areItemsTheSame(
                    oldItem: DiagnosticRecord,
                    newItem: DiagnosticRecord
                ) = oldItem.timestamp == newItem.timestamp

                override fun areContentsTheSame(
                    oldItem: DiagnosticRecord,
                    newItem: DiagnosticRecord
                ) = oldItem == newItem
            }
        }
    }
}
