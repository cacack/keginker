# CI

GitHub Actions, defined in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), runs on every
pull request and every push to `main`.

| Job | Runner | What it does |
|---|---|---|
| Shared tests | ubuntu | `:sharedLogic` and `:sharedUI` Android host tests (JVM), including Robolectric Compose tests of the Android screens |
| Android APK | ubuntu | `:androidApp:testDebugUnitTest`, then `:androidApp:assembleDebug`, signed with the runner's debug key (never the release key); uploads `keginker-debug-apk` (kept 14 days) |
| iOS build | macos | `:sharedLogic:iosSimulatorArm64Test`, then an unsigned simulator build of `iosApp`, then its XCTest target (`iosAppTests`) on any available iPhone simulator, then fails if it declares any permission beyond NFC ([constitution](../../CONSTITUTION.md) C4) |
| CI Success | ubuntu | Gate: fails if any job above failed or was cancelled |

`CI Success` is the required status check for merging to `main`.

[`.github/workflows/release-please.yml`](../../.github/workflows/release-please.yml)
runs on every push to `main`. It keeps a release PR open; merging it creates
the `v*` tag and GitHub Release. It authenticates as the repository's
release-please GitHub App so the release PR runs CI and the tag triggers
[`.github/workflows/release.yml`](../../.github/workflows/release.yml), which
attaches a signed APK and an App Store-signed ipa to the release and
uploads the ipa to TestFlight. Its build jobs hold the signing secrets but
only a read token; separate publish jobs, which run no build code, attest
and upload. See [signing.md](signing.md) for the key, the
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

Actions are pinned to full commit SHAs, with the version in a comment, and
the repository's Actions settings reject any other reference. Dependabot
keeps them current. Every `actions/checkout` sets `persist-credentials: false`, so the
token isn't left in `.git/config` for the build to read.

## Gradle dependency verification

[`gradle/verification-metadata.xml`](../../gradle/verification-metadata.xml)
holds the SHA-256 of every plugin and library the build resolves. Gradle
checks each download against it, locally and in CI, and fails the build on
a mismatch or on an artifact the file doesn't list.

After adding or bumping a dependency or plugin, including on a Dependabot
Gradle PR that leaves the file unchanged (its CI fails until someone
does), refresh the file on macOS, so the iOS artifacts are covered
too. Use an empty Gradle home (`-g`): with a warm cache Gradle skips some
parent POMs and BOMs, and CI then fails on them. The second command adds the
Linux build of AAPT2, which CI's ubuntu jobs use and a Mac never downloads:

```sh
home=$(mktemp -d)
./gradlew -g "$home" --write-verification-metadata sha256 \
  check :androidApp:assembleDebug :androidApp:assembleRelease \
  :sharedLogic:linkReleaseFrameworkIosArm64 :sharedLogic:linkDebugFrameworkIosSimulatorArm64 \
  :sharedLogic:pitest
./gradlew -g "$home" --write-verification-metadata sha256 --no-configuration-cache \
  -I gradle/verification-linux.init.gradle.kts resolveLinuxAapt2
rm -rf "$home"
```

The first command downloads everything again and takes about 15 minutes.

Gradle adds the new checksums and keeps the old ones. Review the diff: it
should only add the components you changed and their new dependencies.
Then commit it with the bump. Checksums of versions no longer used can be
dropped by deleting the file and running both commands again.
