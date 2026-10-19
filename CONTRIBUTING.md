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

## Testing a promise

[docs/architecture/testing.md](docs/architecture/testing.md) maps each promise
the app makes to the check that guards it. A change that touches a promise
adds a check that would fail if the promise broke (a seeded property, a
simulator comparison or a recording, not only an example worked out from the
new code), updates that table, and says what to run by hand if CI can't
check it.

## Building and testing

Requires JDK 21. iOS builds require macOS with Xcode.

- Shared tests: `./gradlew :sharedLogic:testAndroidHostTest :sharedUI:testAndroidHostTest`
- iOS tests (macOS): `./gradlew :sharedLogic:iosSimulatorArm64Test`
- Android transport tests: `./gradlew :androidApp:testDebugUnitTest`
- iOS app tests (transports, image loading; macOS): `xcodebuild -project iosApp/iosApp.xcodeproj -scheme iosApp -destination 'platform=iOS Simulator,name=<an iPhone simulator>' CODE_SIGNING_ALLOWED=NO test`
- Mutation testing (on demand, not in CI): `./gradlew :sharedLogic:pitest`; see
  [docs/reviews/testing/2026-10-18-mutation-testing.md](docs/reviews/testing/2026-10-18-mutation-testing.md)
- Android debug APK: `./gradlew :androidApp:assembleDebug`
- iOS app: open `iosApp/` in Xcode and run.

Both platforms' loader tests turn the photos in `fixtures/photos` into the
golden views beside them (`.pbm`, the expected `renderView` output). The
Android test (in the shared tests) writes a missing golden and fails; check it
by eye, commit it, and the iOS test must match it. `generate.py` there remakes
the photos.

Image pipeline tests check properties over at least 100 seeded inputs
(`kotlin.random.Random(seed)`); a failure names its seed so it can be replayed.

Protocol tests can also replay writes recorded from real tags; see
[docs/guides/record-a-tag-write.md](docs/guides/record-a-tag-write.md) to
record one.

CI has no NFC, so some checks run by hand on a phone and board; see
[docs/guides/run-hardware-tests.md](docs/guides/run-hardware-tests.md). Send
in a run with the **Hardware test run** issue form.

CI runs all of the above on every pull request; the debug APK is attached to
each run as an artifact.

Local builds use the default debug key unless you have the release key; see
[docs/operations/signing.md](docs/operations/signing.md).

## Commits

Conventional commits (`feat:`, `fix:`, `docs:`, `ci:` …).

## Pull request titles

Plain prose, not conventional commits: `Share a diagnostics report after a
write`, not `feat(ios): share a diagnostics report after a write`. PRs land as
merge commits whose body is the PR title, so a conventional title gives
release-please a second copy of every change and duplicates its changelog
entries. CI checks the title.
