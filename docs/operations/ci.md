# CI

GitHub Actions, defined in
[`.github/workflows/ci.yml`](../../.github/workflows/ci.yml), runs on every
pull request and every push to `main`.

| Job | Runner | What it does |
|---|---|---|
| Shared tests | ubuntu | `:sharedLogic` and `:sharedUI` Android host tests (JVM), including Robolectric Compose tests of the Android screens; fails if an Android README screenshot is stale and uploads `android-screenshot-diffs` |
| Android APK | ubuntu | `:androidApp:testDebugUnitTest`, then `:androidApp:assembleDebug`, signed with the runner's debug key (never the release key); uploads `keginker-debug-apk` (kept 14 days) |
| iOS build | macos | `:sharedLogic:iosSimulatorArm64Test`, then an unsigned simulator build of `iosApp`, then its XCTest target (`iosAppTests`) on any available iPhone simulator, then fails if it declares any permission beyond NFC ([constitution](../../CONSTITUTION.md) C4) |
| iOS screenshots | xcode-27 (preview image) | Checks the iOS README screenshots on Xcode 27.1 and an iPhone 17e simulator on iOS 27.0, where they were recorded; uploads `ios-screenshot-diffs` if one is stale |
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

[`.github/workflows/fuzz.yml`](../../.github/workflows/fuzz.yml) runs the
fuzz targets (see [Fuzzing](#fuzzing)) through ClusterFuzzLite: 5 minutes on
every pull request and 30 minutes weekly.

None of these feeds `CI Success`, so findings never block a merge; they are
triaged as issues.

Actions are pinned to full commit SHAs, with the version in a comment, and
the repository's Actions settings reject any other reference. Dependabot
keeps them current. Every `actions/checkout` sets `persist-credentials: false`, so the
token isn't left in `.git/config` for the build to read. The fuzz workflow
has no checkout step: ClusterFuzzLite's actions check out the code
themselves, with a token that can only read.

## Fuzzing

A tag's replies are the app's main untrusted input. The
[Jazzer](https://github.com/CodeIntelligenceTesting/jazzer) targets in
[`fuzz/`](../../fuzz/src/main/kotlin/io/github/cacack/keginker/fuzz) feed
arbitrary bytes to the code that reads them. Each target checks that this
code never throws:

| Target | What it fuzzes |
|---|---|
| `NdefFuzzer` | An NDEF message from tag memory: parsing it and describing its records |
| `DisplayProbeFuzzer` | The display probe on an ISO-DEP, NFC-A or NFC-V tag whose every reply is fuzzed, then the report written from it |
| `EzSignDeviceInfoFuzzer` | The EZ Sign device information reply |
| `WriteFuzzer` | A Waveshare write, classic or Rev2.2, against fuzzed replies |

`:fuzz` compiles `sharedLogic`'s common code as a plain JVM project, so it
builds without the Android SDK. That code must therefore stay plain Kotlin
with no `expect` declarations and no dependencies beyond coroutines. The
Shared tests job compiles `:fuzz` to keep it so. Run every target locally,
30 seconds each:

```sh
./gradlew :fuzz:fuzz
```

`-Pfuzz.seconds=<n>` changes the time, and `./gradlew :fuzz:fuzzNdef` (or
another target's task) runs one. A crash fails the build and skips the
targets after it; add `--continue` to run them anyway. Jazzer writes the
input that crashed a target to `fuzz/build/fuzz/<Name>/crash-*`, with the
stack trace in the output. Fix the crash with a `sharedLogic` test built from
those bytes, so it stays fixed.

In CI, [`.clusterfuzzlite/`](../../.clusterfuzzlite) builds the same targets
in ClusterFuzzLite's JVM image. Scorecard's Fuzzing check detects them there.
That image brings its own Jazzer, so CI and local runs can use different
Jazzer versions. Dependabot updates the image digest and the version catalog
separately.
A new target needs only a `<Name>Fuzzer` object, in a file of the same name
in the `io.github.cacack.keginker.fuzz` package, with a static
`fuzzerTestOneInput(FuzzedDataProvider)`: the Gradle tasks and the
ClusterFuzzLite build both find targets by that suffix.

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
  :sharedLogic:pitest :fuzz:fuzzLibs :fuzz:fuzzNdef -Pfuzz.seconds=1
./gradlew -g "$home" --write-verification-metadata sha256 --no-configuration-cache \
  -I gradle/verification-linux.init.gradle.kts resolveLinuxAapt2
rm -rf "$home"
```

The first command downloads everything again and takes about 15 minutes.

Gradle adds the new checksums and keeps the old ones. Review the diff: it
should only add the components you changed and their new dependencies.
Then commit it with the bump. Checksums of versions no longer used can be
dropped by deleting the file and running both commands again.
