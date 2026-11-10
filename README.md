# ShutterLink

**A Bluetooth shutter button for the phone across the room.**

Install it on the phone in your hand. Pair your other phone to it once, from
that phone's own Bluetooth settings, exactly as you would pair a selfie remote.
Then open any camera app over there, prop the phone up, and press the button.

**Nothing is installed on the camera phone.** It does not need ShutterLink, an
account, a network, or anything else — to it, your phone is simply a Bluetooth
keyboard, and the button sends the keystroke its camera app already treats as a
shutter.

## Why it is this small

It did not start this way. Earlier versions streamed a live 720p viewfinder
from the far phone over Wi-Fi, sent the photo back for review, and ran a pairing
protocol of their own on top of Google's Nearby Connections. All of it worked,
and none of it survived contact with a real network: on a congested home router
the viewfinder ran one to two seconds behind reality, and a viewfinder that lags
is worse than none, because you frame against a moment that has already passed.

Every attempt to fix that added machinery. What was left when the machinery came
out is the part that always worked, and the part that works in a field with no
network at all. [`docs/FINDINGS.md`](docs/FINDINGS.md) is the record of that,
including the measurements that justified each deletion.

What you give up: no live view and no photo coming back. You walk over and look
at the other phone.

## What it does

- **Fires the shutter** on whatever camera app is open on the other phone.
- **Self-timer**, 3 or 10 seconds, counted down on the phone in your hand.
- **Picks the key it sends.** Volume up by default. Not every camera app honours
  the same one, so it is a setting rather than an assumption. Measured on the
  OnePlus stock camera: volume up, volume down, camera, enter and D-pad centre
  all take a photo; headset hook and focus do not.

## What it needs

- The phone in your hand: **Android 10+**, and it must be able to act as a
  Bluetooth HID device. Most can; the app says so plainly if yours cannot.
- The camera phone: **anything with Bluetooth and a camera app.** No Android
  version requirement, because nothing of ours runs on it.
- **No Wi-Fi, no mobile data, no network of any kind.** This is the whole point
  of the design and it works anywhere the two phones are in Bluetooth range.

## Permissions

Four, all Bluetooth: `BLUETOOTH_ADVERTISE` and `BLUETOOTH_CONNECT`, plus
`BLUETOOTH` and `BLUETOOTH_ADMIN` on Android 11 and below. No camera, no
location, no storage, no internet. CI fails the build if an `INTERNET`
permission ever appears. See [`SECURITY.md`](SECURITY.md).

## Building

No Android Studio required — the Gradle wrapper is checked in.

```bash
# JDK 21, and the Android SDK:
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

./gradlew :app:assembleDebug
```

## Documentation

| | |
|---|---|
| [`docs/FINDINGS.md`](docs/FINDINGS.md) | What the hardware taught us, including why nearly all of this app was deleted. |
| [`docs/VERIFY.md`](docs/VERIFY.md) | What has been checked on real phones, and what has not. |
| [`CONTRIBUTING.md`](CONTRIBUTING.md) | Scope, house style, how to test, how releases work. |
| [`SECURITY.md`](SECURITY.md) | The permission list and what pairing does and does not protect. |
| [`CHANGELOG.md`](CHANGELOG.md) | What changed, version by version. |

## What it deliberately does not do

- **No live preview and no photo transfer.** Both were built and removed. See
  FINDINGS 16.
- **No cloud, no account, no internet.** Two phones and a Bluetooth bond.
- **It does not manage the other phone.** It is a button, not a remote desktop.

## Licence

[MIT](LICENSE).
