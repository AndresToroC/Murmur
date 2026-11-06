package com.nochesonora.data.settings

import android.content.Context
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.detection.Sensitivity
import com.nochesonora.data.Retention
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

data class AppSettings(
    val sensitivity: Sensitivity = Sensitivity.MEDIUM,
    val saveFullRecording: Boolean = false,
    val retention: Retention = Retention.FOREVER,
    val batteryPromptShown: Boolean = false,
    /** Notification with the night's summary when the session is stopped. */
    val morningSummary: Boolean = true,
    /** Transcribe on the phone what is said while asleep (SPEECH clips). */
    val transcribeSpeech: Boolean = true,
    /** Save repeating ambient noises only once (wind, fan, rain). */
    val groupRepeats: Boolean = true,
    /** Sounds the person asked to ignore from a clip (the dog, the air conditioner…). */
    val ignoredSounds: Set<SoundType> = emptySet(),
)

private val Context.dataStore by preferencesDataStore(name = "settings")

class SettingsRepository(private val context: Context) {
    private object Keys {
        val sensitivity = stringPreferencesKey("sensitivity")
        val saveFull = booleanPreferencesKey("saveFullRecording")
        val retention = stringPreferencesKey("retention")
        val batteryPromptShown = booleanPreferencesKey("batteryPromptShown")
        val morningSummary = booleanPreferencesKey("morningSummary")
        val transcribeSpeech = booleanPreferencesKey("transcribeSpeech")
        val groupRepeats = booleanPreferencesKey("groupRepeats")
        val ignoredSounds = stringSetPreferencesKey("ignoredSounds")
    }

    val settings: Flow<AppSettings> = context.dataStore.data.map { it.toSettings() }

    suspend fun current(): AppSettings = settings.first()

    suspend fun setSensitivity(value: Sensitivity) = context.dataStore.edit { it[Keys.sensitivity] = value.name }

    suspend fun setSaveFullRecording(value: Boolean) = context.dataStore.edit { it[Keys.saveFull] = value }

    suspend fun setRetention(value: Retention) = context.dataStore.edit { it[Keys.retention] = value.name }

    suspend fun setBatteryPromptShown() = context.dataStore.edit { it[Keys.batteryPromptShown] = true }

    suspend fun setMorningSummary(value: Boolean) = context.dataStore.edit { it[Keys.morningSummary] = value }

    suspend fun setTranscribeSpeech(value: Boolean) = context.dataStore.edit { it[Keys.transcribeSpeech] = value }

    suspend fun setGroupRepeats(value: Boolean) = context.dataStore.edit { it[Keys.groupRepeats] = value }

    suspend fun setIgnored(type: SoundType, ignored: Boolean) = context.dataStore.edit {
        val now = it[Keys.ignoredSounds].orEmpty()
        it[Keys.ignoredSounds] = if (ignored) now + type.name else now - type.name
    }

    private fun Preferences.toSettings() = AppSettings(
        sensitivity = this[Keys.sensitivity]?.let(::storedSensitivity) ?: Sensitivity.MEDIUM,
        saveFullRecording = this[Keys.saveFull] ?: false,
        retention = this[Keys.retention]?.let(::storedRetention) ?: Retention.FOREVER,
        batteryPromptShown = this[Keys.batteryPromptShown] ?: false,
        morningSummary = this[Keys.morningSummary] ?: true,
        transcribeSpeech = this[Keys.transcribeSpeech] ?: true,
        groupRepeats = this[Keys.groupRepeats] ?: true,
        ignoredSounds = this[Keys.ignoredSounds].orEmpty()
            .mapNotNull { name -> runCatching { SoundType.valueOf(name) }.getOrNull() }.toSet(),
    )
}

/** Values saved before 0.8.0 used Spanish names; they are still read so the setting is kept. */
private val LEGACY_SENSITIVITY = mapOf("BAJA" to Sensitivity.LOW, "MEDIA" to Sensitivity.MEDIUM, "ALTA" to Sensitivity.HIGH)
private val LEGACY_RETENTION = mapOf("SIEMPRE" to Retention.FOREVER, "DIAS_7" to Retention.DAYS_7, "DIAS_30" to Retention.DAYS_30)

internal fun storedSensitivity(name: String): Sensitivity? =
    Sensitivity.entries.firstOrNull { it.name == name } ?: LEGACY_SENSITIVITY[name]

internal fun storedRetention(name: String): Retention? =
    Retention.entries.firstOrNull { it.name == name } ?: LEGACY_RETENTION[name]
