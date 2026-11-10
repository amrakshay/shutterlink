# Changelog

All notable changes are recorded here. The format follows
[Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and the project uses
[semantic versioning](https://semver.org/).

The version lives in one place, the [`VERSION`](VERSION) file, which the Android build reads at
configure time — so the APK and the repository cannot ship disagreeing numbers.

## [Unreleased]

ShutterLink is a Bluetooth shutter button. It installs on the phone in your hand, pairs with your
other phone from that phone's own Bluetooth settings, and fires whatever camera app is open there.
Nothing is installed on the camera phone.

### Added

- **The shutter.** The app registers as a Bluetooth HID keyboard and sends one keystroke. Verified
  on a Samsung M04 (Android 14) firing the stock camera on a OnePlus 7 (Android 10): four shots
  for four presses, 1.3–2.4 s from tap to photo on disk.
- **Pairing, in the app.** A "Make this phone visible" button puts the phone into the platform's
  own discoverable mode for five minutes, so the other phone can find it from ordinary Bluetooth
  settings. The screen follows every step and says what failed when something does.
- **The phone's own volume keys as the shutter.** Either key, found by feel
  while you are standing in the shot. Both the press and the release are
  swallowed so the volume does not move, holding the key fires once rather than
  a burst, and the keys are left alone entirely whenever the app is not
  connected.
- **Self-timer**, 3 or 10 seconds, counted down on the phone in your hand.
- **A choice of key.** Volume up by default. Camera apps differ, so it is a setting: on the
  OnePlus stock camera, volume up, volume down, camera, enter and D-pad centre all take a photo,
  while headset hook and focus do not.
- **Repository foundations:** Gradle build, version catalog, checked-in wrapper, CI, release
  workflow, issue and PR templates, and the project documents.

### Removed

Everything below was built, made to work on hardware, and then removed after measurement. The
reasoning and the numbers are in [`docs/FINDINGS.md`](docs/FINDINGS.md), sections 16 to 20.

- **The live viewfinder.** H.264 at 720p streamed between the phones. It reached 28.6 fps and
  about 60 ms of lag on a quiet link, and one to two seconds on a congested home router — which no
  change inside the app could reach, because the bottleneck was the router.
- **ShutterLink's own camera.** With no preview to feed, holding CameraX only prevented the real
  camera app from opening.
- **The photo transfer and review screen.** A 3 MB photo took about 13 seconds over a congested
  local network, and far longer over Bluetooth. Walking over to look at the other phone costs
  nothing and always works.
- **Google Nearby Connections, and the whole `:protocol` module.** With no video to stream and no
  photo to carry, a keystroke needs no data link, no discovery protocol and no Play Services.
- **Nine permissions.** Camera, both location permissions, Wi-Fi state and change, nearby Wi-Fi
  devices, storage read, and two foreground-service types. Four remain, all Bluetooth.
- **The standalone camera mode** and the role picker, which only existed because the app used to
  run on both phones.
