# Verifying ShutterLink on real phones

What has actually been run on hardware, and what has not. Measured on a
**OnePlus 7 (Android 10)** as the camera phone and a **Samsung M04 (Android
14)** as the phone running the app.

Everything before 2026-11-10 tested a different app — one with a live
viewfinder and a photo transfer, both since removed. That history is in
[`FINDINGS.md`](FINDINGS.md); this file covers what ships.

## 1 — Setup  ✅ passed 2026-11-10

| Check | Result |
|---|---|
| App registers as a Bluetooth HID device | **Passes** — `registered=true` on the Samsung |
| It shows the right name to pair with | **Passes** — "Mamta's M04", read from the Bluetooth adapter, not the Nearby name |
| "Make this phone visible" works | **Passes** — system dialog, 300s, `SCAN_MODE_CONNECTABLE_DISCOVERABLE` |
| The other phone can find it | **Passes** — "Mamta's M04" appeared in the OnePlus's own Bluetooth scan |
| Pairing completes | **Passes** — matching code 549770 on both, accepted on each |
| The HID host attaches | **Passes** — `connection state=2, connected=true` |
| The screen follows all of it | **Passes** — setup panel replaced by "Connected to #AK" |

**The phone running the app must be able to act as an HID device.** This was
the single biggest unknown in the whole project. The Samsung M04 on Android 14
does. Nothing here proves any other phone will.

## 2 — The shutter  ✅ passed 2026-11-10

| Check | Result |
|---|---|
| Button fires the OnePlus stock camera | **Passes** — 4 shots, 4 photos |
| Tap to photo on disk | **1.3 s, 2.4 s, 1.3 s** — mostly the camera's own capture and write |
| 3s timer | **Passes** — fired after 6.3 s total |
| 10s timer counts down on screen | **Passes** — read "6" three seconds in |
| Nothing is left stuck | **Passes** — returns to Ready |
| Either volume key on the controller fires it | **Passes** — 5 photos for 5 presses |
| A held key fires once, not a burst | **Passes** — `repeat=0 -> FIRE`, `repeat=1 -> SWALLOW` |
| Volume keys work normally when nothing is connected | **Passes** — `canFire=false -> IGNORE` on both press and release |
| Reconnects after the app restarts | **Passes** — reopening the app with the phones still bonded reaches "Connected" in a few seconds with no setup at all |

| First press after a cold start | **Passes** — 3 for 3 with the app and the camera app both force-stopped and reopened |

The cold-start case needed a fix: the first keystroke after the link came up was
being dropped, and the app now primes the channel with an empty report when a
host attaches. See FINDINGS 6.

## 3 — Not yet checked

| | |
|---|---|
| Any camera app other than the OnePlus stock one | PicsArt is not installed on the test phone. Other apps may ignore the key, which is why the key is a setting |
| Any phone other than these two | In particular, whether another phone will act as an HID device at all |
| Range | Never measured. Classic Bluetooth should give roughly 10 m indoors |
| Outdoors with no network | **Should** work — nothing in this design touches a network — but it has only been run at a desk |
| Battery over a long session | Never measured |
| Re-attaching after the phones go out of range and come back | The app retries every 2 s, never tested for real |
