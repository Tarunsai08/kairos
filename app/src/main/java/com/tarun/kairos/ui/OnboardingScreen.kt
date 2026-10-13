package com.tarun.kairos.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tarun.kairos.scheduler.*

@Composable
fun OnboardingScreen(settings: PlannerSettings, data: PlannerData, vm: PlannerViewModel) {
    var step by rememberSaveable { mutableIntStateOf(0) }
    var wake by rememberSaveable { mutableStateOf(settings.wakeTime) }
    var sleep by rememberSaveable { mutableStateOf(settings.bedtime) }
    var peaks by remember { mutableStateOf(settings.peaks) }
    var add by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val link by vm.kaizen.status.collectAsStateWithLifecycle()
    val permissions =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
            vm.refresh()
        }
    Column(
        Modifier.fillMaxSize()
            .systemBarsPadding()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        Text(
            "KAIROS",
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.primary,
        )
        LinearProgressIndicator(progress = { (step + 1) / 4f }, modifier = Modifier.fillMaxWidth())
        Text("${step+1} of 4", style = MaterialTheme.typography.bodySmall)
        when (step) {
            0 -> {
                Text("A plan that starts with you", style = MaterialTheme.typography.headlineLarge)
                Text("Make room for work, rest and the day you actually have.")
                TimeField(wake, { wake = it }, "Typical wake time")
                TimeField(sleep, { sleep = it }, "Typical bedtime")
            }
            1 -> {
                Text("Find your natural rhythm", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Choose the hours when hard work usually feels easier. You can change this anytime."
                )
                PeakPicker(peaks) { peaks = it }
            }
            2 -> {
                Text("Protect your anchors", style = MaterialTheme.typography.headlineLarge)
                Text("Add a few regular commitments. Sleep will be added from your chosen times.")
                listOf(
                        Triple("Breakfast", "07:30", "08:00"),
                        Triple("Lunch", "12:30", "13:00"),
                        Triple("Dinner", "20:00", "20:30"),
                    )
                    .forEach { (title, start, end) ->
                        OutlinedButton(
                            onClick = { vm.quickBlock(title, BlockType.MEAL, start, end) },
                            enabled = data.blocks.none { it.title == title },
                            modifier = Modifier.fillMaxWidth(),
                        ) {
                            Text(
                                if (data.blocks.any { it.title == title }) "$title added"
                                else "+ $title"
                            )
                        }
                    }
                Row {
                    OutlinedButton(
                        onClick = { vm.quickBlock("Bath", BlockType.BATH, "06:00", "06:30") }
                    ) {
                        Text("+ Bath")
                    }
                    Spacer(Modifier.width(8.dp))
                    OutlinedButton(
                        onClick = { vm.quickBlock("Gym", BlockType.GYM, "17:00", "18:00") }
                    ) {
                        Text("+ Gym")
                    }
                }
                TextButton(onClick = { add = true }) { Text("Add class or another commitment") }
                Text("${data.blocks.size} commitments added")
            }
            else -> {
                Text("Only the access you choose", style = MaterialTheme.typography.headlineLarge)
                Text(
                    "Calendar access uses events already on your phone. Reminders are optional. Kairos works offline with manual check-ins."
                )
                Text(link)
                OutlinedButton(
                    onClick = {
                        permissions.launch(
                            buildList {
                                add(Manifest.permission.READ_CALENDAR)
                                if (Build.VERSION.SDK_INT >= 33)
                                    add(Manifest.permission.POST_NOTIFICATIONS)
                            }
                                .toTypedArray()
                        )
                    },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text("Calendar & notification permissions")
                }
                if (Build.VERSION.SDK_INT >= 31)
                    TextButton(
                        onClick = {
                            runCatching {
                                context.startActivity(
                                    Intent(
                                        Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                        Uri.parse("package:${context.packageName}"),
                                    )
                                )
                            }
                        }
                    ) {
                        Text("Exact reminder permission (optional)")
                    }
                Text(
                    "Both apps must use the same signing key to link Kaizen. Without it, a quick check-in is always available.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(16.dp))
        Button(
            onClick = {
                if (step < 3) step++
                else
                    vm.completeOnboarding(
                        settings.copy(wakeTime = wake, bedtime = sleep, peaks = peaks)
                    )
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(if (step < 3) "Continue" else "Make room for today")
        }
        if (step > 0) TextButton(onClick = { step-- }) { Text("Back") }
    }
    if (add)
        BlockEditor(
            dismiss = { add = false },
            save = {
                vm.saveBlock(it)
                add = false
            },
        )
}
