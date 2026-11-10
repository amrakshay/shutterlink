# Findings

What the hardware actually taught us, kept short.

Measured on a **Samsung M04 (Android 14)** running ShutterLink and a **OnePlus 7
(Android 10)** as the camera phone.

Most of this project's history was an app that no longer exists — one that
streamed a live viewfinder between two phones and sent photos back. Sections 1
to 4 are the findings that still govern the app as it stands. Section 5 is the
condensed record of why the rest was deleted, kept because it is the answer to
the first question anyone asks, and because the measurements are the only honest
reason not to try it again.

---

## 1 · The HID descriptor has to reach usage 0x80

The whole app is a Bluetooth keyboard that sends one key, and the key that
matters — Volume Up — is usage `0x80`. A textbook boot-keyboard report
descriptor declares `Logical Maximum (101)` / `0x65`, because that covers the
printable keys.

Copy that descriptor and the one key the app exists to send **cannot be
expressed at all**. Nothing errors; the report is simply not valid for a usage
outside the declared range, and the shutter does nothing.

`HidShutter.descriptor` declares `Logical Maximum (255)` and
`Usage Maximum (255)` instead. If the shutter ever stops working after someone
tidies that array, this is why.

## 2 · "Registered" and "connected" are different states

`BluetoothHidDevice.registerApp()` succeeding means this phone is now a
keyboard. It does **not** mean anything is listening.

Observed exactly this: `registered=true, plugged=false`, and a shutter button
that silently did nothing. A host has to attach before a report goes anywhere,
which happens after the two phones are bonded. The app tracks both and only
enables the shutter on the second.

```
HID app registered=true plugged=false     <- a keyboard, talking to nobody
HID connection state=2, connected=true    <- now it works
```

## 3 · A phone is not discoverable unless you ask

Classic Bluetooth pairing needs the target to be advertising itself, and Android
phones do not, except while their Bluetooth settings screen is open. A scan from
the other phone finds nothing, however correct the code.

So ShutterLink asks: "Make this phone visible" fires
`ACTION_REQUEST_DISCOVERABLE`, the platform shows its own dialog, and the phone
advertises for five minutes. Confirmed working — after tapping it, the Samsung
appeared in the OnePlus's ordinary Bluetooth scan as "Mamta's M04" and paired
first try with a matching code on both screens.

An earlier version tried to be cleverer: discover the other phone from inside
the app and call `createBond()` on it. That cannot work without this step, and
without it the bond failed twice by hand with no explanation —
`BOND_STATE_BONDING` straight back to `BOND_STATE_NONE`.

## 4 · Reconnecting needs the registration callback, not a retry loop

With the two phones already bonded, reopening the app left it on its setup
screen forever while the keyboard behind it worked perfectly.

Two causes, both ours:

**The host attaches during registration.** `onAppStatusChanged` hands back a
`pluggedDevice`, and that was being thrown away in favour of waiting for
`onConnectionStateChanged` to report `CONNECTED` — which never came, because
from the stack's point of view nothing had changed. The device in that callback
*is* the host.

**The retry loop was its own worst enemy.** It called `connect()` on every
bonded device every two seconds — headphones, a speaker, a TV remote included —
and calling `connect()` again on a device that is already connecting aborts the
attempt in progress. The log cycled `state=1, state=0, state=1, state=0`
indefinitely while a perfectly good host sat waiting. It now filters to devices
that could plausibly host a keyboard, tries one at a time, refuses overlapping
attempts, and waits six seconds between tries.

## 5 · Camera apps disagree about which key is the shutter

Measured against the OnePlus stock camera, each key sent and a seven-second
settle before counting files:

| Key | Takes a photo |
|---|---|
| Volume up | yes |
| Volume down | yes |
| Camera | yes |
| Enter | yes |
| D-pad centre | yes |
| Headset hook | no |
| Focus | no |

Volume up is the default because it is what commercial selfie remotes send. It
is a **setting rather than a constant** precisely because this table is one
camera app on one phone, and there is no reason to believe another app agrees.

Two traps in measuring this. The file appears on disk well after the shutter
fires — a four-second check said Volume Up did nothing, when it had worked and
the photo simply landed later, so allow seven seconds before calling a key
dead. And a camera app that has just been launched may ignore the first press
entirely while it starts up; seen once on the OnePlus, with every press after it
settled working.

---

## 6 · Why the app is this small

ShutterLink began as a remote viewfinder: 720p H.264 streamed from the camera
phone, a live preview with zoom in your hand, and the photo sent back for review
and retake. It was built, and it worked.

Four things were then deleted, each forced by a measurement.

**The live viewfinder.** On a quiet link it ran at 28.6 fps about 60 ms behind
reality. On a congested home router it fell one to two seconds behind and stayed
there. No change inside the app could reach that — Nearby Connections chose
`WIFI_LAN`, both phones through the router, every single time it was observed,
and it refuses to upgrade away from a medium it already classes as high quality:

```
BandwidthUpgradeManager skipped retry upgrade [High quality medium WIFI_LAN]
```

Wi-Fi Direct was never selected once, for a reason that took far too long to
find: the Samsung does not offer it. The negotiated medium set is the
intersection of what each phone supports, and it drops out. Setting
`ConnectionType.DISRUPTIVE` — the switch Quick Share uses to stand up its own
hotspot — changed nothing while both phones shared a network, because no upgrade
is attempted at all in that case.

A viewfinder that lags is worse than none: you frame against a moment that has
already passed while believing you are looking at the present.

**Our own camera.** With no preview to feed, holding CameraX bought nothing a
third-party camera app did not do better, and actively prevented that app from
opening the camera.

**The photo transfer.** 3 MB took about 13 seconds over that same router, and
would be far worse over Bluetooth outdoors. Walking over to look at the other
phone costs nothing and always works.

**Nearby Connections itself.** With no video to stream and no photo to carry, a
keystroke needs no data link, no discovery protocol, no Play Services and no
pairing handshake of ours.

### What that bought

| | Before | Now |
|---|---|---|
| Permissions | 13 | 4, all Bluetooth |
| Camera, location, storage, Wi-Fi permissions | all held | none |
| Google Play Services | required | not used |
| Needed on the camera phone | the app | **nothing** |
| Works with no network | poorly | yes, by construction |

### The part worth remembering

Every deleted piece worked. The preview really did reach 28.6 fps; the transfer
really did deliver the file. Each was correct, and each was load-bearing for a
design that could not survive a busy router.

The failure was never in the code. It was in choosing a design whose critical
path ran over a medium we did not control, and then defending it with
increasingly clever machinery — a quality ladder, congestion control, clock
alignment, frame shedding — all of which was good engineering aimed at the wrong
problem. The question that fixed it was not "how do we make this faster" but
"what is the smallest thing that does the job", and the answer had been sitting
in the design the whole time as a secondary feature: a selfie-stick keystroke.

### One measurement lesson, which cost two wrong answers

**Never compare timestamps taken on different devices.** A shutter lag reported
as 2.9 seconds was really 165 ms: the tap was stamped on the Mac and the capture
read from the camera phone's logcat, and that phone's clock runs **2.59 seconds
ahead**. Earlier, the same mistake with a sign error produced an end-to-end
latency of *minus five seconds*, which at least announced itself — a plausible
wrong number is far more dangerous than an impossible one.

Stamp both ends on the same clock, or measure the skew first and subtract it.
