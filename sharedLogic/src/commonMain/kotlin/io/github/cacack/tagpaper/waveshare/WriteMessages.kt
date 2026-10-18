package io.github.cacack.tagpaper.waveshare

/** What the tag was doing at this step, for messages such as "failed while …". */
val WriteStep.activity: String get() = when (this) {
    WriteStep.INIT, WriteStep.SELECT_PANEL, WriteStep.UNLOCK -> "starting the write"
    WriteStep.POWER_ON, WriteStep.RESET -> "powering the panel"
    WriteStep.CONFIGURE -> "preparing to send the image"
    WriteStep.PREPARE_DATA, WriteStep.START_DATA -> "preparing to send the image"
    WriteStep.SEND_DATA -> "sending the image"
    WriteStep.REFRESH, WriteStep.WAIT_FOR_REFRESH -> "the panel was refreshing"
    WriteStep.POWER_OFF -> "finishing the write"
}

/** Progress at which the whole image has been sent and the panel starts redrawing. */
const val IMAGE_SENT_PROGRESS = 90

/** What to show while a write is at [percent]; [phone] is "phone" or "iPhone". */
fun writeProgressMessage(percent: Int, phone: String): String =
    if (percent < IMAGE_SENT_PROGRESS) {
        "Writing $percent%. Keep the $phone still."
    } else {
        "Updating the display. Keep the $phone still."
    }

/** Shown when a tag isn't one tagpaper can write. */
const val UNSUPPORTED_TAG_MESSAGE = "This tag isn't supported. tagpaper writes Waveshare NFC e-paper tags."

/**
 * A short explanation for the user that says how to recover. Diagnostics
 * belong in [WriteResult.Failure.reason] and [WriteResult.Failure.cause].
 *
 * Phones only notice a tag as it arrives, so retrying means presenting it again.
 */
val WriteResult.Failure.userMessage: String
    get() = if (cause != null) {
        "Lost contact with the tag while ${step.activity}. " +
            "Lift the phone away, then hold it still against the tag and try again."
    } else {
        "The tag gave an unexpected reply while ${step.activity}. " +
            "Lift the phone away and try again; if it keeps happening, this tag may not be supported."
    }
