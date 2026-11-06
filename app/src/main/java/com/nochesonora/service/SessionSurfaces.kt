package com.nochesonora.service

import android.content.Context
import com.nochesonora.widget.MurmurTileService
import com.nochesonora.widget.MurmurWidget

/** Lugares fuera de la app que muestran el estado de la noche: ajustes rápidos y widget. */
object SessionSurfaces {
    fun refresh(context: Context) {
        MurmurTileService.requestUpdate(context)
        MurmurWidget.refreshAll(context)
    }
}
