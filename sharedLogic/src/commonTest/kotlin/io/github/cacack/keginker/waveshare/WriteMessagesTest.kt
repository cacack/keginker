package io.github.cacack.keginker.waveshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteMessagesTest {
    @Test
    fun lostContactNamesTheStepAndSaysToPresentTheTagAgain() {
        val message = WriteResult.Failure(WriteStep.SEND_DATA, "Tag communication failed", Exception("lost")).userMessage

        assertTrue("Lost contact" in message && "sending the image" in message && "Lift the phone away" in message, message)
    }

    @Test
    fun unexpectedReplyIsNotBlamedOnMovement() {
        val message = WriteResult.Failure(WriteStep.POWER_ON, "Unexpected reply 01 00").userMessage

        assertTrue("unexpected reply" in message && "powering the panel" in message, message)
    }

    @Test
    fun progressSaysTheDisplayIsUpdatingOnceTheImageIsSent() {
        assertEquals("Writing 89%. Keep the iPhone still.", writeProgressMessage(IMAGE_SENT_PROGRESS - 1, "iPhone"))
        assertEquals("Updating the display. Keep the phone still.", writeProgressMessage(IMAGE_SENT_PROGRESS, "phone"))
    }
}
