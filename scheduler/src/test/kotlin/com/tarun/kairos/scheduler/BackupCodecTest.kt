package com.tarun.kairos.scheduler

import org.junit.Assert.*
import org.junit.Test

class BackupCodecTest {
    private fun backup(): Backup {
        val task = Task(id = "task", title = "A name with \"quotes\" and 🌱", deadline = 9999999)
        return Backup(
            exportedAt = 1,
            settings = PlannerSettings(),
            data =
                PlannerData(
                    tasks = listOf(task),
                    blocks =
                        listOf(
                            FixedBlock(
                                id = "b",
                                title = "Sleep",
                                type = BlockType.SLEEP,
                                startTime = "23:00",
                                endTime = "05:30",
                            )
                        ),
                    contexts = listOf(DayContext("2026-10-13", moodMorning = 2)),
                    corrections = listOf(EstimateCorrection(Category.CODING, 1.3, 2)),
                ),
        )
    }

    @Test
    fun fullRoundTripPreservesData() {
        val b = backup()
        assertEquals(b, BackupCodec.decode(BackupCodec.encode(b)))
    }

    @Test
    fun unknownColumnsAreAccepted() {
        val b = backup()
        val text =
            BackupCodec.encode(b)
                .replace("\"schemaVersion\": 1", "\"future\": true, \"schemaVersion\": 1")
        assertEquals(b, BackupCodec.decode(text))
    }

    @Test(expected = IllegalArgumentException::class)
    fun unknownSchemaNeverRestores() {
        BackupCodec.decode(
            BackupCodec.encode(backup()).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun duplicateRecordsAreRejected() {
        val b = backup()
        BackupCodec.encode(b.copy(data = b.data.copy(tasks = b.data.tasks + b.data.tasks)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun danglingSubtasksAreRejected() {
        val b = backup()
        BackupCodec.encode(
            b.copy(
                data =
                    b.data.copy(
                        subtasks =
                            listOf(Subtask(taskId = "missing", title = "No parent", order = 0))
                    )
            )
        )
    }

    @Test(expected = IllegalArgumentException::class)
    fun malformedLearnedFactorsAreRejected() {
        val b = backup()
        BackupCodec.encode(
            b.copy(
                data = b.data.copy(corrections = listOf(EstimateCorrection(Category.CODING, 99.0)))
            )
        )
    }

    @Test
    fun removedStepsKeepTheirImmutableWorkHistory() {
        val b = backup()
        val log =
            WorkLog(
                id = "old",
                sessionId = "done",
                taskId = "task",
                subtaskId = "removed-step",
                actualMinutes = 35,
            )
        val withHistory = b.copy(data = b.data.copy(logs = listOf(log)))
        assertEquals(withHistory, BackupCodec.decode(BackupCodec.encode(withHistory)))
    }
}
