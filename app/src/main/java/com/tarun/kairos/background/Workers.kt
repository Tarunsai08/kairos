package com.tarun.kairos.background

import android.content.Context
import androidx.work.*
import com.tarun.kairos.data.*
import com.tarun.kairos.domain.PlanningCoordinator
import com.tarun.kairos.integration.CalendarIntegration
import com.tarun.kairos.integration.KaizenIntegration
import com.tarun.kairos.scheduler.*
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.*
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

@EntryPoint
@InstallIn(SingletonComponent::class)
interface BackgroundServices {
    fun backup(): com.tarun.kairos.backup.BackupService

    fun widgets(): com.tarun.kairos.widgets.WidgetUpdater

    fun reminders(): com.tarun.kairos.reminders.ReminderScheduler

    fun kaizen(): KaizenIntegration

    fun calendar(): CalendarIntegration

    fun planner(): PlanningCoordinator

    fun settings(): SettingsStore

    fun repository(): PlannerRepository
}

fun services(context: Context) =
    EntryPointAccessors.fromApplication(context.applicationContext, BackgroundServices::class.java)

object WorkSchedules {
    fun weeklyBackup(context: Context, settings: PlannerSettings) {
        val manager = WorkManager.getInstance(context)
        if (settings.backupFolder == null) manager.cancelUniqueWork("weekly-backup")
        else
            manager.enqueueUniquePeriodicWork(
                "weekly-backup",
                ExistingPeriodicWorkPolicy.UPDATE,
                PeriodicWorkRequestBuilder<WeeklyBackupWorker>(7, TimeUnit.DAYS)
                    .setInitialDelay(7, TimeUnit.DAYS)
                    .build(),
            )
    }

    fun daily(context: Context, settings: PlannerSettings) {
        val zone = ZoneId.systemDefault()
        val now = ZonedDateTime.now(zone)
        var next = now.toLocalDate().atTime(LocalTime.parse(settings.wakeTime)).atZone(zone)
        if (!next.isAfter(now)) next = next.plusDays(1)
        val request =
            OneTimeWorkRequestBuilder<DailyPlanWorker>()
                .setInitialDelay(Duration.between(now, next).toMillis(), TimeUnit.MILLISECONDS)
                .addTag("daily-plan")
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "daily-plan-${next.toLocalDate()}",
                ExistingWorkPolicy.REPLACE,
                request,
            )
    }
}

class DailyPlanWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = services(applicationContext)
        return try {
            s.calendar().sync()
            s.kaizen().sync()
            s.planner().replan()
            WorkSchedules.daily(applicationContext, s.settings().flow.first())
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.retry()
        }
    }
}

class CheckinWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = services(applicationContext)
        return try {
            val date = inputData.getString("date")?.let { LocalDate.parse(it) } ?: LocalDate.now()
            s.kaizen().sync(date)
            s.planner().replan()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.retry()
        }
    }
}

class ClockRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = services(applicationContext)
        return try {
            s.calendar().sync()
            s.kaizen().sync()
            s.planner().replan()
            WorkSchedules.daily(applicationContext, s.settings().flow.first())
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.retry()
        }
    }
}

class WeeklyBackupWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        return try {
            services(applicationContext).backup().automatic()
            Result.success()
        } catch (e: SecurityException) {
            Result.failure()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.retry()
        }
    }
}
