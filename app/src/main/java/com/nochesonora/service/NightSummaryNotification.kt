package com.nochesonora.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nochesonora.MainActivity
import com.nochesonora.R
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.ui.common.formatDay
import com.nochesonora.ui.common.formatDuration

/** "Wake-up summary": when the session stops, what happened during the night; tapping it opens that night. */
object NightSummaryNotification {
    private const val CHANNEL_ID = "night_summary"
    private const val NOTIFICATION_ID = 1002
    private const val TAG = "NightSummary"

    private fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.summary_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.summary_channel_desc)
            // Whoever stops the session is already awake: no need for a sound.
            setSound(null, null)
            enableVibration(false)
        }
        nm.createNotificationChannel(channel)
    }

    /** Summary text, e.g. "3 h 33 min · talked 8 times · 42 min of snoring · cough 2 · other 9". */
    fun summaryText(context: Context, row: SessionSummaryRow): String {
        val s = row.session
        val r = context.resources
        val parts = buildList {
            add(formatDuration((s.endedAt ?: s.lastHeartbeatAt) - s.startedAt))
            if (row.speechEvents > 0) add(r.getQuantityString(R.plurals.summary_speech, row.speechEvents, row.speechEvents))
            if (row.snoreMs > 0) add(context.getString(R.string.summary_snore, formatDuration(row.snoreMs)))
            else if (row.snoreEvents > 0) add(r.getQuantityString(R.plurals.summary_snore_events, row.snoreEvents, row.snoreEvents))
            if (row.coughEvents > 0) add(r.getQuantityString(R.plurals.summary_cough, row.coughEvents, row.coughEvents))
            if (row.otherEvents > 0) add(r.getQuantityString(R.plurals.summary_other, row.otherEvents, row.otherEvents))
        }
        val quiet = row.speechEvents + row.snoreEvents + row.coughEvents + row.otherEvents == 0
        return if (quiet) context.getString(R.string.summary_quiet, parts.first()) else parts.joinToString(" · ")
    }

    fun post(context: Context, row: SessionSummaryRow) {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            row.session.id.toInt(),
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION, row.session.id)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val text = summaryText(context, row)
        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(R.string.summary_title, formatDay(row.session.startedAt)))
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setContentIntent(open)
            .setAutoCancel(true)
            .setCategory(NotificationCompat.CATEGORY_STATUS)
            .build()
        try {
            context.getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, notification)
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }
}
