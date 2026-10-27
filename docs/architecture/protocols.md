# Tag protocols

NFC e-paper tags fall into several protocol families that are not compatible
with each other. KegInker supports Waveshare classic and Waveshare Rev2.2.

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
| Wait | poll `CD 0A` every 25 ms until the reply is `FF 00`, within the [write time budget](#write-time-budget) |
| Power off | `CD 04` |

- **Image format:** 1 bit per pixel, most significant bit first, 1 = white.
  Some panels, including the 2.7", are turned; see
  [Image orientation](#image-orientation-27).
- **Launcher record:** the tag carries an NDEF Android Application Record for
  the vendor app, which is why tapping a tag launches it.

### Panels

| Panel | Resolution | Type byte | Chunk size |
|---|---|---|---|
| 2.7" | 176 × 264 | `0x10` | 121 bytes |

At 1 bpp a 2.7" frame is 5,808 bytes, which is 48 `CD 19` chunks.

### Image orientation (2.7")

The memory is **176 columns × 264 rows**, row-major, the same shape as the
portrait view. Each row is 22 bytes, MSB first, and a 1 bit is white. Memory
holds the view **turned 180°**: memory pixel `(x, y)` shows view pixel
`(175 − x, 263 − y)`. The view's bottom-right pixel is memory byte 0, and its
top-left is the last bit of byte 5,807. This is inferred from a classic 2.7"
board, which showed a frame of 264-pixel rows as two sideways copies, and
awaits a correct write to confirm it. It matches the Rev2.2 board's visible
rows.

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
in 250-byte chunks. Refresh is `0x22` = `F7`, then `0x20`. KegInker waits
6 s and then polls every 200 ms, as many times as the
[write time budget](#write-time-budget) allows.

### RAM layout (2.7")

The controller RAM is 176 pixels (22 bytes) × **296** rows, 1 bit per pixel,
MSB first, 1 = white. The panel shows only the highest 264 row addresses
(32–295), with the highest at the bottom and RAM column 0 at the right edge.
KegInker writes all 296 rows from the highest address down: the view turned
180° first, then 32 white rows for the hidden addresses. That's 6,512 bytes
in 27 chunks (`packControllerFrame`).

## Write time budget

Every write, on either board, must finish or fail on its own within 15 s of
scheduled time (`WRITE_BUDGET_MS` in `TagExchange.kt`).

| Platform | Limit | Source |
|---|---|---|
| iOS | A tag connection ends roughly 20 s after it starts, and Core NFC has no per-command timeout. | Apple doesn't publish the figure; 20 s is the commonly observed limit ([overview.md](overview.md#constraints-that-shape-the-design)). |
| Android | No session limit. Each command may also take up to 3 s (`TRANSCEIVE_TIMEOUT_MS`) before it fails. | `NfcA`/`IsoDep` timeouts set by `NfcTransport`. |

The budget is the iOS limit less 5 s kept back for finding the tag before the
write and reporting the result after it. Android uses the same budget, so a
write behaves the same on both phones.

A write's scheduled time is its pauses plus 50 ms per command
(`COMMAND_ALLOWANCE_MS`), an estimate of one exchange. Each writer gives the
refresh poll what the budget leaves after its other pauses and commands
(`busyPollLimit`). For the 2.7":

| Board | Fixed pauses | Other commands | Poll | Polls |
|---|---|---|---|---|
| Classic | 2.7 s | 59 | 25 ms | 124 |
| Rev2.2 | 6.4 s, including the 6 s refresh wait | 50 | 200 ms | 24 |

So a refresh that never confirms ends in the writer's "not confirmed" failure
before iOS ends the session. `WriteBudgetTest` totals each writer's worst case
and fails if it exceeds the budget, or leaves room for another poll. A
recorded real write would show how close typical commands are to the
allowance.

EZ Sign [test writes](#test-writes-opt-in) work differently. A 4-colour EZ
Sign refresh takes more than 20 s, longer than an iOS session (#138), so they
send the blocking refresh, which the tag doesn't answer until the panel is
done. That keeps the NFC field up for the whole refresh. The refresh alone
may take up to 45 s (`BLOCKING_REFRESH_TIMEOUT_MS`, its Android timeout), so
a test write's deadline is that plus `WRITE_DEADLINE_MS`
(`TestWriteFamily.deadlineMs`). The tag's reply ends the write; they don't
poll. On a 2.9" 4-colour panel the reply came after about 24 s, and the
iPhone session stayed open. `EzSignTestWriterTest` checks that the refresh
time is how long the tag held its reply, that only the refresh gets the
longer timeout, and that a refresh past it fails at the refresh step.

Scheduled time assumes each command is quick, but on Android a tag can take
up to 3 s over every command. So `WriteController` also stops any write
still running after 30 s of real time (`WRITE_DEADLINE_MS`, twice the
budget) and shows it as a failed write with a report and a retry. A write
that uses the whole budget still has another 15 s for slow commands. On iOS
the session limit comes first.

## Display probe

**Report a display** reads an unknown tag without changing it. By default it
sends only reads that a standard or datasheet defines, built in
`ProbeCommands.kt`. A second, opt-in pass sends vendor queries after them.
Only the separate, opt-in [test writes](#test-writes-opt-in) change what the
display shows.
`DisplayProbeTest` checks over random replies that nothing else goes out, and
that vendor queries go out only when the user turns them on.

| Tag | Commands | Defined by |
|---|---|---|
| ISO-DEP | SELECT each listed AID (`00 A4 04 00 …`; only `D2760000850101`, the NDEF application, which iOS needs listed in Info.plist). If the NDEF application answers: SELECT the capability container `E103` (`00 A4 00 0C`), READ BINARY its 15 bytes (`00 B0`), then SELECT the NDEF file it names and READ BINARY its length and message. | ISO/IEC 7816-4; NFC Forum Type 4 Tag |
| NFC-A, not ISO-DEP | Type 2 READ (`30 <page>`) from page 3, the capability container, 16 bytes at a time, until the NDEF message TLV is complete. Pages 0–2 hold the full UID, which reports shorten, so they're never read. | NFC Forum Type 2 Tag |
| NFC-A identity | After the Type 2 reads: GET_VERSION (`60`), the 8-byte vendor, product and storage size, and READ_SIG (`3C 00`), the 32-byte originality signature, which reports shorten as they do the UID. An error reply, or none (Android throws on a NAK), is recorded and the probe moves on. | NXP NTAG213/215/216 and MIFARE Ultralight EV1 datasheets |
| NFC-V | Get System Information (`02 2B`), then Read Single Block (`02 20 <block>`) from block 0 until the declared count (64 if the tag doesn't answer Get System Information) or the first error. If the memory starts with a capability container, the NDEF message TLV in it. Flags `02` mean not addressed, so commands never carry the UID. | ISO/IEC 15693-3; NFC Forum Type 5 Tag |

Reads stop at 1,024 NDEF bytes, 40 Type 2 reads or 64 NFC-V blocks. The whole
probe, both passes together, stops at 120 commands (`MAX_PROBE_EXCHANGES`)
or after 10 s (`PROBE_DEADLINE_MS`), with a note in its report. A probe that
reaches the command limit, or loses contact on a read it needs, skips the
vendor pass. The report shows memory read by Type 2 READ and Read Single
Block once, under Memory, where a UID split across replies is still hidden;
those exchanges show only the status and a count of data bytes.

### Vendor queries (opt-in)

The **Also try vendor commands** switch, off by default, adds a pass that
sends `VENDOR_QUERIES` for the tag's family after every documented read. Each
query comes from a vendor's app or an open-source writer. No standard defines
it as a read, so its effect on a given tag is unverified. On ISO-DEP, a
command the tag refuses (not `90 00`) ends its query.

| Query | Family | Bytes | Expected reply | Source |
|---|---|---|---|---|
| EZ Sign device information | ISO-DEP | SELECT `D2760000850101`, then `00 D1 00 00 00` | TLVs, then `90 00`: A0 panel geometry and colour mode, A1 panel flags, C0 a 10-character serial, C1 a 4-byte device ID. Reports shorten C0 and C1 as they do the UID. | [niw/EPaperNFCSwift](https://github.com/niw/EPaperNFCSwift/blob/ee0440e270ee7f4637a20e4f6e0ee4b58695b5df/Sources/EPaperNFCSwift/Command.swift#L117-L121), [alt-core/nfc-eink](https://github.com/alt-core/nfc-eink/blob/682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/protocol.py#L76-L82) |

The report decodes an EZ Sign device-information reply per alt-core/nfc-eink
`parse_device_info` into a section giving the panel's resolution, colours,
rows per block and any other TLVs raw (never C0 or C1), and names the
protocol `EZ Sign (ISO-DEP), no label writer yet`; a reply that has data but
doesn't decode gets a note saying why. A bare refusal (a status word alone),
which other Type 4 tags give, gets no note.

INS `D1` is WRITE BINARY in ISO/IEC 7816-4; only vendor behaviour makes this
query a read. Waveshare's `F0 D8 … 0E`, found in magic-epaper-app, is a
panel-type configuration write (alt-core/nfc-eink `build_panel_type_apdu`),
so the probe never sends it, in either pass.

### Test writes (opt-in)

After a probe whose report names a family KegInker can test-write
(`testWriteFamilyFor`; so far an EZ Sign tag whose device information
decoded), the report offers **Test writes**. `TestWriteController` writes
nothing until the user confirms on a second screen, and then writes only the
probed tag, matched by UID. Each pattern is one write, then a few questions;
the answers, the refresh time and every exchange (image bytes counted, never
kept) go into the report's "Test writes" section. A failed write can be
retried. Before the retry, the user can say whether the phone moved on the display
(`POSITION_CHANGED_QUESTION`). The next attempt written shows the answer as
`Position changed`, or `not answered` if the user skipped the question. This matters
because a different position can make a write succeed (#138, #143).

A report read with vendor queries off, of an ISO-DEP tag that accepted the
NDEF application (`suggestsVendorQueries`), instead suggests reading again
with them on: that's the application the device information query selects.

An EZ Sign test write (`EzSignTestWriter`) sends:

| Step | Bytes | Expected reply |
|---|---|---|
| SELECT the NDEF application | `00 A4 04 00 07 D2760000850101` | `90 00` |
| Authenticate | `00 20 00 01 04 20 09 12 10` | `90 00` |
| Image fragment | `F0 D3 00 <last> <Lc> <block> <fragment> <data>`; `<last>` is `01` on a block's last fragment, else `00` | `90 00` |
| Refresh, blocking | `F0 D4 85 00 00` | `90 00`, only once the panel has refreshed |

P2 `80` asks the tag to answer the refresh at once and leave the caller to
poll. That works from a USB reader (alt-core) but not from an iPhone: Core
NFC ends the session about 20 s in, the field drops mid-refresh, and the
panel is left half drawn. P2 `00` holds the reply until the refresh is done,
which niw/EPaperNFCSwift found keeps the session open
([`SendImageOperation.swift`](https://github.com/niw/EPaperNFCSwift/blob/ee0440e270ee7f4637a20e4f6e0ee4b58695b5df/Sources/EPaperNFCSwift/SendImageOperation.swift#L56-L60)).
It does on an iPhone 15 Pro (iOS 27), where a 2.9" 4-colour refresh held the
reply for about 24 s and a 4.2" one for about 22 s.

The 4.2" panel draws more power while it refreshes. With the phone in one
position, its refresh lost contact after 3–13 s, a different time on each
try, which points to the field rather than a time limit, and left the panel
faint and half drawn. With the display turned 90° under the phone, both
refreshes finished. So a contact lost during a refresh tells the user to
turn the phone, not just to hold it still (`REPOSITION_ADVICE`). One write
straight after an interrupted refresh was refused at authentication with
`65 02`; the next one authenticated.

alt-core polls `F0 DE 00 00 01` after the non-blocking refresh (`01 90 00`
busy, `00 90 00` done). After a blocking refresh that had drawn the panel,
that poll still answered `01 90 00`, so a test write doesn't poll (#138).

The frame is packed at the panel's bits per pixel in its native layout
(`packFrame`): a row's first byte holds its last pixels, lowest bits first.
It's cut into 2,000-byte blocks, each sent as an LZO1X stream in fragments of
at most 250 bytes. The stream is one literal run and the end marker
(`lzoLiteralStream`), not LZO1X-1 compression. Any conforming LZO1X
decompressor turns it back into the block, so the panel gets the same pixels;
the only cost is more fragments, about 138 commands for a 400 × 300
4-colour panel. A test write needs no smaller stream.

The patterns (`TestPatterns.kt`) are generated from the decoded resolution
and colours, in native coordinates, so the write assumes nothing about how
the panel is turned:

- **Corner and line:** white, with a black square at native (0, 0) a quarter
  of the short side across, and a black line along native rows 0–3 to the
  middle of the row. The first question asks which way the user holds the
  display, long edge across or up and down, so their "top left" can be
  placed. Where the square lands then shows how native (0, 0) is turned; the
  line's edge shows which way native rows run.
- **Colour bands** (4-colour panels only): four equal bands along native
  rows, band k in colour index k, from native row 0. The answers map each
  index to a colour.

A fixed image would fit one panel size and would need a known orientation to
be read; generated patterns fit any decoded panel and turn the user's answers
into its orientation and colour order. The orientation found is how the
panel's memory maps onto the glass, a fixed fact about the hardware; a label
writer can rotate a label into it whichever way the display is mounted.

The refresh time in the report is how long the tag held its reply to the
blocking refresh. A refresh that outlasts its timeout, or a session that
ends during it, fails at the refresh step, and the report's exchange line
shows how long it ran. A refused or unexpected reply is a failure that names
its step, with a retry.
See [Write time budget](#write-time-budget).

Sources: alt-core/nfc-eink
[protocol.py](https://github.com/alt-core/nfc-eink/blob/682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/protocol.py),
[card.py](https://github.com/alt-core/nfc-eink/blob/682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/card.py)
and
[image.py](https://github.com/alt-core/nfc-eink/blob/682519f87aa6e422930d0f7a178eaa2d0d9640da/src/nfc_eink/image.py)
for the commands, packing and fragments;
[niw/EPaperNFCSwift](https://github.com/niw/EPaperNFCSwift/blob/ee0440e270ee7f4637a20e4f6e0ee4b58695b5df/Sources/EPaperNFCSwift/Command.swift)
and [niw's protocol notes](https://gist.github.com/niw/3885b22d502bb1e145984d41568f202d)
for the same sequence.

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
- **In KegInker:** Report a display decodes its device information and can
  write [test patterns](#test-writes-opt-in). There's no label writer yet
  (#135).

## Sources

- [fossasia/magic-epaper-app](https://github.com/fossasia/magic-epaper-app):
  Apache-2.0 Dart implementations of these families, including the Rev2.2
  (YZRCOS) ISO-DEP sequence from its PR #440.
- [RebornedBrain/nfc_eink](https://github.com/RebornedBrain/nfc_eink): a
  Flipper Zero writer and emulator for Waveshare and Good Display (GPL-3.0;
  used as a reference only).
- [mogenson/flipperzero-waveshare-nfc](https://github.com/mogenson/flipperzero-waveshare-nfc):
  Waveshare panel constants (MIT).
- [alt-core/nfc-eink](https://github.com/alt-core/nfc-eink): a Python EZ
  Sign writer, the source of the device-information TLV layout and of the
  `F0 D8` panel-type finding (MIT).
- [niw's EZ Sign protocol notes](https://gist.github.com/niw/3885b22d502bb1e145984d41568f202d)
  and [EPaperNFCSwift](https://github.com/niw/EPaperNFCSwift) (MIT).
