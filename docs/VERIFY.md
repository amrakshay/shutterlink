# What still has to be checked on two phones

Phases 3 to 7 were written without a second phone available. Everything in them
compiles, and the logic that could be moved into the pure-JVM `protocol` module
is tested there — but **no part of the streaming, control, capture-transfer,
reconnect or HID code has ever run.**

This is the list to work through, in order. It is ordered so that each step only
depends on the ones above it: when something fails, the cause is almost always
in the last step that passed.

Put the debug build on both phones:

```bash
./gradlew :app:assembleDebug
adb -s <camera-phone> install -r app/build/outputs/apk/debug/app-debug.apk
adb -s <controller-phone> install -r app/build/outputs/apk/debug/app-debug.apk
```

Watch both at once while testing:

```bash
adb -s <phone> logcat -s ShutterLink/Transport ShutterLink/Encoder ShutterLink/Decoder ShutterLink/HID
```

## 1 — Finish Phase 2: they find each other  ✅ passed 2026-11-09

| Check | What good looks like |
|---|---|
| Controller lists the camera phone | Its name appears within a few seconds |
| Tap it | Both screens show a four-digit code |
| The codes match | If they differ, something else is connecting — that is the check working |
| Accept on both | Both move on; `ShutterLink/Transport` logs the connection |
| Round trip | The controller shows a latency figure, and it updates every 2s |
| Handshake | The controller shows the camera phone's real name, not a placeholder |

**If discovery finds nothing:** check both phones have Bluetooth *and* Wi-Fi on,
location on (Android ≤ 12), and neither is in battery saver.

## 2 — The shutter  ✅ passed 2026-11-10

Replaces the old "the picture" section. There is no live preview any more; see
FINDINGS §16 for why it was removed.

| Check | Result |
|---|---|
| Controller shows "Ready" once connected | **Passes** |
| Far phone's battery and latency on the top strip | **Passes** — 76%, 60–200 ms |
| Shutter press reaches the camera | **Passes** — 19 ms and 165 ms on two instrumented runs |
| Camera acts on it | **Passes** — 18 ms from message received to `takePicture` |
| Capture completes | **Passes** — about 600 ms at `MAXIMIZE_QUALITY` |

**Measure shutter lag on each phone's own clock, never across devices.** The
OnePlus runs 2.59 s ahead of the Mac, which turned a 165 ms result into an
apparent 2.9 s one. FINDINGS §16 has the detail.

## 3 — The controls  ✅ passed 2026-11-10

| Check | What good looks like |
|---|---|
| Zoom slider | Sends; the effect is visible in the photo that comes back, not before |
| Flip | Switches cameras; the rig screen's "Camera" stat changes |
| Timer | Countdown appears on **both** screens |
| Shutter | Fires; the camera phone's shot count goes up |

Nothing here updates optimistically — if a control appears to do nothing, check
whether the message arrived before assuming the UI is broken. Zoom is now a
blind control: nothing confirms it until the photo arrives.

## 4 — The photo comes back  ✅ passed 2026-11-10

| Check | Result |
|---|---|
| Camera phone's gallery | **Passes** — full size, immediately |
| Transfer progress on the controller | **Passes** — percentage, and says when it is on Bluetooth |
| Review screen opens with the photo | **Passes** — renders, 3065 KB |
| Orientation | **Passes** — phone on its side returned an upright landscape photo |
| Save | Only then does it appear in the controller's gallery |
| Retake | Returns to the shutter |
| Whole loop | **About 7 s** tap to photo on screen |

**The camera phone's copy must exist even if the transfer fails.** Test that
deliberately: start a capture and walk out of range.

## 5 — Phase 6: survival  ⚠️ partly tested 2026-11-09

| Checked | Result |
|---|---|
| Foreground service runs, `isForeground=true`, notification posted | **Passes** |
| Shutter works with the camera phone's screen off | **Not retested since the preview was removed** — the old failure was the frame counter freezing, which no longer exists as a symptom. The camera is still taken away on lock, and the screen-on rebind still covers it |
| Recovers on wake | **Passes** — the screen-on receiver rebinds the camera |
| Reconnect after going out of range | Not tested |
| 20-minute session | Not tested |

The remaining rows need the camera phone to have nothing else competing for the
foreground.


| Check | What good looks like |
|---|---|
| Screen off | A shutter press still takes a photo; the notification is visible |
| Notification | Says the camera is in use, and stops it when tapped |
| Walk out of range | Controller says it is reconnecting, with an attempt count |
| Walk back | Reconnects on its own; the controller returns to Ready |
| Long session | 20 minutes without intervention; watch the thermal chip on the controller |

## 5b — Tomorrow's list

