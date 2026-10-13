package com.tarun.kairos.scheduler

import java.time.*
import java.util.UUID
import kotlinx.serialization.Serializable

fun newId(): String = UUID.randomUUID().toString()

fun Long.localDate(zone: ZoneId): LocalDate = Instant.ofEpochMilli(this).atZone(zone).toLocalDate()

fun LocalDate.at(time: String, zone: ZoneId): Long =
    atTime(LocalTime.parse(time)).atZone(zone).toInstant().toEpochMilli()

fun minutesBetween(start: Long, end: Long): Int = ((end - start) / 60_000).toInt().coerceAtLeast(0)

@Serializable
enum class Category {
    ASSIGNMENT,
    EXAM_PREP,
    RESEARCH,
    CODING,
    SYSTEM_DESIGN,
    ALGORITHM,
    PERSONAL,
    OTHER,
}

@Serializable
enum class TaskStatus {
    ACTIVE,
    DONE,
    ARCHIVED,
}

@Serializable
enum class SubtaskStatus {
    TODO,
    IN_PROGRESS,
    DONE,
}

@Serializable
enum class BlockType {
    CLASS,
    MEAL,
    BATH,
    GYM,
    SLEEP,
    CALL,
    COMMUTE,
    OTHER,
}

@Serializable
enum class SessionKind {
    WORK,
    BREAK,
    EASY_WIN,
    PLAN_SUBTASKS,
    BUFFER,
}

@Serializable
enum class SessionState {
    PLANNED,
    DONE,
    PARTIAL,
    SKIPPED,
}

@Serializable
enum class ContextSource {
    KAIZEN,
    MANUAL,
    DEFAULT,
}

@Serializable
enum class EventTreatment {
    BUSY,
    IGNORE,
    CONVERTED_TO_TASK,
}

@Serializable
data class Task(
    val id: String = newId(),
    val title: String,
    val category: Category = Category.ASSIGNMENT,
    val deadline: Long,
    val difficulty: Int = 3,
    val estimatedHours: Double? = null,
    val isRecurring: Boolean = false,
    val recurrenceRule: String = "DAILY",
    val preferredTime: String = "18:00",
    val status: TaskStatus = TaskStatus.ACTIVE,
    val createdAt: Long = System.currentTimeMillis(),
    val notes: String = "",
) {
    init {
        require(title.isNotBlank())
        require(difficulty in 1..5)
        require(estimatedHours == null || estimatedHours.isFinite() && estimatedHours > 0)
        require(!isRecurring || recurrenceRule in setOf("DAILY", "WEEKLY"))
    }
}

@Serializable
data class Subtask(
    val id: String = newId(),
    val taskId: String,
    val title: String,
    val order: Int,
    val difficulty: Int = 3,
    val estimatedHours: Double? = null,
    val status: SubtaskStatus = SubtaskStatus.TODO,
) {
    init {
        require(title.isNotBlank())
        require(difficulty in 1..5)
        require(estimatedHours == null || estimatedHours.isFinite() && estimatedHours > 0)
    }
}

@Serializable
data class FixedBlock(
    val id: String = newId(),
    val title: String,
    val type: BlockType = BlockType.OTHER,
    val daysOfWeek: Set<Int> = (1..7).toSet(),
    val startTime: String,
    val endTime: String,
    val isFlexible: Boolean = false,
    val flexWindowStart: String? = null,
    val flexWindowEnd: String? = null,
    val validFrom: String? = null,
    val validUntil: String? = null,
    val color: Long = 0xFF8A9B8FL,
    val notes: String = "",
) {
    init {
        require(title.isNotBlank())
        require(daysOfWeek.isNotEmpty() && daysOfWeek.all { it in 1..7 })
        LocalTime.parse(startTime)
        LocalTime.parse(endTime)
        require(startTime != endTime)
        flexWindowStart?.let(LocalTime::parse)
        flexWindowEnd?.let(LocalTime::parse)
        validFrom?.let(LocalDate::parse)
        validUntil?.let(LocalDate::parse)
        require(validFrom == null || validUntil == null || validFrom <= validUntil)
    }

    fun applies(date: LocalDate) =
        date.dayOfWeek.value in daysOfWeek &&
            (validFrom == null || date >= LocalDate.parse(validFrom)) &&
            (validUntil == null || date <= LocalDate.parse(validUntil))
}

@Serializable
data class PlannedSession(
    val id: String = newId(),
    val date: String,
    val start: Long,
    val end: Long,
    val taskId: String? = null,
    val subtaskId: String? = null,
    val kind: SessionKind = SessionKind.WORK,
    val reason: String,
    val isPinned: Boolean = false,
    val state: SessionState = SessionState.PLANNED,
    val startedAt: Long? = null,
) {
    init {
        require(end > start)
        LocalDate.parse(date)
    }

    val minutes
        get() = minutesBetween(start, end)
}

