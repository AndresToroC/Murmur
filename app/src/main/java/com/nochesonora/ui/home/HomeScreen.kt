package com.nochesonora.ui.home

import android.Manifest
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.net.toUri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nochesonora.BuildConfig
import com.nochesonora.R
import com.nochesonora.core.classification.Category
import com.nochesonora.data.db.PauseReason
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.service.HeardItem
import com.nochesonora.service.LivePhase
import com.nochesonora.service.LiveSnapshot
import com.nochesonora.ui.common.ChevronRightIcon
import com.nochesonora.ui.common.ConfirmDialog
import com.nochesonora.ui.common.Dot
import com.nochesonora.ui.common.LevelBars
import com.nochesonora.ui.common.MurmurCard
import com.nochesonora.ui.common.MutedText
import com.nochesonora.ui.common.NightStatTiles
import com.nochesonora.ui.common.PrimaryButton
import com.nochesonora.ui.common.ScreenHeader
import com.nochesonora.ui.common.SecondaryButton
import com.nochesonora.ui.common.SectionLabel
import com.nochesonora.ui.common.StatTile
import com.nochesonora.ui.common.StatusPill
import com.nochesonora.ui.common.TileRow
import com.nochesonora.ui.common.appViewModel
import com.nochesonora.ui.common.formatBytes
import com.nochesonora.ui.common.formatClock
import com.nochesonora.ui.common.formatClockLong
import com.nochesonora.ui.common.formatDay
import com.nochesonora.ui.common.formatDbShort
import com.nochesonora.ui.common.formatTime
import com.nochesonora.ui.common.formatTimeSeconds
import com.nochesonora.ui.common.label
import com.nochesonora.ui.common.pauseLabel
import com.nochesonora.ui.common.yamnetLabel
import com.nochesonora.ui.theme.Border
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.ui.theme.Murmur
import com.nochesonora.ui.theme.OnSurface
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.OnSurfaceSoft
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SnoreAmber
import com.nochesonora.ui.theme.Track
import com.nochesonora.ui.theme.color
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Length of the initial calibration (DetectorConfig.calibrationMs). */
private const val CALIBRATION_MS = 60_000L

/** How long the "Environment measured" notice stays up after calibration ends. */
private const val CALIBRATED_BANNER_MS = 12_000L

