package com.tarun.kairos.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.tarun.kairos.scheduler.*
import java.time.*
import java.time.format.DateTimeFormatter

@Composable
fun TasksScreen(
    data: PlannerData,
    edit: (Task) -> Unit,
    change: (Task) -> Unit,
    risk: Set<String> = emptySet(),
    estimate: (Task) -> String = { it.estimatedHours?.let { h -> "${h}h" } ?: "Adaptive estimate" },
) {
    val zone = ZoneId.systemDefault()
    val today = LocalDate.now()
    val active = data.tasks.filter { it.status == TaskStatus.ACTIVE }
    val groups = active.groupBy {
        val d = it.deadline.localDate(zone)
        if (d <= today) "Today" else if (d <= today.plusDays(7)) "This week" else "Later"
    }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("One thing at a time", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Deadlines give direction. Your plan stays flexible.",
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (active.isEmpty())
            item {
                EmptyCard(
                    "A little space to begin",
                    "Add a task with a name, deadline and difficulty. Kairos will help with the rest.",
                )
            }
        listOf("Today", "This week", "Later").forEach { group ->
            groups[group]?.let { tasks ->
                item { Text(group, style = MaterialTheme.typography.titleMedium) }
                items(tasks.sortedBy { it.deadline }, key = { it.id }) { task ->
                    ElevatedCard(Modifier.fillMaxWidth().clickable { edit(task) }) {
                        Column(
                            Modifier.padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp),
                        ) {
                            Text(task.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "Due ${Instant.ofEpochMilli(task.deadline).atZone(zone).format(DateTimeFormatter.ofPattern("EEE, d MMM · h:mm a"))}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                            Text(estimate(task), style = MaterialTheme.typography.bodyMedium)
                            val subs = data.subtasks.filter { it.taskId == task.id }
                            if (subs.isNotEmpty()) {
                                Text(
                                    "${subs.count{it.status==SubtaskStatus.DONE}} / ${subs.size} steps complete"
                                )
                                LinearProgressIndicator(
                                    progress = {
                                        subs.count { it.status == SubtaskStatus.DONE }.toFloat() /
                                            subs.size
                                    },
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                            if (task.id in risk)
                                Text(
                                    "At risk · let's adjust the scope or capacity",
                                    color = MaterialTheme.colorScheme.error,
                                )
                            Row {
                                TextButton(
                                    onClick = { change(task.copy(status = TaskStatus.DONE)) }
                                ) {
                                    Text("Mark done")
                                }
                                TextButton(
                                    onClick = { change(task.copy(status = TaskStatus.ARCHIVED)) }
                                ) {
                                    Text("Archive")
                                }
                            }
                        }
                    }
                }
            }
        }
        val finished = data.tasks.filter { it.status != TaskStatus.ACTIVE }
        if (finished.isNotEmpty()) {
            item { Text("Finished & archived", style = MaterialTheme.typography.titleMedium) }
            items(finished, key = { it.id }) { t ->
                ListItem(
                    headlineContent = { Text(t.title) },
                    supportingContent = { Text(t.status.name.label()) },
                    trailingContent = {
                        TextButton(onClick = { change(t.copy(status = TaskStatus.ACTIVE)) }) {
                            Text("Reopen")
                        }
                    },
                )
            }
        }
    }
}

@Composable
fun FixedScreen(data: PlannerData, edit: (FixedBlock) -> Unit, delete: (String) -> Unit) {
    var grid by remember { mutableStateOf(true) }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("Your weekly rhythm", style = MaterialTheme.typography.headlineMedium)
            Text("Protect the things that anchor your day.")
            Row {
                FilterChip(grid, { grid = true }, label = { Text("Week grid") })
                Spacer(Modifier.width(8.dp))
                FilterChip(!grid, { grid = false }, label = { Text("List") })
            }
        }
        if (data.blocks.isEmpty())
            item {
                EmptyCard(
                    "Start with your anchors",
                    "Classes, meals, sleep and a little movement. Add them once; your plan works around them.",
                )
            }
        if (grid) item { FixedGrid(data.blocks, edit) }
        if (!grid)
            (1..7).forEach { day ->
                val blocks = data.blocks.filter { day in it.daysOfWeek }.sortedBy { it.startTime }
                item {
                    Text(
                        DayOfWeek.of(day).name.label(),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                if (blocks.isEmpty())
                    item {
                        Text(
                            "No fixed commitments",
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                items(blocks, key = { "$day-${it.id}" }) { b ->
                    OutlinedCard(Modifier.fillMaxWidth().clickable { edit(b) }) {
                        Column(Modifier.padding(16.dp)) {
                            Text(b.title, style = MaterialTheme.typography.titleMedium)
                            Text(
                                "${b.startTime} – ${b.endTime} · ${b.type.name.label()}${if(b.isFlexible)" · Flexible" else ""}"
                            )
                            b.validUntil?.let {
                                Text("Until $it", style = MaterialTheme.typography.bodySmall)
                            }
                            TextButton(onClick = { delete(b.id) }) { Text("Remove") }
                        }
                    }
                }
            }
    }
}

@Composable
fun EmptyCard(title: String, body: String) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            Text(body, style = MaterialTheme.typography.bodyMedium)
        }
    }
}
