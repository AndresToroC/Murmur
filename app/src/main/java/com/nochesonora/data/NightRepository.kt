package com.nochesonora.data

import android.util.Log
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.ClassificationResult
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.classification.VotingClassifier
import com.nochesonora.core.detection.ClosedEvent
import com.nochesonora.core.episodes.EpisodeGrouper
import com.nochesonora.core.episodes.EventLite
import com.nochesonora.data.db.EpisodeEntity
import com.nochesonora.data.db.EventEntity
import com.nochesonora.data.db.NightDao
import com.nochesonora.data.db.PauseEntity
import com.nochesonora.data.db.SessionEntity
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.data.db.effectiveCategory
import com.nochesonora.data.db.soundType
import com.nochesonora.data.files.SessionFileStore
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.withContext

data class SessionDetail(
    val session: SessionEntity,
    val events: List<EventEntity>,
    val episodes: List<EpisodeEntity>,
    val pauses: List<PauseEntity>,
)

/** The single repository: database + audio files. */
class NightRepository(
    private val dao: NightDao,
    private val files: SessionFileStore,
) {
    fun observeSummaries(): Flow<List<SessionSummaryRow>> = dao.observeSummaries()

    fun observeSessionDetail(id: Long): Flow<SessionDetail?> = combine(
        dao.observeSession(id),
        dao.observeEvents(id),
        dao.observeEpisodes(id),
        dao.observePauses(id),
    ) { session, events, episodes, pauses ->
        session?.let { SessionDetail(it, events, episodes, pauses) }
    }

    // ---- Session lifecycle ----

    suspend fun createSession(startedAt: Long, sensitivityDb: Int, saveFullRecording: Boolean): SessionEntity {
        val id = dao.insertSession(
            SessionEntity(
                startedAt = startedAt,
                endedAt = null,
                status = SessionStatus.IN_PROGRESS,
                baselineDbAtStart = null,
                lastHeartbeatAt = startedAt,
                sensitivityDb = sensitivityDb,
                fullRecordingPath = null,
            ),
        )
        if (saveFullRecording) {
            val path = files.fullRecordingFile(id).absolutePath
            files.sessionDir(id).mkdirs()
            dao.setFullRecordingPath(id, path)
        }
        return dao.getSession(id)!!
    }

    suspend fun heartbeat(sessionId: Long, at: Long) = dao.heartbeat(sessionId, at)

    suspend fun setBaseline(sessionId: Long, db: Float) = dao.setBaseline(sessionId, db)

    suspend fun completeSession(sessionId: Long, endedAt: Long) {
        dao.endOpenPauses(sessionId, endedAt)
        dao.finishSession(sessionId, SessionStatus.COMPLETED, endedAt)
    }

    /**
     * Every IN_PROGRESS session other than the live service's becomes INTERRUPTED, ending at its
     * last heartbeat (§6.6). Clips already written are kept.
     *
     * Only sessions created before this process started count: a session created by this same
     * process is always closed by its own service, so there is no race with one that is starting.
     */
    suspend fun markOrphansInterrupted(processStartedAt: Long, activeSessionId: Long?): Int {
        val orphans = dao.getSessionsByStatus(SessionStatus.IN_PROGRESS)
            .filter { it.id != activeSessionId && it.startedAt < processStartedAt }
        for (s in orphans) {
            dao.endOpenPauses(s.id, s.lastHeartbeatAt)
            dao.finishSession(s.id, SessionStatus.INTERRUPTED, s.lastHeartbeatAt)
        }
        return orphans.size
    }

    // ---- Events ----

    /**
     * Inserts the row (to get the id), writes the WAV atomically, stores the path and regroups
     * the episodes. If the WAV fails, the row is deleted.
     */
    suspend fun saveEvent(sessionId: Long, event: ClosedEvent, result: ClassificationResult): Long? {
        require(result.category != Category.BACKGROUND) { "BACKGROUND is not persisted" }
        val id = dao.insertEvent(
            EventEntity(
                sessionId = sessionId,
                episodeId = null,
                category = result.category,
                otherLabel = result.otherLabel,
                startedAt = event.startedAtMs,
                endedAt = event.endedAtMs,
                peakDb = event.peakDb,
                confidence = result.confidence,
                topLabelsJson = VotingClassifier.toJson(result.topLabels),
                filePath = "",
                userCategory = null,
            ),
        )
        val file = files.eventFile(sessionId, id)
        try {
            withContext(Dispatchers.IO) { files.writeWav(file, event.pcm) }
        } catch (e: Exception) {
            Log.e(TAG, "Could not write clip $id", e)
            dao.deleteEvent(id)
            return null
        }
        dao.setEventPath(id, file.absolutePath)
        regroup(sessionId)
        dao.heartbeat(sessionId, System.currentTimeMillis())
        return id
    }

    /** "" = attempted but nothing was understood; null = back to "not attempted". */
    suspend fun setTranscript(eventId: Long, text: String?) = dao.setTranscript(eventId, text)

    suspend fun clearTranscripts() = dao.clearTranscripts()

    /** false if the event no longer exists (it was deleted): a new clip must be saved then. */
    suspend fun addRepeat(sessionId: Long, eventId: Long, at: Long): Boolean {
        val changed = dao.addRepeat(eventId, at) > 0
        if (changed) dao.heartbeat(sessionId, System.currentTimeMillis())
        return changed
    }

    suspend fun setUserCategory(eventId: Long, category: Category) {
        val event = dao.getEvent(eventId) ?: return
        dao.setUserCategory(eventId, if (category == event.category) null else category)
        regroup(event.sessionId)
    }

    suspend fun deleteEvent(eventId: Long) {
        val event = dao.getEvent(eventId) ?: return
        dao.deleteEvent(eventId)
        withContext(Dispatchers.IO) { files.deleteFile(event.filePath) }
        regroup(event.sessionId)
    }

    /** Saved clips from every night whose sound is [type] (what gets deleted when it is ignored). */
    suspend fun clipsOfType(type: SoundType): List<EventEntity> =
        dao.getEventsInCategory(Category.OTHER).filter { it.effectiveCategory == Category.OTHER && it.soundType() == type }

    /** Deletes several clips (rows and WAVs) and rebuilds the episodes of each affected night once. */
    suspend fun deleteEvents(events: List<EventEntity>) {
        if (events.isEmpty()) return
        events.chunked(500).forEach { chunk -> dao.deleteEvents(chunk.map { it.id }) }
        withContext(Dispatchers.IO) { events.forEach { files.deleteFile(it.filePath) } }
        events.map { it.sessionId }.distinct().forEach { regroup(it) }
    }

    private suspend fun regroup(sessionId: Long) {
        val lite = dao.getEvents(sessionId).map { EventLite(it.id, it.category, it.userCategory, it.startedAt, it.endedAt) }
        dao.replaceEpisodes(sessionId, EpisodeGrouper.group(lite))
    }

    // ---- Pauses ----

    suspend fun startPause(sessionId: Long, at: Long, reason: String) {
        dao.endOpenPauses(sessionId, at)
        dao.insertPause(PauseEntity(sessionId = sessionId, startedAt = at, endedAt = null, reason = reason))
    }

    suspend fun endPause(sessionId: Long, at: Long) = dao.endOpenPauses(sessionId, at)

    // ---- Sessions: favorites, deletion, retention ----

    suspend fun setFavorite(sessionId: Long, favorite: Boolean) = dao.setFavorite(sessionId, favorite)

    suspend fun deleteSession(sessionId: Long) {
        dao.deleteSession(sessionId) // cascades: events, episodes, pauses
        withContext(Dispatchers.IO) { files.deleteSession(sessionId) }
    }

    suspend fun deleteAll() {
        dao.deleteAllSessions()
        withContext(Dispatchers.IO) { files.deleteAll() }
    }

    suspend fun applyRetention(retention: Retention, now: Long = System.currentTimeMillis()): Int {
        val candidates = dao.getAllSessions().map {
            RetentionCandidate(it.id, it.startedAt, it.endedAt, it.isFavorite, it.status == SessionStatus.IN_PROGRESS)
        }
        val ids = RetentionPolicy.idsToDelete(now, retention, candidates)
        ids.forEach { deleteSession(it) }
        return ids.size
    }

    fun usedBytes(): Long = files.usedBytes()

    fun freeBytes(): Long = files.freeBytes()

    private companion object {
        const val TAG = "NightRepository"
    }
}
