package com.tarun.kairos.ui

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.tarun.kairos.scheduler.*
import java.time.*
import java.time.temporal.TemporalAdjusters

@Composable
fun FixedGrid(blocks: List<FixedBlock>, edit: (FixedBlock) -> Unit) {
    val zone = ZoneId.systemDefault()
    val monday = LocalDate.now().with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY))
    val density = LocalDensity.current
    val vertical = rememberScrollState(with(density) { 480.dp.roundToPx() })
    val horizontal = rememberScrollState()
    Column(Modifier.fillMaxWidth().height(440.dp).horizontalScroll(horizontal)) {
        Row {
            Spacer(Modifier.width(52.dp))
            (0L..6L).forEach { d ->
                Text(
                    monday.plusDays(d).dayOfWeek.name.take(3),
                    Modifier.width(132.dp).padding(8.dp),
                    style = MaterialTheme.typography.labelLarge,
                )
            }
        }
        Box(Modifier.weight(1f).verticalScroll(vertical)) {
            Row {
                Column(Modifier.width(52.dp)) {
                    (0..23).forEach { h ->
                        Text(
                            "${h.toString().padStart(2,'0')}:00",
                            Modifier.height(96.dp),
                            style = MaterialTheme.typography.labelSmall,
                        )
                    }
                }
                (0L..6L).forEach { offset ->
                    val date = monday.plusDays(offset)
                    val begin = date.atStartOfDay(zone).toInstant().toEpochMilli()
                    val finish = date.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                    Box(Modifier.width(132.dp).height(2304.dp)) {
                        (0..23).forEach { h -> HorizontalDivider(Modifier.offset(y = (h * 96).dp)) }
                        TimeWindows.fixed(date, blocks, zone)
                            .filter { it.second.end > begin && it.second.start < finish }
                            .forEach { (b, interval) ->
                                val start = maxOf(begin, interval.start)
                                val end = minOf(finish, interval.end)
                                val minute =
                                    Instant.ofEpochMilli(start)
                                        .atZone(zone)
                                        .toLocalTime()
                                        .toSecondOfDay() / 60
                                Card(
                                    onClick = { edit(b) },
                                    modifier =
                                        Modifier.offset(y = (minute * 1.6f).dp)
                                            .width(128.dp)
                                            .height(
                                                (minutesBetween(start, end) * 1.6f - 2)
                                                    .coerceAtLeast(24f)
                                                    .dp
                                            )
                                            .semantics {
                                                contentDescription =
                                                    "${b.title}, ${b.startTime} to ${b.endTime}, ${date.dayOfWeek.name.label()}"
                                            },
                                    colors =
                                        CardDefaults.cardColors(
                                            containerColor =
                                                MaterialTheme.colorScheme.surfaceContainerHigh
                                        ),
                                ) {
                                    Column(Modifier.padding(6.dp)) {
                                        Text(
                                            b.title,
                                            style = MaterialTheme.typography.labelMedium,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                        if (minutesBetween(start, end) >= 35)
                                            Text(
                                                "${b.startTime} – ${b.endTime}",
                                                style = MaterialTheme.typography.labelSmall,
                                            )
                                    }
                                }
                            }
                    }
                }
            }
        }
    }
}
