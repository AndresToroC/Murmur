package com.nochesonora.data.files

import android.content.Context
import android.os.StatFs
import com.nochesonora.core.audio.WavWriter
import java.io.File
import java.io.IOException

/** Archivos de audio: `filesDir/sessions/{sessionId}/{eventId}.wav` y `full.aac`. */
class SessionFileStore(context: Context) {
    private val filesDir: File = context.filesDir
    private val sessionsDir = File(filesDir, "sessions")

    fun sessionDir(sessionId: Long): File = File(sessionsDir, sessionId.toString())

    fun eventFile(sessionId: Long, eventId: Long): File = File(sessionDir(sessionId), "$eventId.wav")

    fun fullRecordingFile(sessionId: Long): File = File(sessionDir(sessionId), "full.aac")

    /** Escritura atómica: se escribe a `.tmp` y se renombra. */
    @Throws(IOException::class)
    fun writeWav(target: File, pcm: ShortArray) = WavWriter.writeFileAtomic(target, pcm)

    fun deleteFile(path: String?) {
        if (!path.isNullOrEmpty()) File(path).delete()
    }

    fun deleteSession(sessionId: Long) {
        sessionDir(sessionId).deleteRecursively()
    }

    fun deleteAll() {
        sessionsDir.deleteRecursively()
    }

    fun usedBytes(): Long = sessionsDir.walkBottomUp().filter { it.isFile }.sumOf { it.length() }

    fun freeBytes(): Long = runCatching { StatFs(filesDir.path).availableBytes }.getOrDefault(0L)
}
