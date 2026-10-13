package com.tarun.kairos.ui

import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tarun.kairos.scheduler.*
import java.time.LocalDate
import kotlin.math.roundToInt

@Composable
fun NumberSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    steps: Int,
    unit: String = "minutes",
    change: (Float) -> Unit,
) {
    val locale = LocalConfiguration.current.locales[0]
    Text(
        "$label · ${if(unit=="hours")String.format(locale, "%.1f",value)else value.roundToInt().toString()} $unit",
        style = MaterialTheme.typography.titleSmall,
    )
    Slider(value, change, valueRange = range, steps = steps)
}

@Composable
fun PeakPicker(peaks: Set<Int>, change: (Set<Int>) -> Unit) {
    Text("When does focus feel easier?", style = MaterialTheme.typography.titleSmall)
    (0..23).toList().chunked(6).forEach { row ->
        Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            row.forEach { h ->
                FilterChip(
                    h in peaks,
                    { change(if (h in peaks) peaks - h else peaks + h) },
                    label = { Text("${h.toString().padStart(2,'0')}") },
                )
            }
        }
    }
    Text("Hours use your device's local time.", style = MaterialTheme.typography.bodySmall)
}

@Composable
fun SettingsScreen(settings: PlannerSettings, vm: PlannerViewModel, navigate: (Int) -> Unit) {
    var draft by remember(settings) { mutableStateOf(settings) }
    var multipliers by
        remember(settings) {
            mutableStateOf(
                Category.entries.associateWith {
                    (settings.categoryMultipliers[it] ?: 1.0).toString()
                }
            )
        }
    val context = LocalContext.current
    val pending by vm.pendingBackup.collectAsStateWithLifecycle()
    val status by
        vm.backup.status.collectAsStateWithLifecycle(initialValue = "Checking backup status")
    val link by vm.kaizen.status.collectAsStateWithLifecycle()
    val export =
        rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/json")
        ) {
            it?.let(vm::export)
        }
    val restore =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {
            it?.let(vm::import)
        }
    val folder =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.takePersistableUriPermission(
                        uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or
                            Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
                    )
                    vm.updateSettings(settings.copy(backupFolder = uri.toString()))
                } catch (_: SecurityException) {
                    vm.message.value =
                        "That folder cannot grant ongoing access. Choose another folder."
                }
            }
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("Make Kairos fit you", style = MaterialTheme.typography.headlineMedium)
            Text("A realistic plan starts with your rhythm.")
        }
        item {
            TimeField(draft.wakeTime, { draft = draft.copy(wakeTime = it) }, "Typical wake time")
            TimeField(draft.bedtime, { draft = draft.copy(bedtime = it) }, "Typical bedtime")
            Text(
                "A Sleep commitment sets the planned bedtime when one is present.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            NumberSlider("Focus session", draft.sessionMinutes.toFloat(), 40f..60f, 19) {
                draft = draft.copy(sessionMinutes = it.roundToInt())
            }
            NumberSlider("Short break", draft.breakMinutes.toFloat(), 5f..30f, 24) {
                draft = draft.copy(breakMinutes = it.roundToInt())
            }
            NumberSlider("Long break", draft.longBreakMinutes.toFloat(), 10f..45f, 34) {
                draft = draft.copy(longBreakMinutes = it.roundToInt())
            }
        }
        item {
            NumberSlider(
                "Daily focus capacity",
                draft.capacityHours.toFloat(),
                0.5f..16f,
                30,
                "hours",
            ) {
                draft = draft.copy(capacityHours = (it * 2).roundToInt() / 2.0)
            }
            NumberSlider("Transition buffer", draft.transitionMinutes.toFloat(), 0f..30f, 29) {
                draft = draft.copy(transitionMinutes = it.roundToInt())
            }
            NumberSlider("Deadline buffer", draft.deadlineBufferDays.toFloat(), 0f..7f, 6, "days") {
                draft = draft.copy(deadlineBufferDays = it.roundToInt())
            }
        }
        item { PeakPicker(draft.peaks) { draft = draft.copy(peaks = it) } }
        item {
            Text("Category estimate multipliers", style = MaterialTheme.typography.titleLarge)
            Text("These starting guesses are refined as you complete work.")
        }
        Category.entries.forEach { category ->
            item {
                Field(
                    multipliers.getValue(category),
                    { text -> multipliers = multipliers + (category to text) },
                    category.name.label(),
                )
            }
        }
        item {
            Text("Appearance", style = MaterialTheme.typography.titleLarge)
            Row {
                listOf("SYSTEM", "LIGHT", "DARK").forEach { t ->
                    FilterChip(
                        draft.theme == t,
                        { draft = draft.copy(theme = t) },
                        label = { Text(t.label()) },
                    )
                }
            }
        }
        item {
            Text("Kaizen", style = MaterialTheme.typography.titleLarge)
            Text(link)
            Row {
                Text("Use Kaizen check-ins", Modifier.weight(1f))
                Switch(draft.kaizenEnabled, { draft = draft.copy(kaizenEnabled = it) })
            }
            TextButton(
                onClick = {
                    vm.perform {
                        vm.kaizen.sync(force = true)
                        vm.coordinator.replan()
                    }
                }
            ) {
                Text("Read today's Kaizen check-in")
            }
            Text(
                "A manual check-in stays in charge until you choose to read Kaizen again.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
        item {
            Field(draft.taskPrefix, { draft = draft.copy(taskPrefix = it) }, "Calendar task prefix")
            Row {
                TextButton(onClick = { navigate(4) }) { Text("Manage calendars") }
                TextButton(onClick = { navigate(5) }) { Text("Reminders & quiet hours") }
            }
        }
        item {
            val valid =
                multipliers.values.all {
                    it.toDoubleOrNull()?.let { v -> v.isFinite() && v in 0.1..5.0 } == true
                }
            if (!valid)
                Text(
                    "Multipliers must be between 0.1 and 5.",
                    color = MaterialTheme.colorScheme.error,
                )
            Button(
                onClick = {
                    vm.updateSettings(
                        draft.copy(
                            categoryMultipliers = multipliers.mapValues { it.value.toDouble() }
                        )
                    )
                    vm.message.value = "Settings saved"
                },
                enabled = valid,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text("Save preferences")
            }
        }
        item {
            HorizontalDivider()
            Text("Your data, in your hands", style = MaterialTheme.typography.titleLarge)
            Text(
                "Export includes mood, sleep and planning history. Keep backups in a location you trust."
            )
            Row {
                OutlinedButton(onClick = { export.launch("kairos-${LocalDate.now()}.json") }) {
                    Text("Export JSON")
                }
                Spacer(Modifier.width(8.dp))
                OutlinedButton(
                    onClick = { restore.launch(arrayOf("application/json", "text/plain")) }
                ) {
                    Text("Import JSON")
                }
            }
        }
        item {
            Text("Weekly auto-backup", style = MaterialTheme.typography.titleMedium)
            Text(status)
            Text(
                if (settings.backupFolder == null) "Off"
                else "On · retains the last 4 Kairos auto-backups in your chosen folder"
            )
            OutlinedButton(onClick = { folder.launch(null) }) { Text("Choose backup folder") }
            if (settings.backupFolder != null)
                TextButton(onClick = { vm.updateSettings(settings.copy(backupFolder = null)) }) {
                    Text("Turn off auto-backup")
                }
            TextButton(onClick = { vm.undoPreview() }) { Text("Preview undo of last restore") }
        }
    }
    pending?.let { backup ->
        AlertDialog(
            onDismissRequest = { vm.pendingBackup.value = null },
            title = { Text("Replace current data?") },
            text = {
                Text(
                    "${backup.data.tasks.size} tasks, ${backup.data.blocks.size} fixed blocks, ${backup.data.logs.size} work logs. A local undo copy will be kept. Choose the auto-backup folder again after restoring."
                )
            },
            confirmButton = {
                TextButton(onClick = { vm.restore() }) { Text("Replace & restore") }
            },
            dismissButton = {
                TextButton(onClick = { vm.pendingBackup.value = null }) { Text("Cancel") }
            },
        )
    }
}
