package com.tarun.kairos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.tarun.kairos.scheduler.*
import java.time.*

@Composable
fun ReviewScreen(data: PlannerData) {
    val locale = LocalConfiguration.current.locales[0]
    val today = LocalDate.now()
    val first = today.minusDays(6)
    val zone = ZoneId.systemDefault()
    val sessions =
        data.sessions.filter {
            LocalDate.parse(it.date) in first..today && it.kind in Scheduler.workKinds
        }
    val logs = data.logs.filter { it.completedAt.localDate(zone) in first..today }
    val planned = sessions.sumOf { it.minutes } / 60.0
    val actual = logs.sumOf { it.actualMinutes } / 60.0
    val best =
        data.energy
            .groupBy { it.hourOfDay }
            .mapValues { (_, values) ->
                values.sumOf { it.score * it.samples } /
                    values.sumOf { it.samples }.coerceAtLeast(1)
            }
            .entries
            .sortedByDescending { it.value }
            .take(3)
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(16.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("Notice what helps", style = MaterialTheme.typography.headlineMedium)
            Text("$first – $today · no streaks, no judgment.")
        }
        item {
            ReviewCard("The week you lived") {
                Text(
                    "${String.format(locale, "%.1f",actual)}h of actual focus",
                    style = MaterialTheme.typography.headlineSmall,
                )
                Text(
                    "${String.format(locale, "%.1f",planned)}h planned · ${sessions.count{it.state==SessionState.DONE}} sessions done"
                )
                Text("Actuals include partial sessions. Your real week is useful information.")
            }
        }
        item {
            ReviewCard("When focus felt easier") {
                if (best.isEmpty())
                    Text("Mark a few sessions done, partial or skipped to learn your rhythm.")
                else
                    best.forEach { (hour, score) ->
                        Text(
                            "${LocalTime.of(hour,0)} · ${String.format(locale, "%.0f",score*100)}% learned focus quality"
                        )
                    }
                Text(
                    "Weekday/hour buckets blend with your chosen peaks until 30 samples.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            ReviewCard("Estimates are getting wiser") {
                if (data.corrections.isEmpty())
                    Text("Complete a task or subtask after logging work to calibrate its category.")
                else
                    data.corrections.forEach { c ->
                        Text(
                            "${c.category.name.label()} · ${String.format(locale, "%.2f",c.factor)}× · ${c.samples} observations"
                        )
                    }
                Text(
                    "A factor above 1 means similar work tends to need more time.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        item {
            ReviewCard("Things that need more room") {
                val slipped =
                    data.tasks.filter {
                        it.status == TaskStatus.ACTIVE && it.deadline < System.currentTimeMillis()
                    }
                if (slipped.isEmpty()) Text("No overdue active tasks.")
                else slipped.forEach { Text(it.title) }
                Text(
                    "${sessions.count{it.state==SessionState.SKIPPED}} skipped sessions · ${sessions.count{it.state==SessionState.PARTIAL}} partial sessions"
                )
                Text("Try a smaller step, a clearer scope, or a gentler capacity.")
            }
        }
    }
}

@Composable
private fun ReviewCard(title: String, content: @Composable ColumnScope.() -> Unit) {
    OutlinedCard(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge)
            content()
        }
    }
}
