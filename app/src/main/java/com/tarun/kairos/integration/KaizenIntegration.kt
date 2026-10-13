package com.tarun.kairos.integration

import android.content.*
import android.content.pm.PackageManager
import android.database.ContentObserver
import android.net.Uri
import android.os.*
import androidx.core.content.ContextCompat
import androidx.work.*
import com.tarun.kairos.background.CheckinWorker
import com.tarun.kairos.data.*
import com.tarun.kairos.domain.PlanningCoordinator
import com.tarun.kairos.scheduler.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.LocalDate
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

@Singleton
class KaizenIntegration
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlannerRepository,
    private val settings: SettingsStore,
    private val coordinator: PlanningCoordinator,
) {
    val status = MutableStateFlow("Checking Kaizen link")
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var observer: ContentObserver? = null

    suspend fun read(date: LocalDate): DayContext? =
        withContext(Dispatchers.IO) {
            if (!settings.flow.first().kaizenEnabled) {
                status.value = "Kaizen link disabled · manual check-in available"
                return@withContext null
            }
            try {
                @Suppress("DEPRECATION")
                context.packageManager.getPackageInfo("com.tarun.kaizen", 0)
            } catch (_: PackageManager.NameNotFoundException) {
                status.value = "Kaizen not installed · manual check-in available"
                return@withContext null
            }
            if (
                context.packageManager.checkSignatures(context.packageName, "com.tarun.kaizen") !=
                    PackageManager.SIGNATURE_MATCH
            ) {
                status.value = "Sign both apps with the same key to link Kaizen"
                return@withContext null
            }
            if (
                ContextCompat.checkSelfPermission(context, CompanionContract.permission) !=
                    PackageManager.PERMISSION_GRANTED
            ) {
                status.value = "Wellbeing permission unavailable · manual check-in available"
                return@withContext null
            }
            try {
                val resolver = context.contentResolver
                val version =
                    resolver
                        .query(
                            Uri.parse("content://${CompanionContract.authority}/meta"),
                            null,
                            null,
                            null,
                            null,
                        )
                        ?.use { c ->
                            val col = c.getColumnIndex("contractVersion")
                            if (c.moveToFirst() && col >= 0 && !c.isNull(col)) c.getInt(col) else 0
                        } ?: 0
                if (version != 1) {
                    status.value = "Unsupported Kaizen contract · manual check-in available"
                    return@withContext null
                }
                val value =
                    resolver
                        .query(
                            Uri.parse("content://${CompanionContract.authority}/checkins"),
                            null,
                            "date >= ? AND date <= ?",
                            arrayOf(date.toString(), date.toString()),
                            "date DESC",
                        )
                        ?.use { c ->
                            if (!c.moveToFirst()) null
                            else {
                                val fields =
                                    listOf(
                                        "date",
                                        "wake_time",
                                        "bedtime",
                                        "sleep_hours",
                                        "mood_morning",
                                        "mood_night",
                                        "energy",
                                    )
                                val columns = fields.associateWith { name ->
                                    val i = c.getColumnIndex(name)
                                    if (i < 0 || c.isNull(i)) null else c.getString(i)
                                }
                                CompanionContract.checkin(version, columns, date)
                            }
                        }
                if (
                    value == null ||
                        listOf(value.wakeTime, value.sleepHours, value.moodMorning, value.energy)
                            .all { it == null }
                ) {
                    status.value = "No Kaizen check-in for $date · quick check-in available"
                    return@withContext null
                }
                status.value = "Linked · contract v1 · check-in received"
                value
            } catch (_: SecurityException) {
                status.value = "Wellbeing permission denied · manual check-in available"
                null
            } catch (_: RuntimeException) {
                status.value = "Kaizen data unavailable · manual check-in available"
                null
            }
        }

    suspend fun sync(date: LocalDate = LocalDate.now(), force: Boolean = false) {
        val value = read(date) ?: return
        val existing = repository.snapshot().contexts.find { it.date == date.toString() }
        if (force || existing?.source != ContextSource.MANUAL)
            repository.save(value.copy(allowExtra = existing?.allowExtra ?: false))
    }

    fun observe() {
        if (observer != null) return
        val value =
            object : ContentObserver(Handler(Looper.getMainLooper())) {
                override fun onChange(selfChange: Boolean) {
                    scope.launch {
                        try {
                            sync()
                            coordinator.replan()
                        } catch (_: Exception) {
                            status.value = "Kaizen sync will retry when you open the app"
                        }
                    }
                }
            }
        try {
            context.contentResolver.registerContentObserver(
                Uri.parse("content://${CompanionContract.authority}/checkins"),
                true,
                value,
            )
            observer = value
        } catch (_: RuntimeException) {
            /* Linking remains optional; read() reports the precise fallback. */
        }
    }
}

class KaizenCheckinReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != CompanionContract.action) return
        val date =
            intent.getStringExtra("date")?.let { runCatching { LocalDate.parse(it) }.getOrNull() }
                ?: LocalDate.now()
        val request =
            OneTimeWorkRequestBuilder<CheckinWorker>()
                .setInputData(workDataOf("date" to date.toString()))
                .build()
        WorkManager.getInstance(context)
            .enqueueUniqueWork("kaizen-checkin", ExistingWorkPolicy.REPLACE, request)
    }
}
