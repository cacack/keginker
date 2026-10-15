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

/** A short explanation of a failed write that tells the user how to recover. */
val WriteResult.Failure.message: String
    get() = "The write failed while ${step.activity}. Hold the phone still against the tag and try again."
