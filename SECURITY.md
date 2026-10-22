# Security policy

## Supported versions

KegInker is in early development. Only the latest release gets security
fixes, shipped as a new release. Older releases aren't patched, so update to
the latest one.

## Reporting a vulnerability

Report it privately through GitHub:
[report a vulnerability](https://github.com/cacack/keginker/security/advisories/new).
Please don't open a public issue for a security problem.

Include what you can:

- the version and platform (Android or iOS) affected
- steps to reproduce, or a proof of concept
- what an attacker could do with it

## What to expect

- A first response within 7 days.
- Updates as the report is confirmed and a fix is planned.
- Once fixed, a release and a published GitHub security advisory that
  credits you, if you'd like credit.

## Scope

In scope: the app's code on both platforms, and how releases are built,
signed and published. To check that a release was built by this
repository's CI, see
[try-a-development-build.md](docs/guides/try-a-development-build.md).

Problems in tag firmware or in vendors' own apps belong with those vendors.
If one affects how KegInker talks to a tag, a private report here is still
welcome.

## Security reviews

Every minor release is covered by a full security review. The latest is
[v0.4.2-alpha.1](docs/reviews/security/2026-10-21-0.4.2-alpha.1.md); all of
them are in [docs/reviews/security/](docs/reviews/security/).