@Composable
fun HomeScreen(
    onOpenSession: (Long) -> Unit,
    /** true when the widget or the Quick Settings tile asked to start the night. */
    startRequested: Boolean = false,
    onStartHandled: () -> Unit = {},
) {
    val vm = appViewModel { HomeViewModel(it) }
    val live by vm.live.collectAsStateWithLifecycle()
    val settings by vm.settings.collectAsStateWithLifecycle()
    val last by vm.lastSession.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    var showBatteryDialog by rememberSaveable { mutableStateOf(false) }
    var showSpaceDialog by rememberSaveable { mutableStateOf(false) }
    var showStopDialog by rememberSaveable { mutableStateOf(false) }
    var stopping by remember { mutableStateOf(false) }
    LaunchedEffect(live.serviceRunning) { if (!live.serviceRunning) stopping = false }

    fun message(text: String) {
        scope.launch { snackbar.showSnackbar(text) }
    }

    // Step 3: free space (A13). Step 4: start the service.
    fun checkSpaceAndStart() {
        if (vm.freeBytes() < HomeViewModel.MIN_FREE_BYTES) showSpaceDialog = true else vm.start(context)
    }

    val batteryLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        checkSpaceAndStart()
    }

    fun requestBatteryExemption() {
        vm.markBatteryPromptShown()
        val direct = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, "package:${context.packageName}".toUri())
        try {
            batteryLauncher.launch(direct)
        } catch (e: ActivityNotFoundException) {
            try {
                batteryLauncher.launch(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: ActivityNotFoundException) {
                checkSpaceAndStart()
            }
        }
    }

    // Step 2: battery optimization exemption, asked only once (A16).
    fun afterPermissions() {
        val askBattery = settings?.batteryPromptShown == false && !isIgnoringBatteryOptimizations(context)
        if (askBattery) showBatteryDialog = true else checkSpaceAndStart()
    }

    // Step 1: permissions. Strings are resolved here (not inside the callback) so they follow the app language.
    val noMicPermission = stringResource(R.string.home_no_mic_permission)
    val noNotificationPermission = stringResource(R.string.home_no_notification_permission)
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        val audio = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(context, Manifest.permission.RECORD_AUDIO)
        if (!audio) {
            message(noMicPermission)
            return@rememberLauncherForActivityResult
        }
        if (Build.VERSION.SDK_INT >= 33 && result[Manifest.permission.POST_NOTIFICATIONS] == false) {
            message(noNotificationPermission)
        }
        afterPermissions()
    }

    fun onStartClicked() {
        val needed = buildList {
            if (!hasPermission(context, Manifest.permission.RECORD_AUDIO)) add(Manifest.permission.RECORD_AUDIO)
            if (Build.VERSION.SDK_INT >= 33 && !hasPermission(context, Manifest.permission.POST_NOTIFICATIONS)) {
                add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }
        if (needed.isEmpty()) afterPermissions() else permissionLauncher.launch(needed.toTypedArray())
    }

    // Request from outside the app: same path as the button (permissions, battery, space).
    LaunchedEffect(startRequested) {
        if (startRequested) {
            onStartHandled()
            if (!live.serviceRunning) onStartClicked()
        }
    }

    Box(Modifier.fillMaxSize()) {
        HomeContent(
            live = live,
            last = last,
            stopping = stopping,
            onStart = ::onStartClicked,
            onStop = { showStopDialog = true },
            onOpenSession = onOpenSession,
            onPause = { vm.pause(context) },
            onResume = { vm.resume(context) },
        )
        SnackbarHost(snackbar, Modifier.align(Alignment.BottomCenter))
    }

    if (showBatteryDialog) {
        ConfirmDialog(
            title = stringResource(R.string.home_battery_title),
            text = stringResource(R.string.home_battery_text),
            confirmLabel = stringResource(R.string.action_continue),
            dismissLabel = stringResource(R.string.action_not_now),
            onConfirm = {
                showBatteryDialog = false
                requestBatteryExemption()
            },
            onDismiss = {
                showBatteryDialog = false
                vm.markBatteryPromptShown()
                checkSpaceAndStart()
            },
        )
    }
    if (showSpaceDialog) {
        ConfirmDialog(
            title = stringResource(R.string.home_space_title),
            text = stringResource(R.string.home_space_text, formatBytes(vm.freeBytes())),
            confirmLabel = stringResource(R.string.home_space_confirm),
            onConfirm = {
                showSpaceDialog = false
                vm.start(context)
            },
            onDismiss = { showSpaceDialog = false },
        )
    }
    if (showStopDialog) {
        ConfirmDialog(
            title = stringResource(R.string.home_stop_title),
            text = stringResource(R.string.home_stop_text),
            confirmLabel = stringResource(R.string.home_stop),
            onConfirm = {
                showStopDialog = false
                stopping = true
                vm.stop(context)
            },
            onDismiss = { showStopDialog = false },
        )
    }
}

/** true during the first minute, while the room's background noise is being measured. */
internal val LiveSnapshot.isCalibrating: Boolean
    get() = serviceRunning && calibrationProgress < 1f

