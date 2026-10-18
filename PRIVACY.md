# Privacy policy

*Effective 2026-10-18. Changes to this policy are listed in this file's
[history](https://github.com/cacack/keginker/commits/main/PRIVACY.md).*

KegInker collects, stores and sends no personal data. It has no accounts,
no analytics, no advertising, no crash reporting and no third-party SDKs.

## Network

KegInker works fully offline and never connects to the internet. The
Android app doesn't request internet access, so Android itself blocks it
from the network. The iOS app contains no networking code.

## Your images

You choose images through the system photo picker, which gives KegInker
only the image you pick, not your library. The app keeps that image in
memory to build the preview and write it to a tag. It doesn't save or copy
it anywhere.

When you write a tag, the label goes over NFC to the tag you hold against
your phone, and nowhere else.

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
