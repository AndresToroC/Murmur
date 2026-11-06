package com.nochesonora.ui.detail

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nochesonora.R
import com.nochesonora.core.audio.WavWriter
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.classification.SoundTypes
import com.nochesonora.core.classification.VotingClassifier
import com.nochesonora.data.SessionDetail
import com.nochesonora.data.db.EpisodeEntity
import com.nochesonora.data.db.EventEntity
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.effectiveCategory
import com.nochesonora.data.db.soundType
import com.nochesonora.core.classification.IgnoreFilter
import com.nochesonora.ui.common.BackIcon
import com.nochesonora.ui.common.CategoryDot
import com.nochesonora.ui.common.ChevronDownIcon
import com.nochesonora.ui.common.ChevronUpIcon
import com.nochesonora.ui.common.ClipPlayerControls
import com.nochesonora.ui.common.ConfirmDialog
import com.nochesonora.ui.common.Dot
import com.nochesonora.ui.common.color
import com.nochesonora.ui.common.MurmurCard
import com.nochesonora.ui.common.MutedText
import com.nochesonora.ui.common.NightStatTiles
import com.nochesonora.ui.common.PlayerState
import com.nochesonora.ui.common.SecondaryButton
import com.nochesonora.ui.common.SectionLabel
import com.nochesonora.ui.common.SquareIconButton
import com.nochesonora.ui.common.StarFilledIcon
import com.nochesonora.ui.common.StarIcon
import com.nochesonora.ui.common.appViewModel
import com.nochesonora.ui.common.formatDay
import com.nochesonora.ui.common.formatDbShort
import com.nochesonora.ui.common.formatDuration
import com.nochesonora.ui.common.formatTime
import com.nochesonora.ui.common.formatTimeSeconds
import com.nochesonora.ui.common.label
import com.nochesonora.ui.common.wavPeaks
import com.nochesonora.ui.common.yamnetLabel
import androidx.compose.ui.graphics.Color
import com.nochesonora.ui.theme.Border
import com.nochesonora.ui.theme.BorderStrong
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.transcription.TranscriberAvailability
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnSurface
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.OnSurfaceSoft
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SnoreAmber
import com.nochesonora.ui.theme.Surface
import com.nochesonora.ui.theme.color
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.roundToInt

private val selectableCategories = listOf(Category.SNORING, Category.SPEECH, Category.COUGH, Category.OTHER)

