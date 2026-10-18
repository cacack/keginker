package io.github.cacack.keginker.nfc

/**
 * What the phone learned about a tag when it found it, for diagnostics
 * reports. Captured without sending the tag any command.
 */
class TagInfo(
    /** The NFC technologies the platform reports, such as `NfcA` or `IsoDep`. */
    val technologies: List<String>,
    val uid: ByteArray,
    val atqa: ByteArray? = null,
    val sak: Int? = null,
    /** ISO-DEP ATS historical bytes. */
    val historicalBytes: ByteArray? = null,
)
