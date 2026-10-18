# Contributing

## Layout

Kotlin Multiplatform with native UIs:

- `sharedLogic/` — the shared core: panel definitions, image pipeline,
  tag protocols. Most code and tests live here (`src/commonMain`,
  `src/commonTest`).
- `sharedUI/` — Android UI in Compose (Compose Multiplatform, Android target
  only).
- `androidApp/` — Android entry point.
- `iosApp/` — Xcode project; SwiftUI UI. Its build phase runs Gradle to
  compile `sharedLogic` into a framework.

## Building and testing

Requires JDK 21. iOS builds require macOS with Xcode.

- Shared tests: `./gradlew :sharedLogic:testAndroidHostTest :sharedUI:testAndroidHostTest`
- iOS tests (macOS): `./gradlew :sharedLogic:iosSimulatorArm64Test`
- Android debug APK: `./gradlew :androidApp:assembleDebug`
- iOS app: open `iosApp/` in Xcode and run.

Image pipeline tests check properties over at least 100 seeded inputs
(`kotlin.random.Random(seed)`); a failure names its seed so it can be replayed.

Protocol tests can also replay writes recorded from real tags; see
[docs/guides/record-a-tag-write.md](docs/guides/record-a-tag-write.md) to
record one.

CI runs all of the above on every pull request; the debug APK is attached to
each run as an artifact.

Local builds use the default debug key unless you have the release key; see
[docs/operations/signing.md](docs/operations/signing.md).

## Commits

Conventional commits (`feat:`, `fix:`, `docs:`, `ci:` …).
