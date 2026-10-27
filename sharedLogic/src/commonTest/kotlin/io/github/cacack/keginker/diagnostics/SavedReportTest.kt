package io.github.cacack.keginker.diagnostics

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.time.Instant

class SavedReportTest {
    private val at = Instant.parse("2026-10-26T14:22:33.123Z")

    @Test
    fun theNameIsTheTitleThenTheUtcTimeWithoutColons() {
        assertEquals("display-report-2026-10-26T14-22-33.123Z.txt", savedReportFileName("Display report", at))
        assertEquals("diagnostics-report-2026-10-26T14-22-33.123Z.txt", savedReportFileName("Diagnostics report", at))
    }

    @Test
    fun anythingButLettersAndDigitsBecomesOneDash() {
        assertEquals("a-b-2-2026-10-26T14-22-33.123Z.txt", savedReportFileName("  A / b (2) ", at))
    }
}
