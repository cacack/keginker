# Tag protocols

NFC e-paper tags fall into three protocol families that are not compatible
with each other. tagpaper's first target is Waveshare classic.

## Waveshare classic

Panels: 2.13", 2.7", 2.9", 2.9" B, 4.2", 7.5", 7.5" HD.

- **Transport:** ISO 14443-3A raw frames (Android `NfcA`; iOS
  `NFCMiFareTag.sendMiFareCommand`). Not ISO-DEP.
- **Detection:** reading page 0 returns `WSDZ10m`.
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
  Some panels are rotated 270°.
- **Launcher record:** the tag carries an NDEF Android Application Record for
  the vendor app, which is why tapping a tag launches it.

| Panel | Resolution | Type byte | Chunk size |
|---|---|---|---|
| 2.7" | 264 × 176 | `0x10` | 121 bytes |

At 1 bpp a 2.7" frame is 5,808 bytes, which is 48 `CD 19` chunks.

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
  Apache-2.0 Dart implementations of all three families.
- [RebornedBrain/nfc_eink](https://github.com/RebornedBrain/nfc_eink): a
  Flipper Zero writer and emulator for Waveshare and Good Display (GPL-3.0;
  used as a reference only).
- [mogenson/flipperzero-waveshare-nfc](https://github.com/mogenson/flipperzero-waveshare-nfc):
  Waveshare panel constants (MIT).
- [niw's EZ Sign protocol notes](https://gist.github.com/niw/3885b22d502bb1e145984d41568f202d)
  and [EPaperNFCSwift](https://github.com/niw/EPaperNFCSwift) (MIT).
