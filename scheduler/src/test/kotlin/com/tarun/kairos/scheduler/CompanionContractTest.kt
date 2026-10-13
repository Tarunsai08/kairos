package com.tarun.kairos.scheduler

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CompanionContractTest {
    private val date = LocalDate.of(2026, 10, 13)

    @Test
    fun readsVersionOneAndIgnoresNewColumns() {
        val c =
            CompanionContract.checkin(
                1,
                mapOf(
                    "date" to date.toString(),
                    "wake_time" to "07:10",
                    "sleep_hours" to "5.5",
                    "mood_morning" to "2",
                    "future" to "ignored",
                ),
                date,
            )!!
        assertTrue(c.lowDay)
        assertEquals("07:10", c.wakeTime)
        assertEquals(ContextSource.KAIZEN, c.source)
    }

    @Test
    fun unknownVersionsFallBack() {
        assertNull(CompanionContract.checkin(2, mapOf("date" to date.toString()), date))
    }

    @Test
    fun wrongDateIsRejected() {
        assertNull(CompanionContract.checkin(1, mapOf("date" to "2026-10-12"), date))
    }

    @Test
    fun nullsRemainNullAndInvalidFieldsAreIsolated() {
        val c =
            CompanionContract.checkin(
                1,
                mapOf(
                    "date" to date.toString(),
                    "wake_time" to "garbage",
                    "mood_morning" to "0",
                    "energy" to "999",
                    "sleep_hours" to "NaN",
                ),
                date,
            )!!
        assertNull(c.wakeTime)
        assertNull(c.moodMorning)
        assertNull(c.energy)
        assertNull(c.sleepHours)
    }
}
