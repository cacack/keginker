# CI

GitHub Actions, defined in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), runs on every
pull request and every push to `main`.

| Job | Runner | What it does |
|---|---|---|
| Shared tests | ubuntu | `:sharedLogic` and `:sharedUI` Android host tests (JVM) |
| Android APK | ubuntu | `:androidApp:assembleDebug`, signed with the release key on pushes to `main` (pull requests use the debug key); uploads `keginker-debug-apk` (kept 14 days) |
| iOS build | macos | `:sharedLogic:iosSimulatorArm64Test`, then an unsigned simulator build of `iosApp`, then fails if it declares any permission beyond NFC ([constitution](../../CONSTITUTION.md) C4) |
| CI Success | ubuntu | Gate: fails if any job above failed or was cancelled |

`CI Success` is the required status check for merging to `main`.

[`.github/workflows/release-please.yml`](../../.github/workflows/release-please.yml)
runs on every push to `main`. It keeps a release PR open; merging it creates
the `v*` tag and GitHub Release. It authenticates as the repository's
release-please GitHub App so the release PR runs CI and the tag triggers
[`.github/workflows/release.yml`](../../.github/workflows/release.yml), which
attaches a signed APK to the release. See [signing.md](signing.md) for the
key, the secrets and cutting a release.

Third-party actions are pinned to commit SHAs. Dependabot keeps them current.
