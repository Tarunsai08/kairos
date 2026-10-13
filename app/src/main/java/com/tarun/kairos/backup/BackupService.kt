package com.tarun.kairos.backup

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.tarun.kairos.data.*
import com.tarun.kairos.scheduler.*
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.File
import java.time.*
import java.time.format.DateTimeFormatter
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

private val Context.backupPreferences by preferencesDataStore("backup_status")

@Singleton
class BackupService
@Inject
constructor(
    @ApplicationContext private val context: Context,
    private val repository: PlannerRepository,
    private val settings: SettingsStore,
) {
    private val mutex = Mutex()
    private val statusKey = stringPreferencesKey("status")
    val status = context.backupPreferences.data.map { it[statusKey] ?: "No automatic backup yet" }

    private suspend fun snapshot() =
        Backup(settings = settings.flow.first(), data = repository.snapshot())

    suspend fun export(uri: Uri) =
        withContext(Dispatchers.IO) {
            val text = BackupCodec.encode(snapshot())
            context.contentResolver.openOutputStream(uri, "wt")?.use {
                it.write(text.toByteArray(Charsets.UTF_8))
            } ?: error("The selected location is not writable")
        }

    suspend fun preview(uri: Uri): Backup =
        withContext(Dispatchers.IO) {
            val stream = context.contentResolver.openInputStream(uri) ?: error("Cannot open backup")
            stream.use {
                val out = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8192)
                var count = it.read(buffer)
                while (count >= 0) {
                    require(out.size() + count <= 32 * 1024 * 1024) {
                        "Backup is larger than 32 MB"
                    }
                    out.write(buffer, 0, count)
                    count = it.read(buffer)
                }
                BackupCodec.decode(out.toString("UTF-8"))
            }
        }

    suspend fun previous(): Backup =
        withContext(Dispatchers.IO) {
            val file = File(context.filesDir, "restore-undo.json")
            require(file.exists()) { "No previous restore to undo" }
            BackupCodec.decode(file.readText())
        }

    suspend fun restore(backup: Backup) = mutex.withLock {
        withContext(Dispatchers.IO) {
            BackupCodec.validate(backup)
            val previous = snapshot()
            val file = AtomicFile(File(context.filesDir, "restore-undo.json"))
            val out = file.startWrite()
            try {
                out.write(BackupCodec.encode(previous).toByteArray(Charsets.UTF_8))
                file.finishWrite(out)
            } catch (e: Exception) {
                file.failWrite(out)
                throw e
            }
            try {
                repository.replaceAll(backup.data)
                settings.set(backup.settings.copy(backupFolder = null, onboarded = true))
            } catch (e: Exception) {
                runCatching {
                    repository.replaceAll(previous.data)
                    settings.set(previous.settings)
                }
                throw e
            }
        }
    }

    suspend fun automatic() = mutex.withLock {
        withContext(Dispatchers.IO) {
            val backup = snapshot()
            val folder = backup.settings.backupFolder ?: return@withContext
            val tree = Uri.parse(folder)
            val parent =
                DocumentsContract.buildDocumentUriUsingTree(
                    tree,
                    DocumentsContract.getTreeDocumentId(tree),
                )
            val stamp =
                Instant.now()
                    .atZone(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss-SSS"))
            val name = "kairos-auto-$stamp.json"
            var created: Uri? = null
            var written = false
            try {
                created =
                    DocumentsContract.createDocument(
                        context.contentResolver,
                        parent,
                        "application/json",
                        name,
                    ) ?: error("Backup folder cannot create files")
                context.contentResolver.openOutputStream(created, "wt")?.use {
                    it.write(BackupCodec.encode(backup).toByteArray(Charsets.UTF_8))
                } ?: error("Backup file is not writable")
                written = true
                val children =
                    DocumentsContract.buildChildDocumentsUriUsingTree(
                        tree,
                        DocumentsContract.getTreeDocumentId(tree),
                    )
                val projection =
                    arrayOf(
                        DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                        DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    )
                val own =
                    context.contentResolver.query(children, projection, null, null, null)?.use { c
                        ->
                        buildList {
                            while (c.moveToNext()) {
                                val n = c.getString(1) ?: ""
                                if (
                                    Regex("kairos-auto-[0-9]{8}-[0-9]{6}-[0-9]{3}\\.json")
                                        .matches(n)
                                )
                                    add(n to c.getString(0))
                            }
                        }
                    } ?: emptyList()
                own.sortedByDescending { it.first }
                    .drop(4)
                    .forEach { (_, id) ->
                        DocumentsContract.deleteDocument(
                            context.contentResolver,
                            DocumentsContract.buildDocumentUriUsingTree(tree, id),
                        )
                    }
                context.backupPreferences.edit {
                    it[statusKey] = "Last automatic backup: ${LocalDateTime.now()} · keeping 4"
                }
            } catch (e: Exception) {
                if (!written)
                    created?.let {
                        runCatching {
                            DocumentsContract.deleteDocument(context.contentResolver, it)
                        }
                    }
                context.backupPreferences.edit {
                    it[statusKey] = "Backup failed. Check the selected folder's access."
                }
                throw e
            }
        }
    }
}
