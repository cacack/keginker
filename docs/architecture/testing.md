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
or removes the check that guards it.

| Promise | Source | Guarded by | Issues |
|---|---|---|---|
| The preview shows exactly what the tag will display | Mission, USAGE.md | `WaveshareFrameTest` corner pixels; `WriteSessionTest.previewAndWrittenLabelNeverDisagree` and the held-image tests | #59 closed; #55 in review (#69); #61 closed |
| A write completes and is confirmed, or fails clearly and can be retried at once | Principle 1, C2 | Writer and `WriteControllerTest` failure cases; `WriteMessagesTest`; `WriteBudgetTest` (each write's worst case fits a 15 s budget) | #54 in review (#66); #57 in review (#70); #58 deferred; #46 deferred |
| The protocol code matches real tags | Principle 5 | Synthetic fakes; `RecordingReplayTest` (no recordings yet); transport contract tests (`NfcTransportTest`, `MiFareTransportTests`, `Iso7816TransportTests`) | #56 deferred; #64 closed |
| First-tap success on iOS and Android | C1 | Unrecorded device testing | #62 in review (#76) |
| Photos centre-cropped, upright, transparency white; dither and threshold behave as described | USAGE.md, UI text | Seeded properties in `image/` tests; golden photos in `fixtures/photos` checked by `ImageLoadingTest` (Android) and `ImageLoadingTests` (iOS) | #60 closed; #61 closed |
| The tests would catch real mistakes | — | On-demand PIT run (`./gradlew :sharedLogic:pitest`) | #63 closed |
| Re-labelling from a preset takes a few taps | C3 | — | Deferred: a logged walkthrough once #29 lands (#62) |

Permissions, attestation and security reviews (C4–C6) have their own checks
and aren't part of this map.

Issues marked "in review" close when their pull request merges.

### Deferred

- **#46 — iOS failure and retry behaviour.** iOS mirrors Android's write
  controller in Swift, untested. Sharing or testing it is its own feature
  work, outside this audit; until then C2 on iOS is checked by hand.
- **#56 — real-tag recordings.** The replay harness is merged (#73), but a
  recording needs a real Rev2.2 or classic board and a phone. None is
  committed yet, so the replay checks run over an empty list.
- **#58 — write time budget.** The budget and `WriteBudgetTest` are merged
  (#71). The 50 ms per-command allowance is an estimate; confirming that a
  refresh that never confirms fails before iOS ends the session needs a real
  iPhone and Rev2.2 board.
- **C3 — tap count.** Presets don't exist until #29 lands; the walkthrough
  is logged through the hardware test log (#62) after that.

## What a change that touches a promise adds

A change that alters behaviour a promise in the table covers must:

1. Add or update a check that would fail if the promise broke: a property,
   a simulator comparison or a recording, not only an example worked out
   from the new code.
2. Update the table row if the guard changes.
3. If CI can't check it, say what to run by hand and log the run.
