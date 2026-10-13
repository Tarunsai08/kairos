package com.tarun.kairos.scheduler

import java.time.*

/** Version-one reader ignores new columns and isolates bad optional values. */
object CompanionContract {
    const val authority = "com.tarun.kaizen.provider"
    const val permission = "com.tarun.kaizen.permission.READ_WELLBEING"
    const val action = "com.tarun.kaizen.action.CHECKIN_UPDATED"

    fun checkin(version: Int, columns: Map<String, String?>, date: LocalDate): DayContext? {
        if (version != 1 || columns["date"] != date.toString()) return null
        fun mood(name: String) = columns[name]?.toIntOrNull()?.takeIf { it in 1..5 }
        val wake =
            columns["wake_time"]?.let { runCatching { LocalTime.parse(it).toString() }.getOrNull() }
        val bedtime =
            columns["bedtime"]?.let {
                runCatching { LocalDateTime.parse(it).toString() }.getOrNull()
            }
        val sleep =
            columns["sleep_hours"]?.toDoubleOrNull()?.takeIf { it.isFinite() && it in 0.0..24.0 }
        return DayContext(
            date.toString(),
            wake,
            bedtime,
            sleep,
            mood("mood_morning"),
            mood("mood_night"),
            mood("energy"),
            ContextSource.KAIZEN,
        )
    }
}
