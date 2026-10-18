# Tag protocols

NFC e-paper tags fall into several protocol families that are not compatible
with each other. tagpaper supports Waveshare classic and Waveshare Rev2.2.

## Waveshare classic

Panels: 2.13", 2.7", 2.9", 2.9" B, 4.2", 7.5", 7.5" HD.

- **Transport:** ISO 14443-3A raw frames (Android `NfcA`; iOS
  `NFCMiFareTag.sendMiFareCommand`). Not ISO-DEP.
- **Detection:** the 7-byte NFC-A UID is `WSDZ10m` in ASCII (Android
  `Tag.id`, iOS `NFCMiFareTag.identifier`). Page 0 holds the UID split by a
  check byte, so match the UID, not page 0.
- **Commands:** `CD <cmd> [data]`. A successful reply is `00 00`.

The write sequence:

| Step | Command |
|---|---|
| Init | `CD 0D` |
| Select panel type | `CD 00 <type>` |
| Power and config | `CD 01`, `CD 02`, `CD 03`, `CD 05`, `CD 06`, with short delays |
| Prepare data | `CD 07 00` |
| Image data | most panels: `CD 08 <len> <bytes>` chunks, then `CD 18` |
| | **2.7":** `CD 18` first, then `CD 19 <len> <bytes>` chunks |
| Refresh | `CD 09` |
| Wait | poll `CD 0A` until the reply is `FF 00` |
| Power off | `CD 04` |

- **Image format:** 1 bit per pixel, most significant bit first, 1 = white.
  Some panels, including the 2.7", are rotated; see
  [Image orientation](#image-orientation-27).
- **Launcher record:** the tag carries an NDEF Android Application Record for
  the vendor app, which is why tapping a tag launches it.

### Panels

| Panel | Resolution | Type byte | Chunk size |
|---|---|---|---|
| 2.7" | 264 × 176 | `0x10` | 121 bytes |

At 1 bpp a 2.7" frame is 5,808 bytes, which is 48 `CD 19` chunks.

### Image orientation (2.7")

The controller's memory is **264 columns × 176 rows**, row-major. Each row is
33 bytes, MSB first, and a 1 bit is white. The 2.7" is viewed in **portrait,
176 × 264**. Memory pixel `(x, y)` shows view pixel `(175 − y, x)`: the view
turned 90° counter-clockwise, which is the vendor codec's 270° rotation. The
view's top-right pixel is memory byte 0, and its top-left is byte 5,775.

The vendor codec also accepts landscape input. It rotates that input the same
way and then reads it with a 264-pixel stride. That is only correct for
portrait input, so landscape images wrap rows. tagpaper always crops and
scales to the portrait view first, and unit tests pin the mapping.

Orientation is set per panel (`PanelView`). Check it against hardware before
adding another panel.

## Waveshare Rev2.2 (ISO-DEP)

Boards labelled "Rev2.2" (seen on the 2.7" NFC e-Paper Driver Board) use a
different chip and protocol from classic boards. Verified on a 2.7" board with
an iPhone.

- **Transport:** ISO 14443-4 (ISO-DEP). Android `IsoDep`; iOS
  `NFCISO7816Tag.sendCommand`. On iOS the tag only appears if
  `D2760000850101` is listed under
  `com.apple.developer.nfc.readersession.iso7816.select-identifiers`; the
  tag accepts that SELECT, though the write never uses it.
- **Detection:** the ATS historical bytes start with `YZRCOS` in ASCII (the
  chip's operating system; `59 5A 52 43 4F 53 03 00` seen). The UID is
  4 bytes and differs per board, so it can't identify these boards.
- **Commands:** CLA `0x74` APDUs that drive the panel controller directly.
  Every reply ends in `90 00`.

| Step | APDU |
|---|---|
| Unlock | `74 B1 00 00 08 00 11 22 33 44 55 66 77` |
| Reset (off, then on; 150 ms after each) | `74 97 00 08 00`, `74 97 01 08 00` |
| Select register `r` | `74 99 00 0D 01 r` |
| Write data | `74 9A 00 0E <len> <bytes>` (up to 250) |
| Busy poll | `74 9B 00 0F 01`, reply `<b> 90 00`; busy while `b` is `01` |

The write sets controller registers (SSD16xx-style): `0x01` gate count,
`0x11` = `01` (X increasing, Y decreasing), `0x44`/`0x45` the RAM window,
`0x3C` border, `0x18` = `80` (internal temperature sensor), `0x4E`/`0x4F`
the RAM cursor. It then selects `0x24` (black/white RAM) and sends the frame
in 250-byte chunks. Refresh is `0x22` = `F7`, then `0x20`. tagpaper waits
6 s and then polls.

### RAM layout (2.7")

The controller RAM is 176 pixels (22 bytes) × **296** rows, 1 bit per pixel,
MSB first, 1 = white. The panel shows only the highest 264 row addresses
(32–295), with the highest at the bottom and RAM column 0 at the right edge.
tagpaper writes all 296 rows from the highest address down: the view turned
180° first, then 32 white rows for the hidden addresses. That's 6,512 bytes
in 27 chunks (`packControllerFrame`).

## Good Display

- **Transport:** ISO 14443-4 (ISO-DEP).
- **Commands:** select the NDEF application (`D2760000850101`), then
  proprietary `F0 xx` APDUs: configuration, 250-byte image blocks and
  refresh. On iOS the AID must be listed in Info.plist under
  `com.apple.developer.nfc.readersession.iso7816.select-identifiers`.

## EZ Sign family

- **Transport:** ISO-DEP, with the same AID.
- **Image data:** LZO1X-1 compressed, sent in 2,000-byte blocks of 250-byte
  fragments. 2- or 4-colour panels.
- **Coverage:** some newer tags from other vendors may use this protocol too.
  That's unverified.

## Sources

- [fossasia/magic-epaper-app](https://github.com/fossasia/magic-epaper-app):
  Apache-2.0 Dart implementations of these families, including the Rev2.2
  (YZRCOS) ISO-DEP sequence from its PR #440.
- [RebornedBrain/nfc_eink](https://github.com/RebornedBrain/nfc_eink): a
  Flipper Zero writer and emulator for Waveshare and Good Display (GPL-3.0;
  used as a reference only).
- [mogenson/flipperzero-waveshare-nfc](https://github.com/mogenson/flipperzero-waveshare-nfc):
  Waveshare panel constants (MIT).
- [niw's EZ Sign protocol notes](https://gist.github.com/niw/3885b22d502bb1e145984d41568f202d)
  and [EPaperNFCSwift](https://github.com/niw/EPaperNFCSwift) (MIT).
