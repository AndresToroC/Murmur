package com.nochesonora.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.classification.SoundTypes
import com.nochesonora.core.classification.VotingClassifier

enum class SessionStatus { EN_CURSO, COMPLETADA, INTERRUMPIDA }

/** Why a session paused. The values are stored in the database, so they stay as they are. */
object PauseReason {
    const val MIC_BUSY = "MICROFONO_OCUPADO"
    const val CALL = "LLAMADA"
    const val AUDIO_ERROR = "ERROR_AUDIO"
    /** The user paused from the notification or Home. */
    const val USER = "USUARIO"
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** null while EN_CURSO. */
    val endedAt: Long?,
    val status: SessionStatus,
    /** Filled in when calibration finishes. */
    val baselineDbAtStart: Float?,
    /** Heartbeat every 30 s: if the process dies, it becomes the end time of the INTERRUMPIDA session. */
    val lastHeartbeatAt: Long,
    val sensitivityDb: Int,
    val fullRecordingPath: String?,
    val isFavorite: Boolean = false,
    val notes: String? = null,
)

@Entity(
    tableName = "events",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId"), Index("episodeId")],
)
data class EventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val episodeId: Long?,
    val category: Category,
    /** Original YAMNet label when the category is OTRO. */
    val otherLabel: String?,
    val startedAt: Long,
    val endedAt: Long,
    val peakDb: Float,
    val confidence: Float,
    val topLabelsJson: String,
    val filePath: String,
    val userCategory: Category?,
    /**
     * What was understood from a HABLA clip (on-device transcription). null = not attempted
     * yet; "" = attempted but nothing was understood. Column added in DB version 2.
     */
    val transcript: String? = null,
    /**
     * Times this same ambient noise (wind, fan…) was heard again without saving another clip
     * ([com.nochesonora.core.classification.RepeatFilter]). Column from DB version 3.
     */
    @ColumnInfo(defaultValue = "0") val repeats: Int = 0,
    /** Time of the last repeat (null if there was none). */
    val lastRepeatAt: Long? = null,
)

val EventEntity.effectiveCategory: Category get() = userCategory ?: category

/** The clip's sound type ("Dog", "Wind"…), the same one shown in the detail screen. */
fun EventEntity.soundType(): SoundType? =
    SoundTypes.ofEvent(otherLabel, VotingClassifier.fromJson(topLabelsJson).map { it.label })

@Entity(
    tableName = "episodes",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId")],
)
data class EpisodeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val category: Category,
    val startedAt: Long,
    val endedAt: Long,
    val eventCount: Int,
)

@Entity(
    tableName = "pauses",
    foreignKeys = [
        ForeignKey(entity = SessionEntity::class, parentColumns = ["id"], childColumns = ["sessionId"], onDelete = ForeignKey.CASCADE),
    ],
    indices = [Index("sessionId")],
)
data class PauseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val sessionId: Long,
    val startedAt: Long,
    val endedAt: Long?,
    /** [PauseReason]. */
    val reason: String,
)
