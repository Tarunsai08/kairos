package com.tarun.kairos.scheduler

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SchedulerTest {
    private val zone = ZoneId.of("Asia/Kolkata")
    private val date = LocalDate.of(2026, 10, 13)
    private val now = date.at("05:30", zone)
    private val settings = PlannerSettings()

    private fun task(
        id: String = "task",
        hours: Double? = 2.0,
        difficulty: Int = 3,
        due: Long = date.plusDays(2).at("23:00", zone),
        recurring: Boolean = false,
    ) = Task(id, "Study", Category.ASSIGNMENT, due, difficulty, hours, recurring, createdAt = now)

    private fun plan(data: PlannerData, at: Long = now) = Scheduler().plan(data, settings, at, zone)

    @Test
    fun emptyDayHasNoInventedWork() {
        assertTrue(plan(PlannerData()).sessions.isEmpty())
    }

    @Test
    fun fullyBookedDayDoesNotOverlap() {
        val b = FixedBlock(title = "All day", startTime = "05:30", endTime = "23:00")
        val p =
            plan(
                PlannerData(tasks = listOf(task(due = date.at("22:00", zone))), blocks = listOf(b))
            )
        assertTrue(p.sessions.isEmpty())
        assertTrue("task" in p.atRisk)
    }

    @Test
    fun deadlineTodayIsScheduledBeforeDeadline() {
        val t = task(due = date.at("12:00", zone))
        val p = plan(PlannerData(tasks = listOf(t)))
        assertTrue(p.sessions.any { it.taskId == t.id })
        assertTrue(p.sessions.filter { it.taskId == t.id }.all { it.end <= t.deadline })
        assertFalse(t.id in p.atRisk)
    }

    @Test
    fun infeasibleDeadlineIsVisible() {
        val p = plan(PlannerData(tasks = listOf(task(hours = 20.0, due = date.at("06:00", zone)))))
        assertTrue("task" in p.atRisk)
        assertTrue(p.remainingMinutes.getValue("task") > 0)
    }

    @Test
    fun noSubtasksGetsPlanningNudgeAndWork() {
        val p = plan(PlannerData(tasks = listOf(task(hours = null))))
        assertEquals(1, p.sessions.count { it.kind == SessionKind.PLAN_SUBTASKS })
        assertTrue(p.sessions.any { it.kind == SessionKind.WORK })
        assertTrue(p.sessions.all { it.reason.isNotBlank() })
    }

    @Test
    fun lowMoodLowersCapacityAndStartsEasy() {
        val d =
            PlannerData(
                tasks =
                    listOf(
                        task("easy", hours = 1.0, difficulty = 1),
                        task("hard", hours = 10.0, difficulty = 5),
                    ),
                contexts = listOf(DayContext(date.toString(), moodMorning = 1, sleepHours = 5.0)),
            )
        val p = plan(d)
        assertEquals(216, p.dailyCapacity.getValue(date.toString()))
        assertEquals(SessionKind.EASY_WIN, p.sessions.first { it.date == date.toString() }.kind)
        assertTrue(
            p.sessions
                .filter { it.date == date.toString() && it.kind in Scheduler.workKinds }
                .sumOf { it.minutes } <= 216
        )
    }

    @Test
    fun pinnedSessionIsUntouched() {
        val pin =
            PlannedSession(
                "p",
                date.toString(),
                date.at("07:00", zone),
                date.at("08:00", zone),
                "task",
                reason = "Mine",
                isPinned = true,
            )
        val p = plan(PlannerData(tasks = listOf(task()), sessions = listOf(pin)))
        assertTrue(pin in p.sessions)
        assertTrue(
            p.sessions.filter { it.id != "p" }.none { it.start < pin.end && it.end > pin.start }
        )
    }

    @Test
    fun doneSessionsAndPastArePreserved() {
        val done =
            PlannedSession(
                "d",
                date.toString(),
                date.at("06:00", zone),
                date.at("07:00", zone),
                "task",
                reason = "Done",
                state = SessionState.DONE,
            )
        val p =
            plan(
                PlannerData(tasks = listOf(task()), sessions = listOf(done)),
                date.at("08:00", zone),
            )
        assertTrue(done in p.sessions)
        assertTrue(p.sessions.filter { it.id != "d" }.all { it.start >= date.at("08:00", zone) })
    }

    @Test
    fun overrunShiftsFutureWork() {
        val current =
            PlannedSession(
                "p",
                date.toString(),
                now,
                now + 80 * 60_000L,
                "task",
                reason = "Overrun",
                isPinned = true,
            )
        val p =
            plan(
                PlannerData(tasks = listOf(task(hours = 3.0)), sessions = listOf(current)),
                now + 55 * 60_000L,
            )
        assertTrue(p.sessions.filter { it.id != "p" }.all { it.start >= current.end })
    }

    @Test
    fun recurringDailyHasEveryOccurrence() {
        val p = plan(PlannerData(tasks = listOf(task(hours = 1.0, recurring = true))))
        assertEquals(
            7,
            p.sessions.filter { it.kind in Scheduler.workKinds }.map { it.date }.distinct().size,
        )
        assertEquals(420, p.sessions.filter { it.kind in Scheduler.workKinds }.sumOf { it.minutes })
    }

    @Test
    fun correctionChangesEstimatesButNotExplicitHours() {
        val t = task(hours = null, difficulty = 2)
        assertEquals(
            6.0,
            Estimation.hours(
                t,
                null,
                settings,
                listOf(EstimateCorrection(Category.ASSIGNMENT, 2.0)),
            ),
            0.001,
        )
        assertEquals(
            2.0,
            Estimation.hours(
                t.copy(estimatedHours = 2.0),
                null,
                settings,
                listOf(EstimateCorrection(Category.ASSIGNMENT, 2.0)),
            ),
            0.001,
        )
    }

    @Test
    fun overlappingEventsAndBlocksAreMerged() {
        val block = FixedBlock(title = "Class", startTime = "09:00", endTime = "11:00")
        val event =
            CalendarEvent("e", 1, 1, "Meeting", date.at("10:00", zone), date.at("12:00", zone))
        val p =
            plan(
                PlannerData(
                    tasks = listOf(task(hours = 20.0)),
                    blocks = listOf(block),
                    events = listOf(event),
                )
            )
        val blocked = Interval(date.at("08:50", zone), date.at("12:10", zone))
        assertTrue(
            p.sessions
                .filter { it.date == date.toString() }
                .none { Interval(it.start, it.end).overlaps(blocked) }
        )
    }

    @Test
    fun overnightSleepAndTimezoneAreRespected() {
        val sleep =
            FixedBlock(
                title = "Sleep",
                type = BlockType.SLEEP,
                startTime = "22:00",
                endTime = "07:00",
            )
        val p =
            plan(
                PlannerData(
                    tasks = listOf(task()),
                    blocks = listOf(sleep),
                    contexts = listOf(DayContext(date.toString(), wakeTime = "07:30")),
                )
            )
        assertTrue(
            p.sessions
                .filter { it.date == date.toString() }
                .all { it.start >= date.at("07:30", zone) && it.end <= date.at("22:00", zone) }
        )
    }

    @Test
    fun subtasksStayInOrder() {
        val t = task(hours = null)
        val a = Subtask("a", t.id, "Read", 0, 1, 1.0)
        val b = Subtask("b", t.id, "Write", 1, 5, 1.0)
        val p = plan(PlannerData(tasks = listOf(t), subtasks = listOf(a, b)))
        assertTrue(
            p.sessions.filter { it.subtaskId == "a" }.maxOf { it.end } <=
                p.sessions.filter { it.subtaskId == "b" }.minOf { it.start }
        )
    }

    @Test
    fun allDayEventsDoNotBlockTime() {
        val e =
            CalendarEvent(
                "e",
                1,
                1,
                "Deadline",
                date.at("00:00", zone),
                date.plusDays(1).at("00:00", zone),
                true,
            )
        val d = PlannerData(tasks = listOf(task()))
        assertEquals(plan(d).sessions, plan(d.copy(events = listOf(e))).sessions)
    }

    @Test
    fun deterministicAndNoOverlapsAcrossBusyWeek() {
        val tasks =
            (1..30).map { task("t$it", hours = (it % 4 + 1).toDouble(), difficulty = it % 5 + 1) }
        val d = PlannerData(tasks = tasks)
        val p = plan(d)
        assertEquals(p, plan(d))
        p.sessions.zipWithNext().forEach { (a, b) ->
            assertTrue("${a.id} overlaps ${b.id}", a.end <= b.start)
        }
    }

    @Test
    fun estimateCorrectionIsBounded() {
        val c =
            Estimation.corrected(
                EstimateCorrection(Category.CODING, 2.4),
                Category.CODING,
                99999,
                1.0,
            )
        assertTrue(c.factor in 0.5..2.5)
        assertEquals(1, c.samples)
    }

    @Test
    fun completedActualsReduceRemainingWork() {
        val t = task(hours = 2.0)
        val log = WorkLog(sessionId = "old", taskId = t.id, actualMinutes = 90, completedAt = now)
        val p = plan(PlannerData(tasks = listOf(t), logs = listOf(log)))
        assertEquals(30, p.sessions.filter { it.kind in Scheduler.workKinds }.sumOf { it.minutes })
    }

    @Test
    fun daylightSavingUsesInstants() {
        val ny = ZoneId.of("America/New_York")
        val d = LocalDate.of(2026, 11, 1)
        val t = task(due = d.at("23:00", ny)).copy(createdAt = d.at("00:00", ny))
        val p = Scheduler().plan(PlannerData(tasks = listOf(t)), settings, d.at("05:30", ny), ny)
        assertTrue(p.sessions.all { it.start < it.end })
    }

    @Test
    fun explicitWholeTaskHoursAreDistributedAcrossSteps() {
        val t = task(hours = 2.0)
        val a = Subtask("a", t.id, "Read", 0, 1)
        val b = Subtask("b", t.id, "Build", 1, 5)
        val d = PlannerData(tasks = listOf(t), subtasks = listOf(a, b))
        assertEquals(2.0, Estimation.taskHours(t, d, settings), 0.001)
        assertEquals(
            120,
            plan(d).sessions.filter { it.kind in Scheduler.workKinds }.sumOf { it.minutes },
        )
    }

    @Test
    fun largeLateWakeWindowDoesNotInventPastSlots() {
        val at = date.at("20:15", zone)
        val p =
            plan(PlannerData(tasks = listOf(task(hours = 10.0, due = date.at("22:00", zone)))), at)
        assertTrue(p.sessions.all { it.start >= at })
        assertTrue("task" in p.atRisk)
    }

    @Test
    fun skipDoesNotCreditUnfinishedWork() {
        val skipped =
            PlannedSession(
                "s",
                date.toString(),
                now,
                now + 50 * 60_000L,
                "task",
                reason = "Skipped",
                state = SessionState.SKIPPED,
            )
        val p =
            plan(
                PlannerData(tasks = listOf(task()), sessions = listOf(skipped)),
                now + 10 * 60_000L,
            )
        assertEquals(
            120,
            p.sessions
                .filter { it.state == SessionState.PLANNED && it.kind in Scheduler.workKinds }
                .sumOf { it.minutes },
        )
    }

    @Test
    fun actualOverrunsConsumeDailyCapacity() {
        val t = task(hours = 10.0)
        val done =
            PlannedSession(
                "d",
                date.toString(),
                now,
                now + 50 * 60_000L,
                t.id,
                reason = "Done",
                state = SessionState.DONE,
            )
        val log =
            WorkLog(
                sessionId = done.id,
                taskId = t.id,
                actualMinutes = 90,
                completedAt = now + 90 * 60_000L,
            )
        val p =
            plan(
                PlannerData(tasks = listOf(t), sessions = listOf(done), logs = listOf(log)),
                now + 90 * 60_000L,
            )
        assertEquals(270, p.dailyCapacity.getValue(date.toString()))
    }

    @Test
    fun extraCapacityIsOfferedRatherThanForced() {
        val c = DayContext(date.toString(), moodMorning = 5, sleepHours = 8.0)
        assertEquals(
            360,
            plan(PlannerData(contexts = listOf(c))).dailyCapacity.getValue(date.toString()),
        )
        assertEquals(
            396,
            plan(PlannerData(contexts = listOf(c.copy(allowExtra = true))))
                .dailyCapacity
                .getValue(date.toString()),
        )
    }

    @Test
    fun retryAtExactSkippedStartNeverDropsAllocatedWork() {
        val t = task()
        val step =
            Subtask(id = "step", taskId = t.id, title = "Read", order = 0, estimatedHours = 2.0)
        val skipped =
            PlannedSession(
                "$date:$now:${t.id}:step",
                date.toString(),
                now,
                now + 50 * 60_000L,
                t.id,
                step.id,
                reason = "Skipped",
                state = SessionState.SKIPPED,
            )
        val p =
            plan(
                PlannerData(tasks = listOf(t), subtasks = listOf(step), sessions = listOf(skipped))
            )
        assertEquals(
            120,
            p.sessions
                .filter { it.state == SessionState.PLANNED && it.kind in Scheduler.workKinds }
                .sumOf { it.minutes },
        )
        assertEquals(p.sessions.size, p.sessions.map { it.id }.distinct().size)
    }

    @Test
    fun pinAfterDeadlineIsRespectedButFlagged() {
        val t = task(hours = 1.0, due = date.at("12:00", zone))
        val pin =
            PlannedSession(
                "pin",
                date.toString(),
                date.at("18:00", zone),
                date.at("19:00", zone),
                t.id,
                reason = "My slot",
                isPinned = true,
            )
        val p = plan(PlannerData(tasks = listOf(t), sessions = listOf(pin)))
        assertTrue(pin in p.sessions)
        assertTrue(t.id in p.atRisk)
    }
}
