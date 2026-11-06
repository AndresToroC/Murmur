package com.nochesonora.core.classification

/** FONDO nunca se persiste: los eventos de fondo se descartan. */
enum class Category { RONQUIDO, HABLA, TOS, OTRO, FONDO }

/** Una etiqueta de YAMNet con su score (por ventana o media del clip). */
data class LabelScore(val label: String, val score: Float)
