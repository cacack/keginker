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
   Images up to 120 megapixels open (on Android, files up to 64 MB).
   Turn **Dither** off for text and logos; the slider that appears sets which
   greys become black. Raise it if light text in your image breaks up.
3. Tap **Write to tag**, then hold the phone against the tag and keep it
   still until it says **Done**: on Android, the back of the phone; on
   iPhone, the top edge, while the NFC sheet is showing.
4. If the write fails, tap **Retry**, lift the phone away, and hold it to the
   tag again. Phones only notice a tag as it arrives.
5. If it keeps failing, or the tag isn't recognised, tap **Share details**
   next to **Retry**. It shows a plain-text diagnostics report: the app and
   phone versions, what the tag reported about itself, which step failed,
   whether the image was the test pattern, and the commands exchanged with
   the tag and its replies. It leaves out the label image and shows only the
   start of the tag's ID. iPhones don't reveal some tag details (ATQA, SAK);
   the report marks those unavailable. Read it, then tap **Share** to send it
   through any app, or **Copy**. Post it in a
   [GitHub issue](https://github.com/cacack/keginker/issues), or send it
   straight to whoever asked you to test.
6. If a write finishes but the tag doesn't look right, turn on **Offer a
   report after every write** under **Advanced** and write again, ideally
   the test pattern. **Done** then has **Share details** too.

Diagnostics live under **Advanced** (top right).

Android debug builds log every command: `adb logcat -s keginker-nfc`.

## Report an unsupported display

If your NFC e-paper display isn't in the table above, a report of what it
says about itself helps add it.

1. Open **Advanced** and tap **Report a display**. On iPhone, tap **Read a
   display** too.
2. Optionally turn on **Also try vendor commands**. It's off by default.
   After the standard reads, KegInker also sends the info queries that
   vendors' own apps use, which often name the panel's size and colours.
   No standard defines these as reads, so they could change what the
   display shows. Leave it off for a display you want to keep as it is.
3. Hold the phone against the display and keep it still. With the switch
   off, KegInker sends only standard read commands and writes nothing, so
   the display keeps what it shows.
4. The report appears in full: the app and phone versions, what the tag and
   the phone's OS reported about it (only the start of its ID), which
   applications it accepts, its NDEF records (these often name the vendor's
   app), its memory and version where it offers them, and every command
   sent with the reply.
5. Tap **Open a GitHub issue** to open the **New display report** form in
   your browser with the report filled in. If it's too long to fill in, it's
   copied instead, ready to paste. Add the brand and model printed on the
   display or box, a photo, and the vendor app's name. **Share** and **Copy**
   work too, if you'd rather send it another way; **Share** sends a long
   report as a `.txt` file.

Android phones detect NFC-A, NFC-B, NFC-F and NFC-V (ISO 15693) tags.
iPhones detect ISO 14443 and ISO 15693 tags, and ISO-DEP tags that accept
an application KegInker lists. Android reaches the widest range, so it's the
best phone to try if an iPhone doesn't find the display.

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
every change. iOS releases are distributed through TestFlight; ask the
maintainer for an invitation. See
[docs/guides/try-a-development-build.md](docs/guides/try-a-development-build.md).
