package com.nochesonora.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import androidx.core.app.NotificationCompat
import com.nochesonora.MainActivity
import com.nochesonora.R
import com.nochesonora.data.db.PauseReason

/**
 * The session's ongoing notification. It is also visible with the phone locked and lets the
 * user pause, resume and stop without opening the app.
 */
object SessionNotification {
    const val NOTIFICATION_ID = 1001

    /**
     * v2: DEFAULT importance (no sound or vibration) so Android shows it on the lock screen;
     * with LOW it ended up among the "silent" ones, which many phones hide there.
     * A channel's importance cannot be changed after it is created, hence a new channel.
     */
    private const val CHANNEL_ID = "night_session_v2"
    private const val OLD_CHANNEL_ID = "night_session"
    private const val TAG = "SessionNotification"

    fun ensureChannel(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        nm.deleteNotificationChannel(OLD_CHANNEL_ID)
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.notif_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT,
        ).apply {
            description = context.getString(R.string.notif_channel_desc)
            setSound(null, null)
            enableVibration(false)
            enableLights(false)
            setShowBadge(false)
            lockscreenVisibility = Notification.VISIBILITY_PUBLIC
        }
        // Re-creating an existing channel only updates its name and description (language).
        nm.createNotificationChannel(channel)
    }

    fun build(context: Context, startedAt: Long, events: Int, pauseReason: String?): Notification {
        ensureChannel(context)
        val open = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val userPaused = pauseReason == PauseReason.USER
        val text = when (pauseReason) {
            null -> context.resources.getQuantityString(R.plurals.notif_events, events, events)
            PauseReason.USER -> context.getString(R.string.notif_paused_user)
            PauseReason.CALL -> context.getString(R.string.notif_paused_call)
            PauseReason.AUDIO_ERROR -> context.getString(R.string.notif_paused_retry)
            else -> context.getString(R.string.notif_paused_busy)
        }
        val toggle = if (userPaused) {
            NotificationCompat.Action(R.drawable.ic_play, context.getString(R.string.notif_resume), serviceIntent(context, NightSessionService.ACTION_RESUME, 2))
        } else {
            NotificationCompat.Action(R.drawable.ic_pause, context.getString(R.string.notif_pause), serviceIntent(context, NightSessionService.ACTION_PAUSE, 3))
        }
        val stop = NotificationCompat.Action(
            R.drawable.ic_stop,
            context.getString(R.string.notif_stop),
            serviceIntent(context, NightSessionService.ACTION_STOP, 1),
        )
        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_moon)
            .setContentTitle(context.getString(if (userPaused) R.string.notif_title_paused else R.string.notif_title))
            .setContentText(text)
            .setWhen(startedAt)
            // While paused the chronometer would keep running, which is confusing: hide it.
            .setUsesChronometer(!userPaused)
            .setShowWhen(!userPaused)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setContentIntent(open)
            .addAction(toggle)
            .addAction(stop)
            .build()
    }

    private fun serviceIntent(context: Context, action: String, requestCode: Int): PendingIntent = PendingIntent.getService(
        context,
        requestCode,
        Intent(context, NightSessionService::class.java).setAction(action),
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
    )

    fun update(context: Context, startedAt: Long, events: Int, pauseReason: String?) {
        try {
            context.getSystemService(NotificationManager::class.java)
                ?.notify(NOTIFICATION_ID, build(context, startedAt, events, pauseReason))
        } catch (e: SecurityException) {
            Log.w(TAG, "No notification permission", e)
        }
    }
}
