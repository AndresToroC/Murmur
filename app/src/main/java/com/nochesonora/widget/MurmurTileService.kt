package com.nochesonora.widget

import android.annotation.SuppressLint
import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.graphics.drawable.Icon
import android.os.Build
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import com.nochesonora.MainActivity
import com.nochesonora.MurmurApp
import com.nochesonora.R
import com.nochesonora.data.db.PauseReason
import com.nochesonora.data.settings.AppLanguage
import com.nochesonora.service.NightSessionService

/**
 * Quick settings tile. With no session, it opens the app and starts the night (so it goes through
 * the same permissions and warnings as the Home button, and Android allows starting the microphone
 * because the app is in the foreground). With a session, it stops it.
 */
class MurmurTileService : TileService() {
    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onStartListening() {
        super.onStartListening()
        render()
    }

    override fun onClick() {
        super.onClick()
        val live = (application as MurmurApp).container.liveState.snapshot.value
        if (live.serviceRunning) {
            NightSessionService.stop(this)
        } else if (isLocked) {
            unlockAndRun { openAppAndStart() }
        } else {
            openAppAndStart()
        }
    }

    // On Android 13 and earlier the PendingIntent overload does not exist.
    @SuppressLint("StartActivityAndCollapseDeprecated")
    private fun openAppAndStart() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.ACTION_START_NIGHT)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startActivityAndCollapse(
                PendingIntent.getActivity(this, REQUEST_START, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT),
            )
        } else {
            @Suppress("DEPRECATION")
            startActivityAndCollapse(intent)
        }
    }

    private fun render() {
        val tile = qsTile ?: return
        val live = (application as MurmurApp).container.liveState.snapshot.value
        tile.icon = Icon.createWithResource(this, R.drawable.ic_tile)
        tile.label = getString(R.string.tile_label)
        tile.state = if (live.serviceRunning) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            tile.subtitle = getString(
                when {
                    !live.serviceRunning -> R.string.tile_idle
                    live.pauseReason == PauseReason.USER -> R.string.tile_paused
                    else -> R.string.tile_recording
                },
            )
        }
        tile.updateTile()
    }

    companion object {
        private const val REQUEST_START = 20

        /** Asks Android to redraw the tile (calls onStartListening if it is visible). */
        fun requestUpdate(context: Context) {
            runCatching { requestListeningState(context, ComponentName(context, MurmurTileService::class.java)) }
        }
    }
}
