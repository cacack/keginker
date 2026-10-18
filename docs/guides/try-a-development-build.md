# Try a development build

## Android

The easiest way is a release: download `tagpaper-X.Y.Z.apk` from
[Releases](https://github.com/cacack/tagpaper/releases), open it on the
phone, and allow installs from that source. No GitHub account is needed.
Early releases are marked as pre-releases. To get updates automatically,
add `https://github.com/cacack/tagpaper` to
[Obtainium](https://github.com/ImranR98/Obtainium), with pre-releases
turned on.

For the latest unreleased change, every push and pull request also builds a
debug APK in CI:

1. Download the APK from the latest successful run on `main`. Either open the
   [Actions tab](https://github.com/cacack/tagpaper/actions/workflows/ci.yml)
   and download the `tagpaper-debug-apk` artifact, or use the GitHub CLI:

   ```sh
   gh run download -R cacack/tagpaper -n tagpaper-debug-apk
   ```

2. Install it on a phone (`adb install androidApp-debug.apk`, or open the file
   on the device and allow installs from that source).

CI artifacts need a GitHub login and are kept for 14 days.

Releases, CI builds on `main` and maintainers' local builds share one key, so
they install over each other. Builds signed with another key, such as your
own local build or any pull request's artifact, fail to install with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. So does going from a release back to a
dev build, which Android treats as a downgrade. Uninstall first in either
case. See [signing.md](../operations/signing.md).

NFC writes need a real phone. The Android emulator has no NFC, but debug
builds offer **Simulate a tag** after **Write to tag** to try the flow.

## iOS

iOS builds will be distributed through TestFlight. Until then, build from
source on a Mac with Xcode:

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
