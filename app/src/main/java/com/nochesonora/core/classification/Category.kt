package com.nochesonora.core.classification

/** BACKGROUND (background) is never persisted: background events are discarded. */
enum class Category { SNORING, SPEECH, COUGH, OTHER, BACKGROUND }

/** A YAMNet label with its score (per window or averaged over the clip). */
data class LabelScore(val label: String, val score: Float)
