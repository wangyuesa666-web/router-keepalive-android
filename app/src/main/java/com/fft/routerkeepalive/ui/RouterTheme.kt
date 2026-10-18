package com.fft.routerkeepalive.ui

/** Ported 1:1 from the HarmonyOS `RouterPalette` / `RouterTheme`. */
data class RouterPalette(
    val pageTop: String,
    val pageBottom: String,
    val card: String,
    val inset: String,
    val border: String,
    val primaryText: String,
    val secondaryText: String,
    val accent: String,
    val actionSurface: String,
    val dockLight: String,
    val controlSurface: String,
    val controlBorder: String,
    val controlFocus: String,
    val onAccent: String,
    val warning: String,
    val danger: String,
    val normalStatus: String
)

private val LIGHT_PALETTE = RouterPalette(
    pageTop = "#F9FBF9",
    pageBottom = "#E7F2ED",
    card = "#EAF9FCFA",
    inset = "#EAF3F9F5",
    border = "#BFD2E5DA",
    primaryText = "#173A30",
    secondaryText = "#4C685E",
    accent = "#176149",
    actionSurface = "#E6176149",
    dockLight = "#176149",
    controlSurface = "#F7FFFFFF",
    controlBorder = "#8FAFC9BA",
    controlFocus = "#176149",
    onAccent = "#FFFFFFFF",
    warning = "#94621C",
    danger = "#A64955",
    normalStatus = "#176149"
)

private val DARK_PALETTE = RouterPalette(
    pageTop = "#17131D",
    pageBottom = "#241B2D",
    card = "#D934293F",
    inset = "#D9292231",
    border = "#6251445D",
    primaryText = "#F4EFF7",
    secondaryText = "#C4B9CD",
    accent = "#BDA5F2",
    actionSurface = "#E6BDA5F2",
    dockLight = "#BDA5F2",
    controlSurface = "#E61E1827",
    controlBorder = "#8A756782",
    controlFocus = "#BDA5F2",
    onAccent = "#17131D",
    warning = "#E4B16D",
    danger = "#D88E97",
    normalStatus = "#BDA5F2"
)

object RouterTheme {
    fun palette(dark: Boolean): RouterPalette = if (dark) DARK_PALETTE else LIGHT_PALETTE

    /**
     * Parses `#RRGGBB`, `#AARRGGBB`, `#RGB` and `#ARGB` into an Android colour int.
     * Returns an opaque colour when no alpha is supplied, matching ArkUI's behaviour.
     */
    fun parseColor(value: String): Int {
        val raw = if (value.startsWith("#")) value.substring(1) else value
        val expanded = when (raw.length) {
            6 -> "FF$raw"
            8 -> raw
            3 -> "FF" + raw[0] + raw[0] + raw[1] + raw[1] + raw[2] + raw[2]
            4 -> raw[3].toString() + raw[3] + raw[0] + raw[0] + raw[1] + raw[1] + raw[2] + raw[2]
            else -> "FF$raw"
        }
        return expanded.toLong(16).toInt()
    }
}
