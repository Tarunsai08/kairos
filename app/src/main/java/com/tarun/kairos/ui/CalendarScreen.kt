package com.tarun.kairos.ui

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tarun.kairos.scheduler.*

@Composable
fun CalendarScreen(data: PlannerData, settings: PlannerSettings, vm: PlannerViewModel) {
    val calendars by vm.calendars.collectAsStateWithLifecycle()
    val status by vm.calendar.status.collectAsStateWithLifecycle()
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.refresh()
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("A calendar that stays local", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Reads events already synced on your phone. Google Tasks are not available through Android's calendar provider."
            )
            Text(status)
        }
        item {
            Button(
                onClick = {
                    if (vm.calendar.permitted()) vm.refresh()
                    else permission.launch(Manifest.permission.READ_CALENDAR)
                }
            ) {
                Text(if (vm.calendar.permitted()) "Sync local events" else "Allow calendar access")
            }
        }
        item {
            Row {
                Text("Include all calendars", Modifier.weight(1f))
                Switch(
                    settings.includeAllCalendars,
                    { vm.updateSettings(settings.copy(includeAllCalendars = it)) },
                )
            }
        }
        items(calendars, key = { it.id }) { c ->
            Row {
                Column(Modifier.weight(1f)) {
                    Text(c.name)
                    Text(c.account, style = MaterialTheme.typography.bodySmall)
                }
                Checkbox(
                    settings.includeAllCalendars || c.id in settings.selectedCalendars,
                    { checked ->
                        vm.updateSettings(
                            settings.copy(
                                includeAllCalendars = false,
                                selectedCalendars =
                                    (if (settings.includeAllCalendars)
                                            calendars.map { it.id }.toSet()
                                        else settings.selectedCalendars)
                                        .let { selected ->
                                            if (checked) selected + c.id else selected - c.id
                                        },
                            )
                        )
                    },
                )
            }
        }
        item {
            Text("Events", style = MaterialTheme.typography.titleLarge)
            Text("Titles starting with ${settings.taskPrefix} are suggested as tasks.")
        }
        if (data.events.isEmpty())
            item {
                EmptyCard(
                    "No local events yet",
                    "Enable calendar access and check that your phone's calendar has synced.",
                )
            }
        items(data.events, key = { it.id }) { e ->
            OutlinedCard(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(e.title, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (e.isAllDay) "All day · banner only"
                        else "${e.start.clock()} – ${e.end.clock()}"
                    )
                    Text(e.treatAs.name.label())
                    if (
                        e.title.startsWith(settings.taskPrefix) &&
                            e.treatAs != EventTreatment.CONVERTED_TO_TASK
                    )
                        Text("Suggested task", color = MaterialTheme.colorScheme.primary)
                    Row {
                        TextButton(
                            onClick = { vm.perform { vm.calendar.convert(e) } },
                            enabled = e.treatAs != EventTreatment.CONVERTED_TO_TASK,
                        ) {
                            Text("Turn into task")
                        }
                        TextButton(
                            onClick = {
                                vm.perform {
                                    vm.calendar.treat(
                                        e,
                                        if (e.treatAs == EventTreatment.IGNORE) EventTreatment.BUSY
                                        else EventTreatment.IGNORE,
                                    )
                                }
                            }
                        ) {
                            Text(
                                if (e.treatAs == EventTreatment.IGNORE) "Treat as busy"
                                else "Ignore"
                            )
                        }
                    }
                }
            }
        }
    }
}
