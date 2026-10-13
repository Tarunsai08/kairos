package com.tarun.kairos.ui

import android.app.DatePickerDialog
import android.app.TimePickerDialog
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import com.tarun.kairos.scheduler.*
import java.time.*
import java.time.format.DateTimeFormatter
import kotlin.math.roundToInt

fun String.label() = lowercase().replace('_', ' ').replaceFirstChar(Char::titlecase)

@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    supporting: String? = null,
) {
    OutlinedTextField(
        value,
        onChange,
        modifier.fillMaxWidth(),
        label = { Text(label) },
        singleLine = true,
        supportingText = supporting?.let { { Text(it) } },
    )
}

@Composable
fun TimeField(value: String, onChange: (String) -> Unit, label: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val t = LocalTime.parse(value)
            TimePickerDialog(
                    context,
                    { _, h, m -> onChange(LocalTime.of(h, m).toString()) },
                    t.hour,
                    t.minute,
                    false,
                )
                .show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$label · ${LocalTime.parse(value).format(DateTimeFormatter.ofPattern("h:mm a"))}")
    }
}

@Composable
fun DateField(value: String, onChange: (String) -> Unit, label: String) {
    val context = LocalContext.current
    OutlinedButton(
        onClick = {
            val d = LocalDate.parse(value)
            DatePickerDialog(
                    context,
                    { _, y, m, day -> onChange(LocalDate.of(y, m + 1, day).toString()) },
                    d.year,
                    d.monthValue - 1,
                    d.dayOfMonth,
                )
                .show()
        },
        modifier = Modifier.fillMaxWidth(),
    ) {
        Text("$label · $value")
    }
}

@Composable
fun Difficulty(value: Int, change: (Int) -> Unit) {
    Text("Difficulty", style = MaterialTheme.typography.labelLarge)
    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        (1..5).forEach {
            FilterChip(selected = it == value, onClick = { change(it) }, label = { Text("$it") })
        }
    }
}

