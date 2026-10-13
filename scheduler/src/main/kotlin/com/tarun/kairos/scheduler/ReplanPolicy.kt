package com.tarun.kairos.scheduler
object ReplanPolicy {
    fun overran(session: PlannedSession, now: Long) =
        session.state == SessionState.PLANNED &&
            session.startedAt != null &&
            now > session.end + 10 * 60_000L
}
