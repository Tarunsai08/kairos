package com.tarun.kairos.integration

import android.Manifest
import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.os.*
import android.provider.CalendarContract
import androidx.core.content.ContextCompat
import com.tarun.kairos.data.*
import com.tarun.kairos.domain.PlanningCoordinator
import com.tarun.kairos.scheduler.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.*
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

data class DeviceCalendar(val id: Long, val name: String, val account: String)

@Singleton
class CalendarIntegration
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlannerRepository,
    private val settings: SettingsStore,
    private val coordinator: PlanningCoordinator,
) {
    val status = MutableStateFlow("Calendar permission not granted")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null
    private var pending: Job? = null

    fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.READ_CALENDAR) ==
            PackageManager.PERMISSION_GRANTED

    suspend fun calendars(): List<DeviceCalendar> =
        withContext(Dispatchers.IO) {
            if (!permitted()) return@withContext emptyList()
            try {
                context.contentResolver
                    .query(
                        CalendarContract.Calendars.CONTENT_URI,
                        arrayOf(
                            CalendarContract.Calendars._ID,
                            CalendarContract.Calendars.CALENDAR_DISPLAY_NAME,
                            CalendarContract.Calendars.ACCOUNT_NAME,
                        ),
                        null,
                        null,
                        null,
                    )
                    ?.use { c ->
                        buildList {
                            while (c.moveToNext()) add(
                                DeviceCalendar(
                                    c.getLong(0),
                                    c.getString(1) ?: "Calendar",
                                    c.getString(2) ?: "Local",
                                )
                            )
                        }
                    } ?: emptyList()
            } catch (_: SecurityException) {
                status.value = "Calendar permission was revoked"
                emptyList()
            }
        }

    suspend fun sync() =
        withContext(Dispatchers.IO) {
            if (!permitted()) {
                repository.replaceEvents(emptyList())
                status.value = "Calendar access is optional. Your tasks still work."
                return@withContext
            }
            val config = settings.flow.first()
            val zone = ZoneId.systemDefault()
            val today = LocalDate.now()
            val start = today.atStartOfDay(zone).toInstant().toEpochMilli()
            val end = today.plusDays(8).atStartOfDay(zone).toInstant().toEpochMilli()
            if (!config.includeAllCalendars && config.selectedCalendars.isEmpty()) {
                repository.replaceEvents(emptyList())
                status.value = "No calendars selected"
                return@withContext
            }
            val uri =
                CalendarContract.Instances.CONTENT_URI.buildUpon()
                    .apply {
                        ContentUris.appendId(this, start)
                        ContentUris.appendId(this, end)
                    }
                    .build()
            val columns =
                arrayOf(
                    CalendarContract.Instances.EVENT_ID,
                    CalendarContract.Instances.CALENDAR_ID,
                    CalendarContract.Instances.TITLE,
                    CalendarContract.Instances.BEGIN,
                    CalendarContract.Instances.END,
                    CalendarContract.Instances.ALL_DAY,
                )
            val selection =
                if (config.includeAllCalendars) null
                else
                    "${CalendarContract.Instances.CALENDAR_ID} IN (${config.selectedCalendars.joinToString(","){"?"}})"
            val args =
                if (config.includeAllCalendars) null
                else config.selectedCalendars.map { it.toString() }.toTypedArray()
            try {
                val previous = repository.snapshot().events.associateBy { it.id }
                val events =
                    context.contentResolver
                        .query(
                            uri,
                            columns,
                            selection,
                            args,
                            "${CalendarContract.Instances.BEGIN} ASC",
                        )
                        ?.use { c ->
                            buildList {
                                while (c.moveToNext()) {
                                    val eventId = c.getLong(0)
                                    val calendarId = c.getLong(1)
                                    val rawStart = c.getLong(3)
                                    val rawEnd = c.getLong(4)
                                    val allDay = c.getInt(5) == 1
                                    val id = "$calendarId:$eventId:$rawStart"
                                    val s =
                                        if (allDay)
                                            Instant.ofEpochMilli(rawStart)
                                                .atZone(ZoneOffset.UTC)
                                                .toLocalDate()
                                                .atStartOfDay(zone)
                                                .toInstant()
                                                .toEpochMilli()
                                        else rawStart
                                    val e =
                                        if (allDay)
                                            Instant.ofEpochMilli(rawEnd)
                                                .atZone(ZoneOffset.UTC)
                                                .toLocalDate()
                                                .atStartOfDay(zone)
                                                .toInstant()
                                                .toEpochMilli()
                                        else rawEnd
                                    if (e > s)
                                        add(
                                            CalendarEvent(
                                                id,
                                                eventId,
                                                calendarId,
                                                c.getString(2) ?: "Calendar event",
                                                s,
                                                e,
                                                allDay,
                                                treatAs =
                                                    previous[id]?.treatAs ?: EventTreatment.BUSY,
                                            )
                                        )
                                }
                            }
                        } ?: emptyList()
                repository.replaceEvents(events)
                status.value = "${events.size} local calendar events synced"
            } catch (_: SecurityException) {
                repository.replaceEvents(emptyList())
                status.value = "Calendar permission was revoked"
            }
        }

    fun observe() {
        if (observer != null || !permitted()) return
        val value =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    pending?.cancel()
                    pending = scope.launch {
                        delay(500)
                        try {
                            sync()
                            coordinator.replan()
                        } catch (_: SecurityException) {
                            status.value = "Calendar permission was revoked"
                        } catch (_: Exception) {
                            status.value = "Calendar sync couldn't finish. Open the app to retry."
                        }
                    }
                }
            }
        try {
            context.contentResolver.registerContentObserver(
                CalendarContract.Events.CONTENT_URI,
                true,
                value,
            )
            context.contentResolver.registerContentObserver(
                CalendarContract.Calendars.CONTENT_URI,
                true,
                value,
            )
            observer = value
        } catch (_: SecurityException) {
            status.value = "Calendar permission was revoked"
        }
    }

    suspend fun treat(event: CalendarEvent, treatment: EventTreatment) {
        repository.replaceEvents(
            repository.snapshot().events.map {
                if (it.id == event.id) it.copy(treatAs = treatment) else it
            }
        )
        coordinator.replan()
    }

    suspend fun convert(event: CalendarEvent) {
        val cfg = settings.flow.first()
        val title = event.title.removePrefix(cfg.taskPrefix).trim().ifBlank { "Calendar task" }
        val deadline = if (event.isAllDay) event.end - 60_000L else event.end
        repository.save(Task(title = title, deadline = deadline))
        treat(event, EventTreatment.CONVERTED_TO_TASK)
    }
}
