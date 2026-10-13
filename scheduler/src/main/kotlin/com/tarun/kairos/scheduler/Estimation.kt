package com.tarun.kairos.scheduler

import kotlin.math.*

object Estimation {
    private val subtaskBase = listOf(0.5, 1.0, 2.0, 3.5, 5.0)
    private val taskBase = listOf(1.0, 3.0, 6.0, 10.0, 16.0)

    fun hours(
        task: Task,
        subtask: Subtask?,
        settings: PlannerSettings,
        corrections: List<EstimateCorrection>,
    ): Double {
        val explicit = if (subtask != null) subtask.estimatedHours else task.estimatedHours
        if (explicit != null) return explicit
        val base =
            if (subtask != null) subtaskBase[subtask.difficulty - 1]
            else taskBase[task.difficulty - 1]
        val factor =
            corrections.find { it.category == task.category }?.factor?.coerceIn(0.5, 2.5) ?: 1.0
        return base * (settings.categoryMultipliers[task.category] ?: 1.0) * factor
    }

    fun workHours(
        task: Task,
        subtask: Subtask?,
        data: PlannerData,
        settings: PlannerSettings,
    ): Double {
        if (subtask == null || task.estimatedHours == null || subtask.estimatedHours != null)
            return hours(task, subtask, settings, data.corrections)
        val siblings = data.subtasks.filter { it.taskId == task.id }
        val unknown = siblings.filter { it.estimatedHours == null }
        val remaining =
            (task.estimatedHours - siblings.sumOf { it.estimatedHours ?: 0.0 }).coerceAtLeast(0.0)
        val weights = unknown.sumOf { subtaskBase[it.difficulty - 1] }
        return if (weights > 0) remaining * subtaskBase[subtask.difficulty - 1] / weights else 0.0
    }

    fun taskHours(task: Task, data: PlannerData, settings: PlannerSettings): Double {
        val subs = data.subtasks.filter { it.taskId == task.id }
        return if (subs.isEmpty()) hours(task, null, settings, data.corrections)
        else
            subs
                .filter { it.status != SubtaskStatus.DONE }
                .sumOf { workHours(task, it, data, settings) }
    }

    fun remainingTaskHours(
        task: Task,
        data: PlannerData,
        settings: PlannerSettings,
        zone: java.time.ZoneId = java.time.ZoneId.systemDefault(),
    ): Double {
        val subs = data.subtasks.filter { it.taskId == task.id }
        val units =
            if (subs.isEmpty()) listOf<Subtask?>(null)
            else subs.filter { it.status != SubtaskStatus.DONE }
        return units.sumOf { sub ->
            val logged =
                data.logs
                    .filter {
                        it.taskId == task.id &&
                            it.subtaskId == sub?.id &&
                            (!task.isRecurring ||
                                it.completedAt.localDate(zone) == java.time.LocalDate.now(zone))
                    }
                    .sumOf { it.actualMinutes } / 60.0
            (workHours(task, sub, data, settings) - logged).coerceAtLeast(0.0)
        }
    }

    fun corrected(
        previous: EstimateCorrection?,
        category: Category,
        actualMinutes: Int,
        estimatedMinutes: Double,
    ): EstimateCorrection {
        require(actualMinutes >= 0 && estimatedMinutes > 0 && estimatedMinutes.isFinite())
        val old = previous?.factor ?: 1.0
        val ratio = (actualMinutes / estimatedMinutes).coerceIn(0.5, 2.5)
        return EstimateCorrection(
            category,
            (0.7 * old + 0.3 * ratio).coerceIn(0.5, 2.5),
            (previous?.samples ?: 0) + 1,
        )
    }
}
