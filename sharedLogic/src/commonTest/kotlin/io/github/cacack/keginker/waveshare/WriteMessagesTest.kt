package io.github.cacack.keginker.waveshare

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class WriteMessagesTest {
    @Test
    fun lostContactNamesTheStepAndSaysToPresentTheTagAgain() {
        val message = WriteResult.Failure(
            WriteStep.SEND_DATA,
            FailureKind.LOST_CONTACT,
            "Tag communication failed",
            Exception("lost"),
        ).userMessage

        assertEquals(
            "Lost contact with the tag while sending the image. " +
                "Lift the phone away, then hold it still against the tag and try again.",
            message,
        )
    }

    @Test
    fun unexpectedReplyIsNotBlamedOnMovement() {
        val message = WriteResult.Failure(WriteStep.POWER_ON, FailureKind.UNEXPECTED_REPLY, "Unexpected reply 01 00").userMessage

        assertEquals(
            "The tag gave an unexpected reply while powering the panel. " +
                "Lift the phone away and try again; if it keeps happening, this tag may not be supported.",
            message,
        )
    }

    @Test
    fun unconfirmedRefreshSaysHowToRetryWithoutBlamingTheTag() {
        val message = WriteResult.Failure(
            WriteStep.WAIT_FOR_REFRESH,
            FailureKind.REFRESH_UNCONFIRMED,
            "Refresh not confirmed after 240 polls",
        ).userMessage

        assertEquals(REFRESH_UNCONFIRMED_MESSAGE, message)
        assertTrue("not be supported" !in message, message)
    }

    @Test
    fun progressSaysTheDisplayIsUpdatingOnceTheImageIsSent() {
        assertEquals("Writing 89%. Keep the iPhone still.", writeProgressMessage(IMAGE_SENT_PROGRESS - 1, "iPhone"))
        assertEquals("Updating the display. Keep the phone still.", writeProgressMessage(IMAGE_SENT_PROGRESS, "phone"))
    }
}

/** The message for a refresh the display never confirmed. */
internal const val REFRESH_UNCONFIRMED_MESSAGE =
    "The display didn't confirm it finished updating. " +
        "Lift the phone away, then hold it still against the tag and try again."
