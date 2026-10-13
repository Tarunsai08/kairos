package com.tarun.kairos

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import com.tarun.kairos.background.services
import com.tarun.kairos.scheduler.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class PlannerJourneyTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test
    fun onboardAddTaskAndAdaptManualMoodWithoutKaizen() {
        val s = services(compose.activity)
        runBlocking {
            s.repository().replaceAll(PlannerData())
            s.settings().set(PlannerSettings())
        }
        compose.waitUntil(90_000) {
            compose
                .onAllNodesWithText("A plan that starts with you")
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        android.util.Log.i("KairosJourney", "Onboarding displayed")
        repeat(3) { compose.onNodeWithText("Continue").performClick() }
        compose.onNodeWithText("Make room for today").performClick()
        compose.waitUntil(90_000) {
            compose.onAllNodesWithText("+ Add task").fetchSemanticsNodes().isNotEmpty()
        }
        android.util.Log.i("KairosJourney", "Onboarding completed")
        compose.onNodeWithText("+ Add task").performClick()
        compose.onNodeWithText("Task name").performTextInput("DBMS assignment")
        compose.onNodeWithText("Save task").performScrollTo().performClick()
        compose.waitUntil(90_000) {
            runBlocking { s.repository().snapshot().tasks.any { it.title == "DBMS assignment" } }
        }
        android.util.Log.i("KairosJourney", "Task stored")
        compose.onNodeWithText("Tasks").performClick()
        compose.onNodeWithText("DBMS assignment").assertIsDisplayed()
        compose.onNodeWithText("Today").performClick()
        compose.onNodeWithText("Quick check-in", substring = true).performClick()
        compose.onAllNodesWithText("2").onFirst().performClick()
        compose.onNodeWithText("Use a gentler plan").performClick()
        compose.waitUntil(90_000) {
            runBlocking {
                s.repository().snapshot().contexts.any {
                    it.source == ContextSource.MANUAL && it.moodMorning == 2
                }
            }
        }
        compose.onNodeWithText("Lighter plan today. You've got this.").assertIsDisplayed()
        android.util.Log.i("KairosJourney", "Low-mood check-in stored")
        val data = runBlocking { s.repository().snapshot() }
        assertTrue(data.sessions.any { it.kind in Scheduler.workKinds })
        assertTrue(data.sessions.all { it.reason.isNotBlank() })
        @Suppress("DEPRECATION")
        val permissions =
            compose.activity.packageManager
                .getPackageInfo(
                    compose.activity.packageName,
                    android.content.pm.PackageManager.GET_PERMISSIONS,
                )
                .requestedPermissions
                .orEmpty()
        assertFalse("android.permission.INTERNET" in permissions)
    }
}
