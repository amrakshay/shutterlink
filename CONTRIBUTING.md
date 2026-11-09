# Contributing

Thanks for looking. This is a small project with a deliberately narrow job, so the most useful
thing to read first is what it is not trying to be.

## The scope, so you do not waste your time

This app **puts a camera's viewfinder and shutter on a second phone.** That is the whole job.

Out of scope, and likely to be declined however well built:

- **A general camera app.** Filters, beautification, editing, stickers. If you want your own
  camera app's processing, ShutterLink can fire it for you (see §10 and §11 of
  [`docs/PLAN.md`](docs/PLAN.md)) — the editing belongs in that app, not this one.
- **Anything that needs the internet.** No cloud relay, no account, no sharing. Two phones in one
  room, talking directly. Remote-over-the-internet shooting is a different product with a
  different threat model.
- **Managing the other phone.** File browsing, app control, screen mirroring as a feature in its
  own right. It is a camera remote.

In scope and welcome: lower latency, more phones supported, video recording, accessibility, better
reconnection, and anything in the v2 table in [`docs/PLAN.md`](docs/PLAN.md).

## Getting set up

No Android Studio required — the Gradle wrapper is checked in.

```bash
# JDK 21, and the Android SDK:
sdkmanager "platform-tools" "platforms;android-37.2" "build-tools;37.0.0"
echo "sdk.dir=$ANDROID_SDK_ROOT" > local.properties

./gradlew :protocol:test        # no device, no emulator, a few seconds
./gradlew :app:assembleDebug
```

### The protocol module has no Android dependency, on purpose

`protocol/` holds everything the two phones say to each other, as a plain JVM module. That is what
lets the whole message layer be tested on a desktop in seconds, instead of needing two phones and a
pair of hands every time a field changes.

**Please keep it that way.** CI fails the build if an `android.*` import appears there. Two phones
are already the slowest possible test loop; do not make the protocol part of it.

### Why Nearby Connections rather than raw Wi-Fi Direct

`WifiP2pManager` behaves differently on every OEM skin, and we need the same two things every
phone pair needs: find each other over BLE, then move the video onto Wi-Fi. Nearby Connections
does exactly that and is far better tested across devices than anything we would write.

It sits behind a `Transport` interface so it can be replaced — removing the Google Play Services
dependency is backlog item **T-7**, not a rewrite. If you add transport code, add it behind that
interface, not next to it.

### Measure before optimising the preview

The preview pipeline is the one place where it is easy to make something that *looks* right and
is unusable. The numbers that matter are end-to-end latency and dropped frames, and the app has a
debug overlay that shows both. A change to the encoder, the framing, or the bitrate policy wants
a before-and-after from that overlay in the PR, not an argument.

## House style

- Comments explain **why**, especially where the code looks wrong but is not. Camera, codec and
  transport code accumulates these quickly — a frame dropped on purpose, a surface released in an
  order that looks arbitrary. Write the reason down the first time, not the second.
- British spelling in prose, American in code identifiers where the platform uses it.
- No new dependency without a reason that a paragraph can justify.

## Tests

```bash
./gradlew :protocol:test
```

New protocol behaviour wants a test. Message framing and version negotiation are the two places
where a mistake produces a silent failure rather than an error, so they earn the most.

### Test on two real phones

This matters more here than in most projects. The things that break are **timing, permissions and
OEM behaviour**: a preview that stalls when the screen dims, a Wi-Fi Direct group that forms on one
phone brand and not another, a permission that is optional on Android 10 and mandatory on 14. None
of that is reachable from a unit test, and emulators cannot do Wi-Fi Direct, BLE or a real camera
at all.

[`docs/PLAN.md`](docs/PLAN.md) has a record of what has been exercised on hardware and what has
not. If you change something in that list, please update it.

## Pull requests

- Say what is different for someone using the app, not only what changed in the code.
- Say **which two phones you tested on**, and their Android versions. "OnePlus 7 (Android 10) to
  Pixel 8 (Android 15), preview held for ten minutes, twelve shots" is worth more than a green CI
  run.
- Update `docs/PLAN.md` and `CHANGELOG.md` if behaviour changed.

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
