package com.nochesonora.ui

import android.app.Application
import android.graphics.Bitmap
import android.graphics.Canvas
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.nochesonora.core.audio.WavWriter
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.core.classification.VotingClassifier
import com.nochesonora.data.SessionDetail
import com.nochesonora.data.db.EpisodeEntity
import com.nochesonora.data.db.EventEntity
import com.nochesonora.data.db.SessionEntity
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.service.HeardItem
import com.nochesonora.service.LivePhase
import com.nochesonora.service.LiveSnapshot
import com.nochesonora.ui.common.BackIcon
import com.nochesonora.ui.common.MutedText
import com.nochesonora.ui.common.PlayerState
import com.nochesonora.ui.common.ScreenHeader
import com.nochesonora.ui.common.SectionLabel
import com.nochesonora.ui.common.SquareIconButton
import com.nochesonora.ui.common.StarIcon
import com.nochesonora.ui.common.StatusPill
import com.nochesonora.ui.detail.CategoryFilter
import com.nochesonora.ui.detail.EpisodeCard
import com.nochesonora.ui.detail.categoryCounts
import com.nochesonora.ui.detail.Summary
import com.nochesonora.ui.detail.Timeline
import com.nochesonora.ui.history.HistoryContent
import com.nochesonora.ui.settings.IgnoredSounds
import com.nochesonora.core.classification.SoundType
import com.nochesonora.ui.home.CalibratedBanner
import com.nochesonora.ui.home.HomeContent
import com.nochesonora.ui.theme.Background
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.MurmurTheme
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.io.File
import java.time.Duration
import java.util.Calendar
import kotlin.math.PI
import kotlin.math.sin

