# Testing strategy

KegInker's tests exist to show that the app keeps its promises: the ones in
the [constitution](../../CONSTITUTION.md) (mission, principles, success
criteria) and in [USAGE.md](../../USAGE.md) and the UI text. Each promise
maps to the check that guards it, in the table below. A coverage number
says nothing about whether a promise holds, so there are no coverage
targets.

## Approach

- **Start from the promise.** A test states what a user is told will
  happen and checks it, not the values the code happens to compute today.
- **Seeded properties and simulators over single examples.** Hand-worked
  examples catch change, not wrongness. Prefer a property checked over many
  seeded inputs (`kotlin.random.Random(seed)`, at least 100, a failure names
  its seed) or a simulator written from the spec, such as a panel rebuilt
  from [protocols.md](protocols.md), that the code must agree with.
- **Real recordings over synthetic fakes** where hardware behaviour
  matters: protocol tests replay writes recorded from real tags
  ([record-a-tag-write.md](../guides/record-a-tag-write.md)).
- **A hardware log for what CI can't reach.** CI has no NFC hardware.
  First-tap success (C1), tap counts (C3) and anything a fake can't model
  are checked by hand on real phones and tags, and each run is logged.
- **Check the tests themselves.** Mutation testing
  ([2026-10-18-mutation-testing.md](../reviews/testing/2026-10-18-mutation-testing.md))
  shows whether the tests would fail on real mistakes. It runs on demand.

How to run each suite is in [CONTRIBUTING.md](../../CONTRIBUTING.md); what CI
runs is in [ci.md](../operations/ci.md).

## Promise → check