Picked up on 2026-11-09 and not yet resolved. In the order worth attacking.

### 1. ~~The preview lags~~ — fixed and confirmed 2026-11-10

Confirmed by eye on the phones, not only by the counters.

Cause was HD being pushed down a Bluetooth link before Nearby upgraded to
Wi-Fi. The stream now starts at 360p and steps up when the link reports HIGH.
Blocked writes went from 31+ to 0; the link upgrade from 35s to 1.3s; first
picture from 501ms to 155ms. Full account in FINDINGS §11.

Still worth measuring properly one day: **true glass-to-glass latency**, by
pointing the camera at a running stopwatch and photographing both screens. The
figure in the chip is the control round trip, which is not the same thing.

### 2. ~~Recovery after the screen goes off~~ — passes 2026-11-10

```
before screen off:  3803 f
after 20s off:      3880 f   (buffered frames draining, then still)
15s after wake:      139 f   <- counter reset: a new stream was built
25s after wake:      549 f   <- climbing steadily
```

The `ACTION_SCREEN_ON` / `ACTION_USER_PRESENT` receiver fires and rebuilds the
encoder, binding and video channel; the encoder log shows the restart. The frame
counter resetting is the tell — it is a fresh decoder session, not the old one
resuming.

Streaming still **stops** while the screen is off, and that remains unfixed:
the system takes the camera when the device locks, and the real answer is moving
the camera session into `StreamingService` behind a `LifecycleService`. What is
fixed is that waking the phone brings the picture back instead of leaving a dead
session that looks connected.

### 3. ~~Reconnect after going out of range~~ — passes 2026-11-10

Simulated by cutting the controller's Wi-Fi while keeping adb over USB, so both
phones could be watched through the drop and the recovery.

```
20s after cut:      "Reconnecting - attempt 3 of 20"
30s after restore:  streaming again, with no taps on either phone
                    "Picture is rough for a moment ... still on Bluetooth"
12:59:52  encoder at 640x360     <- starts low
13:00:24  encoder at 1280x720    <- steps up once Wi-Fi takes over
first picture after rejoin: 297ms
```

Two bugs were found and fixed getting here; see FINDINGS §12.

**Open observation:** the control round trip sat at 881 ms, then 554 ms, after
the rejoin, against a 183 ms baseline before the cut. It may simply be Wi-Fi
reassociating and settling, but it was not watched for long enough to say. Worth
a longer look during the endurance run — if latency stays elevated after every
reconnect, something is not being rebuilt cleanly.

**Caveat on the method:** cutting Wi-Fi is not identical to walking out of
range, because Bluetooth stays up. A real walk kills both radios together.
Worth doing once for real.

### 4. A 20-minute session

Never run. Watch for thermal step-downs and battery drain on the camera phone.

## 6 — Phases 2.5 and 5.5: other camera apps  ⚠️ mostly passes 2026-11-10

| Check | Result |
|---|---|
| HID registers | **Passes** — `HID app registered=true` on the Samsung (Android 14). This was the single most uncertain thing in the project |
| Engine picker lists the far phone's camera apps | **Passes** — found `com.oneplus.camera`, shown as "Camera" with "Its own processing — live view pauses" |
| Untested apps flagged | **Passes** — no badge on the OnePlus camera, which is in the known-good list |
| Warns before it can fail | **Passes** — selecting another app with no Bluetooth bond says "Pair the two phones in Bluetooth settings first" |
| Launches the chosen app on the far phone | **Passes** — `com.oneplus.camera/.OPCameraServiceActivity` came to the front |
| Fires its shutter | **Blocked** — needs the two phones bonded in Bluetooth settings. Failed honestly: "Could not reach the other phone's shutter" |
| Exactly one gallery copy | Not reached |
| PicsArt | Not installed on the test phone; still unknown |

**The one remaining step needs a human**: the two phones must be paired with
each other in Android's Bluetooth settings, which no amount of adb can do
safely. HID runs over classic Bluetooth and a Nearby connection is not the same
thing.

## 7 — Before any release

- [ ] Every section above passes on two phones
- [x] One of them is not a OnePlus — the pair is a OnePlus 7 (Android 10, Qualcomm) and a Samsung Galaxy M04 (Android 14, MediaTek), which differ in OEM, SoC and permission regime
- [ ] `./gradlew :protocol:test` green
- [ ] CI green
- [ ] `VERSION` bumped and `CHANGELOG.md` has a section for it
- [ ] The "written, unverified" markers in `PLAN.md` replaced with results

`VERSION` is deliberately still `0.1.0`. Nothing should be tagged until at least
sections 1 to 5 pass: a signed release that cannot show a picture is worse than
no release.
