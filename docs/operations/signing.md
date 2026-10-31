# Signing and releases

Android only installs an update signed with the same key as the installed
app. One release key signs GitHub Releases and maintainers' local builds,
so they install over each other. One exception: Android won't go from a
release to a lower-versioned dev build. An F-Droid or Play release would
carry the same key.

CI builds, of pull requests and of `main`, never get the key. Only the
release workflow does, when a `v*` tag is pushed. CI builds use the runner's
default debug key, which is new for every run, so a CI build doesn't install
over anything else; uninstall first.

The key never enters git. It lives in the secrets of the `release`
environment, which only `v*` tags can deploy to, in a gitignored
`local.properties` on maintainers' machines, and in its backups.

## Where the key comes from

`androidApp/build.gradle.kts` reads four settings, from environment variables
first, then `local.properties`:

| Environment variable | `local.properties` key | Value |
|---|---|---|
| `KEGINKER_KEYSTORE` | `keginker.keystore` | Path to the keystore file |
| `KEGINKER_KEYSTORE_PASSWORD` | `keginker.keystorePassword` | Keystore password |
| `KEGINKER_KEY_ALIAS` | `keginker.keyAlias` | Key alias |
| `KEGINKER_KEY_PASSWORD` | `keginker.keyPassword` | Key password |

With no keystore set, debug builds use the default debug key and release
builds are unsigned. That's the normal case for contributors and for every
pull request. If the keystore is set but its file is missing, or any of the
other three settings is missing, the build fails.

Debug builds signed with the key are still debuggable and log NFC commands.
Don't hand them to testers in place of a release.

## Creating the key (once)

Keep the keystore outside the repository.

```sh
keytool -genkeypair -keystore keginker-release.jks -alias keginker \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=keginker"
```

The keystore is PKCS12, so the key password is the keystore password.

