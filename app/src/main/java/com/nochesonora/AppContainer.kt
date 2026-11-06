package com.nochesonora

import android.content.Context
import com.nochesonora.data.NightRepository
import com.nochesonora.data.db.AppDatabase
import com.nochesonora.data.files.SessionFileStore
import com.nochesonora.data.settings.SettingsRepository
import com.nochesonora.service.LiveState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** Manual DI: a single instance of each dependency for the whole process. */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext
    val processStartedAt: Long = System.currentTimeMillis()
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val database: AppDatabase by lazy { AppDatabase.create(appContext) }
    val fileStore = SessionFileStore(appContext)
    val settings = SettingsRepository(appContext)
    val repository: NightRepository by lazy { NightRepository(database.nightDao(), fileStore) }
    val liveState = LiveState()
}