From the testing audit of 2026-10-18 (#65). Update a row when a change adds
or removes the check that guards it. GitHub shows each issue's state; the
table marks only deferrals.

| Promise | Source | Guarded by | Issues |
|---|---|---|---|
| The preview shows exactly what the tag will display | Mission, USAGE.md | `WaveshareFrameTest` corner pixels; `SimulatedPanelTest` (a panel simulated from [protocols.md](protocols.md) shows exactly the view written to it); `WriteSessionTest.previewAndWrittenLabelNeverDisagree` and the held-image tests | #59; #55; #61 |
| A write completes and is confirmed, or fails clearly and can be retried at once | Principle 1, C2 | Writer and `WriteControllerTest` failure cases, which run for Android and iOS, since both apps write through `WriteController`; `WriteFaultInjectionTest` and `WriteControllerFaultTest` (a fault at every write command fails clearly and the retry works); `WriteFuzzTest` (random replies never pass without a confirmed refresh); `WriteMessagesTest` (an unconfirmed refresh says how to retry without blaming the tag); `WriteBudgetTest` (each write's worst case fits a 15 s budget); `WriteControllerTest` (a tag that answers each command just inside the timeout fails at the 30 s deadline with a report, and the retry works; an iOS session that ends mid-write fails with the exchanges so far); `TagWriterTests` (the iOS screen shows a lost tag's failure and report, then a working retry) | #54; #57; #58 deferred; #46; #98 |
| The protocol code matches real tags | Principle 1, Principle 5 | Synthetic fakes; `RecordingReplayTest` (no recordings yet); transport contract tests (`NfcTransportTest`, `MiFareTransportTests`, `Iso7816TransportTests`) | #56 deferred; #64 |
| First-tap success on iOS and Android | C1 | [Hardware test script](../guides/run-hardware-tests.md) and [log](../operations/hardware-test-log.md), a run per MINOR or MAJOR release | #62 |
| Photos centre-cropped, upright, transparency white; dither and threshold behave as described | USAGE.md, UI text | Seeded properties in `image/` tests; golden photos in `fixtures/photos` checked by `ImageLoadingTest` (Android) and `ImageLoadingTests` (iOS) | #60; #61 |
| A picked image that's too large shows "Couldn't open that image." and the app keeps running; the latest pick is the one shown | USAGE.md, UI text | `ImageLoadingTest` and `ImageLoadingTests` (a 48 MP image loads; a 144 MP one fails from its header; on Android, a file over 64 MB fails while it's read); `WriteViewModelTest` (a later pick wins over a slower earlier one; a failed load shows the message) | #99 |
| Report a display reads the tag and writes nothing to it, unless the user turns on vendor commands; the report shows only the start of the tag's ID, even inside the historical bytes, its originality signature and a display's serial and device ID | USAGE.md, UI text, PRIVACY.md | `DisplayProbeTest` (tags simulated from the NFC Forum Type 2, 4 and 5 specs, the NXP NTAG datasheets and ISO/IEC 15693-3 fail on any command that isn't a read or a listed vendor query; NFC-V system information and block reads up to the 64-block cap; Type 2 reads up to the 40-read cap; NXP GET_VERSION and READ_SIG after the NDEF reads; a tag that throws on GET_VERSION or Get System Information is still read; the vendor pass runs only when opted in, only after the documented reads, not after lost contact on a needed read, and the listed EZ Sign query reaches only ISO-DEP tags and stops at a refused SELECT; the 120-command cap holds across both passes; random replies over 600 seeds draw only bounded reads; a slow tag stops at the 10 s deadline with a note); `DisplayReportTest` (no full UID in fields, memory or replies, including the NFC-V system information and a UID split across memory reads; the signature and the device-information serial and ID are shortened, and device information that doesn't parse is hidden whole; `randomDeviceInformationRepliesNeverShowTheSerialOrId`: over 100 seeds, neither the full serial nor the full ID reaches the report, decoded section included; `noLineShowsMoreOfTheUidThanTheUidLine`: no line shows more of the UID than the UID line, including historical bytes shaped like an EZ Sign tag's; Share sends a file only when the issue link leaves the report out); `DiagnosticReportTest` (`hidesUidBytesInsideTheHistoricalBytes`: a run of UID bytes from anywhere in the UID, in either byte order, longer than the UID line shows, becomes the UID marker; `noRunOfTheUidLongerThanTheUidLineSurvivesInTheHistoricalBytes`: over 100 seeds, no such run is left in the historical bytes); `TagDetailsTest` (the Android OS tag details); `Iso15693TransportTests` (the iOS frame split and error replies); `DisplayProbeTests` (iOS Info.plist lists exactly the probed AIDs) | #24; #98 |
| Nothing is written without the test-write opt-in: test writes start only after the user confirms, write only the probed display, and add answers and timings to the report but never the full UID or image data | USAGE.md, UI text | `TestWriteControllerTest` (`nothingIsWrittenBeforeTheOptIn`, `thePatternsAreGeneratedOnlyOnceTheUserOptsIn`, `onlyTheProbedDisplayIsWritten`, `aProbeWithAUidTooShortToMatchOnOffersNone`, `declinedTestWritesEndWithNoRecords`, cancel and retry keep the records, a cancel while writing is ignored, invalid answers are ignored, a write past the deadline or one that throws is recorded as a failure); `EzSignDeviceInfoTest` (a panel with no pixels or more than the cap isn't decoded; random A0s over 100 seeds never throw); `DisplayTestWriterTests` (iOS: `testNothingIsSentBeforeTheOptIn`, `testTheConfirmationWritesNothingUntilStarted`); `AppTest` (Android: `testWritesWriteNothingUntilConfirmed`; backing out keeps the records so far); `DisplayReportTest` (`theTestWritesSectionRecordsAnswersAndTimings`; `randomTestWritesNeverShowTheUidOrImageData`) | #134 |
| Test patterns land exactly in panel memory, and the refresh is waited out through the blocking refresh, with only that command given a longer timeout | [protocols.md](protocols.md#test-writes-opt-in) | `EzSignTestWriterTest` against `SimulatedEzSignTag`, a tag simulated from alt-core/nfc-eink with a full LZO1X decoder (`everyPatternLandsInPanelMemoryBitForBit`, `randomFramesOnRandomPanelsLandBitForBit`; `aFastTagWaitsOutATwelveSecondRefresh`, `aRefreshLongerThanAnIosSessionIsTimedThroughTheBlockingReply`, `onlyTheRefreshWaitsLongerThanTheUsualTimeout`, `aRefreshThatOutlastsItsTimeoutFailsAtTheRefresh`, `aNeverReadyPanelIsBusyByTheBudgetWhateverEachCommandCosts`, `aTagTooSlowForTheBudgetStillEndsAsBusy`); `EzSignFrameTest`; `TestPatternsTest`; `WriteBudgetTest.ezSignTestWritePollsUntilTheBudgetRunsOutInRealTime`; `TestWriteControllerTest.aFamilyThatHoldsACommandOpenGetsItsOwnDeadline` | #134, #138 |
| A report shows exactly what it holds, at a size it can be shared at, and says a GitHub issue is public | PRIVACY.md, UI text | `NdefTest` (tag text over 300 seeds never keeps a control, format or separator character); `DiagnosticReportTest` (an oversized reply is cut); `DisplayReportTest` (both issue-link captions say issues are public) | #100 |
| The tests would catch real mistakes | — | On-demand PIT run (`./gradlew :sharedLogic:pitest`) | #63 |
| Re-labelling from a preset takes a few taps | C3 | — | Deferred: a logged walkthrough once #29 lands (#62) |

Permissions, attestation and security reviews (C4–C6) have their own checks
and aren't part of this map.

### Deferred

- **#56 — real-tag recordings.** The replay harness is merged (#73), but a
  recording needs a real Rev2.2 or classic board and a phone. None is
  committed yet, so the replay checks run over an empty list.
- **#58 — write time budget.** The budget and `WriteBudgetTest` are merged
  (#71). The 50 ms per-command allowance is an estimate; confirming that a
  refresh that never confirms fails before iOS ends the session needs a real
  iPhone and Rev2.2 board.
- **C3 — tap count.** Presets don't exist until #29 lands; the walkthrough
  is logged in the [hardware test log](../operations/hardware-test-log.md)
  after that.

## What a change that touches a promise adds

A change that alters behaviour a promise in the table covers must:

1. Add or update a check that would fail if the promise broke: a property,
   a simulator comparison or a recording, not only an example worked out
   from the new code.
2. Update the table row if the guard changes.
3. If CI can't check it, say what to run by hand and log the run.
