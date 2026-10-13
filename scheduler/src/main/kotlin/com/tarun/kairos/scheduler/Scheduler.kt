package com.tarun.kairos.scheduler

import java.time.*
import kotlin.math.*

/** Half-open intervals prevent back-to-back commitments from overlapping. */
data class Interval(val start: Long, val end: Long) {
    init {
        require(end >= start)
    }

    val minutes
        get() = minutesBetween(start, end)

    fun overlaps(other: Interval) = start < other.end && other.start < end
}

data class PlanResult(
    val sessions: List<PlannedSession>,
    val atRisk: Set<String>,
    val remainingMinutes: Map<String, Int>,
    val dailyCapacity: Map<String, Int>,
)

object TimeWindows {
    fun subtract(window: Interval, busy: List<Interval>): List<Interval> {
        val clipped =
            busy
                .mapNotNull {
                    val s = max(window.start, it.start)
                    val e = min(window.end, it.end)
                    if (e > s) Interval(s, e) else null
                }
                .sortedBy { it.start }
        val result = mutableListOf<Interval>()
        var cursor = window.start
        clipped.forEach {
            if (it.start > cursor) result += Interval(cursor, it.start)
            cursor = max(cursor, it.end)
        }
        if (cursor < window.end) result += Interval(cursor, window.end)
        return result
    }

    fun fixed(
        date: LocalDate,
        blocks: List<FixedBlock>,
        zone: ZoneId,
    ): List<Pair<FixedBlock, Interval>> =
        listOf(date.minusDays(1), date).flatMap { d ->
            blocks
                .filter { it.applies(d) }
                .map { b ->
                    val s = d.at(b.startTime, zone)
                    val endDate =
                        if (LocalTime.parse(b.endTime) <= LocalTime.parse(b.startTime))
                            d.plusDays(1)
                        else d
                    b to Interval(s, endDate.at(b.endTime, zone))
                }
        }

    fun awake(
        date: LocalDate,
        context: DayContext?,
        settings: PlannerSettings,
        blocks: List<FixedBlock>,
        zone: ZoneId,
    ): Interval {
        val wake = context?.wakeTime ?: settings.wakeTime
        val bedtime =
            blocks.firstOrNull { it.type == BlockType.SLEEP && it.applies(date) }?.startTime
                ?: settings.bedtime
        val endDate =
            if (LocalTime.parse(bedtime) <= LocalTime.parse(wake)) date.plusDays(1) else date
        return Interval(date.at(wake, zone), endDate.at(bedtime, zone))
    }
}

object Energy {
    fun score(
        start: Long,
        zone: ZoneId,
        context: DayContext?,
        settings: PlannerSettings,
        profiles: List<EnergyProfile>,
    ): Double {
        val t = Instant.ofEpochMilli(start).atZone(zone)
        val h = t.hour
        val default =
            when {
                h in settings.peaks -> 0.9
                h in 13..16 -> 0.35
                else -> 0.6
            }
        val learned = profiles.find { it.hourOfDay == h && it.weekday == t.dayOfWeek.value }
        val weight = ((learned?.samples ?: 0) / 30.0).coerceIn(0.0, 1.0)
        val today = ((context?.energy ?: context?.moodMorning ?: 3) - 3) * 0.08
        return ((1 - weight) * default + weight * (learned?.score ?: default) + today).coerceIn(
            0.0,
            1.0,
        )
    }

    fun update(
        previous: EnergyProfile?,
        hour: Int,
        weekday: Int,
        state: SessionState,
    ): EnergyProfile {
        val observation =
            when (state) {
                SessionState.DONE -> 1.0
                SessionState.PARTIAL -> 0.5
                else -> 0.1
            }
        return EnergyProfile(
            hour,
            weekday,
            (previous?.score ?: 0.6) * 0.7 + observation * 0.3,
            (previous?.samples ?: 0) + 1,
        )
    }
}

/** Deterministic greedy planner. No clock, Android API, IO, or random ordering inside plan(). */
class Scheduler {
    private data class UnitOfWork(
        val task: Task,
        val subtask: Subtask?,
        val occurrence: LocalDate?,
        var remaining: Int,
        val total: Int,
        var lastEnd: Long = Long.MIN_VALUE,
    )

