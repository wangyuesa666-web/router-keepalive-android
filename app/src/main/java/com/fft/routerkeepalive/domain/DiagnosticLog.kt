package com.fft.routerkeepalive.domain

data class DiagnosticRecord(
    val timestamp: Long,
    val category: String,
    val durationMs: Double
)

const val MAX_RECORDS = 100

/**
 * Ring-buffer diagnostic log, ported 1:1 from the HarmonyOS `DiagnosticLog`.
 *
 * Only timestamp, category and duration are ever persisted. No URL, SSID, response body,
 * account, password or device identifier is stored.
 */
class DiagnosticLog {
    val records: MutableList<DiagnosticRecord> = mutableListOf()

    fun append(record: DiagnosticRecord) {
        records.add(
            DiagnosticRecord(
                timestamp = record.timestamp,
                category = sanitizeCategory(record.category),
                durationMs = record.durationMs
            )
        )
        if (records.size > MAX_RECORDS) {
            val drop = records.size - MAX_RECORDS
            repeat(drop) { records.removeAt(0) }
        }
    }

    fun clear() {
        records.clear()
    }

    fun serialize(): String =
        records.joinToString("\n") { record ->
            "${record.timestamp}|${sanitizeCategory(record.category)}|${record.durationMs}"
        }

    companion object {
        fun deserialize(value: String): DiagnosticLog {
            val log = DiagnosticLog()
            value.split('\n').forEach { line ->
                val fields = line.split('|')
                if (fields.size == 3) {
                    val timestamp = fields[0].toLongOrNull()
                    val durationMs = fields[2].toDoubleOrNull()
                    if (timestamp != null && durationMs != null) {
                        log.append(DiagnosticRecord(timestamp, fields[1], durationMs))
                    }
                }
            }
            return log
        }
    }
}

internal fun sanitizeCategory(category: String): String {
    val cleaned = category.replace(Regex("[^A-Z0-9_]"), "").take(48)
    return if (cleaned.isEmpty()) "UNKNOWN" else cleaned
}

private fun twoDigits(value: Int): String = if (value < 10) "0$value" else "$value"

/**
 * ArkTS `Math.round` rounds half up (41.6 -> 42, -5 -> 0). Kotlin's `Math.round` rounds half to
 * even, so implement the same half-up rule here to keep the two platforms identical.
 */
fun formatDuration(durationMs: Double): String {
    if (durationMs < 0) {
        return "0 ms"
    }
    return "${kotlin.math.floor(durationMs + 0.5).toLong()} ms"
}

/**
 * Display line for one record: `HH:mm:ss · CATEGORY · 123 ms`. Category is sanitized again so a
 * hand-built or restored record cannot leak a URL or markup into the UI.
 */
fun formatRecordLine(record: DiagnosticRecord): String {
    val at = java.util.Calendar.getInstance().apply { timeInMillis = record.timestamp }
    val hour = at.get(java.util.Calendar.HOUR_OF_DAY)
    val minute = at.get(java.util.Calendar.MINUTE)
    val second = at.get(java.util.Calendar.SECOND)
    val time = "${twoDigits(hour)}:${twoDigits(minute)}:${twoDigits(second)}"
    return "$time · ${sanitizeCategory(record.category)} · ${formatDuration(record.durationMs)}"
}

/** Newest-first window over the log, for the on-screen list. Does not mutate the input. */
fun recentRecords(records: List<DiagnosticRecord>, limit: Int): List<DiagnosticRecord> {
    if (limit <= 0 || records.isEmpty()) {
        return emptyList()
    }
    val start = maxOf(0, records.size - limit)
    return records.subList(start, records.size).reversed()
}
