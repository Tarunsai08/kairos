package com.tarun.kairos.ui

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.tarun.kairos.background.WorkSchedules
import com.tarun.kairos.data.*
import com.tarun.kairos.domain.PlanningCoordinator
import com.tarun.kairos.integration.*
import com.tarun.kairos.scheduler.*
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

@HiltViewModel
class PlannerViewModel
@Inject
constructor(
    val repository: PlannerRepository,
    val settingsStore: SettingsStore,
    val coordinator: PlanningCoordinator,
    val calendar: CalendarIntegration,
    val kaizen: KaizenIntegration,
    val reminders: com.tarun.kairos.reminders.ReminderScheduler,
    private val learning: LearningService,
    val backup: com.tarun.kairos.backup.BackupService,
    @ApplicationContext private val context: Context,
) : ViewModel() {
    val data =
        repository.flow.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), PlannerData())
    val settings =
        settingsStore.flow.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5000),
            PlannerSettings(),
        )
    val pendingBackup = MutableStateFlow<Backup?>(null)
    val ready = MutableStateFlow(false)
    val calendars = MutableStateFlow<List<DeviceCalendar>>(emptyList())
    val now = flow {
        while (currentCoroutineContext().isActive) {
            emit(System.currentTimeMillis())
            perform { coordinator.checkOverruns() }
            delay(30_000)
        }
    }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), System.currentTimeMillis())
    val plan = coordinator.latest

    val message = MutableStateFlow<String?>(null)
    private val actions = Mutex()

    init {
        viewModelScope.launch {
            settingsStore.flow.first()
            repository.snapshot()
            ready.value = true
        }
        refresh()
        viewModelScope.launch { coordinator.updates.collect { message.value = it } }
    }

    fun perform(action: suspend () -> Unit) {
        viewModelScope.launch {
            try {
                actions.withLock { action() }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                message.value = e.message ?: "Couldn't save. Please try again."
            }
        }
    }

    fun saveTask(task: Task, subtasks: List<Subtask>) = perform {
        val previous = repository.snapshot()
        repository.saveTaskWithSubtasks(task, subtasks)
        subtasks
            .filter {
                it.status == SubtaskStatus.DONE &&
                    previous.subtasks.find { old -> old.id == it.id }?.status != SubtaskStatus.DONE
            }
            .forEach { learning.completed(task, it) }
        if (
            task.status == TaskStatus.DONE &&
                previous.tasks.find { it.id == task.id }?.status != TaskStatus.DONE
        )
            learning.completed(task)
        coordinator.replan()
        message.value = "Task saved"
    }

    fun saveBlock(block: FixedBlock) = perform {
        repository.save(block)
        coordinator.replan()
        message.value = "Commitment saved"
    }

    fun updateTask(task: Task) = perform {
        repository.save(task)
        if (task.status == TaskStatus.DONE) learning.completed(task)
        coordinator.replan()
    }

    fun deleteBlock(id: String) = perform {
        repository.dao.deleteBlock(id)
        coordinator.replan()
    }

    fun deleteSubtask(id: String) = perform {
        repository.dao.deleteSubtask(id)
        coordinator.replan()
    }

    fun checkin(context: DayContext) = perform {
        repository.save(context)
        coordinator.replan()
    }

    fun finish(id: String, state: SessionState, minutes: Int? = null, difficulty: Int? = null) =
        perform {
            coordinator.finish(id, state, minutes, difficulty)
        }

    fun start(id: String) = perform {
        coordinator.start(id)
        message.value = "Focus session started"
    }

    fun extend(id: String) = perform { coordinator.extend(id, 15) }

    fun move(id: String, start: Long, minutes: Int) = perform {
        coordinator.move(id, start, minutes)
    }

    fun pin(id: String) = perform { coordinator.pin(id) }

    fun swap(id: String) = perform { coordinator.swap(id) }

    fun updateSettings(settings: PlannerSettings) = perform {
        settingsStore.set(settings)
        calendar.sync()
        kaizen.sync()
        coordinator.replan()
        WorkSchedules.daily(context, settings)
    }

    fun refresh() = perform {
        calendar.observe()
        calendar.sync()
        kaizen.observe()
        kaizen.sync()
        calendars.value = calendar.calendars()
        coordinator.replan()
    }

    fun export(uri: android.net.Uri) = perform {
        backup.export(uri)
        message.value = "Backup exported"
    }

    fun import(uri: android.net.Uri) = perform { pendingBackup.value = backup.preview(uri) }

    fun undoPreview() = perform { pendingBackup.value = backup.previous() }

    fun restore() = perform {
        pendingBackup.value?.let {
            backup.restore(it)
            pendingBackup.value = null
            refresh()
            WorkSchedules.weeklyBackup(context, settingsStore.flow.first())
            message.value = "Data restored. Choose an auto-backup folder again if needed."
        }
    }

    fun completeOnboarding(settings: PlannerSettings) = perform {
        settingsStore.set(settings.copy(onboarded = true))
        if (repository.snapshot().blocks.none { it.type == BlockType.SLEEP })
            repository.save(
                FixedBlock(
                    title = "Sleep",
                    type = BlockType.SLEEP,
                    startTime = settings.bedtime,
                    endTime = settings.wakeTime,
                )
            )
        WorkSchedules.daily(context, settings)
        coordinator.replan()
    }

    fun quickBlock(title: String, type: BlockType, start: String, end: String) = perform {
        if (repository.snapshot().blocks.none { it.title == title }) {
            repository.save(
                FixedBlock(title = title, type = type, startTime = start, endTime = end)
            )
            coordinator.replan()
        }
    }
}
