package com.nochesonora.transcription

import kotlin.math.abs
import kotlin.math.sqrt
import kotlin.math.tanh

/**
 * Prepara un clip de habla para el reconocedor del teléfono (lógica pura, probada en la JVM).
 *
 * Lo que se dice dormido suele ser bajo y entrecortado, y el clip empieza con segundos de silencio
 * (pre-roll). Tal cual, el reconocedor lo descarta ("no se entendió") o se detiene en la primera
 * pausa y solo devuelve una parte. Por eso:
 *  1. Filtro paso alto (~80 Hz): quita el zumbido y el desplazamiento de continua.
 *  2. Detecta los tramos con voz por energía, relativa al ruido del propio clip.
 *  3. Une los tramos separados por pausas cortas en frases y las devuelve por separado, para
 *     reconocer cada una aparte (el reconocedor ya no corta en la primera pausa).
 *  4. Sube el volumen de cada frase hasta un nivel de conversación, con limitador suave.
 *  5. Añade un poco de silencio antes y después, para que el reconocedor detecte el principio y
 *     el final de la frase.
 */
object SpeechPrep {
    const val SAMPLE_RATE = 16_000
    /** 30 ms por trama. */
    const val FRAME = 480

    private const val MERGE_GAP_MS = 500
    private const val PAD_BEFORE_MS = 250
    private const val PAD_AFTER_MS = 400
    /** Un tramo con menos voz que esto (un golpe, un suspiro) no se reconoce solo. */
    private const val MIN_VOICED_MS = 150
    /** Frases más largas se parten por su punto más silencioso. */
    private const val MAX_PHRASE_MS = 20_000
    /** Tope de reconocimientos por clip; con más, se unen las frases más cercanas. */
    const val MAX_PHRASES = 12

    private const val LEAD_SILENCE_MS = 300
    private const val TAIL_SILENCE_MS = 1_200

    /** Nivel RMS objetivo de la voz: -20 dBFS. */
    private const val TARGET_RMS = 3_277.0
    /** Ganancia máxima: +30 dB. */
    private const val MAX_GAIN = 31.6
    /** Por debajo de esto (-66 dBFS) no se considera voz, aunque el cuarto sea muy silencioso. */
    private const val ABS_MIN_RMS = 16.0
    /** La voz debe estar al menos 6 dB por encima del ruido del clip. */
    private const val NOISE_RATIO = 2.0

    /** Frases listas para el reconocedor (PCM16 mono 16 kHz), en orden. Vacía si no hay audio. */
    fun phrases(samples: ShortArray): List<ShortArray> {
        if (samples.isEmpty()) return emptyList()
        val x = highPass(samples)
        val frameRms = frameRms(x)
        val ranges = voicedRanges(frameRms).ifEmpty {
            // Sin contraste claro entre voz y ruido (todo silencio, o todo voz): el clip entero.
            splitLong(0 until frameRms.size, frameRms, MAX_PHRASE_MS * SAMPLE_RATE / 1000 / FRAME)
        }
        return ranges.map { r ->
            val start = r.first * FRAME
            val end = minOf(x.size, (r.last + 1) * FRAME)
            val gain = gainFor(x, frameRms, r)
            withSilence(x, start, end, gain)
        }
    }

    /** Filtro paso alto de un polo a ~80 Hz; también elimina la continua. */
    internal fun highPass(samples: ShortArray): DoubleArray {
        val a = 0.9695
        val out = DoubleArray(samples.size)
        var prevX = samples[0].toDouble()
        var prevY = 0.0
        for (i in samples.indices) {
            val xi = samples[i].toDouble()
            prevY = a * (prevY + xi - prevX)
            prevX = xi
            out[i] = prevY
        }
        return out
    }

    internal fun frameRms(x: DoubleArray): DoubleArray {
        val n = (x.size + FRAME - 1) / FRAME
        return DoubleArray(n) { f ->
            val from = f * FRAME
            val to = minOf(x.size, from + FRAME)
            var sum = 0.0
            for (i in from until to) sum += x[i] * x[i]
            sqrt(sum / (to - from))
        }
    }

