# Constitution

## Mission

Keep every tap handle showing what's pouring. KegInker writes labels to
battery-free NFC e-paper tags on keg tap handles from a phone: pick the beer,
see exactly how the label will look, tap, and trust that it worked. The
app earns trust the same way: its code, permissions and releases are open to
inspection.

## Audience

Homebrewers labelling keg tap handles, who rewrite a tag every time a keg
changes. Taprooms, restaurants and breweries rotating many taps are just as
welcome, and the app should grow with them. Anyone using these tags another
way can still write any image to them.

## Principles

1. **Reliability is the product.** A write either completes and is confirmed,
   or fails clearly and can be retried at once. A faint, half-drawn label is a
   bug.
2. **Trust you can verify, over "trust us."** Apache-2.0, buildable from
   source, free of proprietary SDKs, and suitable for F-Droid. Every release
   is signed and traceable to the commit it was built from, its permissions
   are listed in plain view, and the code gets full security reviews whose
   reports are published.
3. **Native on each platform.** Each platform's own UI toolkit: SwiftUI on
   iOS, Jetpack Compose on Android. No web views, no imitation controls.
4. **Minimal permissions.** NFC and the system photo picker. No network
   access, accounts, analytics or telemetry. Works fully offline.
5. **Displays that fit a tap handle, over display count.** Support the NFC
   e-paper panels that suit tap handles, across vendors, behind one
   consistent flow. Add a panel when tap-handle users need it.

## Non-goals

Where we put our effort instead; other uses keep working as they do today.

- Labels made on the phone. Content comes from the user, not from weather,
  calendars, feeds or anything else fetched over a network.
- Tags powered by the phone's NFC field, rather than battery-powered or
  Bluetooth electronic shelf labels.
- Light composition: text, templates, crop and rotate. Full drawing is left
  to graphics apps.
- Everything stays on the phone, with no cloud sync, accounts or sharing
  services.
- Features designed around tap handles. Writing any image stays available
  for custom labels and every other use.
- Showing what's on tap. Keg inventory, volume and pour tracking are left to
  the tools built for them.

## Success criteria

**C1 — A full write to a supported panel succeeds on the first tap in the
large majority of attempts, on both iOS and Android, including Samsung and
Pixel phones.**
Check: judgement call — measured by hand on real tags and phones, because CI
has no NFC hardware; a tally of attempts during device testing settles it.

**C2 — Every failed write tells the user what happened and offers a retry.**
Check: `./gradlew :sharedLogic:testAndroidHostTest :sharedUI:testAndroidHostTest`
exits 0; the write-controller and write-message tests cover failures naming
the step, retry, unsupported tags and cancel.

**C3 — Re-labelling a tap from a saved preset takes no more than a few taps
plus the write itself.**
Check: judgement call — count the taps from opening the app to the write on
a device; a tap count is a UX walkthrough, not something a test can settle.

**C4 — The release builds request no permissions beyond NFC and photo
access.**
Check: the `Check permissions (C4)` step in
[`release.yml`](.github/workflows/release.yml) passes on each release.

**C5 — Anyone can verify a release APK was built by CI from its tagged
commit.**
Check: `gh attestation verify keginker-X.Y.Z.apk -R cacack/keginker` passes
for the latest release.

**C6 — Every minor release is covered by a full security review, published
in `docs/reviews/security/`.**
Check: judgement call — a person confirms a report exists for the release's
version; whether a review was thorough isn't something a command can settle.

---

*Last refreshed: 2026-10-18*
