# Changelog

All notable changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/).

The version lives in one place, the [`VERSION`](VERSION) file, which the Android build reads at
configure time — so the APK and the repository cannot ship disagreeing numbers.

While the major version is `0`, the app does not yet do what the README describes. See
[`docs/PLAN.md`](docs/PLAN.md) for what is built and what is not.

## [Unreleased]

### Added

- Repository foundations: Gradle build, `:app` and `:protocol` modules, version catalog, checked-in
  wrapper, CI, release workflow, issue and PR templates, and the project documents.
- `protocol/` — protocol version constant, the `Role` enum, and peer version negotiation, with
  tests. A plain JVM module with no Android dependency, enforced by CI.
- A placeholder screen so the app builds, installs and runs from the first commit. Verified on a
  OnePlus 7 (GM1901, Android 10).
- [`docs/PLAN.md`](docs/PLAN.md) — the design and phase plan, including the hardware checks the
  decisions rest on.
- [`design/`](design/) — sixteen UI mocks the app is being built against, viewable in a browser,
  and the small set of rules they follow (dark throughout, one accent colour, awkward constraints
  stated on the screen where they happen).
- Release signing: a 4096-bit key (`CN=shutterlink`, valid to 2054) in the macOS Keychain and in
  the repository's Actions secrets. The whole chain — Keychain, `tools/release-env.sh`,
  `assembleRelease`, `apksigner` — is verified, and the minified release APK was smoke-tested on
  a OnePlus 7.

Nothing user-facing works yet. Phase 1 (the standalone camera) is next.
