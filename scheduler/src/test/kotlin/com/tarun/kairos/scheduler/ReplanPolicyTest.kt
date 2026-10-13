package com.tarun.kairos.scheduler

import org.junit.Assert.*
import org.junit.Test

class ReplanPolicyTest {
    private val session =
        PlannedSession(
            "s",
            "2026-10-13",
            1000,
            3_001_000,
            taskId = "t",
            reason = "Focus",
            startedAt = 1000,
        )

    @Test
    fun onlyRunningOverrunsTrigger() {
        assertFalse(ReplanPolicy.overran(session, session.end + 600_000))
        assertTrue(ReplanPolicy.overran(session, session.end + 600_001))
        assertFalse(ReplanPolicy.overran(session.copy(startedAt = null), session.end + 900_000))
        assertFalse(
            ReplanPolicy.overran(session.copy(state = SessionState.DONE), session.end + 900_000)
        )
    }
}
