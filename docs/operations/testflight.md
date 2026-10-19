# TestFlight

iOS releases reach testers through TestFlight. Each release tag runs
[`release.yml`](../../.github/workflows/release.yml), which archives and signs
the app, uploads it to App Store Connect and attaches the ipa to the GitHub
Release. See [signing.md](signing.md#cutting-a-release) for how a release is
cut.

Signing is cloud-managed: `xcodebuild` uses an App Store Connect API key to
fetch the distribution certificate and profile, so no certificate or profile
is stored in the repository or its secrets.

## Setting up App Store Connect (once)

These steps need a paid Apple Developer Program membership.

1. **Agreements.** The Account Holder accepts the latest agreements in App
   Store Connect → Business. Uploads fail until they're accepted, including
   after Apple publishes new ones.
2. **App ID.** In Certificates, Identifiers & Profiles, register the
   explicit App ID `io.github.cacack.keginker` with the **NFC Tag Reading**
   capability. Xcode may already have registered it during local builds; if
   so, confirm the capability is enabled.
3. **App record.** In App Store Connect → Apps, add a new iOS app with that
   bundle ID. The App Store name must be unique across the store, so it may
   need a qualifier; the name on the home screen stays KegInker, from the
   build.
4. **API key.** In Users and Access → Integrations → App Store Connect API,
   generate a team key with the **Admin** role, which cloud-managed
   distribution signing requires. Note its key ID and the issuer ID shown
   above the key list, and download `AuthKey_XXXX.p8`. Apple lets you
   download it only once.

## Secrets

Add the four secrets. Each `gh secret set` without input prompts for the
value:

```sh
gh secret set APPLE_TEAM_ID -R cacack/keginker                 # e.g. ABCDE12345
gh secret set APP_STORE_CONNECT_KEY_ID -R cacack/keginker
gh secret set APP_STORE_CONNECT_ISSUER_ID -R cacack/keginker
gh secret set APP_STORE_CONNECT_KEY_P8 -R cacack/keginker < AuthKey_XXXX.p8
```

Then back up the `.p8` in a password manager and delete the download. If it's
lost, revoke the key and generate a new one. The iOS job fails, naming every
missing secret, if any is unset.

The key has the Admin role, so treat it like the Android release key. The
[recommended repository settings](signing.md#recommended-repository-settings)
suggest an approval-gated `release` environment; put these four secrets in it
too.

## Versions and build numbers

App Store Connect accepts only `MAJOR.MINOR.PATCH` versions, so the iOS
version is the tag without any pre-release suffix: `v0.4.0-alpha.1` ships as
0.4.0. The build number is `<versionCode>.<run attempt>`, using the
[versionCode](signing.md#cutting-a-release) Android uses, so it rises with
every release and every re-run of the workflow: `v0.4.0-alpha.1` is build
40001.1, and re-running it uploads 40001.2. The app's diagnostics report the
full tag version.

## Adding testers

Uploaded builds appear in App Store Connect → the app → TestFlight once
Apple finishes processing them, usually within minutes.

- **Internal testers** are App Store Connect users on the team, up to 100.
  Add them to an internal group; they get each build as soon as it's
  processed, with no review.
- **External testers** are anyone with an email address or a public link,
  up to 10,000. Add them to an external group. The first build of each
  version goes through Beta App Review, usually within a day; later builds
  of that version usually don't.

Testers install the TestFlight app, accept the invitation, and install
KegInker from it. TestFlight builds expire after 90 days.

## Verifying a build

The ipa attached to each GitHub Release is the build CI uploaded. To check
it was built by this repository's CI from its release tag:

```sh
gh attestation verify keginker-X.Y.Z.ipa -R cacack/keginker
```

The ipa is signed for App Store distribution, so it isn't installable
directly; install through TestFlight. Apple re-signs what TestFlight
delivers, so the attestation covers the uploaded build.
