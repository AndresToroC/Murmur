package com.nochesonora

import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.nochesonora.data.settings.AppLanguage
import com.nochesonora.ui.navigation.NavGraph
import com.nochesonora.ui.navigation.PendingAction
import com.nochesonora.ui.theme.MurmurTheme

class MainActivity : ComponentActivity() {
    /** Lo que pidió una notificación, el widget o el botón de ajustes rápidos; la UI lo consume. */
    private val pendingAction = mutableStateOf<PendingAction?>(null)

    override fun attachBaseContext(newBase: Context) {
        super.attachBaseContext(AppLanguage.wrap(newBase))
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        super.onCreate(savedInstanceState)
        // Al recrear (p. ej. cambio de idioma) no se repite la acción del intent original.
        if (savedInstanceState == null) pendingAction.value = actionFrom(intent)
        setContent {
            MurmurTheme {
                NavGraph(
                    pendingAction = pendingAction.value,
                    onActionHandled = { pendingAction.value = null },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        actionFrom(intent)?.let { pendingAction.value = it }
    }

    private fun actionFrom(intent: Intent?): PendingAction? = when {
        intent == null -> null
        intent.action == ACTION_START_NIGHT -> PendingAction.StartNight
        intent.hasExtra(EXTRA_OPEN_SESSION) -> PendingAction.OpenSession(intent.getLongExtra(EXTRA_OPEN_SESSION, -1))
        else -> null
    }

    companion object {
        /** Comenzar la noche (botón de ajustes rápidos y widget). */
        const val ACTION_START_NIGHT = "com.nochesonora.action.START_NIGHT"
        /** Abrir el detalle de una noche (resumen al despertar y widget). */
        const val EXTRA_OPEN_SESSION = "com.nochesonora.extra.OPEN_SESSION"
    }
}