    /** Rangos de tramas (con relleno) que forman cada frase. */
    internal fun voicedRanges(frameRms: DoubleArray): List<IntRange> {
        if (frameRms.isEmpty()) return emptyList()
        val noise = percentile(frameRms, 0.2)
        val threshold = maxOf(noise * NOISE_RATIO, ABS_MIN_RMS)
        val voiced = BooleanArray(frameRms.size) { frameRms[it] >= threshold }
        val frameMs = FRAME * 1000 / SAMPLE_RATE

        // Tramos continuos de voz.
        val runs = mutableListOf<IntRange>()
        var i = 0
        while (i < voiced.size) {
            if (!voiced[i]) { i++; continue }
            val s = i
            while (i < voiced.size && voiced[i]) i++
            runs += s until i
        }
        if (runs.isEmpty()) return emptyList()

        var mergeGap = MERGE_GAP_MS / frameMs
        var phrases = merge(runs, mergeGap)
        // Se descartan los tramos sueltos demasiado cortos, salvo que no quede nada.
        val minVoiced = MIN_VOICED_MS / frameMs
        phrases = phrases.filter { p -> p.voicedFrames(voiced) >= minVoiced }.ifEmpty { phrases }
        while (phrases.size > MAX_PHRASES) {
            mergeGap *= 2
            phrases = merge(phrases, mergeGap)
        }

        val padBefore = PAD_BEFORE_MS / frameMs
        val padAfter = PAD_AFTER_MS / frameMs
        val padded = phrases.map { maxOf(0, it.first - padBefore)..minOf(frameRms.size - 1, it.last + padAfter) }
        // El relleno puede hacer que dos frases se toquen: no se repite audio entre ellas.
        val disjoint = padded.mapIndexed { k, r ->
            val prevEnd = if (k == 0) -1 else padded[k - 1].last
            maxOf(r.first, prevEnd + 1)..r.last
        }.filter { !it.isEmpty() }
        return disjoint.flatMap { splitLong(it, frameRms, MAX_PHRASE_MS / frameMs) }
    }

    private fun IntRange.voicedFrames(voiced: BooleanArray) = count { voiced[it] }

    private fun merge(ranges: List<IntRange>, gapFrames: Int): List<IntRange> {
        val out = mutableListOf<IntRange>()
        for (r in ranges) {
            val last = out.lastOrNull()
            if (last != null && r.first - last.last - 1 <= gapFrames) out[out.size - 1] = last.first..r.last else out += r
        }
        return out
    }

    /** Parte un rango largo por su trama más silenciosa (de la mitad central), recursivamente. */
    private fun splitLong(r: IntRange, frameRms: DoubleArray, maxFrames: Int): List<IntRange> {
        val len = r.last - r.first + 1
        if (len <= maxFrames) return listOf(r)
        val from = r.first + len / 4
        val to = r.last - len / 4
        val cut = (from..to).minBy { frameRms[it] }
        return splitLong(r.first until cut, frameRms, maxFrames) + splitLong(cut..r.last, frameRms, maxFrames)
    }

    /** Ganancia para que la voz de la frase quede a [TARGET_RMS]; nunca baja el volumen. */
    private fun gainFor(x: DoubleArray, frameRms: DoubleArray, r: IntRange): Double {
        // Nivel de la voz: media cuadrática de la mitad más fuerte de las tramas de la frase.
        val loud = r.map { frameRms[it] }.sortedDescending().let { it.take(maxOf(1, it.size / 2)) }
        val rms = sqrt(loud.sumOf { it * it } / loud.size)
        if (rms <= 0.0) return 1.0
        return (TARGET_RMS / rms).coerceIn(1.0, MAX_GAIN)
    }

    /** La frase con ganancia y limitador, entre silencios (con un ruido mínimo, no ceros exactos). */
    private fun withSilence(x: DoubleArray, start: Int, end: Int, gain: Double): ShortArray {
        val lead = LEAD_SILENCE_MS * SAMPLE_RATE / 1000
        val tail = TAIL_SILENCE_MS * SAMPLE_RATE / 1000
        val out = ShortArray(lead + (end - start) + tail)
        var seed = 0x2545F491
        for (i in out.indices) {
            // Ruido de ±2 LSB: algunos reconocedores tratan los ceros exactos como "sin audio".
            seed = seed xor (seed shl 13); seed = seed xor (seed ushr 17); seed = seed xor (seed shl 5)
            out[i] = ((seed and 3) - 2).toShort()
        }
        for (i in start until end) out[lead + i - start] = limit(x[i] * gain)
        return out
    }

    /** Limitador suave: lineal hasta el 70 % de la escala, luego se curva sin recortar. */
    internal fun limit(v: Double): Short {
        val full = 32_767.0
        val knee = 0.7 * full
        val a = abs(v)
        val y = if (a <= knee) a else knee + (full - knee) * tanh((a - knee) / (full - knee))
        return (if (v < 0) -y else y).toInt().toShort()
    }

    private fun percentile(values: DoubleArray, p: Double): Double {
        val sorted = values.sorted()
        return sorted[((sorted.size - 1) * p).toInt()]
    }

    /** PCM16 little-endian ↔ muestras. */
    fun toSamples(pcm: ByteArray): ShortArray =
        ShortArray(pcm.size / 2) { ((pcm[2 * it].toInt() and 0xFF) or (pcm[2 * it + 1].toInt() shl 8)).toShort() }

    fun toBytes(samples: ShortArray): ByteArray {
        val out = ByteArray(samples.size * 2)
        for (i in samples.indices) {
            out[2 * i] = samples[i].toInt().toByte()
            out[2 * i + 1] = (samples[i].toInt() shr 8).toByte()
        }
        return out
    }
}
