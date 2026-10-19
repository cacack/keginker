package io.github.cacack.keginker.nfc

/**
 * What the phone learned about a tag when it found it, for diagnostics
 * reports. Captured without sending the tag any command.
 */
class TagInfo(
    /**
     * The NFC technologies the platform reports, such as `NfcA` or `IsoDep`
     * (Android), or the Core NFC tag type with its MIFARE family or ISO 7816 AID (iOS).
     */
    val technologies: List<String>,
    val uid: ByteArray,
    /** Null where the platform doesn't expose it, as on iOS. */
    val atqa: ByteArray? = null,
    /** Null where the platform doesn't expose it, as on iOS. */
    val sak: Int? = null,
    /** ISO-DEP ATS historical bytes. */
    val historicalBytes: ByteArray? = null,
)
