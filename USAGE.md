# Usage

KegInker is in early development.

## Supported displays

| Vendor | Panel | Status |
|---|---|---|
| Waveshare | 2.7" NFC e-Paper, Rev2.2 board (black/white) | Works on iPhone; Android untested |
| Waveshare | 2.7" NFC e-Paper, earlier boards (black/white) | Implemented, not yet tested on hardware |

The board revision is printed on the back of the driver board.

More panels are planned; see
[docs/architecture/protocols.md](docs/architecture/protocols.md) for the
families involved.

## Writing a tag

1. Open KegInker. The preview shows exactly what the tag will display; it
   starts with a test pattern whose solid corner marks the top-left.
2. Optionally tap **Choose image**. Photos are centre-cropped to the panel.
   Turn **Dither** off for text and logos; the slider that appears sets which
   greys become black. Raise it if light text in your image breaks up.
3. Tap **Write to tag**, then hold the phone against the tag and keep it
   still until it says **Done**: on Android, the back of the phone; on
   iPhone, the top edge, while the NFC sheet is showing.
4. If the write fails, tap **Retry**, lift the phone away, and hold it to the
   tag again. Phones only notice a tag as it arrives.

Diagnostics live under **Advanced** (top right), including a "Report a
display" entry for unsupported tags (coming soon).

Android debug builds log every command: `adb logcat -s keginker-nfc`.

## Permissions

KegInker asks only for NFC. Choosing an image needs no permission: the
system photo picker hands the app only the image you pick. There is no
internet permission. See [PRIVACY.md](PRIVACY.md).

| Platform | Permission | Why |
|---|---|---|
| Android | NFC (`android.permission.NFC`) | Talk to the tag while you hold the phone against it |
| Android | `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | Added automatically by AndroidX so only KegInker can send broadcasts to its own internal receivers; the app defines it itself, it's never shown to you, and it grants no access to anything |
| iOS | NFC tag reading (`NFCReaderUsageDescription`, reader-session entitlement) | Show the system NFC sheet and talk to the tag |

## Trying a development build

Android APKs are published on
[Releases](https://github.com/cacack/keginker/releases), and CI builds one on
every change. See
[docs/guides/try-a-development-build.md](docs/guides/try-a-development-build.md).
iOS builds will be distributed through TestFlight once available.
