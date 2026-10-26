# Privacy policy

*Effective 2026-10-19. Changes to this policy are listed in this file's
[history](https://github.com/cacack/keginker/commits/main/PRIVACY.md).*

KegInker collects, stores and sends no personal data. It has no accounts,
no analytics, no advertising, no crash reporting and no third-party SDKs.

## Network

KegInker works fully offline and never connects to the internet. The
Android app doesn't request internet access, so Android itself blocks it
from the network. The iOS app contains no networking code.

**Open a GitHub issue**, on a display report, opens GitHub's **New display
report** form in your browser. When the report fits in the link, the form
opens with it filled in. When it's too long, the button copies the report to
your clipboard instead, so you can paste it into the form. Your browser sends
what's in the link to GitHub, and only when you tap it. KegInker itself sends
nothing. GitHub issues are public: once you submit one, anyone can read it.

## Your images

You choose images through the system photo picker, which gives KegInker
only the image you pick, not your library. The app keeps that image in
memory to build the preview and write it to a tag. It doesn't save or copy
it anywhere.

When you write a tag, the label goes over NFC to the tag you hold against
your phone, and nowhere else.

## Diagnostics reports

When a write fails or a tag isn't recognised, **Share details**
builds a plain-text report of what happened: the app and phone versions,
what the tag reported about itself, which step failed, and the commands
exchanged with the tag, with the tag's replies as it sent them. With
**Offer a report after every write** turned on under **Advanced**, **Share
details** also appears after a successful write. A report contains no image
data, shows only the start of the tag's ID, and shows only the first 256
bytes of a very long reply. The app keeps the report in memory, shows it to
you in full, and doesn't save it. It leaves your phone only when you share
it, or copy it and paste it somewhere; KegInker never sends it. Copying puts
it on your clipboard, where other apps, and your other devices if you've set
up clipboard sharing, can read it.

**Report a display**, under **Advanced**, builds the same kind of report
from a display you hold to your phone, reading it without writing to it: the
app and phone versions, what the tag and the phone's OS reported about it,
the start of its ID and of its originality signature, its NDEF records (such
as the vendor app it names), and the read commands sent to it with its
replies. With **Also try vendor commands** turned on, it also holds the
vendor queries and their replies, which show only the start of a display's
serial number and device ID. If you choose **Test writes** and start them,
it also holds your answers about each test pattern, how long each refresh
took, and the commands sent, with no image data. It's kept, shown and shared
the same way.
**Share** sends a long report as a `.txt` file, written to the app's
temporary storage only when you share. There is only ever one such file:
each share replaces it, and otherwise it stays in temporary storage until
the system clears it. KegInker still sends nothing itself.

## What stays on your phone

KegInker stores only its own settings on the device, such as the iOS CRC
framing option under **Advanced**. When saved labels and presets are added,
they will be stored on the device as well, and nowhere else. Uninstalling
the app removes all of it.

Your phone's own backups (Google backup on Android, iCloud or computer
backups on iOS) may include this data as part of backing up the device.
Your phone's settings control that, not KegInker.

## Permissions

KegInker asks only for NFC. [USAGE.md](USAGE.md#permissions) lists each
permission per platform and why it's needed. CI checks every Android release
and every iOS build so neither can request more.

## Questions

Open an [issue](https://github.com/cacack/keginker/issues). For a security
problem, follow [SECURITY.md](SECURITY.md) instead.
