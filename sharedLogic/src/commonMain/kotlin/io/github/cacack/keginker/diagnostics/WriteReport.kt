package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.LabelDisplay
import io.github.cacack.keginker.nfc.FoundTag
import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.nfc.protocolName
import io.github.cacack.keginker.waveshare.WriteStep

/** The app that wrote the report; each platform fills it in. */
data class AppInfo(val version: String, val buildType: String, val commit: String)

/** The phone that wrote the report; each platform fills it in. [os] includes its version. */
data class DeviceInfo(val manufacturer: String, val model: String, val os: String, val nfcEnabled: Boolean)

/** How a write ended, for [WriteReport]. */
enum class Outcome {
    /** The tag confirmed the refresh. */
    SUCCEEDED,

    FAILED,

    /** Nothing was sent, as for an unsupported tag. */
    NOT_ATTEMPTED,
}

/**
 * What happened during a write (or with an unsupported tag), for
 * [formatDiagnosticReport]. [step] and [reason] are set when a write step
 * failed. [cause] can also be set without them, when a write was interrupted
 * or failed unexpectedly. All three are null unless the [outcome] is
 * [Outcome.FAILED].
 */
class WriteReport(
    /** Null if the platform didn't capture it. */
    val tag: TagInfo?,
    /** From [io.github.cacack.keginker.nfc.protocolName], or EZ Sign once its device information decoded. */
    val protocol: String,
    /** The [LabelDisplay.name] the label was composed for. */
    val panel: String,
    /** What the user was shown. */
    val message: String,
    val outcome: Outcome,
    /**
     * Whether the image was the built-in test pattern, so a reader knows what
     * the panel should show. Not reported when the [outcome] is [Outcome.NOT_ATTEMPTED].
     */
    val testPattern: Boolean,
    val step: WriteStep? = null,
    val reason: String? = null,
    val cause: Throwable? = null,
    val exchanges: List<Exchange> = emptyList(),
)

/**
 * The report of writing a label composed for [display] to [tag], built the
 * same way on both platforms. [log] is null when nothing was sent; [step],
 * [reason] and [cause] are as in [WriteReport]. [protocol] is the tag's
 * classification unless the write learned more.
 */
fun writeReport(
    tag: FoundTag,
    display: LabelDisplay,
    message: String,
    outcome: Outcome,
    testPattern: Boolean,
    log: ExchangeLog?,
    step: WriteStep? = null,
    reason: String? = null,
    cause: Throwable? = null,
    protocol: String = tag.protocolName,
) = WriteReport(
    tag = tag.info,
    protocol = protocol,
    panel = display.name,
    message = message,
    outcome = outcome,
    testPattern = testPattern,
    step = step,
    reason = reason,
    cause = cause,
    exchanges = log?.entries.orEmpty(),
)
