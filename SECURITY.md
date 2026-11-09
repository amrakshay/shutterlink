# Security

## The threat model, stated plainly

Two phones in one room talk directly to each other. One is showing a live camera feed; the other
can fire its shutter and pull photos off it. That is worth being careful about, and worth being
honest about what is and is not protected.

**What this app protects against:**

- **A stranger's phone joining your session uninterrupted.** Connections are not silent: both
  phones show a short confirmation code, and the person holding each one has to accept before
  anything is streamed. An attacker within Bluetooth range cannot connect while nobody is looking
  at the screens.
- **Your photos leaving the room.** There is no server, no account, no analytics and no internet
  permission path in the app's design. The far phone keeps the original; the near phone gets a
  copy over the direct link.

**What it does not protect against, and cannot:**

- **Someone with physical access to either phone.** If they can unlock it, they can use the app.
- **A determined attacker in radio range during an active session.** Nearby Connections encrypts
  the link, but the pairing is a short human-checked code, not a certificate. Treat the preview
  stream as confidential-ish, not secret.
- **The other camera app.** When you use "capture with another app" (see `docs/PLAN.md` §11),
  that app's own behaviour, permissions and network access are its business, not ours.

## Permissions, and why each exists

The app asks for a lot, and it should be able to justify every one.

| Permission | Why | When |
|---|---|---|
| `CAMERA` | It is a camera. | Phase 1 |
| `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT` | Finding the other phone, and the HID shutter. | Phase 2 |
| `ACCESS_FINE_LOCATION` | Android 10–11 require it for *any* BLE scan or Wi-Fi Direct discovery. The app does not read your location and does not want it. | Phase 2, Android ≤ 11 only |
| `NEARBY_WIFI_DEVICES` | The Android 13+ replacement for the above, declared `neverForLocation`. | Phase 2, Android 13+ |
| `FOREGROUND_SERVICE` | The far phone has to keep streaming with its screen off. The notification is required and deliberate — you should be able to see that it is running. | Phase 6 |
| `READ_MEDIA_IMAGES` | Picking up a photo another camera app just saved, so it can be sent back to you. | Phase 5.5 |
| `SYSTEM_ALERT_WINDOW` | Only for third-party camera apps that cannot hand a photo back directly; it is what lets ShutterLink return to the foreground afterwards. Requested at the moment you first use such an app, never on launch. | Phase 5.5, optional |

If a permission appears in the manifest that is not in this table, that is a bug — please report it.

## Reporting a vulnerability

Open an issue for anything non-sensitive. For something that should not be public first, contact
the maintainer directly rather than filing it; a fix and a release will follow before the details
are published.

Please include the two phone models and Android versions — most of the interesting behaviour here
is OEM-specific.
