package com.nochesonora.service

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.nochesonora.data.db.SessionEntity
import com.nochesonora.data.db.SessionStatus
import com.nochesonora.data.db.SessionSummaryRow
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class NightSummaryTest {
    private val context: Application get() = ApplicationProvider.getApplicationContext()
    private val start = 1_762_230_660_000L // any time works: only the duration matters
    private val night = SessionEntity(
        id = 1, startedAt = start, endedAt = start + (3 * 60 + 33) * 60_000L, status = SessionStatus.COMPLETADA,
        baselineDbAtStart = -52f, lastHeartbeatAt = start, sensitivityDb = 10, fullRecordingPath = null,
    )

    @Test
    @Config(qualifiers = "es")
    fun spanishSummaryListsWhatHappened() {
        val row = SessionSummaryRow(night, snoreEvents = 12, speechEvents = 8, coughEvents = 1, otherEvents = 9, snoreMs = 42 * 60_000L)
        assertEquals(
            "3 h 33 min · habló 8 veces · 42 min de ronquido · tosió 1 vez · 9 sonidos más",
            NightSummaryNotification.summaryText(context, row),
        )
    }

    @Test
    @Config(qualifiers = "es")
    fun quietNightSaysSo() {
        val row = SessionSummaryRow(night, 0, 0, 0, 0, 0)
        assertEquals("3 h 33 min · noche tranquila, sin sonidos", NightSummaryNotification.summaryText(context, row))
    }

    @Test
    @Config(qualifiers = "en")
    fun englishSummary() {
        val row = SessionSummaryRow(night, snoreEvents = 3, speechEvents = 1, coughEvents = 0, otherEvents = 0, snoreMs = 0)
        assertEquals("3 h 33 min · talked 1 time · 3 snores", NightSummaryNotification.summaryText(context, row))
    }
}
