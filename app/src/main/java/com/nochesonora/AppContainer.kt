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

/** DI manual: una sola instancia de cada dependencia para todo el proceso. */
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
