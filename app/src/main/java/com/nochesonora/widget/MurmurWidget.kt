package com.nochesonora.widget

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.util.Log
import android.view.View
import android.widget.RemoteViews
import com.nochesonora.MainActivity
import com.nochesonora.MurmurApp
import com.nochesonora.R
import com.nochesonora.data.db.PauseReason
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.SessionSummaryRow
import com.nochesonora.data.settings.AppLanguage
import com.nochesonora.service.LiveSnapshot
import com.nochesonora.service.NightSessionService
import com.nochesonora.service.NightSummaryNotification
import com.nochesonora.ui.common.formatDay
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Home screen widget: the night's state (with a chronometer while recording), the last night
 * and a button to start or stop. It is not updated on a timer: the service refreshes it (on start,
 * pause, resume and stop) and so does the app when it opens; the chronometer runs on its own.
 */
class MurmurWidget : AppWidgetProvider() {
    override fun onUpdate(context: Context, manager: AppWidgetManager, ids: IntArray) {
        val pending = goAsync()
        scope.launch {
            try {
                val app = context.applicationContext as MurmurApp
                val live = app.container.liveState.snapshot.value
                val last = withTimeoutOrNull(5_000) {
                    app.container.repository.observeSummaries().first()
                        .firstOrNull { it.session.status != SessionStatus.IN_PROGRESS }
                }
                val views = render(AppLanguage.wrap(context.applicationContext), live, last)
                ids.forEach { manager.updateAppWidget(it, views) }
            } catch (e: Exception) {
                Log.w(TAG, "Could not update the widget", e)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val TAG = "MurmurWidget"
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        /** Redraws every widget placed on the home screen. */
        fun refreshAll(context: Context) {
            val manager = AppWidgetManager.getInstance(context) ?: return
            val ids = manager.getAppWidgetIds(ComponentName(context, MurmurWidget::class.java))
            if (ids.isEmpty()) return
            context.sendBroadcast(
                Intent(context, MurmurWidget::class.java)
                    .setAction(AppWidgetManager.ACTION_APPWIDGET_UPDATE)
                    .putExtra(AppWidgetManager.EXTRA_APPWIDGET_IDS, ids),
            )
        }

        internal fun render(context: Context, live: LiveSnapshot, last: SessionSummaryRow?): RemoteViews {
            val views = RemoteViews(context.packageName, R.layout.widget_murmur)
            val running = live.serviceRunning
            val paused = live.pauseReason == PauseReason.USER

            views.setOnClickPendingIntent(R.id.widget_root, openApp(context))
            views.setTextViewText(
                R.id.widget_status,
                context.getString(
                    when {
                        !running -> R.string.widget_ready
                        paused -> R.string.widget_paused
                        else -> R.string.widget_recording
                    },
                ),
            )
            views.setTextColor(R.id.widget_status, if (running && !paused) ACCENT else MUTED)

            if (running) {
                // Chronometer counts from an elapsedRealtime instant: convert the start time.
                val base = SystemClock.elapsedRealtime() - (System.currentTimeMillis() - live.startedAt).coerceAtLeast(0)
                views.setChronometer(R.id.widget_clock, base, null, !paused)
                views.setViewVisibility(R.id.widget_clock, View.VISIBLE)
                views.setViewVisibility(R.id.widget_last, View.GONE)
                views.setTextViewText(R.id.widget_button, context.getString(R.string.widget_stop))
                views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_button_secondary)
                views.setTextColor(R.id.widget_button, TEXT)
                views.setOnClickPendingIntent(R.id.widget_button, stopSession(context))
            } else {
                views.setViewVisibility(R.id.widget_clock, View.GONE)
                if (last != null) {
                    views.setViewVisibility(R.id.widget_last, View.VISIBLE)
                    views.setTextViewText(R.id.widget_last_day, formatDay(last.session.startedAt))
                    views.setTextViewText(R.id.widget_last_text, NightSummaryNotification.summaryText(context, last))
                    views.setOnClickPendingIntent(R.id.widget_last, openSession(context, last.session.id))
                } else {
                    views.setViewVisibility(R.id.widget_last, View.GONE)
                }
                views.setTextViewText(R.id.widget_button, context.getString(R.string.widget_start))
                views.setInt(R.id.widget_button, "setBackgroundResource", R.drawable.widget_button_primary)
                views.setTextColor(R.id.widget_button, ON_ACCENT)
                views.setOnClickPendingIntent(R.id.widget_button, startNight(context))
            }
            return views
        }

        private fun openApp(context: Context) = PendingIntent.getActivity(
            context, 30,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        /** Start opens the app: it goes through permissions, battery and storage like the Home button. */
        private fun startNight(context: Context) = PendingIntent.getActivity(
            context, 31,
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_START_NIGHT)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun openSession(context: Context, id: Long) = PendingIntent.getActivity(
            context, 32,
            Intent(context, MainActivity::class.java)
                .putExtra(MainActivity.EXTRA_OPEN_SESSION, id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        private fun stopSession(context: Context) = PendingIntent.getService(
            context, 33,
            Intent(context, NightSessionService::class.java).setAction(NightSessionService.ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        // Design A tokens (RemoteViews cannot see the Compose colors).
        private const val ACCENT = 0xFF7CC4FF.toInt()
        private const val ON_ACCENT = 0xFF0A0F1C.toInt()
        private const val TEXT = 0xFFE7ECF5.toInt()
        private const val MUTED = 0xFF93A0B8.toInt()
    }
}
