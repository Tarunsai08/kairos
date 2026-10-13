package com.tarun.kairos.ui

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.tarun.kairos.scheduler.*
import java.time.LocalDate

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KairosApp(vm: PlannerViewModel) {
    val locale = LocalConfiguration.current.locales[0]
    val ready by vm.ready.collectAsStateWithLifecycle()
    val data by vm.data.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val message by vm.message.collectAsStateWithLifecycle()
    val plan by vm.plan.collectAsStateWithLifecycle()
    var more by remember { mutableStateOf(false) }
    var page by rememberSaveable { mutableIntStateOf(0) }
    var taskEdit by remember { mutableStateOf<Task?>(null) }
    var showTask by remember { mutableStateOf(false) }
    var blockEdit by remember { mutableStateOf<FixedBlock?>(null) }
    var showBlock by remember { mutableStateOf(false) }
    var sessionEdit by remember { mutableStateOf<PlannedSession?>(null) }
    var checkin by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(message) {
        message?.let {
            snackbar.showSnackbar(it)
            vm.message.value = null
        }
    }
    KairosTheme(settings.theme) {
        if (!ready) {
            Surface(Modifier.fillMaxSize()) {
                Column(Modifier.padding(32.dp).systemBarsPadding()) {
                    Text("Kairos", style = MaterialTheme.typography.headlineLarge)
                    CircularProgressIndicator()
                }
            }
            return@KairosTheme
        }
        if (!settings.onboarded) {
            OnboardingScreen(settings, data, vm)
            return@KairosTheme
        }
        Scaffold(
            topBar = {
                TopAppBar(
                    title = { Text("Kairos") },
                    actions = {
                        TextButton(onClick = { more = true }) { Text("More") }
                        DropdownMenu(more, { more = false }) {
                            DropdownMenuItem(
                                text = { Text("Calendar") },
                                onClick = {
                                    page = 4
                                    more = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Reminders") },
                                onClick = {
                                    page = 5
                                    more = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Weekly review") },
                                onClick = {
                                    page = 6
                                    more = false
                                },
                            )
                            DropdownMenuItem(
                                text = { Text("Settings & backup") },
                                onClick = {
                                    page = 7
                                    more = false
                                },
                            )
                        }
                    },
                )
            },
            snackbarHost = { SnackbarHost(snackbar) },
            bottomBar = {
                NavigationBar {
                    listOf("Today", "Week", "Tasks", "Rhythm").forEachIndexed { i, label ->
                        NavigationBarItem(
                            page == i,
                            { page = i },
                            icon = { Text(listOf("◷", "▤", "✓", "↻")[i]) },
                            label = { Text(label) },
                        )
                    }
                }
            },
            floatingActionButton = {
                ExtendedFloatingActionButton(
                    onClick = {
                        if (page == 3) {
                            blockEdit = null
                            showBlock = true
                        } else {
                            taskEdit = null
                            showTask = true
                        }
                    }
                ) {
                    Text(if (page == 3) "+ Add commitment" else "+ Add task")
                }
            },
        ) { padding ->
            Box(Modifier.padding(padding).padding(horizontal = 20.dp).fillMaxSize()) {
                when (page) {
                    0 ->
                        TodayScreen(
                            data,
                            settings,
                            plan,
                            vm,
                            { sessionEdit = it },
                            { checkin = true },
                            {
                                taskEdit = it
                                showTask = true
                            },
                        )
                    1 -> WeekScreen(data, settings, plan)
                    2 ->
                        TasksScreen(
                            data,
                            {
                                taskEdit = it
                                showTask = true
                            },
                            vm::updateTask,
                            plan?.atRisk ?: emptySet(),
                        ) { t ->
                            "~${String.format(locale, "%.1f",Estimation.remainingTaskHours(t,data,settings))}h${if(t.estimatedHours==null)" (estimated)" else " (your estimate)"}"
                        }
                    3 ->
                        FixedScreen(
                            data,
                            {
                                blockEdit = it
                                showBlock = true
                            },
                            vm::deleteBlock,
                        )
                    4 -> CalendarScreen(data, settings, vm)
                    5 -> ReminderScreen(settings, vm)
                    6 -> ReviewScreen(data)
                    else -> SettingsScreen(settings, vm) { page = it }
                }
            }
            if (showTask)
                TaskEditor(
                    taskEdit,
                    data.subtasks.filter { it.taskId == taskEdit?.id },
                    dismiss = { showTask = false },
                    save = { t, s ->
                        vm.saveTask(t, s)
                        showTask = false
                    },
                )
            if (showBlock)
                BlockEditor(
                    blockEdit,
                    dismiss = { showBlock = false },
                    save = {
                        vm.saveBlock(it)
                        showBlock = false
                    },
                )
            sessionEdit?.let { SessionEditor(it, { sessionEdit = null }, vm) }
            if (checkin)
                CheckinEditor(
                    data.contexts.find { it.date == LocalDate.now().toString() },
                    { checkin = false },
                ) {
                    vm.checkin(it)
                    checkin = false
                }
        }
    }
}
