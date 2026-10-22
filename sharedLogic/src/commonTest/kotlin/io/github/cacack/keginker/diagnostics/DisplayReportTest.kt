package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.classifyIsoDepTag
import io.github.cacack.keginker.nfc.classifyNfcATag
import io.github.cacack.keginker.probe.Type2Tag
import io.github.cacack.keginker.probe.Type4Tag
import io.github.cacack.keginker.probe.launcherRecord
import io.github.cacack.keginker.probe.ndefTlv
import io.github.cacack.keginker.probe.probeDisplay
import io.github.cacack.keginker.probe.type2Memory
import io.github.cacack.keginker.waveshare.toHex
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlinx.coroutines.test.runTest

private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }

private val APP = AppInfo(version = "1.4.0", buildType = "release", commit = "abc1234")
private val DEVICE = DeviceInfo(manufacturer = "Google", model = "Pixel 8", os = "Android 15", nfcEnabled = true)
private val UID = bytes(0x04, 0xA2, 0x3F, 0x11, 0x22, 0x33, 0x44)

class DisplayReportTest {
    @Test
    fun type4ReportListsWhatTheProbeLearned() = runTest {
        val info = TagInfo(listOf("IsoDep", "NfcA"), UID, bytes(0x44, 0x00), 0x20, bytes(0x80, 0x01))
        val result = probeDisplay(classifyIsoDepTag(info) { Type4Tag(launcherRecord("com.example.epaper")) })

        val text = formatDisplayReport(APP, DEVICE, result)

        listOf(
            "KegInker display report",
            "leaves your phone only if you share it",
            "Version: 1.4.0", "Model: Pixel 8",
            "Technologies: IsoDep, NfcA", "UID: 04 A2 3F … (7 bytes)", "ATQA: 44 00", "SAK: 20", "Historical bytes: 80 01",
            "Detected protocol: unsupported",
            "Probe (read-only)",
            "Applications accepted: D2 76 00 00 85 01 01",
            "Capability container: 00 0F 20",
            "NDEF: 1 record",
            "1. Android app: com.example.epaper",
            "SELECT_AID",
            "> 00 A4 04 00 07 D2 76 00 00 85 01 01 00  < 90 00",
            "READ_NDEF",
        ).forEach { assertTrue(it in text, "Missing \"$it\" in:\n$text") }
    }

    @Test
    fun type2ReportNeverShowsTheFullUid() = runTest {
        val memory = type2Memory(ndefTlv(launcherRecord("com.example.vendor")))
        val uid = memory.copyOfRange(0, 3) + memory.copyOfRange(4, 8)
        val result = probeDisplay(classifyNfcATag(TagInfo(listOf("NfcA"), uid)) { Type2Tag(memory) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertFalse(uid.toHex() in text, text)
        assertFalse(uid.copyOfRange(3, 7).toHex() in text, text)
        assertFalse("Applications accepted" in text, text)
        assertTrue("1. Android app: com.example.vendor" in text, text)
    }

    @Test
    fun aProbeThatStoppedSaysWhy() = runTest {
        val result = probeDisplay(classifyIsoDepTag(TagInfo(listOf("IsoDep"), UID)) { Type4Tag(ByteArray(0), hasNdefApp = false) })

        val text = formatDisplayReport(APP, DEVICE, result)

        assertTrue("Applications accepted: none" in text, text)
        assertTrue("NDEF: none found" in text, text)
        assertTrue("Note: The tag accepted none of the listed applications." in text, text)
    }

    @Test
    fun issueLinkCarriesTheReportWhenItFits() {
        val link = displayReportIssueLink("Line 1\nUID: 04 A2 … & more")

        assertTrue(link.includesReport)
        assertTrue(link.url.startsWith("https://github.com/cacack/keginker/issues/new?template=display-report.yml&report="))
        assertTrue(link.url.endsWith("Line%201%0AUID%3A%2004%20A2%20%E2%80%A6%20%26%20more"), link.url)
    }

    @Test
    fun issueLinkLeavesOutAReportTooLongToFit() {
        val link = displayReportIssueLink("x".repeat(MAX_ISSUE_URL_LENGTH))

        assertFalse(link.includesReport)
        assertEquals("https://github.com/cacack/keginker/issues/new?template=display-report.yml", link.url)
    }

    @Test
    fun issueLinkCaptionSaysIssuesArePublic() {
        listOf(displayReportIssueLink("short"), displayReportIssueLink("x".repeat(MAX_ISSUE_URL_LENGTH))).forEach {
            assertTrue("GitHub issues are public" in it.caption, it.caption)
        }
    }

    @Test
    fun percentEncodingLeavesOnlyUnreservedCharacters() {
        val encoded = percentEncode((0..0x7F).map(Int::toChar).joinToString("") + "é…")

        assertTrue(encoded.all { it.isLetterOrDigit() || it in "-._~%" }, encoded)
        assertTrue(encoded.endsWith("%C3%A9%E2%80%A6"), encoded)
    }
}
