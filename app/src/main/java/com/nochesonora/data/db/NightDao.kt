package com.nochesonora.data.db

import androidx.room.Dao
import androidx.room.Embedded
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Transaction
import com.nochesonora.core.classification.Category
import com.nochesonora.core.episodes.EpisodeLite
import kotlinx.coroutines.flow.Flow

/** Sesión con los conteos por categoría efectiva y los minutos de ronquido (suma de episodios). */
data class SessionSummaryRow(
    @Embedded val session: SessionEntity,
    val snoreEvents: Int,
    val speechEvents: Int,
    val coughEvents: Int,
    val otherEvents: Int,
    val snoreMs: Long,
)

@Dao
abstract class NightDao {
    // ---- Sesiones ----
    @Insert abstract suspend fun insertSession(session: SessionEntity): Long

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract suspend fun getSession(id: Long): SessionEntity?

    @Query("SELECT * FROM sessions WHERE id = :id")
    abstract fun observeSession(id: Long): Flow<SessionEntity?>

    @Query("SELECT * FROM sessions")
    abstract suspend fun getAllSessions(): List<SessionEntity>

    @Query("SELECT * FROM sessions WHERE status = :status")
    abstract suspend fun getSessionsByStatus(status: SessionStatus): List<SessionEntity>

    @Query("UPDATE sessions SET lastHeartbeatAt = :at WHERE id = :id")
    abstract suspend fun heartbeat(id: Long, at: Long)

    @Query("UPDATE sessions SET baselineDbAtStart = :db WHERE id = :id")
    abstract suspend fun setBaseline(id: Long, db: Float)

    @Query("UPDATE sessions SET status = :status, endedAt = :endedAt WHERE id = :id")
    abstract suspend fun finishSession(id: Long, status: SessionStatus, endedAt: Long)

    @Query("UPDATE sessions SET fullRecordingPath = :path WHERE id = :id")
    abstract suspend fun setFullRecordingPath(id: Long, path: String?)

    @Query("UPDATE sessions SET isFavorite = :favorite WHERE id = :id")
    abstract suspend fun setFavorite(id: Long, favorite: Boolean)

    @Query("DELETE FROM sessions WHERE id = :id")
    abstract suspend fun deleteSession(id: Long)

    @Query("DELETE FROM sessions")
    abstract suspend fun deleteAllSessions()

    @Query(
        """
        SELECT s.*,
          (SELECT COUNT(*) FROM events e WHERE e.sessionId = s.id AND COALESCE(e.userCategory, e.category) = 'RONQUIDO') AS snoreEvents,
          (SELECT COUNT(*) FROM events e WHERE e.sessionId = s.id AND COALESCE(e.userCategory, e.category) = 'HABLA') AS speechEvents,
          (SELECT COUNT(*) FROM events e WHERE e.sessionId = s.id AND COALESCE(e.userCategory, e.category) = 'TOS') AS coughEvents,
          (SELECT COUNT(*) FROM events e WHERE e.sessionId = s.id AND COALESCE(e.userCategory, e.category) = 'OTRO') AS otherEvents,
          (SELECT COALESCE(SUM(ep.endedAt - ep.startedAt), 0) FROM episodes ep WHERE ep.sessionId = s.id AND ep.category = 'RONQUIDO') AS snoreMs
        FROM sessions s
        ORDER BY s.startedAt DESC
        """,
    )
    abstract fun observeSummaries(): Flow<List<SessionSummaryRow>>

    // ---- Eventos ----
    @Insert abstract suspend fun insertEvent(event: EventEntity): Long

    @Query("SELECT * FROM events WHERE id = :id")
    abstract suspend fun getEvent(id: Long): EventEntity?

    @Query("UPDATE events SET filePath = :path WHERE id = :id")
    abstract suspend fun setEventPath(id: Long, path: String)

    /** Vuelve a dejar todas las transcripciones "sin intentar" (para rehacerlas). */
    @Query("UPDATE events SET transcript = NULL WHERE transcript IS NOT NULL")
    abstract suspend fun clearTranscripts()

    /** Suma una repetición del mismo ruido ambiental. Devuelve las filas cambiadas (0 si se borró). */
    @Query("UPDATE events SET repeats = repeats + 1, lastRepeatAt = :at WHERE id = :id")
    abstract suspend fun addRepeat(id: Long, at: Long): Int

    @Query("UPDATE events SET transcript = :text WHERE id = :id")
    abstract suspend fun setTranscript(id: Long, text: String?)

    @Query("UPDATE events SET userCategory = :category WHERE id = :id")
    abstract suspend fun setUserCategory(id: Long, category: Category?)

    @Query("DELETE FROM events WHERE id = :id")
    abstract suspend fun deleteEvent(id: Long)

    @Query("DELETE FROM events WHERE id IN (:ids)")
    abstract suspend fun deleteEvents(ids: List<Long>)

    /** Eventos de todas las noches que son (o se corrigieron a) [category]. */
    @Query("SELECT * FROM events WHERE category = :category OR userCategory = :category ORDER BY startedAt")
    abstract suspend fun getEventsInCategory(category: Category): List<EventEntity>

    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY startedAt")
    abstract suspend fun getEvents(sessionId: Long): List<EventEntity>

    @Query("SELECT * FROM events WHERE sessionId = :sessionId ORDER BY startedAt")
    abstract fun observeEvents(sessionId: Long): Flow<List<EventEntity>>

    @Query("UPDATE events SET episodeId = :episodeId WHERE id IN (:eventIds)")
    abstract suspend fun setEpisode(episodeId: Long, eventIds: List<Long>)

    // ---- Episodios ----
    @Insert abstract suspend fun insertEpisode(episode: EpisodeEntity): Long

    @Query("DELETE FROM episodes WHERE sessionId = :sessionId")
    abstract suspend fun deleteEpisodes(sessionId: Long)

    @Query("SELECT * FROM episodes WHERE sessionId = :sessionId ORDER BY startedAt")
    abstract fun observeEpisodes(sessionId: Long): Flow<List<EpisodeEntity>>

    /** Reemplaza los episodios de la sesión y reasigna `episodeId` a sus eventos. */
    @Transaction
    open suspend fun replaceEpisodes(sessionId: Long, episodes: List<EpisodeLite>) {
        deleteEpisodes(sessionId)
        for (ep in episodes) {
            val id = insertEpisode(
                EpisodeEntity(
                    sessionId = sessionId,
                    category = ep.category,
                    startedAt = ep.startedAt,
                    endedAt = ep.endedAt,
                    eventCount = ep.eventIds.size,
                ),
            )
            // SQLite limita los parámetros por consulta: en trozos.
            ep.eventIds.chunked(500).forEach { setEpisode(id, it) }
        }
    }

    // ---- Pausas ----
    @Insert abstract suspend fun insertPause(pause: PauseEntity): Long

    @Query("UPDATE pauses SET endedAt = :endedAt WHERE sessionId = :sessionId AND endedAt IS NULL")
    abstract suspend fun endOpenPauses(sessionId: Long, endedAt: Long)

    @Query("SELECT * FROM pauses WHERE sessionId = :sessionId ORDER BY startedAt")
    abstract fun observePauses(sessionId: Long): Flow<List<PauseEntity>>
}
