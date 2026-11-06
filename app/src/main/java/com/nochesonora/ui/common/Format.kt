package com.nochesonora.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.nochesonora.R
import com.nochesonora.core.classification.Category
import com.nochesonora.data.db.PauseReason
import com.nochesonora.data.db.SessionStatus
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt

/** The app's locale: set by `AppLanguage.wrap` when the activity is created. */
private val locale: Locale get() = Locale.getDefault()
private val isSpanish: Boolean get() = locale.language == "es"

@Composable
fun Category.label(): String = stringResource(
    when (this) {
        Category.RONQUIDO -> R.string.cat_snore
        Category.HABLA -> R.string.cat_speech
        Category.TOS -> R.string.cat_cough
        Category.OTRO -> R.string.cat_other
        Category.FONDO -> R.string.cat_background
    },
)

/**
 * YAMNet label for display: the friendly name of its sound type ("Dog" for "Bark"),
 * see [soundName] in Sounds.kt.
 */
@Composable
fun yamnetLabel(label: String): String = soundName(label)


@Composable
fun SessionStatus.label(): String = stringResource(
    when (this) {
        SessionStatus.EN_CURSO -> R.string.status_in_progress
        SessionStatus.COMPLETADA -> R.string.status_completed
        SessionStatus.INTERRUMPIDA -> R.string.status_interrupted
    },
)

@Composable
fun pauseLabel(reason: String?): String = stringResource(
    when (reason) {
        PauseReason.CALL -> R.string.pause_call
        PauseReason.AUDIO_ERROR -> R.string.pause_audio_error
        PauseReason.USER -> R.string.pause_user
        else -> R.string.pause_mic_busy
    },
)

/** "Mié 4 nov" / "Wed, Nov 4" */
fun formatDay(ms: Long): String {
    val pattern = if (isSpanish) "EEE d MMM" else "EEE, MMM d"
    return SimpleDateFormat(pattern, locale).format(Date(ms)).replace(".", "").replaceFirstChar { it.titlecase(locale) }
}

/** "noviembre 2026" → "Noviembre 2026" / "November 2026" */
fun formatMonthYear(ms: Long): String =
    SimpleDateFormat("LLLL yyyy", locale).format(Date(ms)).replaceFirstChar { it.titlecase(locale) }

/** "23:14" */
fun formatTime(ms: Long): String = SimpleDateFormat("HH:mm", locale).format(Date(ms))

/** "23:14:05" */
fun formatTimeSeconds(ms: Long): String = SimpleDateFormat("HH:mm:ss", locale).format(Date(ms))

/** "7 h 37 min", "12 min", "45 s" */
fun formatDuration(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "$h h $m min"
        m > 0 -> "$m min"
        totalSec == 0L -> "< 1 s"
        else -> "$totalSec s"
    }
}

/** Compact version for tiles: "3h 33m", "12m", "45s". */
fun formatDurationShort(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    return when {
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m"
        else -> "${totalSec}s"
    }
}

/** Session clock, always with hours: "01:12:40". */
fun formatClockLong(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    return "%02d:%02d:%02d".format(totalSec / 3600, (totalSec % 3600) / 60, totalSec % 60)
}

/** "−52" (typographic minus sign, no unit). */
fun formatDbShort(db: Float?): String = db?.let { it.roundToInt().toString().replace('-', '−') } ?: "—"

/** "1:05:09" or "4:09" */
fun formatClock(ms: Long): String {
    val totalSec = (ms / 1000).coerceAtLeast(0)
    val h = totalSec / 3600
    val m = (totalSec % 3600) / 60
    val s = totalSec % 60
    return if (h > 0) "%d:%02d:%02d".format(h, m, s) else "%d:%02d".format(m, s)
}

fun formatDb(db: Float?): String = db?.let { "${formatDbShort(it)} dBFS" } ?: "—"

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> "%.1f GB".format(locale, bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> "${bytes / (1L shl 20)} MB"
    bytes >= 1L shl 10 -> "${bytes / (1L shl 10)} KB"
    else -> "$bytes B"
}

