# Record a tag write for the tests

The protocol tests replay writes recorded from real tags. A recorded write
checks the writer against what a board actually said, which a hand-written
fake can't. This guide covers recording one and adding it as a test fixture.

## What gets recorded

Record the **test pattern**, the image the app starts with, so the fixture
carries nobody's picture. Keep **Dither** on (the default). The test checks
that the image bytes in the recording equal the app's frame for the test
pattern, so any other image or setting fails it.

## Record

You need a debug build and a tag. See
[try-a-development-build.md](try-a-development-build.md).

1. Start capturing the log:
   - **Android:** `adb logcat -c && adb logcat -v raw -s keginker-nfc`
   - **iPhone:** run the app from Xcode and watch its debug console, filtered
     to the `nfc` category (or Console.app, subsystem
     `io.github.cacack.keginker`).
2. Open the app fresh. Leave the test pattern and Dither as they are.
3. Tap **Write to tag** and hold the phone to the tag until it says **Done**.
4. Copy the log lines of that one successful write. If you retried, keep only
   the last attempt.

Each exchange is logged as two lines: `> ` and the command bytes, then `< `
and the reply bytes, in hex. A line starting `! ` is a failed exchange, so
that attempt isn't a successful write.

## Add the fixture

Write the recording in this format:

```
# board: Waveshare 2.7" Rev2.2
# phone: iPhone 15
# os: iOS 26.0
# app: 1.0 (commit abc1234)
# Test pattern, Dither on.

> 74 b1 00 00 08 00 11 22 33 44 55 66 77
< 90 00
> 74 97 00 08 00
< 90 00
...
```

- `board`, `phone`, `os` and `app` notes are required. For a development
  build, give the commit it was built from as the app version.
- Other `#` lines are comments. Blank lines are ignored.
- Keep only `>` and `<` lines from the log, in order. The test rejects any
  other line, so leave out lines like `Connected` or `Write succeeded`.

Save it as a raw string in a new file in
`sharedLogic/src/commonTest/kotlin/io/github/cacack/keginker/replay/`, named
for the board and phone, for example `Rev22Iphone15.kt`:

```kotlin
package io.github.cacack.keginker.replay

internal val REV22_IPHONE_15 = """
# board: Waveshare 2.7" Rev2.2
...
"""
```

Add it to `REV22_RECORDINGS` in `Recordings.kt`, then run the shared tests
(see [CONTRIBUTING.md](../../CONTRIBUTING.md#building-and-testing)).
`RecordingReplayTest` replays each recording: the writer must finish with
`WriteResult.Success`, every command must match the recorded one, and the
image bytes must equal the test pattern's frame. Add a row to the table below.

## Recordings

| Board | Phone | Status |
|---|---|---|
| 2.7" Rev2.2 | iPhone | Not yet recorded |
| 2.7" classic | any | Not yet recorded |

Known gaps: no write is recorded yet, so `RecordingReplayTest` checks only
the replay itself, against a transcript of the writer made in the test. The
classic board's writer has no replay test; one comes with its first
recording.
