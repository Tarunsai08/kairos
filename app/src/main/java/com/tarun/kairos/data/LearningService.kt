package com.tarun.kairos.data

import com.tarun.kairos.scheduler.*
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@Singleton
class LearningService
@Inject
constructor(private val repository: PlannerRepository, private val settings: SettingsStore) {
    private val mutex = Mutex()

    suspend fun session(session: PlannedSession, state: SessionState) = mutex.withLock {
        val data = repository.snapshot()
        val time =
            Instant.ofEpochMilli(session.startedAt ?: session.start).atZone(ZoneId.systemDefault())
        val previous =
            data.energy.find { it.hourOfDay == time.hour && it.weekday == time.dayOfWeek.value }
        repository.save(Energy.update(previous, time.hour, time.dayOfWeek.value, state))
    }

    suspend fun completed(task: Task, subtask: Subtask? = null) = mutex.withLock {
        val data = repository.snapshot()
        val config = settings.flow.first()
        val logs =
            data.logs.filter {
                it.taskId == task.id &&
                    (subtask == null || it.subtaskId == subtask.id) &&
                    !it.learningApplied
            }
        if (logs.isEmpty()) return@withLock
        val base =
            if (subtask != null)
                Estimation.workHours(task, subtask, data.copy(corrections = emptyList()), config)
            else {
                val subs = data.subtasks.filter { it.taskId == task.id }
                if (subs.isEmpty()) Estimation.hours(task, null, config, emptyList())
                else
                    subs.sumOf {
                        Estimation.workHours(task, it, data.copy(corrections = emptyList()), config)
                    }
            }
        if (base <= 0) return@withLock
        repository.save(
            Estimation.corrected(
                data.corrections.find { it.category == task.category },
                task.category,
                logs.sumOf { it.actualMinutes },
                base * 60,
            )
        )
        logs.forEach { repository.save(it.copy(learningApplied = true)) }
    }
}
