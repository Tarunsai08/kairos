package com.tarun.kairos.reminders

import android.content.*
import androidx.work.*
import com.tarun.kairos.background.*
import com.tarun.kairos.scheduler.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

class AlarmReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val uri = intent.data ?: return
        val kind = uri.pathSegments.getOrNull(0) ?: return
        val id = uri.pathSegments.getOrNull(1) ?: return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val s = services(context)
                val reminders = s.reminders()
                if (uri.host == "action") {
                    when (kind) {
                        "start" -> s.planner().start(id)
                        "snooze" -> reminders.snooze(id)
                        "skip" -> s.planner().finish(id, SessionState.SKIPPED)
                        "done" -> s.planner().finish(id, SessionState.DONE)
                        "partial" -> {
                            val session = s.repository().snapshot().sessions.find { it.id == id }
                            s.planner()
                                .finish(
                                    id,
                                    SessionState.PARTIAL,
                                    session?.startedAt?.let {
                                        minutesBetween(it, System.currentTimeMillis())
                                    } ?: ((session?.minutes ?: 0) / 2),
                                )
                        }
                        "extend" -> s.planner().extend(id, 15)
                    }
                    reminders.dismiss(id)
                } else {
                    val data = s.repository().snapshot()
                    val settings = s.settings().flow.first()
                    val risk =
                        s.planner().latest.value?.atRisk
                            ?: Scheduler().plan(data, settings, System.currentTimeMillis()).atRisk
                    reminders.show(kind, id, data, settings, risk)
                    s.widgets().refresh()
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
            } finally {
                pending.finish()
            }
        }
    }
}

class ClockChangeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (
            intent.action !in
                setOf(
                    Intent.ACTION_BOOT_COMPLETED,
                    Intent.ACTION_TIMEZONE_CHANGED,
                    Intent.ACTION_TIME_CHANGED,
                    Intent.ACTION_MY_PACKAGE_REPLACED,
                    "android.app.action.SCHEDULE_EXACT_ALARM_PERMISSION_STATE_CHANGED",
                )
        )
            return
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "clock-refresh",
                ExistingWorkPolicy.REPLACE,
                OneTimeWorkRequestBuilder<ClockRefreshWorker>().build(),
            )
    }
}
