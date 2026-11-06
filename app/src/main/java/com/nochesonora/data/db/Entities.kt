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

object PauseReason {
    const val MICROFONO_OCUPADO = "MICROFONO_OCUPADO"
    const val LLAMADA = "LLAMADA"
    const val ERROR_AUDIO = "ERROR_AUDIO"
    /** El usuario pausó desde la notificación o Inicio. */
    const val USUARIO = "USUARIO"
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val startedAt: Long,
    /** null mientras está EN_CURSO. */
    val endedAt: Long?,
    val status: SessionStatus,
    /** Se llena al terminar la calibración. */
    val baselineDbAtStart: Float?,
    /** Latido cada 30 s: si el proceso muere, es la hora de fin de la sesión INTERRUMPIDA. */
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
    /** Etiqueta YAMNet original cuando la categoría es OTRO. */
    val otherLabel: String?,
    val startedAt: Long,
    val endedAt: Long,
    val peakDb: Float,
    val confidence: Float,
    val topLabelsJson: String,
    val filePath: String,
    val userCategory: Category?,
    /**
     * Lo que se entendió de un clip de HABLA (transcripción en el teléfono). null = aún no se
     * intentó; "" = se intentó y no se entendió nada. Columna añadida en la versión 2 de la BD.
     */
    val transcript: String? = null,
    /**
     * Veces que este mismo ruido ambiental (viento, ventilador…) se volvió a oír sin guardar otro
     * clip ([com.nochesonora.core.classification.RepeatFilter]). Columna de la versión 3 de la BD.
     */
    @ColumnInfo(defaultValue = "0") val repeats: Int = 0,
    /** Hora de la última repetición (null si no hubo). */
    val lastRepeatAt: Long? = null,
)

val EventEntity.effectiveCategory: Category get() = userCategory ?: category

/** Tipo de sonido del clip ("Perro", "Viento"…), el mismo que se muestra en el detalle. */
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
