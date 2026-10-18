# Run the hardware tests

CI has no NFC, so some promises can only be checked by hand on a real phone
and board. This script lists those checks. Run it on a release build (or the
build you're testing), then record the run in the
[hardware test log](../operations/hardware-test-log.md), or send it in with
the **Hardware test run** issue form if you can't edit the log.

## Before you start

- A phone with NFC on, and a supported board (see
  [USAGE.md](../../USAGE.md#supported-displays)). The board revision is
  printed on the back of the driver board.
- The app version: from the release you installed, or the commit a
  development build was built from.
- Paper to tally attempts and copy down any failure message word for word.

An **attempt** is one tap of **Write to tag** followed by holding the phone
to the tag as [USAGE.md](../../USAGE.md#writing-a-tag) describes. It's a
**first-tap success** if it reaches **Done** without a retry.

## Every phone

| # | Scenario | Promise it checks | Expected result |
|---|---|---|---|
| 1 | Write the test pattern 20 times in a row, each from a fresh **Write to tag**. Tally attempts and first-tap successes. | A full write succeeds on the first tap in the large majority of attempts ([C1](../../CONSTITUTION.md#success-criteria)). | Nearly every attempt reaches **Done** first time. Note the message of every failure. |
| 2 | Start a write and lift the phone away while the image is being sent (progress below 90%). Then tap **Retry** and hold the phone to the tag again. | A write either completes or fails clearly, and Retry works (principle 1, C2). | A message saying the write failed "while sending the image" and what to do, then **Retry**. On iPhone the message may instead say the NFC session ended. The retried write reaches **Done**. |
| 3 | Start a write and lift the phone away once the app says **Updating the display**, while the panel is refreshing. Then tap **Retry**. | As scenario 2. | As scenario 2, with the message naming the refresh. The panel may show a partial image until the retry finishes. |
| 4 | After a successful write, compare the tag to the preview. | The preview shows exactly what the tag will display (mission). | The tag matches the preview, with the test pattern's solid corner at the top-left of the panel. |
| 5 | Choose a photo, write it and compare the tag to the preview. | As scenario 4. | The tag matches the preview: same crop, same orientation. |

## Android

| # | Scenario | Promise it checks | Expected result |
|---|---|---|---|
| A1 | Start a write and rotate the phone while it's in progress, keeping it on the tag. | The write survives rotation (the manifest's `configChanges`). | The write carries on and reaches **Done**; progress isn't lost or restarted. |
| A2 | Turn NFC off in Settings and open the app. | Failures say what happened (C2). | "NFC is off. Turn it on in Settings to write tags." Turning NFC back on lets the next write succeed. |
| A3 | Hold two tags together against the phone and start a write. | Failures say what happened and offer a retry (C2). | A clear failure or a write to one tag, never a hang. With one tag, **Retry** reaches **Done**. Record which happened. |

## iPhone

| # | Scenario | Promise it checks | Expected result |
|---|---|---|---|
| I1 | Start a write and tap **Cancel** on the NFC sheet while it's in progress. | A write either completes or fails clearly (principle 1). | The sheet closes, the app shows the write didn't finish and offers to try again; the next write reaches **Done**. |
| I2 | Start a write and hold the phone still through a full write, timing it from the sheet appearing to **Done**. | A full write fits inside the NFC session (C1). | The write reaches **Done** before the sheet times out. Note the time. |
| I3 | Hold two tags together against the iPhone and start a write. | Failures say what happened (C2). | The sheet says "More than one tag found. Hold just one tag to the iPhone." With one tag the write reaches **Done**. |

## Record the run

Add a row to the [log](../operations/hardware-test-log.md) as it describes.
Open an issue for any scenario whose result differed from the expected one.
