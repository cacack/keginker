package io.github.cacack.tagpaper.waveshare

/** What the tag was doing at this step, for messages such as "failed while …". */
val WriteStep.activity: String get() = when (this) {
    WriteStep.INIT, WriteStep.SELECT_PANEL -> "starting the write"
    WriteStep.POWER_ON -> "powering the panel"
    WriteStep.PREPARE_DATA, WriteStep.START_DATA -> "preparing to send the image"
    WriteStep.SEND_DATA -> "sending the image"
    WriteStep.REFRESH, WriteStep.WAIT_FOR_REFRESH -> "the panel was refreshing"
    WriteStep.POWER_OFF -> "finishing the write"
}

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
