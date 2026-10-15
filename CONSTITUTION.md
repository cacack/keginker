# Constitution

## Mission

Make battery-free NFC e-paper tags pleasant and dependable to use from a phone:
pick an image, see exactly how it will look, tap, and trust that it worked.

## Audience

People who use NFC e-paper tags as small rewritable labels. The first and
defining case is homebrewers labelling keg tap handles, who rewrite a tag
every time a keg changes. Anyone else with these tags should find the app
equally useful.

## Principles

1. **Reliability is the product.** A write either completes and is confirmed,
   or fails clearly and can be retried at once. A faint, half-drawn label is a
   bug.
2. **Native on each platform.** Each platform's own UI toolkit: SwiftUI on
   iOS, Jetpack Compose on Android. No web views, no imitation controls.
3. **Minimal permissions.** NFC and the system photo picker. No network
   access, accounts, analytics or telemetry. Works fully offline.
4. **Open and auditable.** Apache-2.0, buildable from source, free of
   proprietary SDKs, and suitable for F-Droid.
5. **Many displays, one app.** Support as many NFC e-paper displays as
   possible, across vendors, behind one consistent flow.

## Non-goals

- Dynamic content: weather, calendars, feeds or anything else fetched over a
  network.
- Battery-powered or Bluetooth electronic shelf labels.
- A general graphics editor. Light composition (text, crop, rotate) yes; a
  drawing suite no.
- Cloud sync, accounts or sharing services.

## Success criteria

- A full write to a supported panel succeeds on the first tap in the large
  majority of attempts, on both iOS and Android, including Samsung and Pixel
  phones.
- Every failed write tells the user what happened and offers a retry.
- Re-labelling a tap from a saved preset takes no more than a few taps plus
  the write itself.
- The release builds request no permissions beyond NFC and photo access.
