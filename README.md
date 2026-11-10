# ShutterLink

**Two Android phones. One becomes the camera, the other becomes the shutter.**

Prop one phone up across the room, press the shutter on the phone in your hand, and the photo
comes back to you a moment later at full resolution — look at it, and shoot again if the framing
is off.

It is the Apple Watch Camera Remote, for Android, without the watch — and without the live
viewfinder, for a reason given below.

> **Status: working on two phones, not yet released.** Pairing, the shutter, zoom, flip, the
> timer, the photo coming back, review and retake, reconnection after a drop, and discovery of the
> far phone's other camera apps have all been run on hardware. Firing another camera app's shutter
> over Bluetooth HID is the one feature still unproven; it needs the two phones bonded in Bluetooth
> settings.
>
> [`docs/VERIFY.md`](docs/VERIFY.md) is the ordered list of what to check and
> [`docs/PLAN.md`](docs/PLAN.md) records what has actually been measured on hardware versus what is
> assumed.

## What it is

Every phone camera has the same problem: to be in the photo, you have to be away from the phone,
and the moment you are away from the phone you cannot see what it sees. Timers are guesswork.
Cheap Bluetooth remotes fire the shutter but show you nothing.

ShutterLink puts the shutter in your hand and the photo back in front of you:

- **A real shutter** for a phone across the room — about 20–170 ms from your tap to the capture.
- **Real control** — zoom, front/back, self-timer — from the phone you are holding.
- **The photo comes back** at full resolution in a few seconds, so you can judge it and shoot
  again. Retake is the framing loop.
- **Nothing is saved to your gallery until you say so.** The photo sits inside the app on the
  controller until you tap Save. The camera phone always keeps its own original.
- **It can also fire other camera apps.** If you want your phone's own camera app — for its night
  mode or its colours — ShutterLink can trigger it the way a Bluetooth selfie remote does.

Nothing goes to the internet.

## Why there is no live viewfinder

There was one, and it was removed.

Streaming 720p H.264 from the far phone worked, and on a quiet link it was good: about 60 ms
behind reality at 28 fps. On a busy home network it was not. Nearby Connections chose `WIFI_LAN` —
both phones talking through the router rather than directly to each other — and when that router
was congested the picture fell one to two seconds behind and stayed there. A viewfinder that lags
is worse than no viewfinder, because you frame against something that has already happened while
believing you are looking at now.

Shoot-and-look is slower, and it is honest. The photo you judge is the photo you took. It also
costs the camera phone far less: it holds the camera idle between shots instead of encoding video
continuously, so it stays cool and keeps its battery.

The streaming work is not lost — [`docs/FINDINGS.md`](docs/FINDINGS.md) records what it taught us,
and the git history has it.

## How it talks

Bluetooth LE finds the other phone and carries the commands, which are small and fast on any
medium. The photo is several megabytes, so it benefits from Wi-Fi: about a second there against
the better part of a minute over Bluetooth. The controller says which one it is on while a photo
is coming across, so a slow transfer never looks like a hang.

**Which Wi-Fi, exactly, is not up to us.** Nearby Connections picks the medium, and in testing it
chose `WIFI_LAN` — the two phones talking over the local network through the router — rather than a
direct phone-to-phone Wi-Fi Direct link, which has never been observed in any test here.

**With no network at all** — outdoors, nothing to join — it falls back to Bluetooth and still
works. Shutter, zoom, flip and timer are unaffected; only the photo transfer is slower. Leaving
the Wi-Fi radio switched on helps even with no network to join.

Nothing leaves the local network in either case. There is no account, no server and no cloud, and
the app holds no `INTERNET` permission at all — CI fails the build if one appears.

## Requirements

- Two phones running **Android 10 (API 29)** or newer.
- Both need Bluetooth LE. Wi-Fi makes the photo transfer fast but is not required — with no
  network at all the pair falls back to Bluetooth and still works.
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
| [`docs/VERIFY.md`](docs/VERIFY.md) | What has been checked on two phones and what has not, in the order to check it. |
| [`docs/FINDINGS.md`](docs/FINDINGS.md) | What the hardware actually taught us, including why the live preview was removed. |
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
