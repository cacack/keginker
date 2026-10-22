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
app and phone versions, what the tag reported about itself, the start of its
ID, its NDEF records (such as the vendor app it names), and the read
commands sent to it with its replies. It's kept, shown and shared the same
way.

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