@Serializable
data class WorkLog(
    val id: String = newId(),
    val sessionId: String,
    val taskId: String,
    val subtaskId: String? = null,
    val actualMinutes: Int,
    val completedAt: Long = System.currentTimeMillis(),
    val perceivedDifficulty: Int? = null,
    val learningApplied: Boolean = false,
) {
    init {
        require(actualMinutes >= 0)
        require(perceivedDifficulty == null || perceivedDifficulty in 1..5)
    }
}

@Serializable
data class DayContext(
    val date: String,
    val wakeTime: String? = null,
    val previousBedtime: String? = null,
    val sleepHours: Double? = null,
    val moodMorning: Int? = null,
    val moodNight: Int? = null,
    val energy: Int? = null,
    val source: ContextSource = ContextSource.DEFAULT,
    val lowEnergy: Boolean = false,
    val allowExtra: Boolean = false,
) {
    init {
        LocalDate.parse(date)
        wakeTime?.let(LocalTime::parse)
        previousBedtime?.let(LocalDateTime::parse)
        require(sleepHours == null || sleepHours.isFinite() && sleepHours in 0.0..24.0)
        require(listOf(moodMorning, moodNight, energy).all { it == null || it in 1..5 })
    }

    val lowDay
        get() = lowEnergy || (moodMorning ?: 3) <= 2 || (sleepHours ?: 8.0) < 6
}

@Serializable
data class CalendarEvent(
    val id: String,
    val eventId: Long,
    val calendarId: Long,
    val title: String,
    val start: Long,
    val end: Long,
    val isAllDay: Boolean = false,
    val lastSynced: Long = System.currentTimeMillis(),
    val treatAs: EventTreatment = EventTreatment.BUSY,
)

@Serializable
data class EstimateCorrection(
    val category: Category,
    val factor: Double = 1.0,
    val samples: Int = 0,
)

@Serializable
data class EnergyProfile(
    val hourOfDay: Int,
    val weekday: Int,
    val score: Double,
    val samples: Int = 0,
)

@Serializable
data class PlannerSettings(
    val wakeTime: String = "05:30",
    val bedtime: String = "23:00",
    val sessionMinutes: Int = 50,
    val breakMinutes: Int = 10,
    val longBreakMinutes: Int = 20,
    val capacityHours: Double = 6.0,
    val transitionMinutes: Int = 10,
    val deadlineBufferDays: Int = 1,
    val categoryMultipliers: Map<Category, Double> =
        mapOf(
            Category.EXAM_PREP to 1.3,
            Category.RESEARCH to 1.4,
            Category.CODING to 1.3,
            Category.PERSONAL to 0.8,
        ),
    val peaks: Set<Int> = setOf(6, 7, 8, 18, 19, 20, 21),
    val selectedCalendars: Set<Long> = emptySet(),
    val kaizenEnabled: Boolean = true,
    val includeAllCalendars: Boolean = true,
    val taskPrefix: String = "[T]",
    val wrapUpTime: String = "21:30",
    val quietDuringClasses: Boolean = true,
    val theme: String = "SYSTEM",
    val onboarded: Boolean = false,
    val backupFolder: String? = null,
) {
    init {
        LocalTime.parse(wakeTime)
        LocalTime.parse(bedtime)
        LocalTime.parse(wrapUpTime)
        require(sessionMinutes in 40..60)
        require(breakMinutes in 5..30)
        require(longBreakMinutes in 10..45)
        require(capacityHours.isFinite() && capacityHours in 0.5..16.0)
        require(transitionMinutes in 0..30)
        require(deadlineBufferDays in 0..7)
        require(categoryMultipliers.values.all { it.isFinite() && it in 0.1..5.0 })
        require(peaks.all { it in 0..23 })
    }
}

@Serializable
data class PlannerData(
    val tasks: List<Task> = emptyList(),
    val subtasks: List<Subtask> = emptyList(),
    val blocks: List<FixedBlock> = emptyList(),
    val sessions: List<PlannedSession> = emptyList(),
    val logs: List<WorkLog> = emptyList(),
    val contexts: List<DayContext> = emptyList(),
    val events: List<CalendarEvent> = emptyList(),
    val corrections: List<EstimateCorrection> = emptyList(),
    val energy: List<EnergyProfile> = emptyList(),
)

@Serializable
data class Backup(
    val schemaVersion: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val settings: PlannerSettings,
    val data: PlannerData,
)
