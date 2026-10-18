# Architecture overview

KegInker is a Kotlin Multiplatform (KMP) project: a shared Kotlin core with a
native UI on each platform.

```
┌────────────── sharedLogic ──────────────┐
│ panel definitions (size, colours, type) │
│ image pipeline: fill (crop + scale) →   │
│   dither → rotate + bit-pack            │
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
Swift). This keeps the protocols testable without a phone or a tag. Most
protocol tests drive a writer with a fake transport whose replies are written
by hand: they check the commands the writer sends and how it handles each
reply, not that a real board agrees. Writes recorded from real tags are
replayed through a transport that fails the test on any command that differs
from the recording; [record-a-tag-write.md](../guides/record-a-tag-write.md)
lists which boards have one.

Every implementation keeps the contract stated in `Transport`'s KDoc: reply
format, failures, framing, timeouts, one command at a time, and closing. Each
platform's transport tests check the clauses its transport enforces, plus its
own threading rules, against a fake of its NFC API (`androidApp/src/test`,
`iosApp/iosAppTests`).

## Constraints that shape the design

- **The tag is powered by the phone's field.** Moving the phone mid-write cuts
  power. The UI guides placement, shows progress, polls the tag until refresh
  completes, and retries cleanly.
- **Timeouts.** Panel refreshes take seconds. Android timeouts are set
  generously. iOS offers no per-command timeout and limits a tag connection to
  roughly 20 seconds, so every write fits a 15 s
  [time budget](protocols.md#write-time-budget).
- **Image pipeline.** Platforms decode images natively, downsampled to a few
  megapixels at most. They hand the core non-premultiplied `0xAARRGGBB`
  pixels (`GrayImage.fromArgb`). The core centre-crops and scales to the
  panel's view, with no letterboxing, so UIs should preview the crop. It then
  reduces to black and white (threshold or Floyd–Steinberg), rotates and packs
  into the panel's memory layout (`encodeFrame`).
- **Done means the tag shows the preview.** On Android, `WriteSession` holds
  the preview and the write together: changing the preview disarms a waiting
  write, and an image that finishes loading mid-write is shown only once the
  write ends. A seeded model-based test checks this across random sequences
  of user actions and tag events.
- **Rotation and packing are per panel.** Each panel declares its view
  orientation (`PanelView`). Unit tests pin the mapping, because it's easy to
  get subtly wrong.

See [protocols.md](protocols.md) for the tag protocols.
