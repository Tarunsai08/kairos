package com.tarun.kairos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tarun.kairos.scheduler.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun Long.clock(): String =
    Instant.ofEpochMilli(this)
        .atZone(ZoneId.systemDefault())
        .format(DateTimeFormatter.ofPattern("h:mm a"))

private data class TimelineItem(
    val id: String,
    val start: Long,
    val end: Long,
    val title: String,
    val note: String,
    val session: PlannedSession? = null,
)

@Composable
fun TodayScreen(
    data: PlannerData,
    settings: PlannerSettings,
    plan: PlanResult?,
    vm: PlannerViewModel,
    edit: (PlannedSession) -> Unit,
    checkin: () -> Unit,
    editTask: (Task) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    val now by vm.now.collectAsStateWithLifecycle()
    val context = data.contexts.find { it.date == today.toString() }
    val sessions = data.sessions.filter { it.date == today.toString() }
    val next =
        sessions.firstOrNull {
            it.state == SessionState.PLANNED &&
                it.kind in Scheduler.workKinds &&
                it.startedAt != null
        }
            ?: sessions.firstOrNull {
                it.state == SessionState.PLANNED && it.kind in Scheduler.workKinds && it.end > now
            }
    LaunchedEffect(today) { vm.perform { vm.coordinator.replan() } }
    val kaizenStatus by vm.kaizen.status.collectAsStateWithLifecycle()
    val haptic = LocalHapticFeedback.current
    val events =
        data.events.filter {
            it.start < today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() &&
                it.end > today.atStartOfDay(zone).toInstant().toEpochMilli()
        }
    val timeline =
        (TimeWindows.fixed(today, data.blocks, zone)
                .filter {
                    it.second.end > today.atStartOfDay(zone).toInstant().toEpochMilli() &&
                        it.second.start <
                            today.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                }
                .map { (b, i) ->
                    TimelineItem(b.id + i.start, i.start, i.end, b.title, b.type.name.label())
                } +
                events
                    .filter { !it.isAllDay && it.treatAs == EventTreatment.BUSY }
                    .map { TimelineItem(it.id, it.start, it.end, it.title, "Calendar") } +
                sessions.map { s ->
                    TimelineItem(
                        s.id,
                        s.start,
                        s.end,
                        when (s.kind) {
                            SessionKind.BREAK -> "Take a breath"
                            SessionKind.PLAN_SUBTASKS -> "Break this into steps?"
                            SessionKind.BUFFER -> "A little breathing room"
                            else ->
                                data.tasks
                                    .find { it.id == s.taskId }
                                    ?.let { t ->
                                        data.subtasks.find { it.id == s.subtaskId }?.title
                                            ?: t.title
                                    } ?: "Focus"
                        },
                        s.reason,
                        s,
                    )
                })
            .sortedBy { it.start }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text(
                today.format(DateTimeFormatter.ofPattern("EEEE, d MMM")),
                style = MaterialTheme.typography.labelLarge,
            )
            Text("A day with room to breathe", style = MaterialTheme.typography.headlineMedium)
        }
        item {
            AssistChip(
                onClick = checkin,
                label = {
                    Text(
                        if (context == null || context.source == ContextSource.DEFAULT)
                            "Quick check-in · how are you today?"
                        else
                            "Mood ${context.moodMorning ?: "—"} · Slept ${context.sleepHours?.let{String.format(locale, "%.1f",it)} ?: "—"}h · ${context.source.name.label()}"
                    )
                },
            )
        }
        item {
            Text(
                kaizenStatus,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (context?.lowDay == true)
            item {
                Text(
                    "Lighter plan today. You've got this.",
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        if ((context?.moodMorning ?: 0) >= 4 && (context?.sleepHours ?: 0.0) >= 7)
            item {
                TextButton(
                    onClick = {
                        vm.perform {
                            context?.let {
                                vm.repository.save(it.copy(allowExtra = true))
                                vm.coordinator.replan()
                            }
                        }
                    }
                ) {
                    Text("Feeling good? Pull a little work forward")
                }
            }
        events
            .filter { it.isAllDay }
            .forEach { e ->
                item {
                    OutlinedCard(Modifier.fillMaxWidth()) {
                        Text("All day · ${e.title}", Modifier.padding(12.dp))
                    }
                }
            }
        item {
            if (next == null)
                EmptyCard(
                    "A clear moment",
                    "${if(data.tasks.none{it.status==TaskStatus.ACTIVE})"Add a task when you're ready." else "No focus sessions left today. You can adjust your capacity or rest."}",
                )
            else {
                Card(
                    colors =
                        CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        ),
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Column(
                        Modifier.padding(20.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            if (next.start <= now) "NOW" else "UP NEXT",
                            style = MaterialTheme.typography.labelLarge,
                        )
                        Text(
                            data.tasks.find { it.id == next.taskId }?.title ?: "Focus",
                            style = MaterialTheme.typography.headlineSmall,
                        )
                        Text("${next.start.clock()} – ${next.end.clock()}")
                        Text(next.reason)
                        Row {
                            Button(onClick = { vm.start(next.id) }) {
                                Text(if (next.startedAt != null) "Started" else "Start")
                            }
                            TextButton(
                                onClick = {
                                    haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                                    edit(next)
                                }
                            ) {
                                Text("Done / partial")
                            }
                            TextButton(onClick = { vm.finish(next.id, SessionState.SKIPPED) }) {
                                Text("Skip")
                            }
                        }
                        TextButton(onClick = { vm.swap(next.id) }) {
                            Text("Swap with another task")
                        }
                    }
                }
            }
        }
        if (plan?.atRisk?.isNotEmpty() == true)
            item {
                OutlinedCard {
                    Column(Modifier.padding(16.dp)) {
                        Text(
                            "Some deadlines need a little care",
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.error,
                        )
                        Text(
                            "Increase capacity in Settings, adjust a flexible commitment, lower the task's scope, or accept partial completion. Every unfinished task stays visible."
                        )
                    }
                }
            }
        item {
            Text("Your timeline", style = MaterialTheme.typography.titleLarge)
            Text(
                "Hold a focus block to move it. Drag vertically to shift by 15-minute steps.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        if (timeline.isEmpty())
            item {
                EmptyCard(
                    "An open day",
                    "Add your fixed rhythm and a task. Your suggested timeline will appear here.",
                )
            }
        items(timeline, key = { it.id }) { item ->
            val s = item.session
            val density = LocalDensity.current
            val step = with(density) { 32.dp.toPx() }
            var drag by remember(s?.id) { mutableFloatStateOf(0f) }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(
                    item.start.clock(),
                    Modifier.width(70.dp),
                    style = MaterialTheme.typography.labelMedium,
                )
                val interaction =
                    if (
                        s != null &&
                            s.kind in Scheduler.workKinds &&
                            s.state == SessionState.PLANNED
                    )
                        Modifier.clickable(onClick = { edit(s) }).pointerInput(s.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { drag = 0f },
                                onDragEnd = {
                                    val steps = (drag / step).roundToInt()
                                    if (steps == 0) edit(s)
                                    else vm.move(s.id, s.start + steps * 15 * 60_000L, s.minutes)
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    drag += amount.y
                                },
                            )
                        }
                    else if (s?.kind == SessionKind.PLAN_SUBTASKS)
                        Modifier.combinedClickable(
                            onClick = { data.tasks.find { it.id == s.taskId }?.let(editTask) },
                            onLongClick = { data.tasks.find { it.id == s.taskId }?.let(editTask) },
                        )
                    else Modifier
                OutlinedCard(
                    Modifier.weight(1f).then(interaction).semantics {
                        contentDescription =
                            "${item.title}, ${item.start.clock()} to ${item.end.clock()}${if(s?.isPinned==true)", pinned" else ""}"
                    },
                    colors =
                        CardDefaults.outlinedCardColors(
                            containerColor =
                                if (s?.kind in Scheduler.workKinds)
                                    MaterialTheme.colorScheme.secondaryContainer
                                else MaterialTheme.colorScheme.surfaceContainerLow
                        ),
                ) {
                    Column(
                        Modifier.padding(14.dp),
                        verticalArrangement = Arrangement.spacedBy(5.dp),
                    ) {
                        Text(item.title, style = MaterialTheme.typography.titleSmall)
                        Text(item.note, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "${item.end.clock()}${s?.let{" · ${it.state.name.label()}${if(it.isPinned)" · Pinned" else ""}"} ?: ""}",
                            style = MaterialTheme.typography.labelSmall,
                        )
                        if (drag != 0f) Text("Shift ${(drag/step).roundToInt()*15} minutes")
                    }
                }
            }
        }
    }
}

@Composable
fun WeekScreen(data: PlannerData, settings: PlannerSettings, plan: PlanResult?) {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val zone = ZoneId.systemDefault()
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("A little ahead", style = MaterialTheme.typography.headlineMedium)
            Text("The week is a suggestion, not a promise.")
        }
        (0L..6L).forEach { offset ->
            val date = today.plusDays(offset)
            val sessions =
                data.sessions.filter {
                    it.date == date.toString() && it.kind in Scheduler.workKinds
                }
            val minutes = sessions.sumOf { it.minutes }
            val deadlines =
                data.tasks.filter {
                    it.status == TaskStatus.ACTIVE && it.deadline.localDate(zone) == date
                }
            item {
                OutlinedCard(Modifier.fillMaxWidth()) {
                    Column(
                        Modifier.padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Text(
                            date.format(DateTimeFormatter.ofPattern("EEEE · d MMM")),
                            style = MaterialTheme.typography.titleLarge,
                        )
                        Text(
                            "${String.format(locale, "%.1f",minutes/60.0)} focus hours · ${sessions.size} sessions"
                        )
                        LinearProgressIndicator(
                            progress = {
                                (minutes / (settings.capacityHours * 60)).toFloat().coerceIn(0f, 1f)
                            },
                            modifier = Modifier.fillMaxWidth(),
                        )
                        sessions.take(3).forEach { s ->
                            Text(
                                "${s.start.clock()} · ${data.tasks.find{it.id==s.taskId}?.title ?: "Focus"}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        deadlines.forEach { t ->
                            Text(
                                "Due · ${t.title}${if(t.id in (plan?.atRisk ?: emptySet()))" · At risk" else ""}",
                                color =
                                    if (t.id in (plan?.atRisk ?: emptySet()))
                                        MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurface,
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SessionEditor(session: PlannedSession, dismiss: () -> Unit, vm: PlannerViewModel) {
    var date by remember { mutableStateOf(session.date) }
    var time by remember {
        mutableStateOf(
            Instant.ofEpochMilli(session.start)
                .atZone(ZoneId.systemDefault())
                .toLocalTime()
                .toString()
        )
    }
    var duration by remember { mutableStateOf(session.minutes.toString()) }
    var actual by remember {
        mutableStateOf(
            session.startedAt?.let {
                minutesBetween(it, System.currentTimeMillis()).coerceAtLeast(1).toString()
            } ?: session.minutes.toString()
        )
    }
    var difficulty by remember { mutableIntStateOf(3) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Make this session yours", style = MaterialTheme.typography.headlineSmall)
            Text(session.reason)
            Row {
                TextButton(
                    onClick = {
                        vm.pin(session.id)
                        dismiss()
                    }
                ) {
                    Text(if (session.isPinned) "Unpin" else "Pin")
                }
                TextButton(
                    onClick = {
                        vm.extend(session.id)
                        dismiss()
                    }
                ) {
                    Text("Need 15 more min")
                }
                TextButton(
                    onClick = {
                        vm.finish(session.id, SessionState.SKIPPED)
                        dismiss()
                    }
                ) {
                    Text("Skip")
                }
            }
            DateField(date, { date = it }, "Move to")
            TimeField(time, { time = it }, "Start")
            Field(duration, { duration = it }, "Duration (15–180 minutes)")
            Button(
                onClick = {
                    duration
                        .toIntOrNull()
                        ?.takeIf { it in 15..180 }
                        ?.let {
                            vm.move(
                                session.id,
                                LocalDate.parse(date).at(time, ZoneId.systemDefault()),
                                it,
                            )
                            dismiss()
                        }
                },
                enabled = duration.toIntOrNull() in 15..180,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Move & pin")
            }
            HorizontalDivider()
            Field(actual, { actual = it }, "Actual minutes")
            Difficulty(difficulty) { difficulty = it }
            Row {
                Button(
                    onClick = {
                        vm.finish(session.id, SessionState.DONE, actual.toIntOrNull(), difficulty)
                        dismiss()
                    },
                    enabled = actual.toIntOrNull() in 0..1440,
                ) {
                    Text("Done")
                }
                TextButton(
                    onClick = {
                        vm.finish(
                            session.id,
                            SessionState.PARTIAL,
                            actual.toIntOrNull(),
                            difficulty,
                        )
                        dismiss()
                    },
                    enabled = actual.toIntOrNull() in 0..1440,
                ) {
                    Text("Partially done")
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckinEditor(existing: DayContext?, dismiss: () -> Unit, save: (DayContext) -> Unit) {
    var wake by remember { mutableStateOf(existing?.wakeTime ?: "05:30") }
    var mood by remember { mutableIntStateOf(existing?.moodMorning ?: 3) }
    var energy by remember { mutableIntStateOf(existing?.energy ?: 3) }
    var sleep by remember { mutableStateOf(existing?.sleepHours?.toString() ?: "") }
    var low by remember { mutableStateOf(existing?.lowEnergy ?: false) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("How are you arriving today?", style = MaterialTheme.typography.headlineSmall)
            TimeField(wake, { wake = it }, "Woke up")
            Text("Morning mood · 1 low, 5 great")
            Row {
                (1..5).forEach { n -> FilterChip(mood == n, { mood = n }, label = { Text("$n") }) }
            }
            Text("Energy")
            Row {
                (1..5).forEach { n ->
                    FilterChip(energy == n, { energy = n }, label = { Text("$n") })
                }
            }
            Field(sleep, { sleep = it }, "Hours slept (optional)")
            Row {
                Text("Low energy today", Modifier.weight(1f))
                Switch(low, { low = it })
            }
            Button(
                onClick = {
                    save(
                        DayContext(
                            LocalDate.now().toString(),
                            wakeTime = wake,
                            sleepHours = sleep.toDoubleOrNull(),
                            moodMorning = mood,
                            energy = energy,
                            source = ContextSource.MANUAL,
                            lowEnergy = low,
                        )
                    )
                },
                enabled =
                    sleep.isBlank() ||
                        sleep.toDoubleOrNull()?.let { it.isFinite() && it in 0.0..24.0 } == true,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Use a gentler plan")
            }
        }
    }
}
