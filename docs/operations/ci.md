# CI

GitHub Actions, defined in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), runs on every
pull request and every push to `main`.

| Job | Runner | What it does |
|---|---|---|
| Shared tests | ubuntu | `:sharedLogic` and `:sharedUI` Android host tests (JVM), including Robolectric Compose tests of the Android screens |
| Android APK | ubuntu | `:androidApp:testDebugUnitTest`, then `:androidApp:assembleDebug`, signed with the release key on pushes to `main` (pull requests use the debug key); uploads `keginker-debug-apk` (kept 14 days) |
| iOS build | macos | `:sharedLogic:iosSimulatorArm64Test`, then an unsigned simulator build of `iosApp`, then its XCTest target (`iosAppTests`) on any available iPhone simulator, then fails if it declares any permission beyond NFC ([constitution](../../CONSTITUTION.md) C4) |
| CI Success | ubuntu | Gate: fails if any job above failed or was cancelled |

`CI Success` is the required status check for merging to `main`.

[`.github/workflows/release-please.yml`](../../.github/workflows/release-please.yml)
runs on every push to `main`. It keeps a release PR open; merging it creates
the `v*` tag and GitHub Release. It authenticates as the repository's
release-please GitHub App so the release PR runs CI and the tag triggers
[`.github/workflows/release.yml`](../../.github/workflows/release.yml), which
attaches a signed APK and an App Store-signed ipa to the release and
uploads the ipa to TestFlight. See [signing.md](signing.md) for the key, the
secrets and cutting a release, and [testflight.md](testflight.md) for the
App Store Connect setup.

[`.github/workflows/codeql.yml`](../../.github/workflows/codeql.yml) runs
CodeQL on every pull request, every push to `main` and weekly. It analyses
the workflows, the Kotlin code (a traced `:androidApp:assembleDebug`, which
compiles `sharedLogic` and `sharedUI` too) and the Swift code (a traced,
unsigned simulator build on macOS). Results appear under Security → Code
scanning.

[`.github/workflows/scorecard.yml`](../../.github/workflows/scorecard.yml)
runs [OpenSSF Scorecard](https://scorecard.dev/viewer/?uri=github.com/cacack/keginker)
weekly, on pushes to `main` and when branch protection changes, publishes
the score shown in the README badge, and uploads its findings to code
scanning.

Neither feeds `CI Success`, so findings never block a merge; they are
triaged as issues.

Third-party actions are pinned to commit SHAs. Dependabot keeps them current.
