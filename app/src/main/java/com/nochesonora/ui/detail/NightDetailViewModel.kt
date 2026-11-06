package com.nochesonora.ui.detail

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nochesonora.AppContainer
import com.nochesonora.core.audio.RecordingOffset
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.SoundType
import com.nochesonora.data.SessionDetail
import com.nochesonora.data.db.EventEntity
import com.nochesonora.data.db.effectiveCategory
import com.nochesonora.transcription.SpeechTranscriber
import com.nochesonora.transcription.TranscriberAvailability
import com.nochesonora.transcription.TranscriptResult
import com.nochesonora.ui.common.ClipPlayer
import com.nochesonora.ui.common.PlayerState
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File

/** Estado de la transcripción de esta noche, para la UI. */
data class TranscriptionUi(
    /** null mientras se consulta si el teléfono puede transcribir. */
    val availability: TranscriberAvailability? = null,
    /** Se pidió la descarga del idioma en esta pantalla. */
    val downloadRequested: Boolean = false,
    /** Eventos que se están transcribiendo ahora (o en cola). */
    val inProgress: Set<Long> = emptySet(),
    /** Eventos en los que el reconocedor del teléfono falló en esta pantalla. */
    val failed: Set<Long> = emptySet(),
) {
    val canTranscribe: Boolean get() = availability is TranscriberAvailability.Ready
}

