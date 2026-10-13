package com.tarun.kairos

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import app.cash.turbine.test
import com.tarun.kairos.data.*
import com.tarun.kairos.scheduler.*
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class StorageTest {
    @Test
    fun transactionFlowsAndForeignKeyCascade() = runBlocking {
        val db =
            Room.inMemoryDatabaseBuilder(
                    ApplicationProvider.getApplicationContext(),
                    KairosDatabase::class.java,
                )
                .build()
        try {
            val repository = PlannerRepository(db)
            repository.flow.test(timeout = 30.seconds) {
                assertTrue(awaitItem().tasks.isEmpty())
                val task =
                    Task(
                        id = "storage-test",
                        title = "A real Room task",
                        deadline = System.currentTimeMillis() + 86400000,
                    )
                val step = Subtask(taskId = task.id, title = "First step", order = 0)
                repository.saveTaskWithSubtasks(task, listOf(step))
                var saved = awaitItem()
                while (saved.tasks.size != 1 || saved.subtasks.size != 1) saved = awaitItem()
                assertEquals(task, saved.tasks.single())
                assertEquals(step, saved.subtasks.single())
                repository.dao.deleteTask(task.id)
                var removed = awaitItem()
                while (removed.tasks.isNotEmpty() || removed.subtasks.isNotEmpty()) removed =
                    awaitItem()
                assertTrue(removed.subtasks.isEmpty())
                cancelAndIgnoreRemainingEvents()
            }
        } finally {
            db.close()
        }
    }
}