Add it to the `release` environment (see
[the release environment](#the-release-environment-once)). Each
`gh secret set` without input prompts for the value, so passwords stay out
of your shell history:

```sh
base64 -i keginker-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R cacack/keginker -e release
gh secret set ANDROID_KEYSTORE_PASSWORD -R cacack/keginker -e release
gh secret set ANDROID_KEY_ALIAS -R cacack/keginker -e release      # the alias given to keytool
gh secret set ANDROID_KEY_PASSWORD -R cacack/keginker -e release   # same as the keystore password
```

To sign local builds too, add the four `keginker.*` keys to
`local.properties`, using an absolute path for `keginker.keystore`.

## Backups

Losing the key means no installed copy can ever be updated: every user would
have to uninstall and reinstall. Keep two backups:

- a password-manager entry holding the keystore file and both passwords
- an offline, encrypted copy, kept somewhere other than the computer that uses it

GitHub secrets can't be read back, so they aren't a backup.

## Rotating the key

Rotate only if the key is compromised, or when moving to a store that
requires it.

1. Create a new keystore as above.
2. Sign a rotation lineage so phones on Android 9 and later accept the new
   key as a successor:
   `apksigner rotate --out lineage --old-signer --ks old.jks --new-signer --ks new.jks`.
   Release builds must then be signed with `--lineage`. That isn't wired into
   Gradle yet.
3. Replace the four secrets, `local.properties` entries and backups.
4. Phones older than Android 9 (minSdk is 7.0) can't follow the rotation and
   need an uninstall and reinstall. Say so in the release notes.

## Cutting a release

Releases are cut by [release-please](https://github.com/googleapis/release-please)
from the conventional commits on `main`
([`release-please.yml`](../../.github/workflows/release-please.yml)). After
each push to `main` it opens or updates a release PR that bumps the version in
[`.release-please-manifest.json`](../../.release-please-manifest.json) and adds
a `CHANGELOG.md` entry: `feat` bumps MINOR and `fix` bumps PATCH while the
version is below 1.0.0, and a breaking change bumps MINOR.

Merge the release PR once its checks pass. release-please then creates the
`vX.Y.Z` tag and its GitHub Release, with the changelog entry as notes.

To cut a pre-release, or to pick a version yourself, end a commit message
with a `Release-As` footer. The release PR then proposes exactly that version:

```sh
git commit -m "fix: keep the write button enabled after a timeout" -m "Release-As: 0.1.0-alpha.2"
```

The footer only takes effect if the changes since the last release include a
`feat`, `fix` or `perf` commit. When every commit is `chore`, `ci`, `docs` or
`refactor`, release-please opens no release PR.

Normal bumps keep the pre-release suffix: after `0.1.0-alpha.1`, a `feat`
proposes `0.2.0-alpha.1` and a `fix` proposes `0.1.1-alpha.1`. To leave the
pre-release series, use `Release-As` with the final version, such as
`Release-As: 0.2.0`.

Versions are `MAJOR.MINOR.PATCH`, or `MAJOR.MINOR.PATCH-alpha.N`, `-beta.N` or
`-rc.N` for pre-releases (N from 1 to 31). MAJOR runs 0–213; MINOR and PATCH
run 0–99.

The tag triggers [`release.yml`](../../.github/workflows/release.yml), which:

1. refuses tags that aren't on `main`, that have no GitHub Release (a
   hand-pushed tag) or that don't match the format above
2. runs the shared tests
3. builds a release APK signed with the key, passing
   `-Pkeginker.versionName` (the tag without the `v`) and
   `-Pkeginker.versionCode` to Gradle
4. refuses to publish if the APK isn't signed, or if it requests any
   permission beyond NFC ([constitution](../../CONSTITUTION.md) C4)
5. records a signed build-provenance attestation for the APK
   ([constitution](../../CONSTITUTION.md) C5)
6. attaches `keginker-X.Y.Z.apk` and its SHA-256 to the release, titles it
   `KegInker X.Y.Z` and marks tags with a suffix as pre-releases

Alongside steps 2 to 6, it builds the iOS app
([testflight.md](testflight.md) covers the App Store Connect setup and
secrets):

1. archives a Release build without `DEBUG`, signed through an App Store
   Connect API key, with version `MAJOR.MINOR.PATCH` and build
   `<versionCode>.<run attempt>`
2. exports an App Store ipa and refuses to publish if its signed
   entitlements or `Info.plist` declare any permission beyond NFC
   ([constitution](../../CONSTITUTION.md) C4)
3. records a signed build-provenance attestation for the ipa
   ([constitution](../../CONSTITUTION.md) C5)
4. uploads the ipa to TestFlight
5. attaches `keginker-X.Y.Z.ipa` and its SHA-256 to the release

On each platform, the steps that run Gradle or Xcode are in a build job
that gets the signing secrets from the `release` environment but only a
read-only token. A separate publish job, which runs no build code,
attests, uploads and edits the release, so build code never holds the
`contents: write`, OIDC or attestation permissions. The build job still
holds the signing key, because Gradle and Xcode sign as they build.

### The release-please app (once)

release-please authenticates as a GitHub App installed only on this
repository, not with `GITHUB_TOKEN`: actions taken with `GITHUB_TOKEN` don't
trigger workflows, so its release PR wouldn't run CI and its tags wouldn't
trigger `release.yml`.

1. Create a GitHub App (webhook off) with repository permissions **Contents**
   and **Pull requests** set to read and write, and install it on this
   repository only.
2. Generate a private key for it and add both secrets:

```sh
gh secret set RELEASE_PLEASE_APP_ID -R cacack/keginker
gh secret set RELEASE_PLEASE_APP_PRIVATE_KEY -R cacack/keginker < app-private-key.pem
```

Then delete the downloaded `.pem`; a new key can be generated if needed.

`versionCode` is `((MAJOR×100 + MINOR)×100 + PATCH)×100 + slot`. The slot is
N for `alpha.N`, 32+N for `beta.N`, 64+N for `rc.N` and 99 for the final
release, so each release installs over every earlier one. For example,
`v0.1.0-alpha.1` is 10001 and `v0.1.0` is 10099.

Builds without those properties (local and CI) use `versionCode` 1 and
`versionName` `0.0.0-dev`. Android won't downgrade, so to go back to a dev
build after installing a release, uninstall first.

### The release environment (once)

The signing and App Store Connect secrets are environment secrets on a
`release` environment that only `v*` tags can deploy to, so no branch, pull
request or other workflow can read them. A tag ruleset limits who can
create `v*` tags. `release.yml` also requires the tag to be on `main` and
to have a GitHub Release.

1. Create the environment and allow only `v*` tags to deploy to it:

   ```sh
   gh api -X PUT repos/cacack/keginker/environments/release \
     --input - <<< '{"deployment_branch_policy":{"protected_branches":false,"custom_branch_policies":true}}'
   gh api -X POST repos/cacack/keginker/environments/release/deployment-branch-policies \
     -f name='v*' -f type=tag
   ```

2. Add the four Android secrets ([above](#creating-the-key-once)) and the
   four [iOS secrets](testflight.md#secrets) with `-e release`. Secrets
   can't be read back, so moving them means entering each value again from
   its backup, then deleting the repository-level copy with
   `gh secret delete <NAME> -R cacack/keginker`.
3. Add a tag ruleset that lets only repository admins and the release-please
   app create, update or delete `v*` tags:

   ```sh
   gh api -X POST repos/cacack/keginker/rulesets --input - <<'EOF'
   {
     "name": "Release tags",
     "target": "tag",
     "enforcement": "active",
     "conditions": {"ref_name": {"include": ["refs/tags/v*"], "exclude": []}},
     "rules": [{"type": "creation"}, {"type": "update"}, {"type": "deletion"}],
     "bypass_actors": [
       {"actor_type": "RepositoryRole", "actor_id": 5, "bypass_mode": "always"},
       {"actor_type": "Integration", "actor_id": <release-please app ID>, "bypass_mode": "always"}
     ]
   }
   EOF
   ```

Optionally, add yourself as a required reviewer on the environment, so
every release waits for an approval before the build jobs get the keys.
