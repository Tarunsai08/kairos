package com.tarun.kairos.widgets

import android.appwidget.AppWidgetManager
import android.content.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.*
import androidx.glance.*
import androidx.glance.action.*
import androidx.glance.appwidget.*
import androidx.glance.appwidget.action.*
import androidx.glance.color.ColorProvider as AdaptiveColorProvider
import androidx.glance.layout.*
import androidx.glance.text.*
import androidx.glance.unit.ColorProvider
import androidx.work.*
import com.tarun.kairos.MainActivity
import com.tarun.kairos.background.services
import com.tarun.kairos.scheduler.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.first

private val foreground = AdaptiveColorProvider(Color(0xFF183D2D), Color(0xFFE5F1E9))
private val background = AdaptiveColorProvider(Color(0xFFF1F6F0), Color(0xFF14231A))
private val riskColor = AdaptiveColorProvider(Color(0xFF9B4540), Color(0xFFFFB4AB))
private val sessionKey = ActionParameters.Key<String>("session_id")

private fun Long.time() =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

@Composable
private fun WidgetText(text: String, large: Boolean = false, color: ColorProvider = foreground) {
    Text(
        text,
        style =
            TextStyle(
                color = color,
                fontSize = if (large) 18.sp else 13.sp,
                fontWeight = if (large) FontWeight.Bold else FontWeight.Normal,
            ),
        maxLines = if (large) 2 else 3,
    )
}

abstract class PlannerWidget(private val kind: Int) : GlanceAppWidget() {
    override val sizeMode = SizeMode.Single

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val s = services(context)
        val data = s.repository().snapshot()
        val settings = s.settings().flow.first()
        val now = System.currentTimeMillis()
        val today = LocalDate.now().toString()
        val sessions =
            data.sessions
                .filter {
                    it.state == SessionState.PLANNED &&
                        it.kind in Scheduler.workKinds &&
                        it.end > now
                }
                .sortedBy { it.start }
        val todayWork = data.sessions.filter { it.date == today && it.kind in Scheduler.workKinds }
        val completed = todayWork.count { it.state == SessionState.DONE }
        val zone = ZoneId.systemDefault()
        val day = LocalDate.now(zone)
        val timeline =
            (sessions
                    .filter { it.date == today }
                    .map {
                        Triple(
                            it.start,
                            it.end,
                            data.tasks.find { t -> t.id == it.taskId }?.title ?: "Focus",
                        )
                    } +
                    TimeWindows.fixed(day, data.blocks, zone).map { (b, i) ->
                        Triple(i.start, i.end, b.title)
                    } +
                    data.events
                        .filter {
                            !it.isAllDay &&
                                it.treatAs == EventTreatment.BUSY &&
                                it.start.localDate(zone) == day
                        }
                        .map { Triple(it.start, it.end, it.title) })
                .filter { it.second > now }
                .sortedBy { it.first }
                .take(3)

