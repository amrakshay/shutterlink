# ShutterLink

**Two Android phones. One becomes the camera, the other becomes the viewfinder.**

Prop one phone up across the room, see yourself live on the phone in your hand, frame the shot,
zoom, and press the shutter — then the photo comes back to you so you can decide whether to
retake it.

It is the Apple Watch Camera Remote, for Android, without the watch.

> **Status: built, largely unverified.** Every phase is written and the app compiles, installs and
> runs. The standalone camera is confirmed working on hardware. Everything that needs *two* phones
> — the preview, the controls, the photo transfer, reconnection, firing other camera apps — has
> never been run, because only one phone was available while it was built.
>
> [`docs/VERIFY.md`](docs/VERIFY.md) is the ordered list of what to check, and
> [`docs/PLAN.md`](docs/PLAN.md) records what has actually been measured on hardware versus what is
> assumed. Nothing is tagged for release until the first five sections of that list pass.

## What it is

Every phone camera has the same problem: to be in the photo, you have to be away from the phone,
and the moment you are away from the phone you cannot see what it sees. Timers are guesswork.
Cheap Bluetooth remotes fire the shutter but show you nothing.

ShutterLink puts the viewfinder in your hand:

- **Live preview** from the far phone, 720p, over Wi-Fi Direct.
- **Real control** — zoom, front/back, self-timer, shutter — from the phone you are holding.
- **The photo comes back** at full resolution, so you can judge it and shoot again.
- **It can also fire other camera apps.** If you want your phone's own camera app — for its night
  mode or its colours — ShutterLink can trigger it the way a Bluetooth selfie remote does. You
  lose the live preview in that mode; you still get the photo back.

Nothing goes to the internet. The two phones talk directly to each other.

## How it talks

Bluetooth LE finds the other phone and carries the commands. The video then moves onto Wi-Fi,
because Bluetooth cannot carry it: a watchable preview needs a few megabits a second.

**Which Wi-Fi, exactly, is not up to us.** Nearby Connections picks the medium, and in testing it
chose `WIFI_LAN` — the two phones talking over the local network through the router — rather than a
direct phone-to-phone Wi-Fi Direct link. That works well on a quiet network and badly on a busy
one, and it is worth knowing if the preview degrades: the bottleneck may be the router rather than
either phone.

Nothing leaves the local network in either case. There is no account, no server and no cloud, and
the app holds no `INTERNET` permission at all — CI fails the build if one appears.

## Requirements

- Two phones running **Android 10 (API 29)** or newer.
- Both need Bluetooth LE and Wi-Fi Direct. Nearly every phone from 2016 onward has both; the app
  checks and says so if one is missing.
- Google Play Services, for the connection layer. See [`docs/PLAN.md`](docs/PLAN.md) §3 — removing
  that dependency is a planned item, not a permanent requirement.

## Building

No Android Studio required — the Gradle wrapper is checked in.

```bash
# JDK 21, and the Android SDK:
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

./gradlew :protocol:test        # no device, no emulator, a few seconds
./gradlew :app:assembleDebug
```

## Documentation

| | |
|---|---|
| [`docs/PLAN.md`](docs/PLAN.md) | The whole design: decisions, phases, v2 backlog, risks, and what has actually been measured on hardware. |
| [`docs/VERIFY.md`](docs/VERIFY.md) | What still has to be checked on two phones, in the order to check it. |
| [`design/`](design/) | The UI mocks the app is built against, and the rules behind them. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Scope, house style, how to test, how releases work. |
| [`SECURITY.md`](SECURITY.md) | What the pairing does and does not protect against. |
| [`CHANGELOG.md`](CHANGELOG.md) | What changed, version by version. |

## What it deliberately does not do

- **No cloud, no account, no internet.** Two phones, one room.
- **It is not a camera app you would use day to day.** It exists for the shot you cannot take with
  the phone in your hand.
- **It does not manage the other phone.** It is a camera remote, not a remote desktop.

## Licence

[MIT](LICENSE).
