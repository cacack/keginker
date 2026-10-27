package io.github.cacack.keginker.waveshare

import io.github.cacack.keginker.LabelDisplay

/** What the tag was doing at this step, for messages such as "failed while …". */
val WriteStep.activity: String get() = when (this) {
    WriteStep.INIT, WriteStep.SELECT_PANEL, WriteStep.UNLOCK, WriteStep.AUTHENTICATE -> "starting the write"
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

/** Shown once a write is done; a report's message too. */
const val DONE_MESSAGE = "Done. The tag keeps this image without power."

/** Shown when a tag isn't one KegInker can write. */
const val UNSUPPORTED_TAG_MESSAGE = "This tag isn't supported. KegInker writes Waveshare and EZ Sign NFC e-paper displays."

/** Shown when the display held to the phone is [found], not the display the user chose. */
fun chooseDisplayMessage(found: LabelDisplay): String = "This is the ${found.title} display. Choose ${found.title} and try again."

/**
 * Shown when a Waveshare tag is held while an EZ Sign display is chosen. The
 * tag doesn't say its size, so this names every Waveshare display.
 */
fun chooseWaveshareMessage(): String =
    "This is a Waveshare display. Choose " +
        LabelDisplay.entries.filter { it.wavesharePanel != null }.joinToString(" or ") { it.title } +
        " and try again."

/**
 * Shown when an ISO-DEP tag that isn't a Waveshare board is held while the
 * Waveshare display [chosen] is: it may be an EZ Sign display, which is
 * identified only once it's chosen, so nothing is sent to it before then.
 */
fun notWaveshareMessage(chosen: LabelDisplay): String =
    "This isn't a ${chosen.title} display. If it's an EZ Sign display, choose it and try again."

/** Shown when an EZ Sign display's device information gives a size no [LabelDisplay] has. */
const val EZ_SIGN_SIZE_UNSUPPORTED_MESSAGE =
    "This EZ Sign display is a size KegInker doesn't write yet. Use Report a display to share it so it can be added."

private const val REFRESH_NOT_CONFIRMED = "The display didn't confirm it finished updating."

/** Shown when the display never confirmed it finished refreshing. */
const val REFRESH_UNCONFIRMED_MESSAGE =
    "$REFRESH_NOT_CONFIRMED Lift the phone away, then hold it still against the tag and try again."

/** Advice when contact is lost while the panel redraws, which a weak field causes more often than movement. */
const val REPOSITION_ADVICE =
    "A display can run short of power while it redraws: " +
        "turn the phone a quarter turn over the display, then hold it still and try again."

/**
 * Shown when the tag never answered a refresh it holds its reply to until
 * the panel is done, as EZ Sign's does: contact lost while it redraws is
 * usually a weak field (#138).
 */
const val BLOCKING_REFRESH_UNCONFIRMED_MESSAGE = "$REFRESH_NOT_CONFIRMED $REPOSITION_ADVICE"

/** Shown when a write runs past [WRITE_DEADLINE_MS]. */
const val WRITE_TIMED_OUT_MESSAGE =
    "The tag answered too slowly, so the write was stopped. " +
        "Lift the phone away, then hold it still against the tag and try again."

/**
 * A short explanation for the user that says how to recover. Diagnostics
 * belong in [WriteResult.Failure.reason] and [WriteResult.Failure.cause].
 *
 * Phones only notice a tag as it arrives, so retrying means presenting it again.
 */
val WriteResult.Failure.userMessage: String
    get() = when (kind) {
        FailureKind.LOST_CONTACT ->
            // A redraw draws the most power, so contact lost then is usually a weak field, not movement (#138).
            "Lost contact with the tag while ${step.activity}. " +
                if (step == WriteStep.REFRESH || step == WriteStep.WAIT_FOR_REFRESH) {
                    REPOSITION_ADVICE
                } else {
                    "Lift the phone away, then hold it still against the tag and try again."
                }
        FailureKind.UNEXPECTED_REPLY ->
            "The tag gave an unexpected reply while ${step.activity}. " +
                "Lift the phone away and try again; if it keeps happening, this tag may not be supported."
        FailureKind.REFRESH_UNCONFIRMED ->
            // Unconfirmed at REFRESH is a blocking refresh that never answered; Waveshare's busy polls end at WAIT_FOR_REFRESH.
            if (step == WriteStep.REFRESH) BLOCKING_REFRESH_UNCONFIRMED_MESSAGE else REFRESH_UNCONFIRMED_MESSAGE
    }
