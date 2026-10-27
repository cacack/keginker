package io.github.cacack.keginker

import io.github.cacack.keginker.diagnostics.SAVED_REPORTS_FOLDER
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Where a Debug build saves a report, as CONTRIBUTING.md's `adb` commands expect. */
@RunWith(RobolectricTestRunner::class)
class SavedReportsTest {
    private val dir = Files.createTempDirectory("saved-reports").toFile()

    @Test
    fun aReportIsSavedAsItsTextUnderTheReportsFolder() = runTest {
        SavedReports(dir, this).save("Display report", "KegInker display report\n").join()

        val saved = File(dir, SAVED_REPORTS_FOLDER).listFiles().orEmpty().single()
        assertTrue(saved.name, saved.name.startsWith("display-report-") && saved.name.endsWith(".txt"))
        assertEquals("KegInker display report\n", saved.readText())
    }

    @Test
    fun aFailedSaveIsLoggedNotThrown() = runTest {
        // A file where the folder should be, so nothing can be written under it.
        File(dir, SAVED_REPORTS_FOLDER).writeText("")

        SavedReports(dir, this).save("Display report", "text").join()

        assertTrue(File(dir, SAVED_REPORTS_FOLDER).isFile)
    }
}
