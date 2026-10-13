package com.tarun.kairos.reminders

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.tarun.kairos.MainActivity
import com.tarun.kairos.R
import com.tarun.kairos.scheduler.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

private val Context.alarmPreferences by preferencesDataStore("alarm_ledger")

@Singleton
class ReminderScheduler @Inject constructor(@ApplicationContext private val context: Context) {
    private val mutex = Mutex()
    private val ledger = stringSetPreferencesKey("scheduled")
    private val alarm = context.getSystemService(AlarmManager::class.java)
    private val notifications = context.getSystemService(NotificationManager::class.java)

    fun exactAllowed() = Build.VERSION.SDK_INT < 31 || alarm.canScheduleExactAlarms()

    fun notificationAllowed() =
        (Build.VERSION.SDK_INT < 33 ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED) && notifications.areNotificationsEnabled()

    private fun token(uri: String) =
        PendingIntent.getBroadcast(
            context,
            0,
            Intent(context, AlarmReceiver::class.java).setData(Uri.parse(uri)),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    suspend fun schedule(data: PlannerData, risk: Set<String>, settings: PlannerSettings) =
        mutex.withLock {
            channels()
            val old = context.alarmPreferences.data.first()[ledger] ?: emptySet()
            old.forEach { alarm.cancel(token(it)) }
            val now = System.currentTimeMillis()
            val entries = linkedMapOf<String, Long>()
            data.sessions
                .filter { it.state == SessionState.PLANNED && it.kind in Scheduler.workKinds }
                .forEach { s ->
                    if (s.start > now) entries["kairos://alarm/start/${Uri.encode(s.id)}"] = s.start
                    if (s.end > now) entries["kairos://alarm/end/${Uri.encode(s.id)}"] = s.end
                }
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now(zone)
            val prefs = context.alarmPreferences.data.first()
            val morning = today.at(settings.wakeTime, zone) + 5 * 60_000L
            if (
                prefs[stringPreferencesKey("last_morning")] != today.toString() &&
                    now < today.at(settings.bedtime, zone)
            )
                entries["kairos://alarm/morning/$today"] = maxOf(now + 5_000L, morning)
            val wrap = today.at(settings.wrapUpTime, zone)
            if (wrap > now) entries["kairos://alarm/wrap/$today"] = wrap
            if (risk.isNotEmpty() && prefs[stringPreferencesKey("last_risk")] != today.toString())
                entries["kairos://alarm/risk/$today"] = now + 60_000L
            entries.forEach { (uri, time) -> set(time, token(uri)) }
            context.alarmPreferences.edit { it[ledger] = entries.keys }
        }

    private fun set(time: Long, token: PendingIntent) {
        try {
            if (exactAllowed())
                alarm.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, token)
            else alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, token)
        } catch (_: SecurityException) {
            alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, time, token)
        }
    }

    fun snooze(id: String) {
        set(
            System.currentTimeMillis() + 10 * 60_000L,
            token("kairos://alarm/start/${Uri.encode(id)}"),
        )
    }

    private fun channels() {
        listOf(
                "morning" to "Morning plan",
                "focus" to "Focus sessions",
                "wrap" to "Evening review",
                "risk" to "Deadline care",
            )
            .forEach { (id, title) ->
                notifications.createNotificationChannel(
                    NotificationChannel(id, title, NotificationManager.IMPORTANCE_DEFAULT)
                )
            }
    }

    private fun action(id: String, kind: String, label: String): NotificationCompat.Action {
        val intent =
            Intent(context, AlarmReceiver::class.java)
                .setData(Uri.parse("kairos://action/$kind/${Uri.encode(id)}"))
        val p =
            PendingIntent.getBroadcast(
                context,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        return NotificationCompat.Action.Builder(0, label, p).build()
    }

    suspend fun show(
        kind: String,
        id: String,
        data: PlannerData,
        settings: PlannerSettings,
        risk: Set<String>,
    ) {
        if (!notificationAllowed()) return
        val now = System.currentTimeMillis()
        val zone = ZoneId.systemDefault()
        val today = LocalDate.now(zone)
        val quiet =
            TimeWindows.fixed(today, data.blocks, zone).any { (b, i) ->
                now >= i.start &&
                    now < i.end &&
                    (b.type == BlockType.SLEEP ||
                        settings.quietDuringClasses && b.type == BlockType.CLASS)
            }
        val fallbackSleep =
            LocalTime.now(zone).let { t ->
                val start = LocalTime.parse(settings.bedtime)
                val end = LocalTime.parse(settings.wakeTime)
                if (start > end) t >= start || t < end else t >= start && t < end
            }
        if (quiet || fallbackSleep) return
        channels()
        val session = data.sessions.find { it.id == id }
        if (
            kind in setOf("start", "end") &&
                (session == null || session.state != SessionState.PLANNED)
        )
            return
        val prefs = context.alarmPreferences.data.first()
        if (
            kind in setOf("morning", "risk") &&
                prefs[stringPreferencesKey("last_$kind")] == today.toString()
        )
            return
        val channel =
            when (kind) {
                "start",
                "end" -> "focus"
                else -> kind
            }
        val work =
            data.sessions.filter {
                it.date == today.toString() &&
                    it.state == SessionState.PLANNED &&
                    it.kind in Scheduler.workKinds
            }
        val title =
            when (kind) {
                "start",
                "end" -> data.tasks.find { it.id == session?.taskId }?.title ?: "Your focus session"
                "morning" -> "Your plan is ready"
                "risk" -> "A deadline needs a little care"
                else -> "A gentle evening check-in"
            }
        val text =
            when (kind) {
                "start" -> session?.reason ?: "One manageable step"
                "end" -> "How did this session go?"
                "morning" ->
                    "${work.size} focus sessions${work.firstOrNull()?.let{" · first at ${Instant.ofEpochMilli(it.start).atZone(zone).toLocalTime()}"} ?: " · room to breathe"}"
                "risk" -> "${risk.size} tasks may need more time or a smaller scope."
                else -> "Mark what you finished today. Small steps count."
            }
        val open =
            PendingIntent.getActivity(
                context,
                0,
                Intent(context, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
        val builder =
            NotificationCompat.Builder(context, channel)
                .setSmallIcon(R.drawable.ic_notification)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setContentIntent(open)
                .setAutoCancel(true)
                .setOnlyAlertOnce(true)
        if (kind == "start") {
            builder.addAction(action(id, "start", "Start"))
            builder.addAction(action(id, "snooze", "Snooze 10 min"))
            builder.addAction(action(id, "skip", "Skip"))
        }
        if (kind == "end") {
            builder.addAction(action(id, "done", "Done"))
            builder.addAction(action(id, "extend", "Need 15 more min"))
            builder.addAction(action(id, "partial", "Partially done"))
        }
        notifications.notify((kind + id).hashCode(), builder.build())
        if (kind in setOf("morning", "risk"))
            context.alarmPreferences.edit {
                it[stringPreferencesKey("last_$kind")] = today.toString()
            }
    }

    fun dismiss(id: String) {
        notifications.cancel(("start" + id).hashCode())
        notifications.cancel(("end" + id).hashCode())
    }
}
