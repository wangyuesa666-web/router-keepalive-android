package com.fft.routerkeepalive.ui

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.view.isVisible
import androidx.core.widget.NestedScrollView
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.fft.routerkeepalive.R
import com.fft.routerkeepalive.databinding.FragmentSettingsBinding
import com.fft.routerkeepalive.databinding.ViewNumberFieldBinding
import com.fft.routerkeepalive.domain.RunScope
import com.fft.routerkeepalive.domain.ssidListToText
import kotlinx.coroutines.launch

/**
 * Settings tab, ported 1:1 from the ArkUI `SettingsContent` builder (2026-10-09 build):
 * BasicCard and AdvancedCard (collapsible).
 *
 * The ArkUI GuidanceCard that used to follow them is intentionally not ported: it exists to explain
 * the HarmonyOS `dataTransfer` continuous-task restrictions and the two-hour screen-off acceptance
 * procedure, neither of which applies on Android (the foreground service is the platform's own
 * mechanism). The advanced card's "network switch race" note was dropped for the same reason —
 * Android pins probes to the Wi-Fi network and disables redirects, so the limitation it describes
 * does not exist here.
 */
class SettingsFragment : TabFragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!
    private val viewModel: KeepAliveViewModel by activityViewModels()

    private val scopeLabels = listOf(
        R.string.scope_target to RunScope.TARGET_WIFI,
        R.string.scope_any to RunScope.ANY_WIFI,
        R.string.scope_manual to RunScope.MANUAL
    )

    private var advancedOpen = false

    /** NumberFieldRow bindings, bound once in onViewCreated. */
    override val tabScroll: NestedScrollView get() = binding.tabScroll
    override val headerPage = LiquidHeaderPage.SETTINGS
    override fun usesWideHeader(): Boolean =
        RouterUiPolicy.settingsUsesColumns(
            RouterUiPolicy.windowClass(
                resources.displayMetrics.widthPixels / resources.displayMetrics.density,
                resources.displayMetrics.heightPixels / resources.displayMetrics.density
            )
        )

    private lateinit var intervalRow: ViewNumberFieldBinding
    private lateinit var timeoutRow: ViewNumberFieldBinding
    private lateinit var retryRow: ViewNumberFieldBinding

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Switching theme rebuilds the Activity, which would otherwise collapse the advanced
        // section the user just opened. Restored here so the UI survives that recreation.
        advancedOpen = savedInstanceState?.getBoolean(KEY_ADVANCED_OPEN, false) ?: false
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putBoolean(KEY_ADVANCED_OPEN, advancedOpen)
    }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        // ViewBinding resolves an <include> that carries an id directly to the included
        // layout's binding, so these are used as-is rather than re-bound.
        intervalRow = binding.rowInterval
        timeoutRow = binding.rowTimeout
        retryRow = binding.rowRetry

        setupScopeSpinner()
        setupTextFields()
        setupNumberRows()
        setupAdvancedToggle()
        setupThemeGroup()
        setupSaveButtons()
        attachPressFeedback()
        binding.root.applyGlassSheen()
        applyAdvancedVisibility()

        viewLifecycleOwner.lifecycleScope.launch {
            viewLifecycleOwner.repeatOnLifecycle(Lifecycle.State.STARTED) {
                viewModel.state.collect { state -> render(state) }
            }
        }
    }

    /**
     * iOS-style press feedback on the tappable controls of this page. The advanced section is
     * collapsed on purpose until first opened, so its buttons are attached when it expands.
     */
    private fun attachPressFeedback() {
        binding.buttonSaveBasic.attachPressFeedback()
        binding.advancedHeader.attachPressFeedback()
        binding.buttonThemeSystem.attachPressFeedback()
        binding.buttonThemeLight.attachPressFeedback()
        binding.buttonThemeDark.attachPressFeedback()
        if (advancedOpen) {
            binding.buttonSaveAdvanced.attachPressFeedback()
            binding.buttonRestoreDefaults.attachPressFeedback()
        }
    }

    /**
     * The SSID and URL fields are free text, so they write to the ViewModel's edit buffer on every
     * keystroke. `render` only pushes the stored value back while the field is unfocused, which is
     * what stops a background state emission from wiping what the user is typing.
     */
    private fun setupTextFields() {
        binding.inputSsids.doAfterTextChanged { text ->
            viewModel.setSsidText(text?.toString().orEmpty())
        }
        binding.inputPrimaryUrl.doAfterTextChanged { text ->
            viewModel.setPrimaryUrl(text?.toString().orEmpty())
        }
        binding.inputFallbackUrl.doAfterTextChanged { text ->
            viewModel.setFallbackUrl(text?.toString().orEmpty())
        }
    }

    // ---- Setup -------------------------------------------------------------------------

    private fun setupScopeSpinner() {
        val labels = scopeLabels.map { getString(it.first) }
        binding.spinnerScope.adapter = ArrayAdapter(
            requireContext(),
            R.layout.simple_spinner_item,
            labels
        ).apply {
            setDropDownViewResource(R.layout.simple_spinner_dropdown)
        }
        binding.spinnerScope.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(
                parent: AdapterView<*>?, view: View?, position: Int, id: Long
            ) {
                viewModel.setScope(scopeLabels[position].second)
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
    }

    /**
     * Each NumberFieldRow keeps its own edit buffer, like the SSID and URL fields.
     *
     * Writing straight through to the draft on every keystroke (as an earlier revision did) breaks
     * in two visible ways:
     *   - clearing the field to retype leaves `toIntOrNull()` null, so the draft silently keeps the
     *     previous number while the box shows nothing, and the next keystroke is applied to a stale
     *     value;
     *   - an out-of-range number typed on the way to a valid one (say "3" on the way to "330") is
     *     committed immediately, so the draft ends up holding an illegal interval.
     * The value is therefore parsed and applied on focus loss and on save, and `render` only pushes
     * the stored value back while the field is unfocused.
     */
    private fun setupNumberRows() {
        intervalRow.label.setText(R.string.label_interval)
        intervalRow.hint.setText(R.string.label_interval_hint)
        timeoutRow.label.setText(R.string.label_timeout)
        timeoutRow.hint.setText(R.string.label_timeout_hint)
        retryRow.label.setText(R.string.label_retry_rounds)
        retryRow.hint.setText(R.string.label_retry_hint)

        bindNumberField(
            intervalRow.value, intervalRow.hint, 15..900,
            { it.draft.intervalSeconds }, viewModel::setInterval
        )
        bindNumberField(
            timeoutRow.value, timeoutRow.hint, 2..30,
            { it.draft.timeoutSeconds }, viewModel::setTimeout
        )
        bindNumberField(
            retryRow.value, retryRow.hint, 1..10,
            { it.draft.retryRounds }, viewModel::setRetryRounds
        )
    }

    /**
     * Applies [apply] with the parsed value when the field loses focus, or immediately on Enter.
     *
     * [hint] is recoloured red while the field holds an out-of-range or unparseable value, so the
     * user sees the problem at the field rather than only after pressing save. The ArkUI page has no
     * equivalent — it validates on save — but the text fields there are `InputType.Number` on a
     * device with a numeric keypad, and Android's soft keyboard happily produces leading zeros and
     * empty strings, so silent rejection is a worse failure mode here.
     */
    private fun bindNumberField(
        field: EditText,
        hint: TextView,
        range: IntRange,
        current: (KeepAliveUiState) -> Int,
        apply: (Int) -> Unit
    ) {
        val normalHint = hint.text.toString()
        field.setOnFocusChangeListener { _, hasFocus ->
            if (hasFocus) {
                showNumberRange(field, hint, range, normalHint)
            } else {
                hint.text = normalHint
                hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
                commitNumberField(field, range, current, apply)
            }
        }
        field.doAfterTextChanged { showNumberRange(field, hint, range, normalHint) }
    }

    private fun showNumberRange(
        field: EditText,
        hint: TextView,
        range: IntRange,
        normalHint: String
    ) {
        val text = field.text?.toString()?.trim().orEmpty()
        val parsed = text.toIntOrNull()
        val valid = parsed != null && parsed in range
        if (valid) {
            hint.text = normalHint
            hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.text_secondary))
        } else {
            hint.text = "需在 ${range.first}–${range.last} 之间"
            hint.setTextColor(ContextCompat.getColor(requireContext(), R.color.danger))
        }
    }

    /**
     * Parses the field and pushes it into the draft on focus loss.
     *
     * An empty or non-numeric box is restored to the stored value rather than committed, so a
     * half-typed number can never reach the draft. An out-of-range number is also refused here and
     * left in the box: the field's hint already shows the range in red, and `validateSettings`
     * rejects it again at save time, so accepting it into the draft would only let an illegal value
     * sit there and be re-shown to the user.
     */
    private fun commitNumberField(
        field: EditText,
        range: IntRange,
        current: (KeepAliveUiState) -> Int,
        apply: (Int) -> Unit
    ) {
        val text = field.text?.toString()?.trim().orEmpty()
        val parsed = text.toIntOrNull()
        if (parsed == null || parsed !in range) {
            val stored = current(viewModel.state.value)
            if (field.text?.toString() != stored.toString()) {
                field.setText(stored.toString())
            }
            return
        }
        apply(parsed)
    }

    private fun setupAdvancedToggle() {
        binding.advancedHeader.setOnClickListener {
            advancedOpen = !advancedOpen
            applyAdvancedVisibility()
        }
    }

    private fun applyAdvancedVisibility() {
        binding.advancedBody.isVisible = advancedOpen
        binding.textAdvancedToggle.setText(if (advancedOpen) R.string.collapse else R.string.expand)
        if (advancedOpen) {
            binding.buttonSaveAdvanced.attachPressFeedback()
            binding.buttonRestoreDefaults.attachPressFeedback()
        }
    }

    private companion object {
        const val KEY_ADVANCED_OPEN = "advanced_open"
    }

    private fun setupThemeGroup() {
        binding.toggleTheme.addOnButtonCheckedListener { _, checkedId, isChecked ->
            if (!isChecked) return@addOnButtonCheckedListener
            val mode = when (checkedId) {
                R.id.button_theme_light -> "light"
                R.id.button_theme_dark -> "dark"
                else -> "system"
            }
            viewModel.setThemeMode(mode)
        }
    }

    private fun setupSaveButtons() {
        binding.buttonSaveBasic.setOnClickListener {
            commitNumberFields()
            viewModel.saveBasic()
        }
        binding.buttonSaveAdvanced.setOnClickListener {
            commitNumberFields()
            viewModel.saveAdvanced()
        }
        // No Snackbar for a successful save or for restore-defaults: both duplicate what the UI
        // already shows (the fields reset to their defaults), and the Snackbar covered the dock,
        // which is how you leave this page. Only validation errors surface, and they appear inline
        // under the save button via renderMessage().
        binding.buttonRestoreDefaults.setOnClickListener { viewModel.restoreDefaults() }
    }

    /**
     * Pushes every number field's current text into the draft before a save.
     *
     * A number field only writes to the draft on focus loss (see [bindNumberField]), so pressing a
     * save button while the keyboard is still open would otherwise drop the value the user just
     * typed. Relying on `clearFocus()` to trigger the commit does not work: focus changes are
     * dispatched asynchronously, so the commit lands after `saveBasic()` has already read the draft.
     * The values are therefore parsed and applied directly here.
     */
    private fun commitNumberFields() {
        applyNumberField(intervalRow.value, 15..900, viewModel::setInterval)
        applyNumberField(timeoutRow.value, 2..30, viewModel::setTimeout)
        applyNumberField(retryRow.value, 1..10, viewModel::setRetryRounds)
    }

    /** Parses [field] and applies it when it holds a valid number; leaves the draft alone otherwise. */
    private fun applyNumberField(field: EditText, range: IntRange, apply: (Int) -> Unit) {
        val parsed = field.text?.toString()?.trim()?.toIntOrNull() ?: return
        if (parsed in range) apply(parsed)
    }

    // ---- Render ------------------------------------------------------------------------

    private fun render(state: KeepAliveUiState) {
        renderScope(state)
        renderSsids(state)
        renderNumbers(state)
        renderUrls(state)
        renderNotify(state)
        renderTheme(state)
        renderMessage(state)
    }

    /**
     * Validation errors only, shown as a short line under the save buttons. Empty for a successful
     * save, so nothing appears and the dock stays reachable.
     */
    private fun renderMessage(state: KeepAliveUiState) {
        val message = state.lastMessage
        if (message.isEmpty()) {
            if (binding.textSaveMessage.isVisible) binding.textSaveMessage.isVisible = false
            return
        }
        binding.textSaveMessage.isVisible = true
        binding.textSaveMessage.text = message
    }

    private fun renderScope(state: KeepAliveUiState) {
        val index = scopeLabels.indexOfFirst { it.second == state.draft.scope }.coerceAtLeast(0)
        if (binding.spinnerScope.selectedItemPosition != index) {
            binding.spinnerScope.setSelection(index)
        }
    }

    /** Only push the controller value while the field is not being edited. */
    private fun renderSsids(state: KeepAliveUiState) {
        if (!binding.inputSsids.hasFocus()) {
            val text = state.ssidText
            if (binding.inputSsids.text?.toString() != text) {
                binding.inputSsids.setText(text)
            }
        }
    }

    private fun renderNumbers(state: KeepAliveUiState) {
        setNumberIfNeeded(intervalRow.value, state.draft.intervalSeconds)
        setNumberIfNeeded(timeoutRow.value, state.draft.timeoutSeconds)
        setNumberIfNeeded(retryRow.value, state.draft.retryRounds)
    }

    private fun setNumberIfNeeded(field: EditText, value: Int) {
        if (!field.hasFocus() && field.text?.toString() != value.toString()) {
            field.setText(value.toString())
        }
    }

    private fun renderUrls(state: KeepAliveUiState) {
        if (!binding.inputPrimaryUrl.hasFocus() &&
            binding.inputPrimaryUrl.text?.toString() != state.draft.primaryUrl
        ) {
            binding.inputPrimaryUrl.setText(state.draft.primaryUrl)
        }
        if (!binding.inputFallbackUrl.hasFocus() &&
            binding.inputFallbackUrl.text?.toString() != state.draft.fallbackUrl
        ) {
            binding.inputFallbackUrl.setText(state.draft.fallbackUrl)
        }
    }

    private fun renderNotify(state: KeepAliveUiState) {
        if (binding.switchNotify.isChecked != state.draft.notifyOnFailure) {
            binding.switchNotify.isChecked = state.draft.notifyOnFailure
        }
    }

    private fun renderTheme(state: KeepAliveUiState) {
        val checkedId = when (viewModel.themeMode.value) {
            "light" -> R.id.button_theme_light
            "dark" -> R.id.button_theme_dark
            else -> R.id.button_theme_system
        }
        if (binding.toggleTheme.checkedButtonId != checkedId) {
            binding.toggleTheme.check(checkedId)
        }
    }

    override fun onDestroyView() {
        _binding = null
        super.onDestroyView()
    }
}

/** Runs [action] with the editable text after every change. */
private fun EditText.doAfterTextChanged(action: (CharSequence?) -> Unit) {
    addTextChangedListener(object : android.text.TextWatcher {
        override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
        override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        override fun afterTextChanged(s: android.text.Editable?) = action(s)
    })
}