class NightDetailViewModel(private val container: AppContainer, private val sessionId: Long) : ViewModel() {
    /** null mientras carga o si se borró. */
    val detail: StateFlow<SessionDetail?> = container.repository.observeSessionDetail(sessionId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val isLive: Boolean get() = container.liveState.activeSessionId == sessionId

    private val player = ClipPlayer(container.appContext, viewModelScope)
    val playerState: StateFlow<PlayerState> = player.state

    private val _expanded = MutableStateFlow<Set<Long>>(emptySet())
    val expanded: StateFlow<Set<Long>> = _expanded.asStateFlow()

    // ---- Transcripción de los clips de habla ----

    private val transcriber = SpeechTranscriber(container.appContext)
    private val _transcription = MutableStateFlow(TranscriptionUi())
    val transcription: StateFlow<TranscriptionUi> = _transcription.asStateFlow()

    /** Cola de eventos por transcribir; uno a la vez. */
    private val queue = Channel<EventEntity>(Channel.UNLIMITED)
    /** Ya intentados en esta pantalla (para no reintentar en bucle los que fallan). */
    private val attempted = mutableSetOf<Long>()

    init {
        viewModelScope.launch {
            _transcription.update { it.copy(availability = transcriber.availability()) }
        }
        viewModelScope.launch { for (event in queue) runTranscription(event) }
        // Automático: al abrir la noche, los clips de habla aún sin transcribir.
        viewModelScope.launch {
            combine(
                detail.filterNotNull(),
                container.settings.settings.map { it.transcribeSpeech },
                _transcription.map { it.canTranscribe }.distinctUntilChanged(),
            ) { d, enabled, ready -> Triple(d, enabled, ready) }
                .collect { (d, enabled, ready) ->
                    if (!enabled || !ready) return@collect
                    d.events.filter { it.needsTranscript() && it.id !in attempted }.forEach(::enqueue)
                }
        }
    }

    private fun EventEntity.needsTranscript() =
        effectiveCategory == Category.HABLA && transcript == null && filePath.isNotEmpty()

    private fun enqueue(event: EventEntity) {
        if (event.id in _transcription.value.inProgress) return
        attempted += event.id
        _transcription.update { it.copy(inProgress = it.inProgress + event.id, failed = it.failed - event.id) }
        queue.trySend(event)
    }

    /** "Transcribir" / "Transcribir de nuevo" en un clip. */
    fun transcribe(event: EventEntity) {
        if (_transcription.value.canTranscribe) enqueue(event)
    }

    fun downloadLanguage() {
        val needs = _transcription.value.availability as? TranscriberAvailability.NeedsDownload ?: return
        _transcription.update { it.copy(downloadRequested = true) }
        viewModelScope.launch { transcriber.downloadLanguage(needs.languageTag) }
    }

    private suspend fun runTranscription(event: EventEntity) {
        try {
            val ready = _transcription.value.availability as? TranscriberAvailability.Ready ?: return
            if (!File(event.filePath).exists()) return
            when (val result = transcriber.transcribe(event.filePath, ready.languageTag)) {
                is TranscriptResult.Text -> container.repository.setTranscript(event.id, result.text)
                TranscriptResult.LanguageUnavailable ->
                    // El modelo desapareció o no estaba: volver a consultar qué ofrece el teléfono.
                    _transcription.update { it.copy(availability = transcriber.availability()) }
                is TranscriptResult.Failed -> {
                    Log.w(TAG, "No se pudo transcribir el evento ${event.id}: error ${result.error}")
                    _transcription.update { it.copy(failed = it.failed + event.id) }
                }
            }
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.w(TAG, "Fallo al transcribir el evento ${event.id}", e)
            _transcription.update { it.copy(failed = it.failed + event.id) }
        } finally {
            _transcription.update { it.copy(inProgress = it.inProgress - event.id) }
        }
    }

    // ---- Episodios y reproducción ----

    fun toggleEpisode(id: Long) {
        _expanded.value = _expanded.value.let { if (id in it) it - id else it + id }
    }

    fun expandEpisode(id: Long) {
        _expanded.value = _expanded.value + id
    }

    fun toggleEvent(event: EventEntity) {
        if (event.filePath.isNotEmpty()) player.toggle(eventKey(event.id), event.filePath)
    }

    fun seek(key: String, ms: Long) = player.seek(key, ms)

    fun hasFullRecording(detail: SessionDetail): Boolean =
        detail.session.fullRecordingPath?.let { File(it).exists() } == true

    fun toggleFull() {
        val path = detail.value?.session?.fullRecordingPath ?: return
        player.toggle(FULL_KEY, path)
    }

    /** Salta a la hora del evento en la grabación completa (3 s antes, para tener contexto). */
    fun playFullAt(event: EventEntity) {
        val d = detail.value ?: return
        val path = d.session.fullRecordingPath ?: return
        val pauses = d.pauses.map { RecordingOffset.Pause(it.startedAt, it.endedAt) }
        val offset = RecordingOffset.offsetMs(d.session.startedAt, event.startedAt, pauses)
        player.play(FULL_KEY, path, (offset - 3_000).coerceAtLeast(0))
    }

    fun setCategory(event: EventEntity, category: Category) {
        viewModelScope.launch { container.repository.setUserCategory(event.id, category) }
    }

    fun deleteEvent(event: EventEntity) {
        if (playerState.value.key == eventKey(event.id)) player.stop()
        viewModelScope.launch { container.repository.deleteEvent(event.id) }
    }

    /** Sonidos de la lista de ignorados (para no ofrecer "Ignorar" dos veces). */
    val ignoredSounds: StateFlow<Set<SoundType>> = container.settings.settings.map { it.ignoredSounds }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    /** Cuántos clips de [type] hay guardados en todas las noches. */
    suspend fun savedClipsOf(type: SoundType): Int = container.repository.clipsOfType(type).size

    /** "Ignorar este sonido": desde ahora no se guarda; si [deleteSaved], se borran los que ya hay. */
    fun ignoreSound(type: SoundType, deleteSaved: Boolean) {
        if (deleteSaved) player.stop()
        viewModelScope.launch {
            container.settings.setIgnored(type, true)
            if (deleteSaved) container.repository.deleteEvents(container.repository.clipsOfType(type))
        }
    }

    fun toggleFavorite() {
        val s = detail.value?.session ?: return
        viewModelScope.launch { container.repository.setFavorite(s.id, !s.isFavorite) }
    }

    override fun onCleared() {
        queue.close()
        player.release()
    }

    companion object {
        const val FULL_KEY = "full"
        private const val TAG = "NightDetailViewModel"
        fun eventKey(id: Long) = "event:$id"
    }
}
