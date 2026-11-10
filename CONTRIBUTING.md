# Contributing

Thanks for looking. This is a small project with a deliberately narrow job, so the most useful
thing to read first is what it is not trying to be.

## The scope, so you do not waste your time

This app **is a Bluetooth shutter button for a camera app on a second phone.** That is the whole
job. It had a larger one — a live viewfinder and a photo transfer — and both were removed after
measurement; see `docs/FINDINGS.md` 16 and 20 before proposing their return.

Out of scope, and likely to be declined however well built:

- **A general camera app.** Filters, beautification, editing, stickers. ShutterLink fires your
  own camera app, so its processing is already what you get — the editing belongs in that app,
  not this one.
- **Anything that needs the internet.** No cloud relay, no account, no sharing. Two phones in one
  room. The app works in a field with no network of any kind, and that is not an accident — it
  is what is left after everything that needed a network was measured and removed.
- **Managing the other phone.** File browsing, app control, screen mirroring as a feature in its
  own right. It is a camera remote.

- **Anything that has to be installed on the camera phone.** It needs nothing today, which is
  most of the app's value. A proposal that changes that has to be worth losing it.

In scope and welcome: more phones confirmed working, more camera apps confirmed to honour a key,
a wider choice of keys, accessibility, a more forgiving pairing flow, and better behaviour when
the phones drift out of Bluetooth range and back.

## Getting set up

No Android Studio required — the Gradle wrapper is checked in.

```bash
# JDK 21, and the Android SDK:
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

./gradlew :app:assembleDebug
```

### Why there is no network code at all

There was: Nearby Connections, for discovery and for carrying video and photos. It is gone. The
shutter is a Bluetooth HID keystroke, which needs no data link, no Play Services and no network,
and therefore works in a field. Anything that reintroduces a network dependency has to justify
itself against that — `docs/FINDINGS.md` 16, 18 and 20 are the measurements that got us here.

### Measure on two phones, and never across their clocks

Two separate confident wrong numbers in this project came from comparing a timestamp taken on one
device with one taken on another. A shutter lag of "2.9 seconds" was really 165 ms plus a 2.59 s
clock skew between a phone and the Mac reading its logcat. Timestamp both ends on the same clock,
or measure the skew first and subtract it.

### Test on two real phones

This matters more here than in most projects. The things that break are **timing, permissions and
OEM behaviour**: a phone that will not act as an HID device, a camera app that ignores the key
phone brand and not another, a permission that is optional on Android 10 and mandatory on 14. None
of that is reachable from a unit test, and an emulator cannot act as a Bluetooth HID device at
all.

[`docs/VERIFY.md`](docs/VERIFY.md) has a record of what has been exercised on hardware and what
has not. If you change something in that list, please update it.

## Pull requests

- Say what is different for someone using the app, not only what changed in the code.
- Say **which two phones you tested on**, and their Android versions. "OnePlus 7 (Android 10) to
  Pixel 8 (Android 15), paired and fired twelve shots" is worth more than a green CI
  run.
- Update `docs/VERIFY.md` and `CHANGELOG.md` if behaviour changed.

## Versioning

The version lives in [`VERSION`](VERSION) and the Android build reads it, deriving `versionCode`
from it (`1.2.3` becomes `10203`). A release is one edit, not two that can disagree.

## Releasing

Push a `v*` tag and the release workflow does the rest: it checks the tag against `VERSION`,
builds, signs, verifies the signature with `apksigner`, pulls the notes out of `CHANGELOG.md` and
publishes the APK. CI signs from repository secrets.

The full sequence:

```bash
# 1. Land everything for the release on main.
# 2. Set the version and write the notes.
echo "0.2.0" > VERSION
$EDITOR CHANGELOG.md          # move Unreleased into a [0.2.0] — YYYY-MM-DD section
git commit -am "Release 0.2.0"
git push

# 3. Tag it. The tag must match VERSION or the workflow fails loudly.
git tag v0.2.0
git push origin v0.2.0
```

To sign locally instead:

```bash
source tools/release-env.sh && ./gradlew :app:assembleRelease
```

**There is no keystore and no password anywhere in this repository, and there must not be.** It is
public, and a signing key committed here would let anyone build an APK that Android accepts as an
upgrade to this one — a key that has been published cannot be withdrawn. The password and the
keystore live in the macOS login Keychain, under the account `shutterlink`:

| Service | Holds |
|---|---|
| `shutterlink-keystore-password` | the store and key password |
| `shutterlink-keystore-base64` | the keystore itself |

`tools/release-env.sh` reads both and rebuilds `~/.shutterlink/release.keystore` when it is
missing, so the file on disk is a cache and the Keychain is the source. With iCloud Keychain on,
that is also the off-machine backup.

The same two values go into the repository's GitHub Actions secrets as
`SHUTTERLINK_KEYSTORE_BASE64`, `SHUTTERLINK_KEYSTORE_PASSWORD` and `SHUTTERLINK_KEY_PASSWORD`.

**If both the Keychain entries and the GitHub secrets are lost, the key is gone**, and no future
release can upgrade an installed copy in place — people would have to uninstall and re-pair.

### Creating the signing key, once

```bash
keytool -genkeypair -v -keystore ~/.shutterlink/release.keystore \
  -alias shutterlink -keyalg RSA -keysize 4096 -validity 10000 \
  -dname "CN=shutterlink, O=amrakshay, C=IN"

security add-generic-password -a shutterlink -s shutterlink-keystore-password -w '<password>'
base64 -i ~/.shutterlink/release.keystore | \
  security add-generic-password -a shutterlink -s shutterlink-keystore-base64 -w "$(cat)"
```

The release workflow checks the APK's certificate contains `CN=shutterlink` before publishing, so
a build that silently came out unsigned fails rather than shipping.
