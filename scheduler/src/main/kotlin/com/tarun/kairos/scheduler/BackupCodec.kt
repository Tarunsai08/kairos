package com.tarun.kairos.scheduler

import kotlinx.serialization.json.*

object BackupCodec {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
    }

    fun encode(backup: Backup): String {
        validate(backup)
        return json.encodeToString(backup)
    }

    fun decode(text: String): Backup {
        val root = json.parseToJsonElement(text).jsonObject
        val version =
            root["schemaVersion"]?.jsonPrimitive?.intOrNull
                ?: error("Missing backup schema version")
        require(version == 1) {
            "This backup uses schema $version. Use a compatible Kairos version."
        }
        // v1 is the initial format. Breaking future schemas must register a migration
        // before increasing schemaVersion; unknown schemas never replace user data.
        return json.decodeFromJsonElement<Backup>(root).also(::validate)
    }

    fun validate(backup: Backup) {
        require(backup.schemaVersion == 1)
        val d = backup.data
        fun unique(ids: List<String>, name: String) {
            require(ids.distinct().size == ids.size) { "Duplicate $name records" }
        }
        unique(d.tasks.map { it.id }, "task")
        unique(d.subtasks.map { it.id }, "subtask")
        unique(d.blocks.map { it.id }, "fixed block")
        unique(d.sessions.map { it.id }, "session")
        unique(d.logs.map { it.id }, "work log")
        unique(d.logs.map { it.sessionId }, "session log")
        unique(d.contexts.map { it.date }, "day context")
        unique(d.events.map { it.id }, "calendar event")
        unique(d.corrections.map { it.category.name }, "correction")
        unique(d.energy.map { "${it.weekday}:${it.hourOfDay}" }, "energy bucket")
        val tasks = d.tasks.map { it.id }.toSet()
        val subs = d.subtasks.associateBy { it.id }
        require(d.subtasks.all { it.taskId in tasks }) { "A subtask has no parent task" }
        require(d.sessions.all { it.taskId == null || it.taskId in tasks }) {
            "A session has no task"
        }
        require(
            d.sessions.all {
                it.subtaskId == null ||
                    (subs[it.subtaskId] == null || subs[it.subtaskId]?.taskId == it.taskId)
            }
        ) {
            "A session refers to the wrong subtask"
        }
        require(
            d.logs.all {
                it.taskId in tasks &&
                    (it.subtaskId == null ||
                        (subs[it.subtaskId] == null || subs[it.subtaskId]?.taskId == it.taskId))
            }
        ) {
            "A work log has an invalid task"
        }
        require(d.events.all { it.end > it.start }) { "Invalid calendar event range" }
        require(
            d.corrections.all { it.factor.isFinite() && it.factor in 0.5..2.5 && it.samples >= 0 }
        ) {
            "Invalid estimate correction"
        }
        require(
            d.energy.all {
                it.hourOfDay in 0..23 &&
                    it.weekday in 1..7 &&
                    it.score.isFinite() &&
                    it.score in 0.0..1.0 &&
                    it.samples >= 0
            }
        ) {
            "Invalid energy profile"
        }
        // Call normal constructors as well: serialization must not bypass invariants.
        backup.settings.copy()
        d.tasks.forEach { it.copy() }
        d.subtasks.forEach { it.copy() }
        d.blocks.forEach { it.copy() }
        d.sessions.forEach { it.copy() }
        d.contexts.forEach { it.copy() }
        d.logs.forEach { it.copy() }
    }
}