@Composable
fun <T : Enum<T>> Choice(label: String, value: T, options: Array<T>, change: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        OutlinedButton(onClick = { expanded = true }, modifier = Modifier.fillMaxWidth()) {
            Text("$label · ${value.name.label()}")
        }
        DropdownMenu(expanded, onDismissRequest = { expanded = false }) {
            options.forEach { option ->
                DropdownMenuItem(
                    text = { Text(option.name.label()) },
                    onClick = {
                        change(option)
                        expanded = false
                    },
                )
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TaskEditor(
    task: Task? = null,
    initialSubtasks: List<Subtask> = emptyList(),
    dismiss: () -> Unit,
    save: (Task, List<Subtask>) -> Unit,
) {
    val zone = ZoneId.systemDefault()
    val instant =
        task?.deadline?.let { Instant.ofEpochMilli(it).atZone(zone) }
            ?: LocalDate.now().plusDays(3).atTime(17, 0).atZone(zone)
    var title by remember { mutableStateOf(task?.title ?: "") }
    var category by remember { mutableStateOf(task?.category ?: Category.ASSIGNMENT) }
    var date by remember { mutableStateOf(instant.toLocalDate().toString()) }
    var time by remember { mutableStateOf(instant.toLocalTime().toString()) }
    var difficulty by remember { mutableIntStateOf(task?.difficulty ?: 3) }
    var hours by remember { mutableStateOf(task?.estimatedHours?.toString() ?: "") }
    var notes by remember { mutableStateOf(task?.notes ?: "") }
    var recurring by remember { mutableStateOf(task?.isRecurring ?: false) }
    var rule by remember { mutableStateOf(task?.recurrenceRule ?: "DAILY") }
    var preferred by remember { mutableStateOf(task?.preferredTime ?: "18:00") }
    var newSubtask by remember { mutableStateOf("") }
    var subtasks by remember { mutableStateOf(initialSubtasks.sortedBy { it.order }) }
    var error by remember { mutableStateOf<String?>(null) }
    val id = remember { task?.id ?: newId() }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (task == null) "Make room for a task" else "Edit task",
                style = MaterialTheme.typography.headlineSmall,
            )
            Field(title, { title = it }, "Task name")
            DateField(date, { date = it }, "Deadline")
            TimeField(time, { time = it }, "Due time")
            Difficulty(difficulty) { difficulty = it }
            Choice("Category", category, Category.entries.toTypedArray()) { category = it }
            Field(
                hours,
                { hours = it },
                "Hours (optional)",
                supporting = "Leave blank for an adaptive estimate",
            )
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text("Repeat this task")
                Switch(recurring, { recurring = it })
            }
            if (recurring) {
                Row {
                    FilterChip(rule == "DAILY", { rule = "DAILY" }, label = { Text("Daily") })
                    FilterChip(rule == "WEEKLY", { rule = "WEEKLY" }, label = { Text("Weekly") })
                }
                TimeField(preferred, { preferred = it }, "Preferred slot")
            }
            Text("Steps · optional", style = MaterialTheme.typography.titleSmall)
            subtasks.forEachIndexed { index, s ->
                var displacement by remember(s.id) { mutableFloatStateOf(0f) }
                var cardHeight by remember(s.id) { mutableIntStateOf(1) }
                ElevatedCard(
                    Modifier.onSizeChanged { cardHeight = it.height.coerceAtLeast(1) }
                        .zIndex(if (displacement == 0f) 0f else 1f)
                        .graphicsLayer { translationY = displacement }
                        .pointerInput(s.id, index) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = { displacement = 0f },
                                onDragCancel = { displacement = 0f },
                                onDragEnd = {
                                    val from = subtasks.indexOfFirst { it.id == s.id }
                                    if (from >= 0) {
                                        val to =
                                            (from + (displacement / cardHeight).roundToInt())
                                                .coerceIn(0, subtasks.lastIndex)
                                        val ordered = subtasks.toMutableList()
                                        ordered.add(to, ordered.removeAt(from))
                                        subtasks = ordered.mapIndexed { i, v -> v.copy(order = i) }
                                    }
                                    displacement = 0f
                                },
                                onDrag = { change, amount ->
                                    change.consume()
                                    displacement += amount.y
                                },
                            )
                        }
                ) {
                    Column(Modifier.padding(12.dp)) {
                        Text(s.title)
                        Difficulty(s.difficulty) { n ->
                            subtasks = subtasks.map {
                                if (it.id == s.id) it.copy(difficulty = n) else it
                            }
                        }
                        Row {
                            TextButton(
                                onClick = {
                                    if (index > 0) {
                                        val list = subtasks.toMutableList()
                                        list[index] = list[index - 1]
                                        list[index - 1] = s
                                        subtasks = list.mapIndexed { i, v -> v.copy(order = i) }
                                    }
                                },
                                enabled = index > 0,
                            ) {
                                Text("Move up")
                            }
                            TextButton(onClick = { subtasks = subtasks.filter { it.id != s.id } }) {
                                Text("Remove")
                            }
                            TextButton(
                                onClick = {
                                    subtasks = subtasks.map {
                                        if (it.id == s.id)
                                            it.copy(
                                                status =
                                                    if (s.status == SubtaskStatus.DONE)
                                                        SubtaskStatus.TODO
                                                    else SubtaskStatus.DONE
                                            )
                                        else it
                                    }
                                }
                            ) {
                                Text(if (s.status == SubtaskStatus.DONE) "Reopen" else "Done")
                            }
                        }
                    }
                }
            }
            Field(newSubtask, { newSubtask = it }, "Add a step")
            TextButton(
                onClick = {
                    if (newSubtask.isNotBlank()) {
                        subtasks =
                            subtasks +
                                Subtask(
                                    taskId = id,
                                    title = newSubtask.trim(),
                                    order = subtasks.size,
                                    difficulty = difficulty,
                                )
                        newSubtask = ""
                    }
                },
                enabled = newSubtask.isNotBlank(),
            ) {
                Text("Add step")
            }
            Field(notes, { notes = it }, "Notes (optional)")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    val h = hours.takeIf { it.isNotBlank() }?.toDoubleOrNull()
                    if (title.isBlank()) error = "Give your task a name."
                    else if (
                        hours.isNotBlank() && (h == null || !h.isFinite() || h <= 0 || h > 10000)
                    )
                        error = "Enter a positive number of hours."
                    else {
                        val saved =
                            Task(
                                id,
                                title.trim(),
                                category,
                                LocalDate.parse(date).at(time, zone),
                                difficulty,
                                h,
                                recurring,
                                rule,
                                preferred,
                                task?.status ?: TaskStatus.ACTIVE,
                                task?.createdAt ?: System.currentTimeMillis(),
                                notes,
                            )
                        save(saved, subtasks.mapIndexed { i, s -> s.copy(order = i) })
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save task")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BlockEditor(block: FixedBlock? = null, dismiss: () -> Unit, save: (FixedBlock) -> Unit) {
    var title by remember { mutableStateOf(block?.title ?: "") }
    var type by remember { mutableStateOf(block?.type ?: BlockType.CLASS) }
    var days by remember { mutableStateOf(block?.daysOfWeek ?: (1..5).toSet()) }
    var start by remember { mutableStateOf(block?.startTime ?: "09:00") }
    var end by remember { mutableStateOf(block?.endTime ?: "10:00") }
    var flexible by remember { mutableStateOf(block?.isFlexible ?: false) }
    var fs by remember { mutableStateOf(block?.flexWindowStart ?: "17:00") }
    var fe by remember { mutableStateOf(block?.flexWindowEnd ?: "20:00") }
    var range by remember { mutableStateOf(block?.validFrom != null || block?.validUntil != null) }
    var from by remember { mutableStateOf(block?.validFrom ?: LocalDate.now().toString()) }
    var until by remember {
        mutableStateOf(block?.validUntil ?: LocalDate.now().plusMonths(4).toString())
    }
    var notes by remember { mutableStateOf(block?.notes ?: "") }
    var error by remember { mutableStateOf<String?>(null) }
    ModalBottomSheet(onDismissRequest = dismiss) {
        Column(
            Modifier.padding(horizontal = 24.dp).verticalScroll(rememberScrollState()).imePadding(),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("Your fixed rhythm", style = MaterialTheme.typography.headlineSmall)
            Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                listOf(BlockType.MEAL, BlockType.BATH, BlockType.GYM, BlockType.SLEEP).forEach { t
                    ->
                    SuggestionChip(
                        onClick = {
                            type = t
                            title = t.name.label()
                            days = (1..7).toSet()
                            start =
                                when (t) {
                                    BlockType.SLEEP -> "23:00"
                                    BlockType.MEAL -> "12:30"
                                    BlockType.BATH -> "06:00"
                                    else -> "17:00"
                                }
                            end =
                                when (t) {
                                    BlockType.SLEEP -> "05:30"
                                    BlockType.MEAL -> "13:00"
                                    BlockType.BATH -> "06:30"
                                    else -> "18:00"
                                }
                        },
                        label = { Text(t.name.label()) },
                    )
                }
            }
            Field(title, { title = it }, "Title")
            Choice("Type", type, BlockType.entries.toTypedArray()) { type = it }
            Text("Days", style = MaterialTheme.typography.labelLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                (1..7).forEach { d ->
                    FilterChip(
                        d in days,
                        { days = if (d in days) days - d else days + d },
                        label = { Text(listOf("M", "T", "W", "T", "F", "S", "S")[d - 1]) },
                    )
                }
            }
            TimeField(start, { start = it }, "Start")
            TimeField(end, { end = it }, "End")
            if (LocalTime.parse(end) < LocalTime.parse(start))
                Text("Ends the following day", style = MaterialTheme.typography.bodySmall)
            Row {
                Text("Flexible window", Modifier.weight(1f))
                Switch(flexible, { flexible = it })
            }
            if (flexible) {
                TimeField(fs, { fs = it }, "Earliest start")
                TimeField(fe, { fe = it }, "Latest end")
            }
            Row {
                Text("Semester / date range", Modifier.weight(1f))
                Switch(range, { range = it })
            }
            if (range) {
                DateField(from, { from = it }, "From")
                DateField(until, { until = it }, "Until")
            }
            Field(notes, { notes = it }, "Notes")
            error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Button(
                onClick = {
                    try {
                        save(
                            FixedBlock(
                                id = block?.id ?: newId(),
                                title = title.trim(),
                                type = type,
                                daysOfWeek = days,
                                startTime = start,
                                endTime = end,
                                isFlexible = flexible,
                                flexWindowStart = if (flexible) fs else null,
                                flexWindowEnd = if (flexible) fe else null,
                                validFrom = if (range) from else null,
                                validUntil = if (range) until else null,
                                notes = notes,
                            )
                        )
                    } catch (e: IllegalArgumentException) {
                        error = "Check the title, days, times and date range."
                    }
                },
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save commitment")
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}
