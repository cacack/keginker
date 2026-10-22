# Try a development build

## Android

The easiest way is a release: download `keginker-X.Y.Z.apk` from
[Releases](https://github.com/cacack/keginker/releases), open it on the
phone, and allow installs from that source. No GitHub account is needed.
Early releases are marked as pre-releases. To get updates automatically,
add `https://github.com/cacack/keginker` to
[Obtainium](https://github.com/ImranR98/Obtainium), with pre-releases
turned on.

To check that a downloaded APK was built by this repository's CI from its
release tag, use the [GitHub CLI](https://cli.github.com):

```sh
gh attestation verify keginker-X.Y.Z.apk -R cacack/keginker
```

For the latest unreleased change, every push and pull request also builds a
debug APK in CI:

1. Download the APK from the latest successful run on `main`. Either open the
   [Actions tab](https://github.com/cacack/keginker/actions/workflows/ci.yml)
   and download the `keginker-debug-apk` artifact, or use the GitHub CLI:

   ```sh
   gh run download -R cacack/keginker -n keginker-debug-apk
   ```

2. Install it on a phone (`adb install androidApp-debug.apk`, or open the file
   on the device and allow installs from that source).

CI artifacts need a GitHub login and are kept for 14 days.

CI builds are signed with a debug key that's new for every run, so a CI
build doesn't install over a release, another CI build or a local build: the
install fails with `INSTALL_FAILED_UPDATE_INCOMPATIBLE`. So does going from
a release back to a dev build, which Android treats as a downgrade.
Uninstall first in either case. See [signing.md](../operations/signing.md).

NFC writes need a real phone. The Android emulator has no NFC, but debug
builds offer **Simulate a tag** after **Write to tag** to try the flow.

## iOS

Each release is distributed through TestFlight. Ask the maintainer for an
invitation, then install the TestFlight app, accept the invitation and
install KegInker from it. Maintainers add testers as described in
[testflight.md](../operations/testflight.md#adding-testers).

The `keginker-X.Y.Z.ipa` on each release is the build CI uploaded to
TestFlight. It can't be installed directly, but it can be checked against
its release tag:

```sh
gh attestation verify keginker-X.Y.Z.ipa -R cacack/keginker
```

To try an unreleased change, build from source on a Mac with Xcode:

1. Create `iosApp/Configuration/Local.xcconfig` containing
   `TEAM_ID=` and your Apple Developer team ID. The file is gitignored, so
   your team stays out of the repository. Writing tags needs the NFC Tag
   Reading capability, which requires a paid Apple Developer Program
   membership.
2. Open `iosApp/iosApp.xcodeproj`, choose your iPhone, and run.

The Simulator has no NFC; debug builds there write to a simulated tag
instead. In debug builds, **Advanced** also has a **CRC framing** option,
which chooses whether commands to earlier (classic) Waveshare boards carry an
NFC checksum. Which framing those boards need on iPhone is still untested;
Rev2.2 boards don't use it. Debug builds log every NFC exchange to Xcode's console under `nfc`.
