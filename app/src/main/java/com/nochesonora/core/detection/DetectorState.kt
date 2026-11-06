package com.nochesonora.core.detection

enum class DetectorState { CALIBRANDO, ESCUCHANDO, ONSET, EVENTO }

/** Evento cerrado por la capa 2, listo para clasificar. */
class ClosedEvent(
    /** Inicio real (primer bloque del onset), en ms de reloj. */
    val startedAtMs: Long,
    /** Fin real (último bloque sobre el umbral), en ms de reloj. */
    val endedAtMs: Long,
    /** Hora de la primera muestra de [pcm] (inicio − pre-roll). */
    val clipStartedAtMs: Long,
    val peakDb: Float,
    val pcm: ShortArray,
    /** true si se cortó por llegar a la duración máxima. */
    val wasCut: Boolean,
)

class DetectorOutput(
    val state: DetectorState,
    val levelDb: Float,
    val baselineDb: Float?,
    val thresholdDb: Float?,
    val calibrationProgress: Float,
    val closedEvent: ClosedEvent?,
)