/** Home content, without permissions or dialogs (also used by the screenshot capture). */
@Composable
internal fun HomeContent(
    live: LiveSnapshot,
    last: SessionSummaryRow?,
    stopping: Boolean,
    onStart: () -> Unit,
    onStop: () -> Unit,
    onOpenSession: (Long) -> Unit,
    onPause: () -> Unit = {},
    onResume: () -> Unit = {},
) {
    Column(Modifier.fillMaxSize().padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 16.dp)) {
        ScreenHeader("Murmur") {
            when {
                !live.serviceRunning -> StatusPill("v${BuildConfig.VERSION_NAME}")
                live.phase == LivePhase.PAUSED -> StatusPill(stringResource(R.string.home_pill_paused), SnoreAmber)
                live.isCalibrating -> StatusPill(stringResource(R.string.home_pill_calibrating), Primary)
                else -> StatusPill(stringResource(R.string.home_pill_recording))
            }
        }
        Spacer(Modifier.height(18.dp))
        Column(
            Modifier.weight(1f).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(18.dp),
        ) {
            if (live.serviceRunning) LivePanel(live) else IdlePanel(last = last, onOpenSession = onOpenSession)
        }
        Spacer(Modifier.height(12.dp))
        val centered = MaterialTheme.typography.bodySmall.copy(textAlign = TextAlign.Center)
        if (live.serviceRunning) {
            if (stopping) {
                Row(
                    Modifier.fillMaxWidth().height(56.dp),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(stringResource(R.string.home_saving), color = OnSurfaceSoft)
                }
            } else {
                val userPaused = live.phase == LivePhase.PAUSED && live.pauseReason == PauseReason.USER
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    if (userPaused) {
                        PrimaryButton(stringResource(R.string.home_resume), onClick = onResume, modifier = Modifier.weight(1f))
                    } else {
                        SecondaryButton(stringResource(R.string.home_pause), onClick = onPause, modifier = Modifier.weight(1f))
                    }
                    SecondaryButton(stringResource(R.string.home_stop), onClick = onStop, modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(8.dp))
            val pausedNote = live.phase == LivePhase.PAUSED && live.pauseReason == PauseReason.USER
            MutedText(
                stringResource(if (pausedNote) R.string.home_paused_note else R.string.home_keep_charging),
                Modifier.fillMaxWidth(),
                centered,
            )
        } else {
            PrimaryButton(stringResource(R.string.home_start), onClick = onStart)
            Spacer(Modifier.height(8.dp))
            MutedText(stringResource(R.string.privacy_note), Modifier.fillMaxWidth(), centered)
        }
    }
}

@Composable
private fun IdlePanel(last: SessionSummaryRow?, onOpenSession: (Long) -> Unit) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(10_000)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        SectionLabel(stringResource(R.string.home_tonight))
        Text(formatTime(now), style = Murmur.clock)
        Text(
            stringResource(R.string.home_ready, formatDay(now)),
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = OnSurfaceSoft,
        )
    }

    MurmurCard(Modifier.fillMaxWidth()) {
        Text(
            stringResource(R.string.home_tips_title),
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
            color = OnSurfaceMuted,
        )
        Spacer(Modifier.height(12.dp))
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Tip(stringResource(R.string.home_tip_charger))
            Tip(stringResource(R.string.home_tip_near_bed))
            Tip(stringResource(R.string.home_tip_screen_off))
            Tip(stringResource(R.string.home_tip_calibration))
        }
    }

    // The most recent finished session (completed or interrupted); one still in progress does not count.
    if (last != null) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.home_last_night))
            val s = last.session
            MurmurCard(Modifier.fillMaxWidth(), onClick = { onOpenSession(s.id) }) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(formatDay(s.startedAt), style = MaterialTheme.typography.titleMedium)
                        val end = s.endedAt?.let { " → ${formatTime(it)}" } ?: ""
                        Text("${formatTime(s.startedAt)}$end", style = Murmur.monoBody, color = OnSurfaceMuted)
                    }
                    Icon(ChevronRightIcon, stringResource(R.string.action_open), tint = OnSurfaceMuted, modifier = Modifier.size(20.dp))
                }
                Spacer(Modifier.height(12.dp))
                NightStatTiles(
                    durationMs = (s.endedAt ?: s.lastHeartbeatAt) - s.startedAt,
                    snoreMs = last.snoreMs,
                    speech = last.speechEvents,
                    cough = last.coughEvents,
                    other = last.otherEvents,
                )
            }
        }
    }
}

@Composable
private fun Tip(text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Dot(Primary, 6.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium.copy(fontSize = 15.sp), color = OnSurface)
    }
}