@Composable
fun NightDetailScreen(sessionId: Long, onBack: () -> Unit) {
    val vm = appViewModel { NightDetailViewModel(it, sessionId) }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val player by vm.playerState.collectAsStateWithLifecycle()
    val expanded by vm.expanded.collectAsStateWithLifecycle()
    val transcription by vm.transcription.collectAsStateWithLifecycle()
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    var toDelete by remember { mutableStateOf<EventEntity?>(null) }
    var toIgnore by remember { mutableStateOf<SoundType?>(null) }
    val ignoredSounds by vm.ignoredSounds.collectAsStateWithLifecycle()
    // Episode filter by category (null = all).
    var filter by rememberSaveable { mutableStateOf<Category?>(null) }

    Column(Modifier.fillMaxSize()) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
        ) {
            SquareIconButton(BackIcon, stringResource(R.string.common_back), onBack)
            val title = detail?.session?.let { formatDay(it.startedAt) } ?: stringResource(R.string.detail_title_fallback)
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                textAlign = TextAlign.Center,
                modifier = Modifier.weight(1f),
            )
            val s = detail?.session
            if (s != null) {
                if (s.isFavorite) SquareIconButton(StarFilledIcon, stringResource(R.string.common_unfavorite), vm::toggleFavorite, tint = SnoreAmber)
                else SquareIconButton(StarIcon, stringResource(R.string.common_favorite), vm::toggleFavorite)
            } else {
                Spacer(Modifier.size(44.dp))
            }
        }

        val d = detail ?: return@Column
        // The night in progress is drawn up to "now".
        val now by produceState(System.currentTimeMillis(), d.session.status) {
            while (d.session.status == SessionStatus.IN_PROGRESS) {
                value = System.currentTimeMillis()
                delay(30_000)
            }
        }
        val endMs = d.session.endedAt ?: if (vm.isLive) now else d.session.lastHeartbeatAt
        val eventsByEpisode = remember(d.events) { d.events.groupBy { it.episodeId } }
        val soundCounts = remember(d.events) { nightSoundCounts(d.events) }
        val hasSpeech = remember(d.events) { d.events.any { it.effectiveCategory == Category.SPEECH } }
        val categoryCounts = remember(d.events) { categoryCounts(d.events) }
        // If the filtered category has no episodes left (deleted or corrected), show them all.
        val activeFilter = filter?.takeIf { it in categoryCounts }
        val shownEpisodes = remember(d.episodes, activeFilter) {
            if (activeFilter == null) d.episodes else d.episodes.filter { it.category == activeFilter }
        }
        // Items before the episodes (to jump to one from the timeline).
        val headerItems = 7

        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 8.dp, bottom = 24.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item { Summary(d, endMs) }
            item {
                Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row {
                        MutedText(stringResource(R.string.detail_timeline), Modifier.weight(1f))
                        val base = formatDbShort(d.session.baselineDbAtStart)
                        val clips = pluralStringResource(R.plurals.detail_clips, d.events.size, d.events.size)
                        MutedText(stringResource(R.string.detail_baseline_clips, base, clips), style = Murmur.monoSmall)
                    }
                    Timeline(
                        startMs = d.session.startedAt,
                        endMs = endMs,
                        episodes = d.episodes,
                        pauses = d.pauses,
                        onEpisodeTap = { ep ->
                            vm.expandEpisode(ep.id)
                            // An episode of another category is not in the filtered list: clear the filter.
                            val list = if (activeFilter == null || ep.category == activeFilter) shownEpisodes else d.episodes
                            if (list === d.episodes) filter = null
                            val index = list.indexOfFirst { it.id == ep.id }
                            if (index >= 0) scope.launch { listState.animateScrollToItem(headerItems + index) }
                        },
                    )
                    if (d.pauses.isNotEmpty()) MutedText(stringResource(R.string.detail_pauses, d.pauses.size))
                }
            }
            item { if (soundCounts.isNotEmpty()) IdentifiedSounds(soundCounts) }
            item { if (hasSpeech) TranscriptionNote(transcription, onDownload = vm::downloadLanguage) }
            item {
                if (vm.hasFullRecording(d)) {
                    MurmurCard(Modifier.fillMaxWidth().padding(top = 8.dp), radius = 14.dp, padding = 14.dp) {
                        Text(stringResource(R.string.detail_full_recording), style = MaterialTheme.typography.titleSmall)
                        Spacer(Modifier.height(10.dp))
                        ClipPlayerControls(
                            key = NightDetailViewModel.FULL_KEY,
                            state = player,
                            onToggle = vm::toggleFull,
                            onSeek = { vm.seek(NightDetailViewModel.FULL_KEY, it) },
                        )
                    }
                }
            }
            item {
                SectionLabel(
                    stringResource(if (d.episodes.isEmpty()) R.string.detail_no_events else R.string.detail_episodes),
                    Modifier.padding(top = 8.dp),
                )
            }
            item {
                if (categoryCounts.size > 1) {
                    CategoryFilter(
                        counts = categoryCounts,
                        total = d.events.size,
                        selected = activeFilter,
                        onSelect = { filter = it },
                    )
                }
            }
            items(shownEpisodes, key = { it.id }) { ep ->
                EpisodeCard(
                    episode = ep,
                    events = eventsByEpisode[ep.id].orEmpty(),
                    expanded = ep.id in expanded,
                    player = player,
                    hasFull = vm.hasFullRecording(d),
                    onToggle = { vm.toggleEpisode(ep.id) },
                    onPlay = vm::toggleEvent,
                    onSeek = vm::seek,
                    onPlayFull = vm::playFullAt,
                    onCategory = vm::setCategory,
                    onDelete = { toDelete = it },
                    ignoredSounds = ignoredSounds,
                    onIgnore = { toIgnore = it },
                    transcribing = transcription.inProgress,
                    failed = transcription.failed,
                    canTranscribe = transcription.canTranscribe,
                    onTranscribe = vm::transcribe,
                )
            }
        }
    }

    toDelete?.let { ev ->
        ConfirmDialog(
            title = stringResource(R.string.detail_delete_event_title),
            text = stringResource(R.string.detail_delete_event_text),
            confirmLabel = stringResource(R.string.common_delete),
            onConfirm = {
                vm.deleteEvent(ev)
                toDelete = null
            },
            onDismiss = { toDelete = null },
        )
    }
    toIgnore?.let { type ->
        val saved by produceState<Int?>(null, type) { value = vm.savedClipsOf(type) }
        IgnoreSoundDialog(
            type = type,
            savedClips = saved,
            onConfirm = { deleteSaved ->
                vm.ignoreSound(type, deleteSaved)
                toIgnore = null
            },
            onDismiss = { toIgnore = null },
        )
    }
}

