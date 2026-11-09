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
- **Phase 6 — surviving real use.** A foreground service so the camera keeps running with the
  screen off (required, not a convenience — Android stops the camera within seconds otherwise), a
  tested backoff policy that reconnects without hammering the radio, and thermal stepping that
  drops the stream quality before the phone throttles itself. **Written but not yet run.**
- **Phases 3–5 — the live preview, remote control, and the photo coming back.** The camera feeds a
  hardware H.264 encoder directly through its input Surface, so no frame is copied through the CPU;
  frames are length-prefixed with a resynchronising magic and sent on a separate channel from the
  control messages. The controller decodes to a SurfaceView and drives zoom, camera switch, timer
  and shutter by message, never optimistically. Captures are written to the camera phone's gallery
  first — that copy is the original and survives a failed transfer — then sent across.
  **Written but not yet run on two phones.**
- **Phase 2 (in progress) — the two phones find each other.** A `Transport` seam with a Nearby
  Connections implementation behind it (BLE discovery, automatic Wi-Fi Direct upgrade), the
  `Message` protocol with a CBOR codec that drops anything it does not understand rather than
  breaking the link, a version-aware permission screen that states why each one is needed, and the
  pairing flow — advertise, discover, confirm a four-digit code on both phones, handshake, measure
  the round trip. Advertising and the permission flow are verified on hardware; everything from
  discovery onwards needs a second phone.
- **Phase 1 — the standalone camera.** Role picker (remembered per phone), CameraX preview,
  shutter, pinch and stepped zoom bounded by what the device actually reports, a 0/3/10s self-timer
  with an on-screen countdown, front/back switching, and the last shot as a thumbnail. Capture
  orientation follows the physical phone rather than the locked-portrait window — a propped-up
  camera gets turned on its side constantly, and without this every such shot saved rotated. Photos are written straight into
  `DCIM/ShutterLink` through MediaStore, so they belong to you rather than to the app and need no
  storage permission on API 29+. The controller half says plainly that it is not built yet.
- [`design/`](design/) — sixteen UI mocks the app is being built against, viewable in a browser,
  and the small set of rules they follow (dark throughout, one accent colour, awkward constraints
  stated on the screen where they happen).
- Release signing: a 4096-bit key (`CN=shutterlink`, valid to 2054) in the macOS Keychain and in
  the repository's Actions secrets. The whole chain — Keychain, `tools/release-env.sh`,
  `assembleRelease`, `apksigner` — is verified, and the minified release APK was smoke-tested on
  a OnePlus 7.

Nothing user-facing works yet. Phase 1 (the standalone camera) is next.