/**
 * Capturas de las pantallas con los datos de la noche del 3 de noviembre, para revisar el diseño
 * sin teléfono. Solo corre con la variable NS_SCREENSHOTS; deja los PNG en build/screenshots.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class, qualifiers = "es-w390dp-h844dp-xhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ScreenshotTest {
    private val out = File("build/screenshots").apply { mkdirs() }

    private fun at(h: Int, m: Int, s: Int = 0): Long = Calendar.getInstance().apply {
        set(2026, Calendar.NOVEMBER, if (h >= 12) 3 else 4, h, m, s)
        set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    private val night = SessionEntity(
        id = 1, startedAt = at(23, 31), endedAt = at(3, 4), status = SessionStatus.COMPLETADA,
        baselineDbAtStart = -52f, lastHeartbeatAt = at(3, 4), sensitivityDb = 10, fullRecordingPath = null,
    )

    private fun shoot(name: String, steps: Int = 10, onStep: (Int) -> Unit = {}, content: @Composable () -> Unit) {
        assumeTrue(System.getenv("NS_SCREENSHOTS") != null)
        val controller = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        // Lo mismo que hace AppLanguage.wrap: fechas y números en el idioma de los recursos.
        java.util.Locale.setDefault(controller.get().resources.configuration.locales[0])
        val activity = controller.get()
        activity.setContent {
            MurmurTheme { Box(Modifier.fillMaxSize().background(Background)) { content() } }
        }
        repeat(steps) {
            onStep(it)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(150))
            Thread.sleep(170) // delay() y Dispatchers.IO corren en tiempo real
        }
        val view = activity.window.decorView
        val bitmap = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        view.draw(Canvas(bitmap))
        File(out, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
        controller.pause().stop().destroy()
    }

    @Test
    fun inicioEnVivo() {
        val levels = listOf(-60, -58, -59, -57, -54, -56, -58, -59, -57, -55, -44, -36, -39, -48, -54, -57, -58, -59, -58, -56,
            -57, -58, -59, -60, -58, -57, -54, -46, -34, -38, -49, -56, -58, -59, -57, -58, -56, -57, -58, -49)
        var live by mutableStateOf(
            LiveSnapshot(
                serviceRunning = true, sessionId = 1, startedAt = System.currentTimeMillis() - (72 * 60 + 40) * 1000L,
                phase = LivePhase.ESCUCHANDO, levelDb = -49f, baselineDb = -52f, thresholdDb = -42f, calibrationProgress = 1f,
                counts = mapOf(Category.HABLA to 8, Category.OTRO to 9),
                recent = listOf(
                    HeardItem(at(0, 41, 12), Category.HABLA, "Speech", discarded = false),
                    HeardItem(at(0, 12, 3), Category.OTRO, "Inside, small room", discarded = false),
                    HeardItem(at(0, 5, 47), Category.FONDO, "Mechanical fan", discarded = true),
                    HeardItem(at(23, 41, 20), Category.HABLA, "Speech", discarded = false),
                ),
                discardedCount = 3,
                backgroundLabel = "Mechanical fan",
            ),
        )
        shoot("1_inicio_en_vivo", steps = levels.size, onStep = { live = live.copy(levelDb = levels[it].toFloat()) }) {
            HomeContent(live = live, last = null, stopping = false, onStart = {}, onStop = {}, onOpenSession = {})
        }
    }

    @Test
    fun inicioEnReposo() {
        val last = SessionSummaryRow(night, snoreEvents = 0, speechEvents = 8, coughEvents = 0, otherEvents = 9, snoreMs = 0)
        shoot("2_inicio_reposo") {
            HomeContent(live = LiveSnapshot(), last = last, stopping = false, onStart = {}, onStop = {}, onOpenSession = {})
        }
    }

    @Test
    fun detalleDeLaNoche() = detailShot("3_detalle_noche", filter = null, openIndex = 1)

    /** Filtro en "Otro" con el viento del ventilador guardado una vez y sus repeticiones. */
    @Test
    fun detalleFiltradoRuidoRepetido() = detailShot("14_detalle_filtro", filter = Category.OTRO, openIndex = 2)

    private fun detailShot(name: String, filter: Category?, openIndex: Int) {
        val wav = File(out, "clip.wav")
        // 8 s: silencio, una frase de ~2 s y silencio, para que la onda tenga forma.
        val samples = ShortArray(16_000 * 8) { i ->
            val t = i / 16_000.0
            val env = if (t in 3.0..5.2) sin(PI * (t - 3.0) / 2.2) * (0.6 + 0.4 * sin(t * 23)) else 0.02
            (env * 9_000 * sin(2 * PI * 180 * t)).toInt().toShort()
        }
        WavWriter.writeFileAtomic(wav, samples)

        val raw = listOf(
            Triple(at(23, 31), 7_000L, Category.OTRO), Triple(at(23, 31, 20), 3_000L, Category.HABLA),
            Triple(at(23, 32), 39_000L, Category.OTRO), Triple(at(23, 33), 600L, Category.HABLA),
            Triple(at(23, 34), 1_200L, Category.HABLA), Triple(at(23, 41), 2_000L, Category.HABLA),
            Triple(at(0, 2), 1_500L, Category.HABLA), Triple(at(1, 15), 4_000L, Category.OTRO),
            Triple(at(2, 0), 2_000L, Category.HABLA), Triple(at(2, 25), 1_800L, Category.HABLA),
            Triple(at(2, 40), 2_500L, Category.HABLA),
        )
        val episodes = raw.mapIndexed { i, (start, len, cat) ->
            EpisodeEntity(id = i + 1L, sessionId = 1, category = cat, startedAt = start, endedAt = start + len, eventCount = if (i == 2) 4 else 1)
        }
        val events = episodes.map { ep ->
            EventEntity(
                id = ep.id, sessionId = 1, episodeId = ep.id, category = ep.category,
                otherLabel = when {
                    ep.category != Category.OTRO -> null
                    ep.id == 3L -> "Wind noise (microphone)"
                    else -> "Inside, small room"
                },
                startedAt = ep.startedAt, endedAt = ep.endedAt, peakDb = -38f, confidence = 0.72f,
                topLabelsJson = VotingClassifier.toJson(listOf(LabelScore("Speech", 0.72f), LabelScore("Silence", 0.11f), LabelScore("Inside, small room", 0.05f))),
                filePath = wav.absolutePath, userCategory = null,
                repeats = if (ep.id == 3L) 123 else 0,
                lastRepeatAt = if (ep.id == 3L) at(5, 40) else null,
            )
        }
        val d = SessionDetail(night, events, episodes, emptyList())
        val open = episodes[openIndex]
        val shown = if (filter == null) episodes else episodes.filter { it.category == filter }
        shoot(name) {
            Column(Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp),
                ) {
                    SquareIconButton(BackIcon, "Volver", {})
                    Text("Mar 3 nov", style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center, modifier = Modifier.weight(1f))
                    SquareIconButton(StarIcon, "Favorita", {})
                }
                Column(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Summary(d, night.endedAt!!)
                    Row(Modifier.padding(top = 8.dp)) {
                        MutedText("Línea de tiempo", Modifier.weight(1f))
                        MutedText("base −52 dBFS · 17 clips", style = Murmur.monoSmall)
                    }
                    Timeline(night.startedAt, night.endedAt!!, episodes, emptyList(), {})
                    SectionLabel("Episodios", Modifier.padding(top = 8.dp))
                    CategoryFilter(categoryCounts(events), events.size, filter, {})
                    shown.take(5).forEach { ep ->
                        EpisodeCard(
                            episode = ep, events = events.filter { it.episodeId == ep.id }, expanded = ep == open,
                            player = PlayerState(), hasFull = false, onToggle = {}, onPlay = {}, onSeek = { _, _ -> },
                            onPlayFull = {}, onCategory = { _, _ -> }, onDelete = {},
                        )
                    }
                }
            }
        }
    }

    /** Ajustes → Sonidos ignorados, con el perro y el aire acondicionado marcados desde un clip. */
    @Test
    fun sonidosIgnorados() {
        shoot("15_sonidos_ignorados") {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                ScreenHeader("Ajustes")
                IgnoredSounds(setOf(SoundType.DOG, SoundType.FAN, SoundType.DOOR), onRemove = {})
                IgnoredSounds(emptySet(), onRemove = {})
            }
        }
    }

    /** Noches del calendario: 3 de noviembre (la del día 3), 2, 1, más dos de semanas anteriores. */
    private fun historyRows(): List<SessionSummaryRow> {
        val day = 86_400_000L
        return listOf(
            SessionSummaryRow(night.copy(isFavorite = true), 0, 8, 0, 9, 0),
            SessionSummaryRow(
                night.copy(id = 2, startedAt = night.startedAt - day - 3_600_000L, endedAt = night.endedAt!! - day + 3 * 3_600_000L),
                12, 3, 2, 4, 42 * 60_000L,
            ),
            SessionSummaryRow(
                night.copy(id = 3, startedAt = night.startedAt - 2 * day, endedAt = null, status = SessionStatus.INTERRUMPIDA,
                    lastHeartbeatAt = night.startedAt - 2 * day + 2 * 3_600_000L),
                0, 0, 0, 0, 0,
            ),
            SessionSummaryRow(night.copy(id = 4, startedAt = night.startedAt - 6 * day, endedAt = night.endedAt!! - 6 * day), 5, 0, 1, 2, 18 * 60_000L),
            SessionSummaryRow(night.copy(id = 5, startedAt = night.startedAt - 9 * day, endedAt = night.endedAt!! - 9 * day), 0, 2, 0, 3, 0),
        )
    }

    @Composable
    private fun History(rows: List<SessionSummaryRow>) {
        HistoryContent(
            sessions = rows,
            today = java.time.LocalDate.of(2026, 11, 4),
            onOpenSession = {}, onFavorite = {}, onDelete = {},
        )
    }

    @Test
    fun historialCalendario() {
        shoot("4_historial") { History(historyRows()) }
    }

    @Test
    fun inicioCalibrando() {
        shoot("6_inicio_calibrando") { HomeContent(live = calibrating(), last = null, stopping = false, onStart = {}, onStop = {}, onOpenSession = {}) }
    }

    private fun calibrating() = LiveSnapshot(
        serviceRunning = true, sessionId = 1, startedAt = System.currentTimeMillis() - 23_000L,
        phase = LivePhase.CALIBRANDO, levelDb = -55f, baselineDb = -53f, thresholdDb = -43f, calibrationProgress = 23f / 60f,
        counts = emptyMap(), backgroundLabel = "Mechanical fan",
    )

    @Test
    @Config(qualifiers = "en-w390dp-h844dp-xhdpi")
    fun englishHome() {
        val last = SessionSummaryRow(night, snoreEvents = 0, speechEvents = 8, coughEvents = 0, otherEvents = 9, snoreMs = 0)
        shoot("7_en_inicio") { HomeContent(live = LiveSnapshot(), last = last, stopping = false, onStart = {}, onStop = {}, onOpenSession = {}) }
    }

    @Test
    @Config(qualifiers = "en-w390dp-h844dp-xhdpi")
    fun englishCalibrating() {
        shoot("8_en_calibrando") { HomeContent(live = calibrating(), last = null, stopping = false, onStart = {}, onStop = {}, onOpenSession = {}) }
    }

    @Test
    @Config(qualifiers = "en-w390dp-h844dp-xhdpi")
    fun englishHistory() {
        shoot("9_en_historial") { History(historyRows()) }
    }

    /** El medidor y la onda con datos fijos (en las capturas de pantalla completa aún no se han cargado). */
    @Test
    fun componentes() {
        val levels = listOf(-60, -58, -59, -57, -54, -56, -58, -59, -57, -55, -44, -36, -39, -48, -54, -57, -58, -59, -58, -56,
            -57, -58, -59, -60, -58, -57, -54, -46, -34, -38, -49, -56, -58, -59, -57, -58, -56, -57, -58, -49).map { it.toFloat() }
        val peaks = listOf(6, 10, 14, 22, 18, 26, 30, 24, 16, 12, 20, 28, 24, 14, 10, 8, 12, 18, 22, 16, 10, 8, 6, 10, 14, 12, 8, 6, 8, 10, 6, 4, 6, 8, 6, 4)
            .map { it / 32f }
        shoot("5_componentes", steps = 2) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                com.nochesonora.ui.common.MurmurCard(Modifier.fillMaxWidth()) {
                    com.nochesonora.ui.common.LevelBars(levels, baselineDb = -52f, thresholdDb = -42f)
                }
                CalibratedBanner(calibrating().copy(calibrationProgress = 1f, baselineDb = -52f))
                com.nochesonora.ui.common.MurmurCard(Modifier.fillMaxWidth()) {
                    com.nochesonora.ui.common.ClipPlayerControls(
                        key = "event:1",
                        state = PlayerState(key = "event:1", isPlaying = true, positionMs = 2_000, durationMs = 8_000),
                        onToggle = {}, onSeek = {}, peaks = peaks,
                    )
                }
            }
        }
    }

    @Test
    fun tendencias() {
        shoot("10_tendencias") {
            com.nochesonora.ui.history.HistoryContent(
                sessions = historyRows(),
                today = java.time.LocalDate.of(2026, 11, 4),
                onOpenSession = {}, onFavorite = {}, onDelete = {},
                initialTab = com.nochesonora.ui.history.HistoryTab.TRENDS,
            )
        }
    }

    @Test
    fun inicioEnPausa() {
        val live = LiveSnapshot(
            serviceRunning = true, sessionId = 1, startedAt = System.currentTimeMillis() - (95 * 60 + 12) * 1000L,
            phase = LivePhase.PAUSA, pauseReason = com.nochesonora.data.db.PauseReason.USUARIO,
            levelDb = -100f, baselineDb = -52f, thresholdDb = -42f, calibrationProgress = 1f,
            counts = mapOf(Category.HABLA to 3, Category.RONQUIDO to 2), backgroundLabel = "Mechanical fan",
        )
        shoot("11_inicio_en_pausa") { HomeContent(live = live, last = null, stopping = false, onStart = {}, onStop = {}, onOpenSession = {}) }
    }

    /** El widget tal como lo dibuja el lanzador (RemoteViews), grabando y en reposo. */
    @Test
    fun widget() {
        val last = SessionSummaryRow(night, snoreEvents = 4, speechEvents = 8, coughEvents = 0, otherEvents = 9, snoreMs = 12 * 60_000L)
        val recording = LiveSnapshot(serviceRunning = true, sessionId = 1, startedAt = System.currentTimeMillis() - (72 * 60 + 40) * 1000L, phase = LivePhase.ESCUCHANDO)
        shoot("12_widget", steps = 3) {
            Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
                for (live in listOf(LiveSnapshot(), recording)) {
                    androidx.compose.ui.viewinterop.AndroidView(
                        factory = { ctx ->
                            com.nochesonora.widget.MurmurWidget.render(ctx, live, last).apply(ctx, android.widget.FrameLayout(ctx))
                        },
                        modifier = Modifier.fillMaxWidth().height(180.dp),
                    )
                }
            }
        }
    }

    /** El logo "Luna de barras" como ícono (círculo y squircle), monocromo, glifo de estado y encabezado. */
    @Test
    fun logo() {
        @Composable
        fun Launcher(size: androidx.compose.ui.unit.Dp, shape: androidx.compose.ui.graphics.Shape, mono: Boolean = false) {
            Box(Modifier.size(size).clip(shape)) {
                if (mono) {
                    Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(0xFFD6E2FF)))
                } else {
                    androidx.compose.foundation.Image(
                        androidx.compose.ui.res.painterResource(com.nochesonora.R.drawable.ic_launcher_background), null,
                        Modifier.fillMaxSize(), contentScale = androidx.compose.ui.layout.ContentScale.FillBounds,
                    )
                }
                androidx.compose.foundation.Image(
                    androidx.compose.ui.res.painterResource(
                        if (mono) com.nochesonora.R.drawable.ic_launcher_monochrome else com.nochesonora.R.drawable.ic_launcher_foreground,
                    ),
                    null,
                    // El ícono adaptativo muestra el centro (72 de 108 dp): se amplía igual que el sistema.
                    Modifier.fillMaxSize().graphicsLayer { scaleX = 1.5f; scaleY = 1.5f },
                    colorFilter = if (mono) androidx.compose.ui.graphics.ColorFilter.tint(androidx.compose.ui.graphics.Color(0xFF1A3157)) else null,
                )
            }
        }
        shoot("13_logo", steps = 2) {
            Column(Modifier.padding(24.dp), verticalArrangement = Arrangement.spacedBy(24.dp)) {
                Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Bottom) {
                    Launcher(180.dp, androidx.compose.foundation.shape.CircleShape)
                    Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        Launcher(64.dp, androidx.compose.foundation.shape.RoundedCornerShape(22.dp))
                        Launcher(48.dp, androidx.compose.foundation.shape.CircleShape)
                        Launcher(48.dp, androidx.compose.foundation.shape.CircleShape, mono = true)
                    }
                }
                Row(
                    Modifier.fillMaxWidth().background(androidx.compose.ui.graphics.Color.Black).padding(12.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text("23:31", color = androidx.compose.ui.graphics.Color.White, style = Murmur.monoBody)
                    androidx.compose.material3.Icon(
                        androidx.compose.ui.res.painterResource(com.nochesonora.R.drawable.ic_stat_moon), null,
                        tint = androidx.compose.ui.graphics.Color.White, modifier = Modifier.size(20.dp),
                    )
                }
                ScreenHeader("Murmur")
            }
        }
    }
}
