# CI

GitHub Actions, defined in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), runs on every
pull request and every push to `main`.

| Job | Runner | What it does |
|---|---|---|
| Shared tests | ubuntu | `:sharedLogic` and `:sharedUI` Android host tests (JVM) |
| Android APK | ubuntu | `:androidApp:assembleDebug`; uploads `tagpaper-debug-apk` (kept 14 days) |
| iOS build | macos | `:sharedLogic:iosSimulatorArm64Test`, then an unsigned simulator build of `iosApp` |
| CI Success | ubuntu | Gate: fails if any job above failed or was cancelled |

`CI Success` is the required status check for merging to `main`.

Third-party actions are pinned to commit SHAs. Dependabot keeps them current.
