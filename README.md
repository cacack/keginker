# KegInker

An open-source Android and iOS app for writing images to battery-free NFC
e-paper tags. Pick an image, preview it dithered for your panel, tap your
phone to the tag, and the image stays after you walk away.

Built first for keg tap-handle labels on Waveshare's 2.7" NFC-powered
e-paper, with the goal of supporting as many NFC e-paper displays as
possible.

## Principles

- **Reliable writes.** Generous timeouts, clear progress, a completion check
  and clean retry.
- **Native feel.** SwiftUI on iOS and Jetpack Compose on Android, over a
  shared Kotlin Multiplatform core.
- **Minimal permissions.** NFC and the photo picker. No network, accounts
  or telemetry.

## Status

Early development. See [USAGE.md](USAGE.md) for supported displays and how to
try a development build.

## Documentation

- [CONSTITUTION.md](CONSTITUTION.md): mission, principles, non-goals
- [CONTRIBUTING.md](CONTRIBUTING.md): building, testing, layout
- [docs/architecture/](docs/architecture/): design and tag protocols
- [docs/decisions/](docs/decisions/): decision records
- [docs/guides/](docs/guides/): how-tos
- [docs/operations/](docs/operations/): CI

## License

[Apache License 2.0](LICENSE). See [NOTICE](NOTICE) for attributions.