/** "Ignore "Dog"?", with the option to also delete the existing clips of that sound. */
@Composable
internal fun IgnoreSoundDialog(type: SoundType, savedClips: Int?, onConfirm: (deleteSaved: Boolean) -> Unit, onDismiss: () -> Unit) {
    var deleteSaved by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = Surface,
        title = { Text(stringResource(R.string.ignore_dialog_title, type.label())) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.ignore_dialog_text), color = OnSurfaceSoft)
                if (savedClips != null && savedClips > 0) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth().clickable { deleteSaved = !deleteSaved },
                    ) {
                        Checkbox(checked = deleteSaved, onCheckedChange = { deleteSaved = it })
                        Text(
                            pluralStringResource(R.plurals.ignore_dialog_delete, savedClips, savedClips),
                            color = OnSurface,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(deleteSaved) }) {
                Text(stringResource(if (deleteSaved) R.string.ignore_dialog_confirm_delete else R.string.ignore_dialog_confirm), color = Primary)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel), color = OnSurfaceMuted) } },
    )
}

@Composable
internal fun Summary(d: SessionDetail, endMs: Long) {
    val s = d.session
    val counts = d.events.groupingBy { it.effectiveCategory }.eachCount()
    val snoreMs = d.episodes.filter { it.category == Category.SNORING }.sumOf { it.endedAt - it.startedAt }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        val end = s.endedAt?.let { " → ${formatTime(it)}" } ?: ""
        Text(
            stringResource(R.string.detail_summary, formatTime(s.startedAt) + end, s.status.label(), s.sensitivityDb),
            style = Murmur.monoSmall,
            color = OnSurfaceMuted,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        NightStatTiles(
            durationMs = endMs - s.startedAt,
            snoreMs = snoreMs,
            speech = counts[Category.SPEECH] ?: 0,
            cough = counts[Category.COUGH] ?: 0,
            other = counts[Category.OTHER] ?: 0,
        )
        if (s.status == SessionStatus.INTERRUPTED) {
            Text(
                stringResource(R.string.detail_interrupted),
                style = MaterialTheme.typography.bodySmall,
                color = CoughRed,
            )
        }
    }
}

/** Sound type of each OTHER event of the night, counted (for "Identified sounds"). */
internal fun nightSoundCounts(events: List<EventEntity>): List<Pair<SoundType, Int>> =
    SoundTypes.count(
        events.filter { it.effectiveCategory == Category.OTHER }
            // A repeated noise (wind, fan) counts every time it was heard.
            .flatMap { e -> List(1 + e.repeats) { e.soundType() } },
    )

/** Clips per category, in category order (only the ones present). */
internal fun categoryCounts(events: List<EventEntity>): Map<Category, Int> {
    val counts = events.groupingBy { it.effectiveCategory }.eachCount()
    return selectableCategories.filter { it in counts }.associateWith { counts.getValue(it) }
}

