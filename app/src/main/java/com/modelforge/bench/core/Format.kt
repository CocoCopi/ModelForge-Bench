package com.modelforge.bench.core

import java.util.Locale

object Format {
    fun bytes(value: Long): String {
        if (value < 0) return "—"
        if (value < 1_000) return "$value B"
        val units = arrayOf("KB", "MB", "GB", "TB")
        var v = value.toDouble() / 1_000.0
        var i = 0
        while (v >= 1000.0 && i < units.lastIndex) {
            v /= 1000.0
            i++
        }
        return if (v >= 100.0) String.format(Locale.US, "%.0f %s", v, units[i])
        else String.format(Locale.US, "%.1f %s", v, units[i])
    }

    fun params(count: Long): String = when {
        count >= 1_000_000_000 -> String.format(Locale.US, "%.2fB", count / 1_000_000_000.0)
        count >= 1_000_000 -> String.format(Locale.US, "%.2fM", count / 1_000_000.0)
        count >= 1_000 -> String.format(Locale.US, "%.1fK", count / 1_000.0)
        else -> count.toString()
    }

    fun ms(value: Double): String = when {
        !value.isFinite() -> "—"
        value >= 1000.0 -> String.format(Locale.US, "%.1f s", value / 1000.0)
        value >= 100.0 -> String.format(Locale.US, "%.0f ms", value)
        value >= 10.0 -> String.format(Locale.US, "%.1f ms", value)
        else -> String.format(Locale.US, "%.2f ms", value)
    }

    fun tps(value: Double): String =
        if (!value.isFinite() || value <= 0.0) "—" else String.format(Locale.US, "%.1f tok/s", value)

    fun percent(fraction: Double, digits: Int = 1): String =
        if (!fraction.isFinite()) "—" else String.format(Locale.US, "%.${digits}f%%", fraction * 100.0)

    fun money(value: Double): String = when {
        !value.isFinite() -> "—"
        value >= 100.0 -> String.format(Locale.US, "$%.0f", value)
        value >= 1.0 -> String.format(Locale.US, "$%.2f", value)
        value >= 0.01 -> String.format(Locale.US, "$%.3f", value)
        else -> String.format(Locale.US, "$%.4f", value)
    }

    fun date(epochMs: Long): String {
        if (epochMs <= 0) return "—"
        val fmt = java.text.SimpleDateFormat("d MMM yyyy, HH:mm", Locale.getDefault())
        return fmt.format(java.util.Date(epochMs))
    }

    fun duration(ms: Long): String {
        val s = ms / 1000
        return when {
            s < 60 -> "${s}s"
            s < 3600 -> "${s / 60}m ${s % 60}s"
            else -> "${s / 3600}h ${(s % 3600) / 60}m"
        }
    }
}
