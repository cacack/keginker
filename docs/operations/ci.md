# CI

GitHub Actions, defined in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), runs on every
pull request and every push to `main`.

| Job | Runner | What it does |
|---|---|---|
| Shared tests | ubuntu | `:sharedLogic` and `:sharedUI` Android host tests (JVM) |
| Android APK | ubuntu | `:androidApp:assembleDebug`, signed with the release key on pushes to `main` (pull requests use the debug key); uploads `tagpaper-debug-apk` (kept 14 days) |
| iOS build | macos | `:sharedLogic:iosSimulatorArm64Test`, then an unsigned simulator build of `iosApp` |
| CI Success | ubuntu | Gate: fails if any job above failed or was cancelled |

`CI Success` is the required status check for merging to `main`.

[`.github/workflows/release.yml`](../../.github/workflows/release.yml) runs
when a `v*` tag is pushed. It publishes a GitHub Release with a signed APK.
See [signing.md](signing.md) for the key, the secrets and cutting a release.

Third-party actions are pinned to commit SHAs. Dependabot keeps them current.
