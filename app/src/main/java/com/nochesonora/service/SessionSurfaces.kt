package com.nochesonora.service

import android.content.Context
import com.nochesonora.widget.MurmurTileService
import com.nochesonora.widget.MurmurWidget

/** Places outside the app that show the night's state: quick settings and the widget. */
object SessionSurfaces {
    fun refresh(context: Context) {
        MurmurTileService.requestUpdate(context)
        MurmurWidget.refreshAll(context)
    }
}
