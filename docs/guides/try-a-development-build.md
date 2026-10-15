# Try a development build

## Android

Every push and pull request builds a debug APK in CI.

1. Download the APK from the latest successful run on `main`. Either open the
   [Actions tab](https://github.com/cacack/tagpaper/actions/workflows/ci.yml)
   and download the `tagpaper-debug-apk` artifact, or use the GitHub CLI:

   ```sh
   gh run download -R cacack/tagpaper -n tagpaper-debug-apk
   ```

2. Install it on a phone (`adb install androidApp-debug.apk`, or open the file
   on the device and allow installs from that source).

Debug builds from different machines are signed with different keys. Android
won't install one over another; it fails with
`INSTALL_FAILED_UPDATE_INCOMPATIBLE`. Uninstall the existing app first.

NFC writes need a real phone. The Android emulator has no NFC, but debug
builds offer **Simulate a tag** after **Write to tag** to try the flow.

## iOS

iOS builds will be distributed through TestFlight. Until then, build from
source on a Mac with Xcode:

1. Set `TEAM_ID` in `iosApp/Configuration/Config.xcconfig` to your Apple
   Developer team. Writing tags needs the NFC Tag Reading capability, which
   requires a paid Apple Developer Program membership.
2. Open `iosApp/iosApp.xcodeproj`, choose your iPhone, and run.

The Simulator has no NFC; debug builds there write to a simulated tag
instead. Debug builds also show a **CRC** switch, which chooses whether
commands carry an NFC checksum; which framing Waveshare tags need on iPhone
is still being tested.
