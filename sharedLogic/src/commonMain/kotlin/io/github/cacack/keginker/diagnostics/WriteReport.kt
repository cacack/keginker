package io.github.cacack.keginker.diagnostics

import io.github.cacack.keginker.nfc.TagInfo
import io.github.cacack.keginker.waveshare.WriteStep

/** The app that wrote the report; each platform fills it in. */
data class AppInfo(val version: String, val buildType: String, val commit: String)

/** The phone that wrote the report; each platform fills it in. [os] includes its version. */
data class DeviceInfo(val manufacturer: String, val model: String, val os: String, val nfcEnabled: Boolean)

/**
 * What happened during a write (or with an unsupported tag), for
 * [formatDiagnosticReport]. [step] and [reason] are set when a write step
 * failed. [cause] can also be set without them, when a write was interrupted
 * or failed unexpectedly. All three are null for an unsupported tag or a
 * write that [succeeded].
 */
class WriteReport(
    /** Null if the platform didn't capture it. */
    val tag: TagInfo?,
    /** From [io.github.cacack.keginker.nfc.protocolName]. */
    val protocol: String,
    val panel: String,
    /** What the user was shown. */
    val message: String,
    val step: WriteStep? = null,
    val reason: String? = null,
    val cause: Throwable? = null,
    val exchanges: List<Exchange> = emptyList(),
    /** Whether the tag confirmed the refresh. */
    val succeeded: Boolean = false,
    /** Whether the image was the built-in test pattern, so a reader knows what the panel should show. */
    val testPattern: Boolean = false,
)
