package com.tarun.kairos.domain

import com.tarun.kairos.data.*
import com.tarun.kairos.reminders.ReminderScheduler
import com.tarun.kairos.scheduler.*
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@Singleton
class PlanningCoordinator
@Inject
constructor(
    private val repository: PlannerRepository,
    private val settings: SettingsStore,
    private val reminders: ReminderScheduler,
    private val widgets: com.tarun.kairos.widgets.WidgetUpdater,
    private val learning: LearningService,
) {
    private val mutex = Mutex()
    val latest = MutableStateFlow<PlanResult?>(null)
    val updates = MutableSharedFlow<String>(extraBufferCapacity = 1)

    suspend fun replan() = mutex.withLock { planLocked() }

    private suspend fun planLocked() {
        val data = repository.snapshot()
        val config = settings.flow.first()
        val now = System.currentTimeMillis()
        val plan =
            withContext(Dispatchers.Default) {
                Scheduler().plan(data, config, now, ZoneId.systemDefault())
            }
        repository.replaceSessions(plan.sessions)
        latest.value = plan
        reminders.schedule(data.copy(sessions = plan.sessions), plan.atRisk, config)
        widgets.refresh()
        val moved =
            plan.sessions.firstOrNull { s ->
                s.state == SessionState.PLANNED &&
                    s.kind in Scheduler.workKinds &&
                    data.sessions.any { old ->
                        old.taskId == s.taskId && old.start > now && old.start != s.start
                    }
            }
        if (moved != null) {
            val title = data.tasks.find { it.id == moved.taskId }?.title ?: "Work"
            updates.tryEmit("Plan updated: $title has a new focus slot")
        }
    }

    suspend fun checkOverruns() = mutex.withLock {
        val d = repository.snapshot()
        val now = System.currentTimeMillis()
        val session = d.sessions.firstOrNull { ReplanPolicy.overran(it, now) } ?: return@withLock
        val extended =
            session.copy(
                end = maxOf(session.end + 15 * 60_000L, now + 15 * 60_000L),
                isPinned = true,
            )
        try {
            validate(extended, d, setOf(session.id), allowPast = true)
            repository.replaceSessions(d.sessions.map { if (it.id == session.id) extended else it })
            planLocked()
        } catch (_: IllegalArgumentException) {
            updates.tryEmit(
                "Your session overran a commitment. Mark your actual work before continuing."
            )
        }
    }

    suspend fun start(id: String) = mutex.withLock {
        val d = repository.snapshot()
        repository.replaceSessions(
            d.sessions.map {
                if (it.id == id && it.state == SessionState.PLANNED)
                    it.copy(startedAt = System.currentTimeMillis())
                else it
            }
        )
    }

    suspend fun finish(
        id: String,
        state: SessionState,
        actualMinutes: Int? = null,
        perceivedDifficulty: Int? = null,
    ) = mutex.withLock {
        val d = repository.snapshot()
        val s = d.sessions.find { it.id == id } ?: return@withLock
        if (s.state != SessionState.PLANNED || s.kind !in Scheduler.workKinds) return@withLock
        val now = System.currentTimeMillis()
        val actual =
            actualMinutes
                ?: s.startedAt?.let { minutesBetween(it, now) }?.coerceAtLeast(1)
                ?: if (state == SessionState.DONE) s.minutes else 0
        if (state != SessionState.SKIPPED)
            s.taskId?.let { taskId ->
                repository.save(
                    WorkLog(
                        id = s.id,
                        sessionId = s.id,
                        taskId = taskId,
                        subtaskId = s.subtaskId,
                        actualMinutes = actual.coerceIn(0, 1440),
                        completedAt = now,
                        perceivedDifficulty = perceivedDifficulty,
                    )
                )
            }
        val ended =
            if (state == SessionState.PARTIAL && now > s.start)
                minOf(s.end, now).coerceAtLeast(s.start + 60_000L)
            else s.end
        repository.replaceSessions(
            d.sessions.map { if (it.id == id) it.copy(state = state, end = ended) else it }
        )
        learning.session(s, state)
        planLocked()
    }

    suspend fun extend(id: String, minutes: Int) = mutex.withLock {
        require(minutes in 1..120)
        val d = repository.snapshot()
        val s = d.sessions.find { it.id == id } ?: return@withLock
        require(s.state == SessionState.PLANNED)
        val extended = s.copy(end = s.end + minutes * 60_000L, isPinned = true)
        validate(extended, d, ignore = setOf(id), allowPast = true)
        repository.replaceSessions(d.sessions.map { if (it.id == id) extended else it })
        planLocked()
    }

    suspend fun move(id: String, start: Long, durationMinutes: Int) = mutex.withLock {
        require(durationMinutes in 15..180)
        require(start >= System.currentTimeMillis()) { "Choose a future time." }
        val d = repository.snapshot()
        val s = d.sessions.find { it.id == id } ?: return@withLock
        require(s.state == SessionState.PLANNED) { "Completed sessions stay unchanged." }
        val moved =
            s.copy(
                date = start.localDate(ZoneId.systemDefault()).toString(),
                start = start,
                end = start + durationMinutes * 60_000L,
                isPinned = true,
            )
        validate(moved, d, ignore = setOf(id))
        repository.replaceSessions(d.sessions.map { if (it.id == id) moved else it })
        planLocked()
    }

    suspend fun pin(id: String) = mutex.withLock {
        val d = repository.snapshot()
        repository.replaceSessions(
            d.sessions.map { if (it.id == id) it.copy(isPinned = !it.isPinned) else it }
        )
        planLocked()
    }

    suspend fun swap(id: String) = mutex.withLock {
        val d = repository.snapshot()
        val s = d.sessions.find { it.id == id } ?: return@withLock
        val now = System.currentTimeMillis()
        require(s.start > now && !s.isPinned && s.state == SessionState.PLANNED) {
            "Choose a future, unpinned session."
        }
        val other =
            d.sessions.firstOrNull {
                it.id != id &&
                    it.date == s.date &&
                    it.start > now &&
                    it.kind in Scheduler.workKinds &&
                    it.state == SessionState.PLANNED &&
                    !it.isPinned &&
                    it.taskId != s.taskId &&
                    it.minutes == s.minutes
            } ?: error("No matching session to swap. Use Move instead.")
        val a = s.copy(start = other.start, end = other.end, isPinned = true)
        val b = other.copy(start = s.start, end = s.end, isPinned = true)
        validate(a, d, setOf(s.id, other.id))
        validate(b, d, setOf(s.id, other.id))
        repository.replaceSessions(
            d.sessions.map {
                when (it.id) {
                    s.id -> a
                    other.id -> b
                    else -> it
                }
            }
        )
        planLocked()
    }

    private suspend fun validate(
        s: PlannedSession,
        d: PlannerData,
        ignore: Set<String>,
        allowPast: Boolean = false,
    ) {
        val zone = ZoneId.systemDefault()
        val date = s.start.localDate(zone)
        val cfg = settings.flow.first()
        val interval = Interval(s.start, s.end)
        if (!allowPast) require(s.start >= System.currentTimeMillis()) { "Choose a future time." }
        val buffer = cfg.transitionMinutes * 60_000L
        val busy =
            TimeWindows.fixed(date, d.blocks, zone).map {
                Interval(it.second.start - buffer, it.second.end + buffer)
            } +
                d.events
                    .filter { !it.isAllDay && it.treatAs == EventTreatment.BUSY }
                    .map { Interval(it.start - buffer, it.end + buffer) } +
                d.sessions
                    .filter { it.id !in ignore && (it.isPinned || it.state == SessionState.DONE) }
                    .map { Interval(it.start, it.end) }
        require(busy.none { it.overlaps(interval) }) {
            "That time overlaps a commitment or pinned session."
        }
        val awake =
            TimeWindows.awake(
                date,
                d.contexts.find { it.date == date.toString() },
                cfg,
                d.blocks,
                zone,
            )
        require(s.start >= awake.start && s.end <= awake.end) {
            "Keep sessions within your awake hours."
        }
    }
}
