package com.nochesonora.core.audio

import com.nochesonora.core.SignalGen
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class PcmTest {
    @Test
    fun zerosAreFloor() {
        assertEquals(-100f, Pcm.rmsDb(ShortArray(1600)), 0f)
    }

    @Test
    fun fullScaleSineIsMinus3Db() {
        assertEquals(-3.01f, Pcm.rmsDb(SignalGen.sine(1000.0, 1.0)), 0.05f)
    }

    @Test
    fun halfAmplitudeIsMinus6DbMore() {
        assertEquals(-9.03f, Pcm.rmsDb(SignalGen.sine(1000.0, 1.0, 16384.0)), 0.05f)
    }

    @Test
    fun toFloatPadsWithZeros() {
        val out = FloatArray(4) { 9f }
        Pcm.toFloat(shortArrayOf(16384, -32768), 0, 2, out)
        assertArrayEquals(floatArrayOf(0.5f, -1f, 0f, 0f), out, 0f)
    }
}

class RingBufferTest {
    private fun seq(from: Int, n: Int) = ShortArray(n) { (from + it).toShort() }

    @Test
    fun readsLastWithWrapAround() {
        val ring = RingBuffer(10)
        ring.write(seq(0, 7))
        ring.write(seq(7, 7)) // wraps around
        assertEquals(10, ring.size)
        assertArrayEquals(seq(4, 10), ring.readLast(10))
        assertArrayEquals(seq(11, 3), ring.readLast(3))
    }

    @Test
    fun readLastReturnsOnlyWhatExists() {
        val ring = RingBuffer(10)
        ring.write(seq(0, 4))
        assertArrayEquals(seq(0, 4), ring.readLast(8))
    }

    @Test
    fun writeLargerThanCapacityKeepsTail() {
        val ring = RingBuffer(5)
        ring.write(seq(0, 12))
        assertArrayEquals(seq(7, 5), ring.readLast(5))
    }

    @Test
    fun readLastMillisecondsOfFiveSecondRing() {
        val ring = RingBuffer(Pcm.msToSamples(5_000))
        repeat(70) { b -> ring.write(ShortArray(1600) { b.toShort() }) } // 7 s
        val last3s = ring.readLast(Pcm.msToSamples(3_000))
        assertEquals(48_000, last3s.size)
        assertEquals(40.toShort(), last3s.first())
        assertEquals(69.toShort(), last3s.last())
    }

    @Test
    fun clearEmpties() {
        val ring = RingBuffer(5)
        ring.write(seq(0, 3))
        ring.clear()
        assertEquals(0, ring.readLast(5).size)
    }
}

class WavWriterTest {
    @Test
    fun headerAndSizes() {
        val samples = shortArrayOf(1, -1, 256, 32767)
        val out = ByteArrayOutputStream()
        WavWriter.write(out, samples)
        val bytes = out.toByteArray()
        assertEquals(44 + samples.size * 2, bytes.size)
        val bb = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(36 + 8, bb.getInt(4))
        assertEquals("WAVE", String(bytes, 8, 4, Charsets.US_ASCII))
        assertEquals("fmt ", String(bytes, 12, 4, Charsets.US_ASCII))
        assertEquals(16, bb.getInt(16))
        assertEquals(1, bb.getShort(20).toInt())
        assertEquals(1, bb.getShort(22).toInt())
        assertEquals(16_000, bb.getInt(24))
        assertEquals(32_000, bb.getInt(28))
        assertEquals(2, bb.getShort(32).toInt())
        assertEquals(16, bb.getShort(34).toInt())
        assertEquals("data", String(bytes, 36, 4, Charsets.US_ASCII))
        assertEquals(8, bb.getInt(40))
        assertEquals(1, bb.getShort(44).toInt())
        assertEquals(-1, bb.getShort(46).toInt())
        assertEquals(256, bb.getShort(48).toInt())
        assertEquals(32767, bb.getShort(50).toInt())
    }

    @Test
    fun largeClipCrossesInternalBuffer() {
        val samples = ShortArray(10_000) { it.toShort() }
        val out = ByteArrayOutputStream()
        WavWriter.write(out, samples)
        val bb = ByteBuffer.wrap(out.toByteArray()).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(9_999, bb.getShort(44 + 9_999 * 2).toInt())
    }
}

class WavFileTest {
    @get:org.junit.Rule
    val tmp = org.junit.rules.TemporaryFolder()

    /** Regression B4: syncing after close threw SyncFailedException and no clip was ever saved. */
    @Test
    fun atomicWriteSucceedsAndLeavesNoTmp() {
        val target = java.io.File(tmp.root, "sessions/7/12.wav")
        val samples = SignalGen.sine(440.0, 2.0, 8000.0)
        WavWriter.writeFileAtomic(target, samples)
        assertEquals(WavWriter.HEADER_SIZE + samples.size * 2L, target.length())
        assertEquals(listOf("12.wav"), target.parentFile!!.list()!!.toList())
        val bytes = target.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals(samples[100], ByteBuffer.wrap(bytes, 44 + 200, 2).order(ByteOrder.LITTLE_ENDIAN).short)
    }
}

class AdtsTest {
    @Test
    fun headerForLcMono16k() {
        val h = Adts.header(100)
        assertEquals(7, h.size)
        assertEquals(0xFF, h[0].toInt() and 0xFF)
        assertEquals(0xF1, h[1].toInt() and 0xFF)
        // LC profile (1), 16 kHz index (8), channels = 1
        val b2 = h[2].toInt() and 0xFF
        assertEquals(1, b2 shr 6)
        assertEquals(8, (b2 shr 2) and 0xF)
        val channels = ((b2 and 1) shl 2) or ((h[3].toInt() and 0xFF) shr 6)
        assertEquals(1, channels)
        val len = ((h[3].toInt() and 3) shl 11) or ((h[4].toInt() and 0xFF) shl 3) or ((h[5].toInt() and 0xFF) shr 5)
        assertEquals(107, len)
    }
}