/** "All · Snoring · Speech · Cough · Other", with the clip count of each. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun CategoryFilter(counts: Map<Category, Int>, total: Int, selected: Category?, onSelect: (Category?) -> Unit) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        FilterPill(stringResource(R.string.detail_filter_all), total, selected == null, dot = null) { onSelect(null) }
        counts.forEach { (category, n) ->
            FilterPill(category.label(), n, selected == category, dot = category.color()) {
                onSelect(if (selected == category) null else category)
            }
        }
    }
}

@Composable
private fun FilterPill(label: String, count: Int, selected: Boolean, dot: Color?, onClick: () -> Unit) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.width(6.dp))
            Text("$count", style = Murmur.monoSmall, color = OnSurfaceMuted)
        },
        leadingIcon = dot?.let { { Dot(it, 8.dp) } },
        colors = FilterChipDefaults.filterChipColors(
            containerColor = Color.Transparent,
            labelColor = OnSurfaceSoft,
            selectedContainerColor = Primary.copy(alpha = 0.16f),
            selectedLabelColor = OnSurface,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = Border,
            selectedBorderColor = Primary,
        ),
    )
}

/** The most frequent type among the events of an OTHER episode (null if none has a type). */
private fun episodeSoundType(events: List<EventEntity>): SoundType? =
    SoundTypes.count(events.map { it.soundType() }).firstOrNull()?.first

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdentifiedSounds(counts: List<Pair<SoundType, Int>>) {
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MutedText(stringResource(R.string.detail_sounds_title))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            counts.forEach { (type, n) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Dot(type.color(), 8.dp)
                    Spacer(Modifier.width(6.dp))
                    Text(type.label(), style = MaterialTheme.typography.bodyMedium, color = OnSurfaceSoft)
                    Spacer(Modifier.width(4.dp))
                    Text("$n×", style = Murmur.monoBody, color = OnSurfaceMuted)
                }
            }
        }
    }
}

/** A line about transcription when it is not ready (and the language download button). */
@Composable
private fun TranscriptionNote(ui: TranscriptionUi, onDownload: () -> Unit) {
    val availability = ui.availability ?: return
    if (availability is TranscriberAvailability.Ready) {
        val uiLocale = androidx.compose.ui.platform.LocalConfiguration.current.locales[0]
        val language = java.util.Locale.forLanguageTag(availability.languageTag)
        val name = language.getDisplayLanguage(uiLocale).replaceFirstChar { it.titlecase(uiLocale) }
        Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            MutedText(stringResource(R.string.transcript_language, name, availability.languageTag))
            if (!availability.confirmed) {
                MutedText(stringResource(if (ui.downloadRequested) R.string.transcript_downloading else R.string.transcript_unconfirmed))
                if (!ui.downloadRequested) {
                    SecondaryButton(stringResource(R.string.transcript_download), onClick = onDownload, height = 36.dp)
                }
            }
        }
        return
    }
    val text = when {
        availability is TranscriberAvailability.NeedsDownload && ui.downloadRequested -> R.string.transcript_downloading
        availability is TranscriberAvailability.NeedsDownload -> R.string.transcript_needs_download
        availability == TranscriberAvailability.NotSupported -> R.string.transcript_not_supported
        availability == TranscriberAvailability.LanguageMissing -> R.string.transcript_language_missing
        else -> return
    }
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MutedText(stringResource(text))
        if (availability is TranscriberAvailability.NeedsDownload && !ui.downloadRequested) {
            SecondaryButton(stringResource(R.string.transcript_download), onClick = onDownload, height = 36.dp)
        }
    }
}