        val risk = s.planner().latest.value?.atRisk ?: Scheduler().plan(data, settings, now).atRisk
        s.widgets().scheduleRefresh()
        provideContent {
            Column(
                GlanceModifier.fillMaxSize()
                    .background(background)
                    .padding(16.dp)
                    .clickable(actionStartActivity<MainActivity>())
            ) {
                when (kind) {
                    0 -> {
                        WidgetText("KAIROS · NOW / NEXT")
                        Spacer(GlanceModifier.height(10.dp))
                        val next = sessions.firstOrNull()
                        if (next == null) {
                            WidgetText("Room to breathe", true)
                            WidgetText("Open Kairos to plan a small step.")
                        } else {
                            WidgetText(
                                data.tasks.find { it.id == next.taskId }?.title ?: "Focus",
                                true,
                            )
                            WidgetText(
                                if (next.start <= now) "${minutesBetween(now,next.end)} min left"
                                else next.start.time()
                            )
                            Spacer(GlanceModifier.height(6.dp))
                            Button(
                                "Done",
                                onClick =
                                    actionRunCallback<MarkDoneAction>(
                                        actionParametersOf(sessionKey to next.id)
                                    ),
                            )
                        }
                    }
                    1 -> {
                        WidgetText("KAIROS · TODAY", true)
                        Row {
                            Image(
                                provider =
                                    ImageProvider(
                                        progressRing(
                                            if (todayWork.isEmpty()) 0f
                                            else completed.toFloat() / todayWork.size
                                        )
                                    ),
                                contentDescription =
                                    "$completed of ${todayWork.size} sessions complete",
                                modifier = GlanceModifier.size(30.dp),
                            )
                            Spacer(GlanceModifier.width(8.dp))
                            WidgetText("$completed / ${todayWork.size} complete")
                        }
                        Spacer(GlanceModifier.height(8.dp))
                        if (timeline.isEmpty()) WidgetText("Nothing left to chase today.")
                        else
                            timeline.forEach { (start, _, title) ->
                                WidgetText("${start.time()} · $title")
                            }
                    }
                    else -> {
                        WidgetText("KAIROS · DEADLINES")
                        val deadlines =
                            data.tasks
                                .filter { it.status == TaskStatus.ACTIVE }
                                .sortedBy { it.deadline }
                                .take(2)
                        if (deadlines.isEmpty()) WidgetText("An open horizon", true)
                        else
                            deadlines.forEach { task ->
                                WidgetText(
                                    "${task.title} · ${task.deadline.localDate(ZoneId.systemDefault())}${if(task.id in risk)" · At risk" else ""}",
                                    color = if (task.id in risk) riskColor else foreground,
                                )
                            }
                    }
                }
            }
        }
    }
}

class NowWidget : PlannerWidget(0)

class TodayWidget : PlannerWidget(1)

class DeadlinesWidget : PlannerWidget(2)

class NowWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = NowWidget()
}

class TodayWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = TodayWidget()
}

class DeadlinesWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget = DeadlinesWidget()
}

class MarkDoneAction : ActionCallback {
    override suspend fun onAction(
        context: Context,
        glanceId: GlanceId,
        parameters: ActionParameters,
    ) {
        val id = parameters[sessionKey] ?: return
        services(context).planner().finish(id, SessionState.DONE)
    }
}

@Singleton
class WidgetUpdater @Inject constructor(@ApplicationContext private val context: Context) {
    suspend fun refresh() {
        NowWidget().updateAll(context)
        TodayWidget().updateAll(context)
        DeadlinesWidget().updateAll(context)
        scheduleRefresh()
    }

    fun scheduleRefresh() {
        val manager = AppWidgetManager.getInstance(context)
        val installed =
            listOf(
                    NowWidgetReceiver::class.java,
                    TodayWidgetReceiver::class.java,
                    DeadlinesWidgetReceiver::class.java,
                )
                .any { manager.getAppWidgetIds(ComponentName(context, it)).isNotEmpty() }
        val work = WorkManager.getInstance(context)
        if (installed)
            work.enqueueUniquePeriodicWork(
                "widget-refresh",
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<WidgetRefreshWorker>(15, TimeUnit.MINUTES).build(),
            )
        else work.cancelUniqueWork("widget-refresh")
    }
}

class WidgetRefreshWorker(context: Context, params: WorkerParameters) :
    CoroutineWorker(context, params) {
    override suspend fun doWork(): Result {
        val s = services(applicationContext)
        return try {
            s.widgets().refresh()
            Result.success()
        } catch (e: Exception) {
            if (e is CancellationException) throw e
            Result.retry()
        }
    }
}

private fun progressRing(progress: Float): android.graphics.Bitmap {
    val bitmap =
        android.graphics.Bitmap.createBitmap(96, 96, android.graphics.Bitmap.Config.ARGB_8888)
    val canvas = android.graphics.Canvas(bitmap)
    val paint =
        android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG).apply {
            style = android.graphics.Paint.Style.STROKE
            strokeWidth = 9f
            strokeCap = android.graphics.Paint.Cap.ROUND
            color = android.graphics.Color.rgb(181, 202, 185)
        }
    val rect = android.graphics.RectF(8f, 8f, 88f, 88f)
    canvas.drawArc(rect, 0f, 360f, false, paint)
    paint.color = android.graphics.Color.rgb(78, 141, 106)
    canvas.drawArc(rect, -90f, progress.coerceIn(0f, 1f) * 360f, false, paint)
    return bitmap
}
