# Signing and releases

Android only installs an update signed with the same key as the installed
app. One release key signs GitHub Releases, CI builds of `main`, and
maintainers' local builds, so they install over each other. One exception:
Android won't go from a release to a lower-versioned dev build. An F-Droid or
Play release would carry the same key.

Pull-request builds never get the key, because their build scripts haven't
been reviewed yet. They use the default debug key.

The key never enters git. It lives in GitHub Actions secrets, in a gitignored
`local.properties` on maintainers' machines, and in its backups.

## Where the key comes from

`androidApp/build.gradle.kts` reads four settings, from environment variables
first, then `local.properties`:

| Environment variable | `local.properties` key | Value |
|---|---|---|
| `TAGPAPER_KEYSTORE` | `tagpaper.keystore` | Path to the keystore file |
| `TAGPAPER_KEYSTORE_PASSWORD` | `tagpaper.keystorePassword` | Keystore password |
| `TAGPAPER_KEY_ALIAS` | `tagpaper.keyAlias` | Key alias |
| `TAGPAPER_KEY_PASSWORD` | `tagpaper.keyPassword` | Key password |

With no keystore set, debug builds use the default debug key and release
builds are unsigned. That's the normal case for contributors and for every
pull request. If the keystore is set but its file is missing, or any of the
other three settings is missing, the build fails.

Debug builds signed with the key are still debuggable and log NFC commands.
Don't hand them to testers in place of a release.

## Creating the key (once)

Keep the keystore outside the repository.

```sh
keytool -genkeypair -keystore tagpaper-release.jks -alias tagpaper \
  -keyalg RSA -keysize 4096 -validity 10000 -dname "CN=tagpaper"
```

The keystore is PKCS12, so the key password is the keystore password.

Add it to GitHub. Each `gh secret set` without input prompts for the value,
so passwords stay out of your shell history:

```sh
base64 -i tagpaper-release.jks | gh secret set ANDROID_KEYSTORE_BASE64 -R cacack/tagpaper
gh secret set ANDROID_KEYSTORE_PASSWORD -R cacack/tagpaper
gh secret set ANDROID_KEY_ALIAS -R cacack/tagpaper      # tagpaper
gh secret set ANDROID_KEY_PASSWORD -R cacack/tagpaper   # same as the keystore password
```

To sign local builds too, add the four `tagpaper.*` keys to
`local.properties`, using an absolute path for `tagpaper.keystore`.

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

Tag a commit on `main` and push the tag:

```sh
git tag v0.1.0-alpha.1 origin/main && git push origin v0.1.0-alpha.1
```

Tags are `vMAJOR.MINOR.PATCH`, or `vMAJOR.MINOR.PATCH-alpha.N`, `-beta.N` or
`-rc.N` for pre-releases (N from 1 to 31). MAJOR runs 0–213; MINOR and PATCH
run 0–99.

[`release.yml`](../../.github/workflows/release.yml) then:

1. refuses tags that aren't on `main` or don't match the format above
2. runs the shared tests
3. builds a release APK signed with the key, passing
   `-Ptagpaper.versionName` (the tag without the `v`) and
   `-Ptagpaper.versionCode` to Gradle
4. refuses to publish if the APK isn't signed
5. publishes a GitHub Release with `tagpaper-X.Y.Z.apk`, its SHA-256 and
   notes generated from the commits; tags with a suffix are marked as
   pre-releases

`versionCode` is `((MAJOR×100 + MINOR)×100 + PATCH)×100 + slot`. The slot is
N for `alpha.N`, 32+N for `beta.N`, 64+N for `rc.N` and 99 for the final
release, so each release installs over every earlier one. For example,
`v0.1.0-alpha.1` is 10001 and `v0.1.0` is 10099.

Builds without those properties (local and CI) use `versionCode` 1 and
`versionName` `0.0.0-dev`. Android won't downgrade, so to go back to a dev
build after installing a release, uninstall first.

### Recommended repository settings

`release.yml` checks that a tag is on `main`, but anyone who can push a tag
can still trigger it. To tighten that further, in the repository settings:

- add a tag ruleset that limits creating `v*` tags to maintainers
- optionally move the four secrets into an environment (for example
  `release`) that requires approval, and add `environment: release` to the job
