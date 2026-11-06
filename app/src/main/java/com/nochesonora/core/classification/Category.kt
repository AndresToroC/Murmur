package com.nochesonora.core.classification

/** FONDO (background) is never persisted: background events are discarded. */
enum class Category { RONQUIDO, HABLA, TOS, OTRO, FONDO }

/** A YAMNet label with its score (per window or averaged over the clip). */
data class LabelScore(val label: String, val score: Float)