    private data class Slot(
        var start: Long,
        val end: Long,
        val date: LocalDate,
        val energy: Double,
    ) {
        val minutes
            get() = minutesBetween(start, end)
    }

    fun plan(
        data: PlannerData,
        settings: PlannerSettings,
        now: Long,
        zone: ZoneId = ZoneId.systemDefault(),
    ): PlanResult {
        val today = now.localDate(zone)
        val horizonEnd = today.plusDays(7).atStartOfDay(zone).toInstant().toEpochMilli()
        val preserved =
            data.sessions.filter {
                it.isPinned || it.state != SessionState.PLANNED || it.start <= now
            }
        val result = preserved.toMutableList()
        val capacities = linkedMapOf<String, Int>()
        val availableByDay = linkedMapOf<LocalDate, List<Interval>>()
        (0L..6L).forEach { offset ->
            val date = today.plusDays(offset)
            val context = data.contexts.find { it.date == date.toString() }
            val window = TimeWindows.awake(date, context, settings, data.blocks, zone)
            val start = max(window.start, if (offset == 0L) now else window.start)
            val awake = Interval(min(start, window.end), window.end)
            val buffer = settings.transitionMinutes * 60_000L
            val busy =
                TimeWindows.fixed(date, data.blocks, zone).map {
                    Interval(it.second.start - buffer, it.second.end + buffer)
                } +
                    data.events
                        .filter { !it.isAllDay && it.treatAs == EventTreatment.BUSY }
                        .map { Interval(it.start - buffer, it.end + buffer) } +
                    preserved
                        .filter {
                            it.isPinned ||
                                it.state == SessionState.DONE ||
                                it.state == SessionState.PLANNED && it.end > now
                        }
                        .map { Interval(it.start, it.end) }
            availableByDay[date] = TimeWindows.subtract(awake, busy)
            var factor =
                when {
                    context?.lowDay == true -> 0.6
                    (context?.moodMorning ?: 3) >= 4 &&
                        (context?.sleepHours ?: 8.0) >= 7 &&
                        context?.allowExtra == true -> 1.1
                    else -> 1.0
                }
            if (offset > 0) {
                val prev = data.contexts.find { it.date == date.minusDays(1).toString() }
                val previousWork =
                    data.sessions.filter {
                        it.date == date.minusDays(1).toString() && it.kind in workKinds
                    }
                if (prev?.moodNight != null && previousWork.size >= 3) {
                    val completion =
                        previousWork.count { it.state == SessionState.DONE }.toDouble() /
                            previousWork.size
                    factor *= if (prev.moodNight <= 2 || completion < 0.5) 0.85 else 1.0
                }
            }
            val loggedIds = data.logs.map { it.sessionId }.toSet()
            val actualToday =
                data.logs
                    .filter { log ->
                        val session = data.sessions.find { it.id == log.sessionId }
                        session?.date == date.toString() ||
                            session == null && log.completedAt.localDate(zone) == date
                    }
                    .sumOf { it.actualMinutes }
            val reserved =
                preserved
                    .filter {
                        it.date == date.toString() &&
                            it.kind in workKinds &&
                            it.id !in loggedIds &&
                            (it.isPinned ||
                                it.state == SessionState.DONE ||
                                it.state == SessionState.PLANNED && it.end > now)
                    }
                    .sumOf { it.minutes }
            capacities[date.toString()] =
                max(0, (settings.capacityHours * 60 * factor).roundToInt() - reserved - actualToday)
        }
        val units = mutableListOf<UnitOfWork>()
        data.tasks
            .filter { it.status == TaskStatus.ACTIVE }
            .sortedBy { it.id }
            .forEach { task ->
                val subs = data.subtasks.filter { it.taskId == task.id }.sortedBy { it.order }
                val occurrences =
                    if (task.isRecurring)
                        (0L..6L)
                            .map { today.plusDays(it) }
                            .filter { d ->
                                d >= task.createdAt.localDate(zone) &&
                                    (task.recurrenceRule == "DAILY" ||
                                        d.dayOfWeek == task.createdAt.localDate(zone).dayOfWeek)
                            }
                            .map { it }
                    else listOf<LocalDate?>(null)
                occurrences.forEach { occurrence ->
                    val pieces =
                        if (subs.isEmpty()) listOf<Subtask?>(null)
                        else subs.filter { it.status != SubtaskStatus.DONE }
                    pieces.forEach { sub ->
                        val total =
                            (Estimation.workHours(task, sub, data, settings) * 60).roundToInt()
                        val creditedLogs =
                            data.logs.filter {
                                it.taskId == task.id &&
                                    it.subtaskId == sub?.id &&
                                    (occurrence == null ||
                                        it.completedAt.localDate(zone) == occurrence)
                            }
                        val loggedIds = creditedLogs.map { it.sessionId }.toSet()
                        val reserved = preserved.filter {
                            it.taskId == task.id &&
                                it.subtaskId == sub?.id &&
                                it.kind in workKinds &&
                                it.id !in loggedIds &&
                                (occurrence == null || it.date == occurrence.toString()) &&
                                (it.state == SessionState.DONE ||
                                    it.state == SessionState.PLANNED &&
                                        (it.isPinned || it.end > now))
                        }
                        units +=
                            UnitOfWork(
                                task,
                                sub,
                                occurrence,
                                max(
                                    0,
                                    total -
                                        creditedLogs.sumOf { it.actualMinutes } -
                                        reserved.sumOf { it.minutes },
                                ),
                                total,
                                reserved.maxOfOrNull { it.end } ?: Long.MIN_VALUE,
                            )
                    }
                }
            }
        val risk = mutableSetOf<String>()
        preserved
            .filter { it.isPinned && it.state == SessionState.PLANNED }
            .forEach { pin ->
                data.tasks
                    .find {
                        it.id == pin.taskId &&
                            it.status == TaskStatus.ACTIVE &&
                            !it.isRecurring &&
                            pin.end > it.deadline
                    }
                    ?.let { risk += it.id }
            }
        availableByDay.forEach { (date, gaps) ->
            val context = data.contexts.find { it.date == date.toString() }
            val low = context?.lowDay == true
            var capacity = capacities.getValue(date.toString())
            val slots = mutableListOf<Slot>()
            val breaks = mutableListOf<PlannedSession>()
            gaps.forEach { gap ->
                var cursor = gap.start
                var n = 0
                while (cursor + 25 * 60_000L <= gap.end) {
                    val length = if (low) 40 else settings.sessionMinutes
                    val end = min(cursor + length * 60_000L, gap.end)
                    slots +=
                        Slot(
                            cursor,
                            end,
                            date,
                            Energy.score(cursor, zone, context, settings, data.energy),
                        )
                    n++
                    val rest =
                        if (n % 3 == 0) max(settings.longBreakMinutes, if (low) 25 else 0)
                        else max(settings.breakMinutes, if (low) 15 else 0)
                    val breakEnd = min(end + rest * 60_000L, gap.end)
                    if (breakEnd > end)
                        breaks +=
                            session(
                                date,
                                end,
                                breakEnd,
                                null,
                                SessionKind.BREAK,
                                if (n % 3 == 0) "A longer pause to reset"
                                else "Step away for a moment",
                            )
                    cursor = breakEnd
                }
            }
            val used = mutableListOf<PlannedSession>()
            fun deadline(u: UnitOfWork) =
                u.occurrence?.let {
                    TimeWindows.awake(
                            it,
                            data.contexts.find { c -> c.date == it.toString() },
                            settings,
                            data.blocks,
                            zone,
                        )
                        .end
                } ?: u.task.deadline
            fun eligible(u: UnitOfWork, slot: Slot): Boolean {
                if (
                    u.remaining <= 0 ||
                        u.occurrence != null && u.occurrence != date ||
                        slot.start >= deadline(u) ||
                        slot.minutes < 25
                )
                    return false
                val previous = units.filter {
                    it.task.id == u.task.id &&
                        it.occurrence == u.occurrence &&
                        it.subtask != null &&
                        u.subtask != null &&
                        it.subtask.order < u.subtask.order
                }
                if (
                    previous.any { it.remaining > 0 } ||
                        slot.start < (previous.maxOfOrNull { it.lastEnd } ?: Long.MIN_VALUE)
                )
                    return false
                if (
                    low && (u.subtask?.difficulty ?: u.task.difficulty) >= 4 && u.occurrence == null
                ) {
                    val later =
                        availableByDay
                            .filter { it.key > date }
                            .entries
                            .sumOf { (d, free) ->
                                min(
                                    capacities.getValue(d.toString()),
                                    free
                                        .filter { it.start < u.task.deadline }
                                        .sumOf {
                                            minutesBetween(it.start, min(it.end, u.task.deadline))
                                        },
                                )
                            }
                    val competing =
                        units
                            .filter {
                                it !== u &&
                                    it.occurrence == null &&
                                    it.task.deadline <= u.task.deadline
                            }
                            .sumOf { it.remaining }
                    if (
                        later >= u.remaining + competing &&
                            u.task.deadline >
                                date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    )
                        return false
                }
                return true
            }
            fun score(u: UnitOfWork): Double {
                val buffer =
                    max(
                        settings.deadlineBufferDays * 86_400_000L,
                        ((u.task.deadline - u.task.createdAt).coerceAtLeast(0) * 0.15).toLong(),
                    )
                val target =
                    if (u.occurrence != null) deadline(u)
                    else max(now, min(u.task.deadline, u.task.deadline - buffer))
                val free =
                    availableByDay.values
                        .flatten()
                        .sumOf { minutesBetween(max(now, it.start), min(target, it.end)) }
                        .coerceAtLeast(6)
                val days = max(0.0, (deadline(u) - now) / 86_400_000.0)
                return 0.5 * u.remaining / free +
                    0.3 / (days + 1) +
                    0.2 * (u.subtask?.difficulty ?: u.task.difficulty) / 5.0
            }
            fun place(u: UnitOfWork, slot: Slot, easy: Boolean = false) {
                if (capacity <= 0) return
                val hasSteps = data.subtasks.any { it.taskId == u.task.id }
                val plannedBefore =
                    result.any { it.taskId == u.task.id && it.kind == SessionKind.PLAN_SUBTASKS } ||
                        used.any { it.taskId == u.task.id && it.kind == SessionKind.PLAN_SUBTASKS }
                if (
                    !easy &&
                        !hasSteps &&
                        !plannedBefore &&
                        !u.task.isRecurring &&
                        data.logs.none { it.taskId == u.task.id } &&
                        slot.minutes >= 40 &&
                        capacity >= 40
                ) {
                    val planningSlot =
                        slots
                            .filter { eligible(u, it) && it.minutes >= 40 }
                            .minByOrNull { it.start } ?: slot
                    val p =
                        session(
                            date,
                            planningSlot.start,
                            planningSlot.start + 15 * 60_000L,
                            u,
                            SessionKind.PLAN_SUBTASKS,
                            "Break this into steps? A little clarity helps.",
                        )
                    used += p
                    planningSlot.start = p.end
                    capacity -= 15
                }
                val minutes =
                    minOf(
                        u.remaining,
                        capacity,
                        minutesBetween(slot.start, min(slot.end, deadline(u))),
                        if (easy) 30
                        else if (low && (u.subtask?.difficulty ?: u.task.difficulty) >= 4) 40
                        else settings.sessionMinutes,
                    )
                if (minutes <= 0) return
                val reason =
                    when {
                        easy -> "Easy win to get started"
                        low -> "Lighter plan today · a manageable step"
                        deadline(u) - now < 86_400_000L -> "Due soon · one focused step"
                        slot.energy >= 0.8 && (u.subtask?.difficulty ?: u.task.difficulty) >= 4 ->
                            "Peak focus slot · hardest step of ${u.task.title}"
                        u.task.isRecurring ->
                            "A little progress, every ${if(u.task.recurrenceRule=="DAILY")"day" else "week"}"
                        else -> "Steady progress · finish ahead of your deadline"
                    }
                val s =
                    session(
                        date,
                        slot.start,
                        slot.start + minutes * 60_000L,
                        u,
                        if (easy) SessionKind.EASY_WIN else SessionKind.WORK,
                        reason,
                    )
                used += s
                slot.start = s.end
                u.remaining -= minutes
                u.lastEnd = max(u.lastEnd, s.end)
                capacity -= minutes
            }
            if (low) {
                repeat(2) {
                    val slot = slots.filter { it.minutes >= 25 }.minByOrNull { it.start }
                    if (slot != null) {
                        val u =
                            units
                                .filter {
                                    eligible(it, slot) &&
                                        (it.subtask?.difficulty ?: it.task.difficulty) <= 2
                                }
                                .maxByOrNull { score(it) }
                        if (u != null) place(u, slot, true)
                    }
                }
            }
            // Work units are selected by priority; hard work chooses high-energy slots.
            // Subtask eligibility carries the preceding step's end across days.
            var previousTask: String? = null
            var run = 0
            while (capacity > 0) {
                val candidates =
                    units
                        .filter { u -> slots.any { eligible(u, it) } }
                        .sortedWith(
                            compareByDescending<UnitOfWork> { score(it) }.thenBy { it.task.id }
                        )
                if (candidates.isEmpty()) break
                val u =
                    if (run >= 3)
                        candidates.firstOrNull { it.task.id != previousTask } ?: candidates.first()
                    else candidates.first()
                val candidatesSlots = slots.filter { eligible(u, it) }
                val diff = u.subtask?.difficulty ?: u.task.difficulty
                val slot =
                    if (u.task.isRecurring) {
                        val preference = date.at(u.task.preferredTime, zone)
                        candidatesSlots.minWithOrNull(
                            compareBy<Slot> { abs(it.start - preference) }.thenBy { it.start }
                        )!!
                    } else if (diff >= 4)
                        candidatesSlots.maxWithOrNull(
                            compareBy<Slot> { it.energy }.thenBy { -it.start }
                        )!!
                    else if (diff <= 2)
                        candidatesSlots.minWithOrNull(
                            compareBy<Slot> { it.energy }.thenBy { it.start }
                        )!!
                    else candidatesSlots.minBy { it.start }
                val old = capacity
                place(u, slot)
                if (capacity == old) break
                if (previousTask == u.task.id) run++
                else {
                    previousTask = u.task.id
                    run = 1
                }
            }
            result += used
            // Only retain a break following an actual focus/planning session.
            result += breaks.filter { b ->
                used.any { it.end <= b.start && it.end > b.start - 60 * 60_000L } &&
                    used.any { it.start >= b.end }
            }
            units
                .filter {
                    it.remaining > 0 &&
                        deadline(it) <=
                            date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                .forEach { risk += it.task.id }
        }
        units
            .filter {
                it.remaining > 0 && (it.occurrence != null || it.task.deadline <= horizonEnd)
            }
            .forEach { risk += it.task.id }
        units
            .filter { it.remaining > 0 && it.occurrence == null && it.task.deadline > horizonEnd }
            .forEach { u ->
                val extraDays = (u.task.deadline - horizonEnd) / 86_400_000.0
                if (u.remaining > extraDays * settings.capacityHours * 60) risk += u.task.id
            }
        val usedIds = mutableSetOf<String>()
        val uniqueSessions = result.map { session ->
            var id = session.id
            var attempt = 0
            while (!usedIds.add(id)) {
                attempt++
                id = "${session.id}:retry$attempt"
            }
            if (id == session.id) session else session.copy(id = id)
        }
        return PlanResult(
            uniqueSessions.sortedBy { it.start },
            risk,
            units.groupBy { it.task.id }.mapValues { it.value.sumOf { u -> u.remaining } },
            capacities,
        )
    }

    private fun session(
        date: LocalDate,
        start: Long,
        end: Long,
        u: UnitOfWork?,
        kind: SessionKind,
        reason: String,
    ): PlannedSession {
        // Stable identity means a repeated plan with the same input has identical output.
        val id = "${date}:${start}:${u?.task?.id ?: kind.name}:${u?.subtask?.id ?: ""}"
        return PlannedSession(
            id,
            date.toString(),
            start,
            end,
            u?.task?.id,
            u?.subtask?.id,
            kind,
            reason,
        )
    }

    companion object {
        val workKinds = setOf(SessionKind.WORK, SessionKind.EASY_WIN)
    }
}
