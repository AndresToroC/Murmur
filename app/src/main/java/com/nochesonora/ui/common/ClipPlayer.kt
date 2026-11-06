package com.nochesonora.ui.common

import android.content.Context
import android.net.Uri
import androidx.annotation.OptIn
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Text
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.pointerInput
import com.nochesonora.R
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnPrimary
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.Track
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import androidx.media3.extractor.DefaultExtractorsFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File

data class PlayerState(
    /** What is playing ("event:12", "full"). */
    val key: String? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
)

/**
 * A single ExoPlayer per screen (lives in the ViewModel and is released in `onCleared`).
 * Playing another clip stops the previous one. Use from the main thread only.
 */
@OptIn(UnstableApi::class)
class ClipPlayer(context: Context, private val scope: CoroutineScope) {
    // CBR seeking: allows seeking inside full.aac (ADTS has no index).
    private val player: ExoPlayer = ExoPlayer.Builder(
        context,
        DefaultMediaSourceFactory(context, DefaultExtractorsFactory().setConstantBitrateSeekingEnabled(true)),
    ).build()

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()
    private var ticker: Job? = null

    init {
        player.addListener(object : Player.Listener {
            override fun onIsPlayingChanged(isPlaying: Boolean) {
                _state.update { it.copy(isPlaying = isPlaying) }
                if (isPlaying) startTicker() else ticker?.cancel()
                publishPosition()
            }

            override fun onPlaybackStateChanged(playbackState: Int) {
                if (playbackState == Player.STATE_ENDED) {
                    player.pause()
                    player.seekTo(0)
                }
                publishPosition()
            }
        })
    }

    fun toggle(key: String, path: String, startMs: Long = 0) {
        if (_state.value.key == key) {
            if (player.isPlaying) player.pause() else player.play()
            return
        }
        play(key, path, startMs)
    }

    fun play(key: String, path: String, startMs: Long = 0) {
        player.stop()
        player.setMediaItem(MediaItem.fromUri(Uri.fromFile(File(path))))
        player.prepare()
        if (startMs > 0) player.seekTo(startMs)
        player.play()
        _state.value = PlayerState(key = key, isPlaying = false, positionMs = startMs)
    }

    fun seek(key: String, positionMs: Long) {
        if (_state.value.key != key) return
        player.seekTo(positionMs)
        publishPosition()
    }

    fun stop() {
        player.stop()
        _state.value = PlayerState()
    }

    fun release() {
        ticker?.cancel()
        player.release()
    }

    private fun startTicker() {
        ticker?.cancel()
        ticker = scope.launch {
            while (isActive) {
                publishPosition()
                delay(200)
            }
        }
    }

    private fun publishPosition() {
        val duration = player.duration.takeIf { it > 0 } ?: 0L
        _state.update { it.copy(positionMs = player.currentPosition.coerceAtLeast(0), durationMs = duration) }
    }
}

/**
 * Peaks of a mono PCM16 WAV in [buckets] slices (0–1), to draw the clip's waveform.
 * Reads the file once; a 60 s clip is ~2 MB. Returns null if it cannot be read.
 */
fun wavPeaks(path: String, buckets: Int = 40): List<Float>? = runCatching {
    val bytes = File(path).readBytes()
    val start = 44
    val samples = (bytes.size - start) / 2
    if (samples <= 0) return@runCatching null
    val per = maxOf(1, samples / buckets)
    val peaks = FloatArray(buckets)
    for (b in 0 until buckets) {
        var max = 0
        val from = b * per
        val to = minOf(samples, from + per)
        var i = from
        while (i < to) {
            val lo = bytes[start + 2 * i].toInt() and 0xFF
            val hi = bytes[start + 2 * i + 1].toInt()
            val v = kotlin.math.abs((hi shl 8) or lo)
            if (v > max) max = v
            i++
        }
        peaks[b] = max / 32768f
    }
    // Scaled relative to the clip's peak, with a square root so soft sounds stay visible.
    val top = peaks.maxOrNull()?.takeIf { it > 0f } ?: return@runCatching peaks.map { 0.1f }
    peaks.map { kotlin.math.sqrt(it / top).coerceIn(0.1f, 1f) }
}.getOrNull()

/**
 * Round play/pause button + progress waveform (or bar); tapping the waveform seeks to that point.
 * Null [peaks] draws a thin bar (e.g. the full recording, which is not a WAV).
 */
@Composable
fun ClipPlayerControls(
    key: String,
    state: PlayerState,
    onToggle: () -> Unit,
    onSeek: (Long) -> Unit,
    modifier: Modifier = Modifier,
    peaks: List<Float>? = null,
    knownDurationMs: Long = 0L,
) {
    val active = state.key == key
    val duration = if (active && state.durationMs > 0) state.durationMs else knownDurationMs
    val progress = if (active && duration > 0) (state.positionMs.toFloat() / duration).coerceIn(0f, 1f) else 0f
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier.size(44.dp).clip(CircleShape).background(Primary).clickable(onClick = onToggle),
            contentAlignment = Alignment.Center,
        ) {
            if (active && state.isPlaying) Icon(PauseIcon, stringResource(R.string.player_pause), tint = OnPrimary, modifier = Modifier.size(20.dp))
            else Icon(Icons.Filled.PlayArrow, stringResource(R.string.player_play), tint = OnPrimary, modifier = Modifier.size(22.dp))
        }
        Spacer(Modifier.width(12.dp))
        val seekable = active && state.durationMs > 0
        Canvas(
            Modifier
                .weight(1f)
                .height(32.dp)
                .pointerInput(seekable, state.durationMs) {
                    detectTapGestures { pos ->
                        if (seekable) onSeek((pos.x / size.width * state.durationMs).toLong())
                    }
                },
        ) {
            if (peaks != null && peaks.isNotEmpty()) {
                val gap = 2.dp.toPx()
                val n = peaks.size
                val w = (size.width - gap * (n - 1)) / n
                peaks.forEachIndexed { i, p ->
                    val h = size.height * p
                    val played = (i + 0.5f) / n <= progress
                    drawRoundRect(
                        color = if (played) Primary else Track,
                        topLeft = Offset(i * (w + gap), (size.height - h) / 2f),
                        size = Size(w, h),
                        cornerRadius = CornerRadius(2.dp.toPx()),
                    )
                }
            } else {
                val h = 6.dp.toPx()
                val top = (size.height - h) / 2f
                drawRoundRect(Track, Offset(0f, top), Size(size.width, h), CornerRadius(h / 2))
                drawRoundRect(Primary, Offset(0f, top), Size(size.width * progress, h), CornerRadius(h / 2))
            }
        }
        Spacer(Modifier.width(10.dp))
        val pos = if (active) state.positionMs else 0L
        Text(
            if (duration > 0) "${formatClock(pos)} / ${formatClock(duration)}" else formatClock(pos),
            style = Murmur.monoSmall,
            color = OnSurfaceMuted,
        )
    }
}
