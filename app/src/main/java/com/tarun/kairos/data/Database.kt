package com.tarun.kairos.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

// Queryable identity/order fields remain relational. Payloads hold the versioned,
// strongly typed domain records so the scheduler never depends on Android/Room.
@Entity(tableName = "tasks")
data class TaskRow(
    @PrimaryKey val id: String,
    val deadline: Long,
    val status: String,
    val payload: String,
)

@Entity(
    tableName = "subtasks",
    foreignKeys =
        [
            ForeignKey(
                entity = TaskRow::class,
                parentColumns = ["id"],
                childColumns = ["taskId"],
                onDelete = ForeignKey.CASCADE,
            )
        ],
    indices = [Index("taskId")],
)
data class SubtaskRow(
    @PrimaryKey val id: String,
    val taskId: String,
    val position: Int,
    val payload: String,
)

@Entity(tableName = "fixed_blocks")
data class BlockRow(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "sessions", indices = [Index("date"), Index("taskId")])
data class SessionRow(
    @PrimaryKey val id: String,
    val date: String,
    val start: Long,
    val taskId: String?,
    val payload: String,
)

@Entity(tableName = "work_logs", indices = [Index(value = ["sessionId"], unique = true)])
data class LogRow(@PrimaryKey val id: String, val sessionId: String, val payload: String)

@Entity(tableName = "day_contexts")
data class ContextRow(@PrimaryKey val date: String, val payload: String)

@Entity(tableName = "calendar_events")
data class EventRow(@PrimaryKey val id: String, val payload: String)

@Entity(tableName = "estimate_corrections")
data class CorrectionRow(@PrimaryKey val category: String, val payload: String)

@Entity(tableName = "energy_profiles", primaryKeys = ["hour", "weekday"])
data class EnergyRow(val hour: Int, val weekday: Int, val payload: String)

@Dao
interface PlannerDao {
    @Query("SELECT * FROM tasks") suspend fun snapshotTasks(): List<TaskRow>

    @Query("SELECT * FROM subtasks") suspend fun snapshotSubtasks(): List<SubtaskRow>

    @Query("SELECT * FROM fixed_blocks") suspend fun snapshotBlocks(): List<BlockRow>

    @Query("SELECT * FROM sessions") suspend fun snapshotSessions(): List<SessionRow>

    @Query("SELECT * FROM work_logs") suspend fun snapshotLogs(): List<LogRow>

    @Query("SELECT * FROM day_contexts") suspend fun snapshotContexts(): List<ContextRow>

    @Query("SELECT * FROM calendar_events") suspend fun snapshotEvents(): List<EventRow>

    @Query("SELECT * FROM estimate_corrections")
    suspend fun snapshotCorrections(): List<CorrectionRow>

    @Query("SELECT * FROM energy_profiles") suspend fun snapshotEnergy(): List<EnergyRow>

    @Query("SELECT * FROM tasks ORDER BY deadline") fun tasks(): Flow<List<TaskRow>>

    @Query("SELECT * FROM subtasks ORDER BY position") fun subtasks(): Flow<List<SubtaskRow>>

    @Query("SELECT * FROM fixed_blocks") fun blocks(): Flow<List<BlockRow>>

    @Query("SELECT * FROM sessions ORDER BY start") fun sessions(): Flow<List<SessionRow>>

    @Query("SELECT * FROM work_logs") fun logs(): Flow<List<LogRow>>

    @Query("SELECT * FROM day_contexts ORDER BY date") fun contexts(): Flow<List<ContextRow>>

    @Query("SELECT * FROM calendar_events") fun events(): Flow<List<EventRow>>

    @Query("SELECT * FROM estimate_corrections") fun corrections(): Flow<List<CorrectionRow>>

    @Query("SELECT * FROM energy_profiles") fun energy(): Flow<List<EnergyRow>>

    @Upsert suspend fun task(row: TaskRow)

    @Upsert suspend fun subtask(row: SubtaskRow)

    @Upsert suspend fun block(row: BlockRow)

    @Upsert suspend fun sessions(rows: List<SessionRow>)

    @Upsert suspend fun log(row: LogRow)

    @Upsert suspend fun context(row: ContextRow)

    @Upsert suspend fun events(rows: List<EventRow>)

    @Upsert suspend fun correction(row: CorrectionRow)

    @Upsert suspend fun energy(row: EnergyRow)

    @Query("DELETE FROM tasks WHERE id=:id") suspend fun deleteTask(id: String)

    @Query("DELETE FROM subtasks WHERE id=:id") suspend fun deleteSubtask(id: String)

    @Query("DELETE FROM fixed_blocks WHERE id=:id") suspend fun deleteBlock(id: String)

    @Query("DELETE FROM sessions") suspend fun clearSessions()

    @Query("DELETE FROM calendar_events") suspend fun clearEvents()

    @Query("DELETE FROM tasks") suspend fun clearTasks()

    @Query("DELETE FROM fixed_blocks") suspend fun clearBlocks()

    @Query("DELETE FROM work_logs") suspend fun clearLogs()

    @Query("DELETE FROM day_contexts") suspend fun clearContexts()

    @Query("DELETE FROM estimate_corrections") suspend fun clearCorrections()

    @Query("DELETE FROM energy_profiles") suspend fun clearEnergy()
}

@Database(
    entities =
        [
            TaskRow::class,
            SubtaskRow::class,
            BlockRow::class,
            SessionRow::class,
            LogRow::class,
            ContextRow::class,
            EventRow::class,
            CorrectionRow::class,
            EnergyRow::class,
        ],
    version = 1,
    exportSchema = true,
)
abstract class KairosDatabase : RoomDatabase() {
    abstract fun planner(): PlannerDao
}
