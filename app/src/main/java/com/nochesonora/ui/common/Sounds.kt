package com.nochesonora.ui.common

import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.nochesonora.R
import com.nochesonora.core.classification.SoundType
import com.nochesonora.core.classification.SoundTypes
import com.nochesonora.ui.theme.CoughRed
import com.nochesonora.ui.theme.OtherGray
import com.nochesonora.ui.theme.Primary
import com.nochesonora.ui.theme.SnoreAmber
import com.nochesonora.ui.theme.SpeechBlue

/** Sound type of a YAMNet label; null if it has no friendly one. */
fun soundTypeOf(yamnetLabel: String): SoundType? = SoundTypes.of(yamnetLabel)

@get:StringRes
val SoundType.nameRes: Int
    get() = when (this) {
        SoundType.DOG -> R.string.sound_dog
        SoundType.CAT -> R.string.sound_cat
        SoundType.ANIMAL -> R.string.sound_animal
        SoundType.BABY -> R.string.sound_baby
        SoundType.MOAN -> R.string.sound_moan
        SoundType.LAUGHTER -> R.string.sound_laughter
        SoundType.VOICES -> R.string.sound_voices
        SoundType.SNORING -> R.string.sound_snoring
        SoundType.BREATHING -> R.string.sound_breathing
        SoundType.COUGH -> R.string.sound_cough
        SoundType.SNEEZE -> R.string.sound_sneeze
        SoundType.THROAT -> R.string.sound_throat
        SoundType.BODY -> R.string.sound_body
        SoundType.MOVEMENT -> R.string.sound_movement
        SoundType.IMPACT -> R.string.sound_impact
        SoundType.DOOR -> R.string.sound_door
        SoundType.FOOTSTEPS -> R.string.sound_footsteps
        SoundType.ALARM -> R.string.sound_alarm
        SoundType.SIREN -> R.string.sound_siren
        SoundType.VEHICLE -> R.string.sound_vehicle
        SoundType.FIREWORKS -> R.string.sound_fireworks
        SoundType.RAIN -> R.string.sound_rain
        SoundType.WIND -> R.string.sound_wind
        SoundType.WATER -> R.string.sound_water
        SoundType.MUSIC_TV -> R.string.sound_music_tv
        SoundType.HOUSEHOLD -> R.string.sound_household
        SoundType.FAN -> R.string.sound_fan
        SoundType.ROOM -> R.string.sound_room
    }

/** Dot color for a type: body sounds use their category's color; the rest, gray. */
fun SoundType.color(): Color = when (this) {
    SoundType.SNORING, SoundType.BREATHING -> SnoreAmber
    SoundType.VOICES, SoundType.LAUGHTER -> SpeechBlue
    SoundType.COUGH, SoundType.SNEEZE, SoundType.THROAT -> CoughRed
    SoundType.ALARM, SoundType.SIREN, SoundType.FIREWORKS -> Primary
    else -> OtherGray
}

@Composable
fun SoundType.label(): String = stringResource(nameRes)

/**
 * Friendly name of a YAMNet label: its type ("Dog" for "Bark"); if it has no type, the Spanish
 * translation of the most common ones; failing that, the original label.
 */
@Composable
fun soundName(yamnetLabel: String): String {
    SoundTypes.of(yamnetLabel)?.let { return it.label() }
    val spanish = LocalConfiguration.current.locales[0].language == "es"
    return if (spanish) yamnetEs[yamnetLabel] ?: yamnetLabel else yamnetLabel
}

/** Translations of labels with no type of their own (or that are preferred as is). */
private val yamnetEs = mapOf(
    "Silence" to "Silencio",
    "Desconocido" to "Desconocido",
    "Sin clasificar" to "Sin clasificar",
    "Sound effect" to "Efecto de sonido",
    "Cacophony" to "Ruido mezclado",
    "Sine wave" to "Tono",
    "Harmonic" to "Tono",
    "Chirp tone" to "Tono",
    "Pulse" to "Pulso",
    "Sonar" to "Sonar",
    "Arrow" to "Silbido",
    "Whip" to "Latigazo",
    "Squeal" to "Chillido",
    "Zing" to "Zumbido",
    "Boing" to "Rebote",
    "Whistle" to "Silbato",
    "Steam whistle" to "Silbato",
    "Distortion" to "Distorsión",
    "Sidetone" to "Ruido del micrófono",
)
