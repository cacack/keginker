# Architecture overview

tagpaper is a Kotlin Multiplatform (KMP) project: a shared Kotlin core with a
native UI on each platform.

```
┌────────────── sharedLogic ──────────────┐
│ panel definitions (size, colours, type) │
│ image pipeline: crop/scale → quantise → │
│   dither → bit-pack                     │
│ protocol state machines → byte commands │
│ presets                                 │
└───────────────┬─────────────────────────┘
                │ Transport.transceive(bytes, timeout) → bytes
     ┌──────────┴──────────┐
 Android                  iOS
 Compose UI               SwiftUI
 NfcA / IsoDep            Core NFC (NFCMiFareTag /
 (long setTimeout)        NFCISO7816Tag)
```

## Modules

| Module | Role |
|---|---|
| `sharedLogic/` | The core. Platform-independent, unit-tested on the JVM. Compiled to a static framework for iOS. |
| `sharedUI/` | Android UI in Compose (Compose Multiplatform, Android target only). |
| `androidApp/` | Android entry point. |
| `iosApp/` | Xcode project with the SwiftUI UI. Its build phase runs Gradle to compile `sharedLogic`. |

## The Transport seam

The core never calls an NFC API. A protocol is a state machine that emits
commands and consumes replies through one small interface:

```kotlin
interface Transport {
    suspend fun transceive(command: ByteArray, timeoutMs: Long): ByteArray
}
```

Each platform implements it natively (Android `NfcA`/`IsoDep`, iOS Core NFC in
Swift). This keeps the protocols testable without a phone or a tag: tests
drive them with a fake transport that replays recorded exchanges.

## Constraints that shape the design

- **The tag is powered by the phone's field.** Moving the phone mid-write cuts
  power. The UI guides placement, shows progress, polls the tag until refresh
  completes, and retries cleanly.
- **Timeouts.** Panel refreshes take seconds. Android timeouts are set
  generously. iOS offers no per-command timeout and limits a tag connection to
  roughly 20 seconds, so writes must be efficient.
- **Rotation and packing are per panel.** They're covered by unit tests in the
  core, because they're easy to get subtly wrong.

See [protocols.md](protocols.md) for the tag protocols.
