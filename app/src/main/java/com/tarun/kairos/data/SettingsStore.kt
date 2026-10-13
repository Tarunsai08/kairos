package com.tarun.kairos.data

import android.content.Context
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import com.tarun.kairos.scheduler.PlannerSettings
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.*
import kotlinx.serialization.json.Json

private val Context.preferences by preferencesDataStore("planner_settings")

@Singleton
class SettingsStore @Inject constructor(@ApplicationContext private val context: Context) {
    private val key = stringPreferencesKey("settings_v1")
    val flow: Flow<PlannerSettings> =
        context.preferences.data.map {
            it[key]?.let { text -> Json.decodeFromString<PlannerSettings>(text) }
                ?: PlannerSettings()
        }

    suspend fun set(settings: PlannerSettings) {
        context.preferences.edit { it[key] = Json.encodeToString(settings) }
    }

    suspend fun update(transform: (PlannerSettings) -> PlannerSettings) {
        context.preferences.edit { prefs ->
            val current =
                prefs[key]?.let { Json.decodeFromString<PlannerSettings>(it) } ?: PlannerSettings()
            prefs[key] = Json.encodeToString(transform(current))
        }
    }
}
