package com.tarun.kairos

import android.app.Application
import com.tarun.kairos.background.WorkSchedules
import com.tarun.kairos.data.SettingsStore
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

@HiltAndroidApp
class KairosApplication : Application() {
    @Inject lateinit var settings: SettingsStore
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        scope.launch {
            val config = settings.flow.first()
            WorkSchedules.daily(this@KairosApplication, config)
            WorkSchedules.weeklyBackup(this@KairosApplication, config)
        }
    }
}
