package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.nfc.Transport
import kotlin.random.Random

// Test models of both Waveshare protocols, written from the protocol rather
// than from the writers, so the fault tests can judge what the writers did.

/** One write sequence: how a good tag answers, and what each command means. */
internal class Protocol(
    val name: String,
    /** A good tag's reply; its busy poll reports the refresh finished. */
    val goodReply: (ByteArray) -> ByteArray,
    /** The busy poll's reply while the panel is still refreshing. */
    val busy: ByteArray,
    /** A well-formed reply without the success status. */
    val refusal: ByteArray,
    /** A reply cut short. */
    val truncated: ByteArray,
    val isBusyPoll: (ByteArray) -> Boolean,
    /** Whether [reply] acknowledges a command that isn't a busy poll. */
    val isAcknowledged: (ByteArray) -> Boolean,
    /** Whether [reply] to a busy poll confirms the refresh finished. */
    val confirmsRefresh: (ByteArray) -> Boolean,
    /** The step command [index] of [commands] belongs to. */
    val stepOf: (commands: List<ByteArray>, index: Int) -> WriteStep,
    val write: suspend (Transport, onProgress: (Int) -> Unit) -> WriteResult,
) {
    override fun toString() = name

    /** Any reply this protocol could accept for some command. */
    fun isAnySuccess(reply: ByteArray) = isAcknowledged(reply) || confirmsRefresh(reply)

    /** Random bytes that no command would accept. */
    fun garbage(random: Random): ByteArray {
        while (true) {
            val reply = random.nextBytes(random.nextInt(0, 9))
            if (!isAnySuccess(reply)) return reply
        }
    }

    /** The commands of a write to a good tag. */
    suspend fun cleanWrite(): List<ByteArray> {
        val tag = ScriptedTag { command, _ -> goodReply(command) }
        check(write(tag) {} == WriteResult.Success) { "$name: a good tag must be written" }
        return tag.sent
    }

    companion object {
        private val panel = WavesharePanel.EPD_2IN7

        val CLASSIC = Protocol(
            name = "classic",
            goodReply = { if (it.isClassic(0x0A)) bytes(0xFF, 0x00) else bytes(0x00, 0x00) },
            busy = bytes(0x00, 0x01),
            refusal = bytes(0x01, 0x00),
            truncated = bytes(0x00),
            isBusyPoll = { it.isClassic(0x0A) },
            isAcknowledged = { it.size >= 2 && it[0] == 0x00.toByte() && it[1] == 0x00.toByte() },
            confirmsRefresh = { it.size >= 2 && it[0] == 0xFF.toByte() && it[1] == 0x00.toByte() },
            stepOf = { commands, index -> classicStep(commands[index][1].toInt() and 0xFF) },
            write = { transport, onProgress ->
                WaveshareWriter(transport, pause = {}).write(panel, ByteArray(panel.frameSize), onProgress)
            },
        )

        val REV22 = Protocol(
            name = "Rev2.2",
            goodReply = { if (it.isIns(0x9B)) bytes(0x00, 0x90, 0x00) else bytes(0x90, 0x00) },
            busy = bytes(0x01, 0x90, 0x00),
            refusal = bytes(0x69, 0x82),
            truncated = bytes(0x90),
            isBusyPoll = { it.isIns(0x9B) },
            isAcknowledged = ::endsInSuccess,
            confirmsRefresh = { endsInSuccess(it) && it.size >= 3 && it[0] != 0x01.toByte() },
            stepOf = ::rev22Step,
            write = { transport, onProgress ->
                WaveshareIsoDepWriter(transport, pause = {})
                    .write(panel, ByteArray(panel.controllerFrameSize), onProgress)
            },
        )

        val ALL = listOf(CLASSIC, REV22)

        private fun classicStep(code: Int) = when (code) {
            0x0D -> WriteStep.INIT
            0x00 -> WriteStep.SELECT_PANEL
            0x01, 0x02, 0x03, 0x05, 0x06 -> WriteStep.POWER_ON
            0x07 -> WriteStep.PREPARE_DATA
            0x18 -> WriteStep.START_DATA
            0x19 -> WriteStep.SEND_DATA
            0x09 -> WriteStep.REFRESH
            0x0A -> WriteStep.WAIT_FOR_REFRESH
            0x04 -> WriteStep.POWER_OFF
            else -> error("Unknown classic command ${byteArrayOf(0xCD.toByte(), code.toByte()).toHex()}")
        }

        /** Data writes (`9A`) belong to the step of the register selected (`99`) before them. */
        private fun rev22Step(commands: List<ByteArray>, index: Int): WriteStep {
            val command = commands[index]
            return when (command[1].toInt() and 0xFF) {
                0xB1 -> WriteStep.UNLOCK
                0x97 -> WriteStep.RESET
                0x9B -> WriteStep.WAIT_FOR_REFRESH
                0x99 -> registerStep(command[5].toInt() and 0xFF)
                0x9A -> rev22Step(commands, (index - 1 downTo 0).first { commands[it].isIns(0x99) })
                else -> error("Unknown Rev2.2 command ${command.toHex()}")
            }
        }

        private fun registerStep(register: Int) = when (register) {
            0x24 -> WriteStep.SEND_DATA
            0x20, 0x22 -> WriteStep.REFRESH
            else -> WriteStep.CONFIGURE
        }

        private fun endsInSuccess(reply: ByteArray) =
            reply.size >= 2 && reply[reply.size - 2] == 0x90.toByte() && reply[reply.size - 1] == 0x00.toByte()

        private fun ByteArray.isClassic(code: Int) = size >= 2 && this[0] == 0xCD.toByte() && this[1] == code.toByte()

        private fun ByteArray.isIns(ins: Int) = size >= 2 && this[0] == 0x74.toByte() && this[1] == ins.toByte()

        private fun bytes(vararg values: Int) = ByteArray(values.size) { values[it].toByte() }
    }
}

/** How a tag misbehaves, from one command onwards. */
internal enum class Fault {
    /** The tag left the field: every exchange throws. */
    LIFTED,

    /** Replies arrive without the success status. */
    REFUSED,
    EMPTY,
    TRUNCATED,

    /** Random bytes that no command would accept. */
    GARBAGE,
    ;

    fun reply(protocol: Protocol, random: Random): ByteArray = when (this) {
        LIFTED -> throw IllegalStateException("Tag was lost")
        REFUSED -> protocol.refusal
        EMPTY -> ByteArray(0)
        TRUNCATED -> protocol.truncated
        GARBAGE -> protocol.garbage(random)
    }
}

/** Records every command and answers with [reply]. */
internal class ScriptedTag(private val reply: (command: ByteArray, index: Int) -> ByteArray) : Transport {
    val sent = mutableListOf<ByteArray>()
    var closed = false
        private set

    override fun close() {
        closed = true
    }

    override suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray {
        sent += command
        return reply(command, sent.size - 1)
    }
}

/** A tag that answers like a good one until command [failAt], then with [fault] from there on. */
internal fun faultyTag(protocol: Protocol, failAt: Int, fault: Fault): ScriptedTag {
    val random = Random(failAt)
    return ScriptedTag { command, index -> if (index < failAt) protocol.goodReply(command) else fault.reply(protocol, random) }
}