@Composable
private fun LivePanel(live: LiveSnapshot) {
    val now by produceState(System.currentTimeMillis()) {
        while (true) {
            value = System.currentTimeMillis()
            delay(1_000)
        }
    }
    // Short level history for the bar meter (one bar every 150 ms ≈ 6 s on screen).
    val currentLevel by rememberUpdatedState(live.levelDb)
    val levels = remember { mutableStateListOf<Float>() }
    LaunchedEffect(Unit) {
        while (true) {
            levels.add(currentLevel)
            if (levels.size > 40) levels.removeAt(0)
            delay(150)
        }
    }

    // Brief "Environment measured" notice right after calibration ends (only if calibration was seen).
    var sawCalibration by remember { mutableStateOf(live.isCalibrating) }
    var showCalibrated by remember { mutableStateOf(false) }
    LaunchedEffect(live.isCalibrating) {
        if (live.isCalibrating) {
            sawCalibration = true
        } else if (sawCalibration) {
            showCalibrated = true
            delay(CALIBRATED_BANNER_MS)
            showCalibrated = false
        }
    }

    val elapsed = if (live.startedAt > 0) now - live.startedAt else 0L
    val status = when {
        live.sessionId == null -> stringResource(R.string.home_status_preparing)
        live.phase == LivePhase.PAUSED -> pauseLabel(live.pauseReason)
        live.isCalibrating -> stringResource(R.string.home_status_calibrating)
        live.phase == LivePhase.EVENT -> stringResource(R.string.home_status_event)
        else -> stringResource(R.string.home_status_recording)
    }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.padding(top = 8.dp)) {
        SectionLabel(stringResource(R.string.home_session_time))
        Text(formatClockLong(elapsed), style = Murmur.clock)
        Text(
            if (live.startedAt > 0) stringResource(R.string.home_status_since, status, formatTime(live.startedAt)) else status,
            style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp),
            color = if (live.phase == LivePhase.EVENT) Primary else OnSurfaceSoft,
        )
    }

    if (live.isCalibrating) CalibrationCard(live) else if (showCalibrated) CalibratedBanner(live)

    MurmurCard(Modifier.fillMaxWidth()) {
        Row {
            Text(
                stringResource(R.string.home_live_level),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp),
                color = OnSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            Text("${formatDbShort(live.levelDb)} dBFS", style = Murmur.monoBody, color = OnSurfaceMuted)
        }
        Spacer(Modifier.height(12.dp))
        LevelBars(levels, live.baselineDb, live.thresholdDb)
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.SpaceBetween, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.home_baseline, formatDbShort(live.baselineDb)), style = Murmur.monoSmall, color = OnSurfaceMuted)
            Text(stringResource(R.string.home_threshold, formatDbShort(live.thresholdDb)), style = Murmur.monoSmall, color = OnSurfaceMuted)
            val backgroundName = live.backgroundLabel?.let { yamnetLabel(it).lowercase() } ?: "—"
            Text(stringResource(R.string.home_background, backgroundName), style = Murmur.monoSmall, color = OnSurfaceMuted, maxLines = 1)
        }
    }

    TileRow {
        listOf(Category.SNORING, Category.SPEECH, Category.COUGH, Category.OTHER).forEach {
            StatTile(
                value = "${live.counts[it] ?: 0}",
                label = it.label(),
                dot = it.color(),
                modifier = Modifier.weight(1f),
            )
        }
    }

    if (!live.classifierAvailable) {
        Text(
            stringResource(R.string.home_classifier_missing),
            color = CoughRed,
            style = MaterialTheme.typography.bodySmall,
        )
    }

    RecentHeard(live)
}

/**
 * First minute: Murmur measures the room's background noise. Shows the progress and what comes
 * next. Sounds during this minute are still detected (a provisional threshold applies from 5 s).
 */
@Composable
internal fun CalibrationCard(live: LiveSnapshot) {
    val progress = live.calibrationProgress.coerceIn(0f, 1f)
    val elapsedMs = (progress * CALIBRATION_MS).toLong()
    val remainingSec = ((CALIBRATION_MS - elapsedMs + 999) / 1000).coerceAtLeast(0)
    MurmurCard(Modifier.fillMaxWidth(), borderColor = Primary.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge("1", active = true)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_calibration_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.home_calibration_text),
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceSoft,
        )
        Spacer(Modifier.height(14.dp))
        ProgressLine(progress)
        Spacer(Modifier.height(8.dp))
        Row(Modifier.fillMaxWidth()) {
            Text(
                "${formatClock(elapsedMs)} / 1:00",
                style = Murmur.monoSmall,
                color = OnSurfaceMuted,
                modifier = Modifier.weight(1f),
            )
            Text(stringResource(R.string.home_calibration_remaining, remainingSec), style = Murmur.monoSmall, color = OnSurfaceMuted)
        }
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            StepBadge("2", active = false)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_calibration_next),
                style = MaterialTheme.typography.bodySmall,
                color = OnSurfaceMuted,
            )
        }
    }
}

