package com.tarun.kairos.ui

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.tarun.kairos.scheduler.PlannerSettings

@Composable
fun ReminderScreen(settings: PlannerSettings, vm: PlannerViewModel) {
    val context = LocalContext.current
    val permission =
        rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
            vm.refresh()
        }
    LazyColumn(
        verticalArrangement = Arrangement.spacedBy(14.dp),
        contentPadding = PaddingValues(bottom = 100.dp),
    ) {
        item {
            Text("Gentle reminders", style = MaterialTheme.typography.headlineMedium)
            Text(
                "Morning, session start/end, evening wrap-up and a maximum of one deadline alert each day. Mute individual channels in Android settings."
            )
        }
        item {
            Button(
                onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && !vm.reminders.notificationAllowed())
                        permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else
                        context.startActivity(
                            Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                                .putExtra(Settings.EXTRA_APP_PACKAGE, context.packageName)
                        )
                }
            ) {
                Text(
                    if (vm.reminders.notificationAllowed()) "Notification settings"
                    else "Allow notifications"
                )
            }
        }
        item {
            Text(
                if (vm.reminders.exactAllowed()) "Exact reminders available"
                else "Inexact reminders active · delivery may be delayed"
            )
        }
        if (Build.VERSION.SDK_INT >= 31)
            item {
                OutlinedButton(
                    onClick = {
                        context.startActivity(
                            Intent(
                                Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                                Uri.parse("package:${context.packageName}"),
                            )
                        )
                    }
                ) {
                    Text("Exact alarm permission")
                }
            }
        item {
            Row {
                Text("Quiet during classes", Modifier.weight(1f))
                Switch(
                    settings.quietDuringClasses,
                    { vm.updateSettings(settings.copy(quietDuringClasses = it)) },
                )
            }
            Text("Sleep hours are always quiet.")
        }
        item {
            TimeField(
                settings.wrapUpTime,
                { vm.updateSettings(settings.copy(wrapUpTime = it)) },
                "Evening wrap-up",
            )
        }
    }
}
