package com.nochesonora.ui.settings

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.nochesonora.BuildConfig
import com.nochesonora.R
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.detection.Sensitivity
import com.nochesonora.data.Retention
import com.nochesonora.data.settings.AppLanguage
import com.nochesonora.data.settings.Language
import com.nochesonora.ui.common.ChevronRightIcon
import com.nochesonora.ui.common.ConfirmDialog
import com.nochesonora.ui.common.Dot
import com.nochesonora.ui.common.color
import com.nochesonora.ui.common.label
import com.nochesonora.ui.common.MurmurCard
import com.nochesonora.ui.common.MutedText
import com.nochesonora.ui.common.ScreenHeader
import com.nochesonora.ui.common.appViewModel
import com.nochesonora.ui.common.formatBytes
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.ui.theme.OnSurfaceMuted
import com.nochesonora.ui.theme.Primary

@Composable
fun SettingsScreen(onOpenDiagnostics: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it) }
    val settings by vm.settings.collectAsStateWithLifecycle()
    val storage by vm.storage.collectAsStateWithLifecycle()
    val live by vm.live.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmFull by remember { mutableStateOf(false) }
    var deleteStep by remember { mutableIntStateOf(0) }

    Column(
        Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 20.dp),
    ) {
        ScreenHeader(stringResource(R.string.settings_title))
        val s = settings ?: return@Column
        Spacer(Modifier.height(20.dp))

        // Language: each option is written in its own language so it is recognizable even without knowing the other.
        Text(stringResource(R.string.settings_language), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val current = remember { AppLanguage.get(context) }
        val languages = listOf(Language.ES to "Español", Language.EN to "English")
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            languages.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = current == value,
                    onClick = {
                        if (value != current) {
                            AppLanguage.set(context, value)
                            context.findActivity()?.recreate()
                        }
                    },
                    shape = SegmentedButtonDefaults.itemShape(i, languages.size),
                ) { Text(label) }
            }
        }
        MutedText(
            stringResource(
                if (live.serviceRunning) R.string.settings_language_note_live else R.string.settings_language_note,
            ),
        )

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_sensitivity), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val sensitivities = listOf(
            Sensitivity.BAJA to stringResource(R.string.settings_sensitivity_low),
            Sensitivity.MEDIA to stringResource(R.string.settings_sensitivity_medium),
            Sensitivity.ALTA to stringResource(R.string.settings_sensitivity_high),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            sensitivities.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = s.sensitivity == value,
                    onClick = { vm.setSensitivity(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, sensitivities.size),
                ) { Text(label) }
            }
        }
        MutedText(stringResource(R.string.settings_sensitivity_note))

        Spacer(Modifier.height(24.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.settings_full_recording), style = MaterialTheme.typography.titleMedium)
                MutedText(stringResource(R.string.settings_full_recording_note))
            }
            Switch(
                checked = s.saveFullRecording,
                onCheckedChange = { on -> if (on) confirmFull = true else vm.setSaveFull(false) },
            )
        }

        Spacer(Modifier.height(24.dp))
        SwitchRow(
            title = stringResource(R.string.settings_morning_summary),
            note = stringResource(R.string.settings_morning_summary_note),
            checked = s.morningSummary,
            onChange = vm::setMorningSummary,
        )

        Spacer(Modifier.height(24.dp))
        SwitchRow(
            title = stringResource(R.string.settings_transcribe),
            note = stringResource(R.string.settings_transcribe_note),
            checked = s.transcribeSpeech,
            onChange = vm::setTranscribeSpeech,
        )

        Spacer(Modifier.height(24.dp))
        SwitchRow(
            title = stringResource(R.string.settings_group_repeats),
            note = stringResource(R.string.settings_group_repeats_note),
            checked = s.groupRepeats,
            onChange = vm::setGroupRepeats,
        )

        Spacer(Modifier.height(24.dp))
        IgnoredSounds(s.ignoredSounds, onRemove = { vm.setIgnored(it, false) })

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_retention), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(8.dp))
        val retentions = listOf(
            Retention.SIEMPRE to stringResource(R.string.settings_retention_forever),
            Retention.DIAS_7 to stringResource(R.string.settings_retention_7),
            Retention.DIAS_30 to stringResource(R.string.settings_retention_30),
        )
        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
            retentions.forEachIndexed { i, (value, label) ->
                SegmentedButton(
                    selected = s.retention == value,
                    onClick = { vm.setRetention(value) },
                    shape = SegmentedButtonDefaults.itemShape(i, retentions.size),
                ) { Text(label) }
            }
        }
        MutedText(stringResource(R.string.settings_retention_note))

        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.settings_storage), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        storage?.let {
            Text(stringResource(R.string.settings_storage_usage, formatBytes(it.usedBytes), formatBytes(it.freeBytes)))
        }
        Spacer(Modifier.height(8.dp))
        OutlinedButton(onClick = { deleteStep = 1 }, enabled = !live.serviceRunning) {
            Text(
                stringResource(R.string.settings_delete_all),
                color = if (live.serviceRunning) MaterialTheme.colorScheme.onSurfaceVariant else CoughRed,
            )
        }
        if (live.serviceRunning) MutedText(stringResource(R.string.settings_unavailable_live))

        Spacer(Modifier.height(24.dp))
        MurmurCard(Modifier.fillMaxWidth(), onClick = onOpenDiagnostics) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.settings_diagnostics), style = MaterialTheme.typography.titleMedium)
                    MutedText(stringResource(R.string.settings_diagnostics_note))
                }
                Icon(ChevronRightIcon, null, tint = OnSurfaceMuted, modifier = Modifier.size(20.dp))
            }
        }

        Spacer(Modifier.height(32.dp))
        MutedText("Murmur ${BuildConfig.VERSION_NAME}")
        MutedText(stringResource(R.string.privacy_note))
    }

    if (confirmFull) {
        ConfirmDialog(
            title = stringResource(R.string.settings_full_dialog_title),
            text = stringResource(R.string.settings_full_dialog_text),
            confirmLabel = stringResource(R.string.settings_full_dialog_confirm),
            onConfirm = {
                confirmFull = false
                vm.setSaveFull(true)
            },
            onDismiss = { confirmFull = false },
        )
    }
    when (deleteStep) {
        1 -> ConfirmDialog(
            title = stringResource(R.string.settings_delete_dialog_title),
            text = stringResource(R.string.settings_delete_dialog_text),
            confirmLabel = stringResource(R.string.action_continue),
            onConfirm = { deleteStep = 2 },
            onDismiss = { deleteStep = 0 },
        )
        2 -> ConfirmDialog(
            title = stringResource(R.string.settings_delete_confirm_title),
            text = stringResource(R.string.settings_delete_confirm_text),
            confirmLabel = stringResource(R.string.settings_delete_all),
            onConfirm = {
                deleteStep = 0
                vm.deleteAll()
            },
            onDismiss = { deleteStep = 0 },
        )
    }
}

private tailrec fun Context.findActivity(): Activity? = when (this) {
    is Activity -> this
    is ContextWrapper -> baseContext.findActivity()
    else -> null
}

/** "Ignored sounds": those marked from a clip, each with "Stop ignoring". */
@Composable
internal fun IgnoredSounds(ignored: Set<SoundType>, onRemove: (SoundType) -> Unit) {
    Column {
        Text(stringResource(R.string.settings_ignored_title), style = MaterialTheme.typography.titleMedium)
        if (ignored.isEmpty()) {
            MutedText(stringResource(R.string.settings_ignored_empty))
            return@Column
        }
        MutedText(stringResource(R.string.settings_ignored_note))
        Spacer(Modifier.height(4.dp))
        ignored.sortedBy { it.ordinal }.forEach { type ->
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Dot(type.color(), 8.dp)
                Spacer(Modifier.width(12.dp))
                Text(type.label(), style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                TextButton(onClick = { onRemove(type) }) {
                    Text(stringResource(R.string.settings_ignored_remove), color = Primary)
                }
            }
        }
    }
}

@Composable
private fun SwitchRow(title: String, note: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            MutedText(note)
        }
        Switch(checked = checked, onCheckedChange = onChange)
    }
}