/** Brief notice when the calibration minute ends. */
@Composable
internal fun CalibratedBanner(live: LiveSnapshot) {
    MurmurCard(Modifier.fillMaxWidth(), borderColor = Primary.copy(alpha = 0.45f)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("✓", color = Primary, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.width(10.dp))
            Text(
                stringResource(R.string.home_calibrated_title),
                style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.SemiBold),
            )
        }
        Spacer(Modifier.height(6.dp))
        val backgroundName = live.backgroundLabel?.let { yamnetLabel(it).lowercase() }
        Text(
            if (backgroundName != null) {
                stringResource(R.string.home_calibrated_text_bg, backgroundName, formatDbShort(live.baselineDb))
            } else {
                stringResource(R.string.home_calibrated_text, formatDbShort(live.baselineDb))
            },
            style = MaterialTheme.typography.bodyMedium,
            color = OnSurfaceSoft,
        )
    }
}

@Composable
private fun StepBadge(number: String, active: Boolean) {
    Box(
        Modifier
            .size(22.dp)
            .clip(RoundedCornerShape(11.dp))
            .background(if (active) Primary else Track),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            number,
            style = Murmur.monoTiny.copy(fontWeight = FontWeight.Bold),
            color = if (active) MaterialTheme.colorScheme.onPrimary else OnSurfaceMuted,
        )
    }
}

@Composable
private fun ProgressLine(progress: Float) {
    Box(
        Modifier
            .fillMaxWidth()
            .height(6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(Border),
    ) {
        Box(
            Modifier
                .fillMaxWidth(progress)
                .fillMaxHeight()
                .clip(RoundedCornerShape(3.dp))
                .background(Primary),
        )
    }
}

/** "Recently heard": every classified clip, including those ignored as background. */
@Composable
private fun RecentHeard(live: LiveSnapshot) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.home_recent))
        if (live.recent.isEmpty()) {
            MutedText(
                stringResource(if (live.thresholdDb == null) R.string.home_recent_empty_warmup else R.string.home_recent_empty),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        live.recent.forEach { HeardRow(it) }
        if (live.discardedCount > 0) {
            MutedText(stringResource(R.string.home_recent_discarded, live.discardedCount))
        }
        if (live.repeatedCount > 0) {
            MutedText(stringResource(R.string.home_recent_repeated, live.repeatedCount))
        }
        if (live.ignoredCount > 0) {
            MutedText(stringResource(R.string.home_recent_user_ignored, live.ignoredCount))
        }
        if (live.saveErrors > 0) {
            Text(
                stringResource(R.string.home_recent_errors, live.saveErrors, live.lastError.orEmpty()),
                color = CoughRed,
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun HeardRow(item: HeardItem) {
    val label = item.label?.let { yamnetLabel(it) }
    val categoryLabel = item.category.label()
    val dot: Color
    val text: String
    val color: Color
    when {
        item.saveFailed -> {
            dot = CoughRed; text = stringResource(R.string.home_heard_not_saved, categoryLabel); color = CoughRed
        }
        item.ignoredType != null -> {
            dot = Track
            text = stringResource(R.string.home_heard_user_ignored, item.ignoredType.label())
            color = OnSurfaceMuted
        }
        item.repeated -> {
            dot = Track
            text = stringResource(R.string.home_heard_repeated, label ?: categoryLabel)
            color = OnSurfaceMuted
        }
        item.discarded -> {
            dot = Track
            text = if (label != null) stringResource(R.string.home_heard_ignored_label, label) else stringResource(R.string.home_heard_ignored)
            color = OnSurfaceMuted
        }
        else -> {
            dot = item.category.color()
            text = categoryLabel + (label?.takeIf { item.category == Category.OTHER }?.let { " · $it" } ?: "")
            color = OnSurface
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(formatTimeSeconds(item.atMs), style = Murmur.monoBody, color = OnSurfaceMuted, modifier = Modifier.width(72.dp))
        Dot(dot, 8.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge.copy(fontSize = 15.sp), color = color)
    }
}

private fun hasPermission(context: Context, permission: String) =
    ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED

private fun isIgnoringBatteryOptimizations(context: Context): Boolean =
    context.getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(context.packageName) == true
