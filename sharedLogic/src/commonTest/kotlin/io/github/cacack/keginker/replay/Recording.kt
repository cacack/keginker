package io.github.cacack.keginker.replay

import io.github.cacack.keginker.nfc.Transport
import kotlin.test.fail

/**
 * A write recorded from a real tag: every command and reply in order, as a
 * debug build logs them, plus [notes] on where it came from. See
 * docs/guides/record-a-tag-write.md for the format and how to record one.
 */
class Recording(val notes: Map<String, String>, val exchanges: List<Exchange>)

class Exchange(val command: ByteArray, val reply: ByteArray)

/** Notes every recording must carry, as `# key: value` lines. */
val REQUIRED_NOTES = listOf("board", "phone", "os", "app")

/**
 * Reads a recording: `# key: value` notes (other `#` lines are comments),
 * `> hex` commands each followed by its `< hex` reply, and blank lines.
 * Anything else is rejected, so a stray log line can't hide in a fixture.
 */
fun parseRecording(text: String): Recording {
    val notes = linkedMapOf<String, String>()
    val exchanges = mutableListOf<Exchange>()
    var command: ByteArray? = null
    text.lines().forEachIndexed { index, raw ->
        val line = raw.trim()
        val where = "Line ${index + 1}"
        when {
            line.isEmpty() -> {}
            line.startsWith("#") -> {
                val note = line.removePrefix("#").split(":", limit = 2)
                if (note.size == 2) notes[note[0].trim()] = note[1].trim()
            }
            line.startsWith(">") -> {
                require(command == null) { "$where: command follows a command with no reply" }
                command = parseHex(line.drop(1), where)
            }
            line.startsWith("<") -> {
                val sent = requireNotNull(command) { "$where: reply with no command before it" }
                exchanges += Exchange(sent, parseHex(line.drop(1), where))
                command = null
            }
            else -> throw IllegalArgumentException("$where: expected '# note', '> command' or '< reply', got \"$line\"")
        }
    }
    require(command == null) { "Recording ends with a command that has no reply" }
    require(exchanges.isNotEmpty()) { "Recording has no exchanges" }
    val missing = REQUIRED_NOTES.filter { notes[it].isNullOrEmpty() }
    require(missing.isEmpty()) { "Recording is missing notes: ${missing.joinToString()}" }
    return Recording(notes, exchanges)
}

private fun parseHex(text: String, where: String): ByteArray {
    val pairs = text.trim().split(Regex("\\s+")).filter { it.isNotEmpty() }
    require(pairs.isNotEmpty()) { "$where: no bytes" }
    return ByteArray(pairs.size) { i ->
        val pair = pairs[i]
        require(pair.length == 2) { "$where: \"$pair\" isn't one hex byte" }
        (pair.toIntOrNull(16) ?: throw IllegalArgumentException("$where: \"$pair\" isn't hex")).toByte()
    }
}

fun ByteArray.hex() = joinToString(" ") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

/**
 * Answers each command with the reply recorded for it. A command that differs
 * from the recorded one, or comes after the recording ends, fails the test:
 * it throws an [AssertionError], which writers don't mistake for a tag error.
 */
class ReplayTransport(private val recording: Recording) : Transport {
    private var next = 0

    /** Whether every recorded exchange has been replayed. */
    val finished get() = next == recording.exchanges.size

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        val number = next + 1
        val expected = recording.exchanges.getOrNull(next)
            ?: fail("Command $number is past the end of the recording: ${command.hex()}")
        if (!command.contentEquals(expected.command)) {
            fail("Command $number differs from the recording\n  recorded: ${expected.command.hex()}\n  sent:     ${command.hex()}")
        }
        next++
        return expected.reply.copyOf()
    }
}
