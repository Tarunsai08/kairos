package com.tarun.kairos.data

import androidx.room.withTransaction
import com.tarun.kairos.scheduler.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

val AppJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
}

@Singleton
class PlannerRepository @Inject constructor(private val db: KairosDatabase) {
    val dao = db.planner()
    private val core =
        combine(dao.tasks(), dao.subtasks(), dao.blocks(), dao.sessions(), dao.logs()) {
            t,
            st,
            b,
            s,
            l ->
            PlannerData(
                tasks = t.map { AppJson.decodeFromString<Task>(it.payload) },
                subtasks = st.map { AppJson.decodeFromString<Subtask>(it.payload) },
                blocks = b.map { AppJson.decodeFromString<FixedBlock>(it.payload) },
                sessions = s.map { AppJson.decodeFromString<PlannedSession>(it.payload) },
                logs = l.map { AppJson.decodeFromString<WorkLog>(it.payload) },
            )
        }
    val flow =
        combine(core, dao.contexts(), dao.events(), dao.corrections(), dao.energy()) {
            d,
            c,
            e,
            co,
            en ->
            d.copy(
                contexts = c.map { AppJson.decodeFromString<DayContext>(it.payload) },
                events = e.map { AppJson.decodeFromString<CalendarEvent>(it.payload) },
                corrections = co.map { AppJson.decodeFromString<EstimateCorrection>(it.payload) },
                energy = en.map { AppJson.decodeFromString<EnergyProfile>(it.payload) },
            )
        }

    suspend fun snapshot(): PlannerData = db.withTransaction {
        PlannerData(
            tasks = dao.snapshotTasks().map { AppJson.decodeFromString<Task>(it.payload) },
            subtasks = dao.snapshotSubtasks().map { AppJson.decodeFromString<Subtask>(it.payload) },
            blocks = dao.snapshotBlocks().map { AppJson.decodeFromString<FixedBlock>(it.payload) },
            sessions =
                dao.snapshotSessions().map { AppJson.decodeFromString<PlannedSession>(it.payload) },
            logs = dao.snapshotLogs().map { AppJson.decodeFromString<WorkLog>(it.payload) },
            contexts =
                dao.snapshotContexts().map { AppJson.decodeFromString<DayContext>(it.payload) },
            events =
                dao.snapshotEvents().map { AppJson.decodeFromString<CalendarEvent>(it.payload) },
            corrections =
                dao.snapshotCorrections().map {
                    AppJson.decodeFromString<EstimateCorrection>(it.payload)
                },
            energy =
                dao.snapshotEnergy().map { AppJson.decodeFromString<EnergyProfile>(it.payload) },
        )
    }

    suspend fun save(task: Task) =
        dao.task(TaskRow(task.id, task.deadline, task.status.name, AppJson.encodeToString(task)))

    suspend fun save(subtask: Subtask) =
        dao.subtask(
            SubtaskRow(subtask.id, subtask.taskId, subtask.order, AppJson.encodeToString(subtask))
        )

    suspend fun save(block: FixedBlock) =
        dao.block(BlockRow(block.id, AppJson.encodeToString(block)))

    suspend fun save(context: DayContext) =
        dao.context(ContextRow(context.date, AppJson.encodeToString(context)))

    suspend fun save(log: WorkLog) =
        dao.log(LogRow(log.id, log.sessionId, AppJson.encodeToString(log)))

    suspend fun save(correction: EstimateCorrection) =
        dao.correction(CorrectionRow(correction.category.name, AppJson.encodeToString(correction)))

    suspend fun save(profile: EnergyProfile) =
        dao.energy(EnergyRow(profile.hourOfDay, profile.weekday, AppJson.encodeToString(profile)))

    suspend fun replaceSessions(sessions: List<PlannedSession>) = db.withTransaction {
        dao.clearSessions()
        dao.sessions(
            sessions.map {
                SessionRow(it.id, it.date, it.start, it.taskId, AppJson.encodeToString(it))
            }
        )
    }

    suspend fun replaceEvents(events: List<CalendarEvent>) = db.withTransaction {
        dao.clearEvents()
        dao.events(events.map { EventRow(it.id, AppJson.encodeToString(it)) })
    }

    suspend fun saveTaskWithSubtasks(task: Task, subtasks: List<Subtask>) {
        val removed =
            dao.subtasks().first().filter {
                it.taskId == task.id && it.id !in subtasks.map { s -> s.id }
            }
        db.withTransaction {
            save(task)
            removed.forEach { dao.deleteSubtask(it.id) }
            subtasks.forEach { save(it) }
        }
    }

    suspend fun replaceAll(data: PlannerData) = db.withTransaction {
        dao.clearTasks()
        dao.clearBlocks()
        dao.clearSessions()
        dao.clearLogs()
        dao.clearContexts()
        dao.clearEvents()
        dao.clearCorrections()
        dao.clearEnergy()
        data.tasks.forEach { save(it) }
        data.subtasks.forEach { save(it) }
        data.blocks.forEach { save(it) }
        replaceSessions(data.sessions)
        data.logs.forEach { save(it) }
        data.contexts.forEach { save(it) }
        replaceEvents(data.events)
        data.corrections.forEach { save(it) }
        data.energy.forEach { save(it) }
    }
}
