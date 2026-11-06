package com.nochesonora.ui.diagnostics

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nochesonora.R
import com.nochesonora.core.classification.Category
import com.nochesonora.core.classification.LabelScore
import com.nochesonora.service.LivePhase
import com.nochesonora.ui.common.LevelMeter
import com.nochesonora.ui.common.BackIcon
import com.nochesonora.ui.common.MurmurCard
import com.nochesonora.ui.common.SquareIconButton
import androidx.compose.foundation.layout.width
import com.nochesonora.ui.common.MutedText
import com.nochesonora.ui.common.appViewModel
import com.nochesonora.ui.common.formatDb
import com.nochesonora.ui.common.label
import com.nochesonora.ui.common.pauseLabel
import com.nochesonora.ui.theme.CoughRed
import kotlin.math.roundToInt

@Composable
fun DiagnosticsScreen(onBack: () -> Unit) {
    val vm = appViewModel { DiagnosticsViewModel(it) }
    val live by vm.live.collectAsStateWithLifecycle()
    val test by vm.test.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var denied by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        denied = !granted
        if (granted) vm.runTest()
    }

    Column(Modifier.fillMaxSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp, bottom = 8.dp)) {
            SquareIconButton(BackIcon, stringResource(R.string.common_back), onBack)
            Spacer(Modifier.width(14.dp))
            Text(stringResource(R.string.diag_title), style = MaterialTheme.typography.titleLarge)
        }
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 8.dp),
        ) {
            Text(stringResource(R.string.diag_live), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(8.dp))
            MurmurCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                Column(Modifier.padding(16.dp)) {
                    if (!live.serviceRunning) {
                        Text(stringResource(R.string.diag_no_session))
                    } else {
                        LevelMeter(live.levelDb, live.baselineDb, live.thresholdDb)
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.diag_level, formatDb(live.levelDb)))
                        Text(stringResource(R.string.diag_base_threshold, formatDb(live.baselineDb), formatDb(live.thresholdDb)))
                        val phase = when (live.phase) {
                            LivePhase.CALIBRATING -> stringResource(R.string.diag_phase_calibrating, (live.calibrationProgress * 100).roundToInt())
                            LivePhase.LISTENING -> stringResource(R.string.diag_phase_listening)
                            LivePhase.EVENT -> stringResource(R.string.diag_phase_event)
                            LivePhase.PAUSED -> stringResource(R.string.diag_phase_paused, pauseLabel(live.pauseReason))
                        }
                        Text(stringResource(R.string.diag_detector_state, phase))
                        if (live.audioSource.isNotEmpty()) MutedText(stringResource(R.string.diag_audio_source, live.audioSource))
                        if (live.droppedEvents > 0) MutedText(stringResource(R.string.diag_dropped, live.droppedEvents))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.diag_last_clip), style = MaterialTheme.typography.titleMedium)
                        if (live.lastTop3.isEmpty()) {
                            MutedText(stringResource(R.string.diag_none_yet))
                        } else {
                            val cat = live.lastCategory?.label() ?: "—"
                            Text(if (live.lastDiscarded) stringResource(R.string.diag_discarded, cat) else cat)
                            LabelList(live.lastTop3)
                        }
                    }
                }
            }

            Spacer(Modifier.height(24.dp))
            Text(stringResource(R.string.diag_test_title), style = MaterialTheme.typography.titleMedium)
            MutedText(stringResource(R.string.diag_test_hint))
            Spacer(Modifier.height(8.dp))
            Button(
                onClick = {
                    val granted = ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) ==
                        PackageManager.PERMISSION_GRANTED
                    if (granted) vm.runTest() else permission.launch(Manifest.permission.RECORD_AUDIO)
                },
                enabled = !live.serviceRunning && !vm.isTesting,
                modifier = Modifier.fillMaxWidth(),
            ) { Text(stringResource(R.string.diag_test_title)) }
            if (live.serviceRunning) MutedText(stringResource(R.string.diag_busy))
            if (denied) Text(stringResource(R.string.diag_permission), color = CoughRed)
            Spacer(Modifier.height(12.dp))

            when (val t = test) {
                TestState.Idle -> Unit
                is TestState.Recording -> {
                    Text(stringResource(R.string.diag_recording))
                    LinearProgressIndicator(progress = { t.progress }, modifier = Modifier.fillMaxWidth())
                }
                TestState.Classifying -> {
                    Text(stringResource(R.string.diag_classifying))
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                is TestState.Failed -> Text(
                    if (t.detail != null) stringResource(t.messageRes, t.detail) else stringResource(t.messageRes),
                    color = CoughRed,
                )
                is TestState.Done -> MurmurCard(Modifier.fillMaxWidth(), padding = 0.dp) {
                    Column(Modifier.padding(16.dp)) {
                        val r = t.result
                        Text(stringResource(R.string.diag_mean_level, formatDb(t.meanLevelDb)))
                        val cat = if (r.category == Category.OTHER) stringResource(R.string.common_other_with_label, r.otherLabel.toString()) else r.category.label()
                        Text(
                            stringResource(if (r.category == Category.BACKGROUND) R.string.diag_category_discarded else R.string.diag_category, cat),
                            style = MaterialTheme.typography.titleMedium,
                        )
                        Text(stringResource(R.string.diag_confidence, (r.confidence * 100).roundToInt(), r.windowCount))
                        Text(stringResource(R.string.diag_snore_like, stringResource(if (r.snore.isSnoreLike) R.string.diag_yes else R.string.diag_no)))
                        MutedText(
                            stringResource(
                                R.string.diag_periodic,
                                stringResource(if (r.snore.isPeriodic) R.string.diag_yes_lower else R.string.diag_no_lower),
                                r.snore.peakCount,
                                (r.snore.lowFrequencyRatio * 100).roundToInt(),
                            ),
                        )
                        MutedText(stringResource(R.string.diag_audio_source, t.source))
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.diag_labels), style = MaterialTheme.typography.titleMedium)
                        LabelList(r.meanScores.take(15))
                    }
                }
            }
        }
    }
}

@Composable
private fun LabelList(labels: List<LabelScore>) {
    labels.forEach { (label, score) ->
        Row(Modifier.fillMaxWidth()) {
            Text(label, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text("${(score * 100).roundToInt()} %", style = MaterialTheme.typography.bodyMedium)
        }
    }
}
