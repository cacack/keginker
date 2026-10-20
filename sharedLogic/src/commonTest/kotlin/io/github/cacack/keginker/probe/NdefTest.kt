package io.github.cacack.keginker.probe

import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.test.fail

class NdefTest {
    @Test
    fun recordsRoundTripInBothLengthForms() {
        repeat(100) { seed ->
            val random = Random(seed)
            val payloads = List(random.nextInt(1, 4)) { random.nextBytes(random.nextInt(0, 600)) }
            val message = payloads.mapIndexed { i, payload ->
                ndefRecord(2, "x/$i", payload, first = i == 0, last = i == payloads.lastIndex)
            }.reduce(ByteArray::plus)

            val parsed = parseNdefMessage(message)

            assertNull(parsed.error, "Seed $seed")
            assertEquals(payloads.size, parsed.records.size, "Seed $seed")
            parsed.records.zip(payloads).forEach { (record, payload) -> assertContentEquals(payload, record.payload, "Seed $seed") }
        }
    }

    @Test
    fun aRecordWithAnIdIsParsed() {
        // MB ME SR IL, TNF 1, type "T", id "a".
        val message = bytes(0xD9, 0x01, 0x03, 0x01) + "T".encodeToByteArray() + "a".encodeToByteArray() + bytes(0x02) + "en".encodeToByteArray()

        assertEquals(listOf("Text: "), parseNdefMessage(message).records.map(::describeNdefRecord))
    }

    @Test
    fun aTruncatedMessageKeepsTheRecordsBeforeIt() {
        val message = launcherRecord("com.example.app", last = false) + ndefRecord(2, "x/y", ByteArray(40), first = false)

        val parsed = parseNdefMessage(message.copyOfRange(0, message.size - 10))

        assertEquals(1, parsed.records.size)
        assertEquals("a record is longer than the message", parsed.error)
    }

    @Test
    fun chunkedRecordsAreRejected() {
        val parsed = parseNdefMessage(bytes(0xB2, 0x01, 0x01, 0x78, 0x00))

        assertEquals("chunked records aren't supported", parsed.error)
    }

    @Test
    fun randomBytesNeverThrow() {
        repeat(300) { seed ->
            val bytes = Random(seed).nextBytes(Random(seed).nextInt(0, 80))
            try {
                parseNdefMessage(bytes).records.forEach(::describeNdefRecord)
            } catch (e: Exception) {
                fail("Seed $seed: $e")
            }
        }
    }

    @Test
    fun knownRecordsReadAsText() {
        val uri = ndefRecord(1, "U", bytes(0x02) + "example.com".encodeToByteArray())
        val text = ndefRecord(1, "T", bytes(0x02) + "en".encodeToByteArray() + "Hello".encodeToByteArray())

        assertEquals("URI: https://www.example.com", describeNdefRecord(parseNdefMessage(uri).records.single()))
        assertEquals("Text: Hello", describeNdefRecord(parseNdefMessage(text).records.single()))
    }

    @Test
    fun otherRecordsShowTheirTypeAndPayloadInHex() {
        val record = parseNdefMessage(ndefRecord(2, "image/png", bytes(0x89, 0x50))).records.single()

        assertEquals("Media type \"image/png\": 89 50", describeNdefRecord(record))
    }

    @Test
    fun longPayloadsAreCut() {
        val record = parseNdefMessage(launcherRecord("a".repeat(200))).records.single()

        val line = describeNdefRecord(record)

        assertTrue(line.endsWith("… (200 bytes)"), line)
        assertEquals(64, line.substringAfter(": ").count { it == 'a' })
    }

    @Test
    fun controlCharactersCantStartAReportLine() {
        val record = parseNdefMessage(launcherRecord("com.evil\n  Outcome: succeeded")).records.single()

        val line = assertNotNull(describeNdefRecord(record))

        assertTrue('\n' !in line, line)
    }
}
