# Usage

tagpaper is in early development. Android and iOS builds can write an image
to a Waveshare 2.7" tag; this hasn't yet been confirmed on hardware.

## Supported displays

| Vendor | Panel | Status |
|---|---|---|
| Waveshare | 2.7" NFC-Powered e-Paper (black/white) | First target |

More panels are planned; see
[docs/architecture/protocols.md](docs/architecture/protocols.md) for the
families involved.

## Writing a tag

1. Open tagpaper. The preview shows exactly what the tag will display; it
   starts with a test pattern whose solid corner marks the top-left.
2. Optionally tap **Choose image**. Photos are centre-cropped to the panel.
   Turn **Dither** off for text and logos.
3. Tap **Write to tag**, then hold the phone against the tag and keep it
   still until it says **Done**: on Android, the back of the phone; on
   iPhone, the top edge, while the NFC sheet is showing.
4. If the write fails, tap **Retry**, lift the phone away, and hold it to the
   tag again. Phones only notice a tag as it arrives.

Android debug builds log every command: `adb logcat -s tagpaper-nfc`.

## Trying a development build

Android development builds are produced by CI on every change. See
[docs/guides/try-a-development-build.md](docs/guides/try-a-development-build.md).
iOS builds will be distributed through TestFlight once available.