@Composable
internal fun EpisodeCard(
    episode: EpisodeEntity,
    events: List<EventEntity>,
    expanded: Boolean,
    player: PlayerState,
    hasFull: Boolean,
    onToggle: () -> Unit,
    onPlay: (EventEntity) -> Unit,
    onSeek: (String, Long) -> Unit,
    onPlayFull: (EventEntity) -> Unit,
    onCategory: (EventEntity, Category) -> Unit,
    onDelete: (EventEntity) -> Unit,
    /** Sounds already ignored ("Ignore this sound" is not offered for them). */
    ignoredSounds: Set<SoundType> = emptySet(),
    onIgnore: (SoundType) -> Unit = {},
    /** Events being transcribed. */
    transcribing: Set<Long> = emptySet(),
    /** Events where the recognizer failed. */
    failed: Set<Long> = emptySet(),
    /** The phone can transcribe (to show "Transcribe"). */
    canTranscribe: Boolean = false,
    onTranscribe: (EventEntity) -> Unit = {},
) {
    MurmurCard(
        Modifier.fillMaxWidth(),
        radius = 14.dp,
        borderColor = if (expanded) BorderStrong else Border,
        padding = 0.dp,
    ) {
        Column(Modifier.fillMaxWidth().clickable(onClick = onToggle).padding(horizontal = 14.dp, vertical = 12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryDot(episode.category)
                Spacer(Modifier.width(10.dp))
                // An OTHER episode shows the sound's name ("Dog", "Door"…), not "Other".
                val soundType = if (episode.category == Category.OTHER) remember(events) { episodeSoundType(events) } else null
                Text(
                    soundType?.label() ?: episode.category.label(),
                    style = MaterialTheme.typography.bodyLarge.copy(fontWeight = FontWeight.SemiBold),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(end = 8.dp),
                )
                Icon(
                    if (expanded) ChevronUpIcon else ChevronDownIcon,
                    stringResource(if (expanded) R.string.detail_collapse else R.string.detail_expand),
                    tint = OnSurfaceMuted,
                    modifier = Modifier.size(18.dp),
                )
            }
            // Time, duration and clips below the name: long sound names ("Room noise") no longer
            // cut off this data.
            val clips = pluralStringResource(R.plurals.detail_clips, episode.eventCount, episode.eventCount)
            Text(
                "${formatTime(episode.startedAt)} · ${formatDuration(episode.endedAt - episode.startedAt)} · $clips",
                style = Murmur.monoSmall,
                color = OnSurfaceMuted,
                maxLines = 1,
                modifier = Modifier.padding(start = 18.dp, top = 2.dp),
            )
            // When collapsed, a speech episode shows the start of what was understood.
            if (!expanded && episode.category == Category.SPEECH) {
                val snippet = events.firstNotNullOfOrNull { it.transcript?.takeIf(String::isNotBlank) }
                if (snippet != null) {
                    Text(
                        stringResource(R.string.transcript_quote, snippet),
                        style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic),
                        color = OnSurfaceSoft,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(start = 18.dp, top = 6.dp),
                    )
                }
            }
        }
        if (expanded) {
            events.forEachIndexed { i, ev ->
                if (i > 0) HorizontalDivider(Modifier.padding(horizontal = 14.dp), color = Border)
                EventBlock(
                    event = ev,
                    showHeader = events.size > 1,
                    player = player,
                    hasFull = hasFull,
                    transcribing = ev.id in transcribing,
                    failed = ev.id in failed,
                    canTranscribe = canTranscribe,
                    onPlay = onPlay,
                    onSeek = onSeek,
                    onPlayFull = onPlayFull,
                    onCategory = onCategory,
                    onDelete = onDelete,
                    onTranscribe = onTranscribe,
                    ignoredSounds = ignoredSounds,
                    onIgnore = onIgnore,
                )
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun EventBlock(
    event: EventEntity,
    showHeader: Boolean,
    player: PlayerState,
    hasFull: Boolean,
    transcribing: Boolean,
    failed: Boolean,
    canTranscribe: Boolean,
    onPlay: (EventEntity) -> Unit,
    onSeek: (String, Long) -> Unit,
    onPlayFull: (EventEntity) -> Unit,
    onCategory: (EventEntity, Category) -> Unit,
    onDelete: (EventEntity) -> Unit,
    onTranscribe: (EventEntity) -> Unit,
    ignoredSounds: Set<SoundType>,
    onIgnore: (SoundType) -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val key = NightDetailViewModel.eventKey(event.id)
    val category = event.effectiveCategory
    // Waveform and actual WAV duration (includes the seconds before and after the sound).
    val wave by produceState<Pair<List<Float>?, Long>>(null to 0L, event.filePath) {
        value = withContext(Dispatchers.IO) {
            val f = File(event.filePath)
            if (event.filePath.isEmpty() || !f.exists()) null to 0L
            else wavPeaks(event.filePath, 36) to ((f.length() - WavWriter.HEADER_SIZE) / 2 * 1000 / 16_000)
        }
    }
    Column(
        Modifier.padding(start = 14.dp, end = 14.dp, bottom = 14.dp, top = if (showHeader) 10.dp else 0.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        val soundType = remember(event.otherLabel, event.topLabelsJson) { event.soundType() }
        val name = when {
            event.userCategory != null -> stringResource(R.string.detail_corrected, category.label())
            category == Category.OTHER && soundType != null -> soundType.label()
            category == Category.OTHER -> stringResource(R.string.common_other_with_label, event.otherLabel?.let { yamnetLabel(it) } ?: "?")
            else -> category.label()
        }
        if (showHeader || event.userCategory != null || category == Category.OTHER) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(formatTimeSeconds(event.startedAt), style = Murmur.monoBody, color = OnSurfaceMuted)
                Spacer(Modifier.width(10.dp))
                Text(name, style = MaterialTheme.typography.bodyMedium, color = OnSurfaceSoft)
            }
        }
        if (event.filePath.isNotEmpty()) {
            ClipPlayerControls(
                key = key,
                state = player,
                onToggle = { onPlay(event) },
                onSeek = { onSeek(key, it) },
                peaks = wave.first,
                knownDurationMs = wave.second,
            )
        }
        if (category == Category.SPEECH) Transcript(event.transcript, transcribing, failed)
        if (event.repeats > 0) {
            val until = event.lastRepeatAt?.let { formatTime(it) } ?: ""
            MutedText(
                pluralStringResource(R.plurals.detail_repeats, event.repeats, event.repeats, until),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                stringResource(R.string.detail_peak_confidence, formatDbShort(event.peakDb), (event.confidence * 100).roundToInt()),
                style = Murmur.monoTiny,
                color = OnSurfaceMuted,
            )
            val top = VotingClassifier.fromJson(event.topLabelsJson)
            if (top.isNotEmpty()) {
                Text(
                    top.joinToString(" · ") { "${it.label} ${(it.score * 100).roundToInt()}%" },
                    style = Murmur.monoTiny,
                    color = OnSurfaceMuted,
                )
            }
        }
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (category == Category.SPEECH && canTranscribe && !transcribing && event.filePath.isNotEmpty()) {
                SecondaryButton(
                    stringResource(if (event.transcript == null && !failed) R.string.transcript_action else R.string.transcript_action_again),
                    onClick = { onTranscribe(event) },
                    height = 36.dp,
                )
            }
            Box {
                SecondaryButton(stringResource(R.string.detail_change_category), onClick = { menu = true }, height = 36.dp)
                DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                    selectableCategories.forEach { c ->
                        DropdownMenuItem(
                            text = { Text(c.label()) },
                            leadingIcon = { CategoryDot(c) },
                            enabled = c != category,
                            onClick = {
                                menu = false
                                onCategory(event, c)
                            },
                        )
                    }
                }
            }
            SecondaryButton(stringResource(R.string.common_delete), onClick = { onDelete(event) }, height = 36.dp)
            if (category == Category.OTHER && IgnoreFilter.canIgnore(soundType) && soundType !in ignoredSounds) {
                SecondaryButton(stringResource(R.string.detail_ignore_sound), onClick = { soundType?.let(onIgnore) }, height = 36.dp)
            }
            if (hasFull) SecondaryButton(stringResource(R.string.detail_go_to_recording), onClick = { onPlayFull(event) }, height = 36.dp)
        }
    }
}

/** What was understood from the clip, in quotes; or the transcription state. */
@Composable
private fun Transcript(text: String?, transcribing: Boolean, failed: Boolean = false) {
    when {
        transcribing -> Row(verticalAlignment = Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = OnSurfaceMuted)
            Spacer(Modifier.width(8.dp))
            MutedText(stringResource(R.string.transcript_running), style = MaterialTheme.typography.bodyMedium)
        }
        text == null && failed -> MutedText(stringResource(R.string.transcript_failed), style = MaterialTheme.typography.bodyMedium)
        text == null -> Unit
        text.isBlank() -> MutedText(stringResource(R.string.transcript_no_match), style = MaterialTheme.typography.bodyMedium)
        else -> Text(
            stringResource(R.string.transcript_quote, text),
            style = MaterialTheme.typography.bodyLarge.copy(fontStyle = FontStyle.Italic),
            color = OnSurface,
        )
    }
}
