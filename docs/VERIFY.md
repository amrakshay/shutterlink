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

## 1 — Finish Phase 2: they find each other

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

## 2 — Phase 3: the picture

| Check | What good looks like |
|---|---|
| Preview appears | Within ~2s of connecting |
| Latency | **Target: under 200 ms glass-to-glass.** Point the camera at a stopwatch on a third screen and photograph both together |
| Frame counter | Climbs steadily; the dropped counter stays near zero |
| Recovery | Cover the camera, uncover: picture returns without reconnecting |
| Late join | Disconnect the controller and reconnect: picture returns at the next keyframe, not never |

The last two exercise the config-before-keyframe logic, which is the part most
likely to be wrong and shows up as a permanently black viewfinder.

**If the preview is black but frames are counting:** the decoder is getting
pictures before setup bytes. **If no frames count at all:** the encoder or the
stream channel, not the decoder.

## 3 — Phase 4: the controls

| Check | What good looks like |
|---|---|
| Zoom slider | The far camera zooms; the preview follows |
| Flip | Switches cameras; preview resumes within ~2s |
| Timer | Countdown appears on **both** screens |
| Shutter | Fires; the camera phone's screen shows the count going up |

Nothing here updates optimistically — if a control appears to do nothing, check
whether the message arrived before assuming the UI is broken.

## 4 — Phase 5: the photo comes back

| Check | What good looks like |
|---|---|
| Camera phone's gallery | The photo is there immediately, full size |
| Transfer | Progress appears on the controller |
| Review screen | Opens with the photo |
| Save | Only then does it appear in the controller's gallery |
| Retake | Returns to the viewfinder, preview still live |

**The camera phone's copy must exist even if the transfer fails.** Test that
deliberately: start a capture and walk out of range.

## 5 — Phase 6: survival

| Check | What good looks like |
|---|---|
| Screen off | Streaming continues; the notification is visible |
| Notification | Says the camera is in use, and stops it when tapped |
| Walk out of range | Controller says it is reconnecting, with an attempt count |
| Walk back | Reconnects on its own, preview returns |
| Long session | 20 minutes without intervention; watch for thermal step-downs in the status |

## 6 — Phases 2.5 and 5.5: other camera apps

**This is the least certain part of the project.** Three things are unknown:
whether each phone will act as a Bluetooth HID device at all, whether a given
camera app honours the key, and whether 2.5 seconds is long enough for the
chosen app to have taken the camera before the key is sent.

That last one is a fixed delay, which is the weakest part of this design. §11 of
the plan calls for detecting readiness with `PACKAGE_USAGE_STATS` instead; that
was not built, because it adds a permission for something that cannot be tested
without two phones. **If the shutter fires too early, this delay is the first
thing to look at** — it is one constant in `RemoteViewfinderScreen`.

| Check | What good looks like |
|---|---|
| HID registers | `ShutterLink/HID` logs `registered=true` |
| Classic Bluetooth pairing | The two phones are paired in Settings, not just connected by ShutterLink |
| Engine picker | Lists the camera apps actually installed on the far phone |
| OnePlus Camera | Fires; photo returns; **exactly one** copy in the gallery |
| PicsArt | Unknown. If the shutter does not fire, try the other keys in settings before assuming it is broken |

**The duplicate check matters most here.** Take one shot with each app and count
the gallery entries. Two is a bug; zero is a worse one.

## 7 — Before any release

- [ ] Every section above passes on two phones
- [ ] One of them is not a OnePlus — OEM behaviour is the main unknown
- [ ] `./gradlew :protocol:test` green
- [ ] CI green
- [ ] `VERSION` bumped and `CHANGELOG.md` has a section for it
- [ ] The "written, unverified" markers in `PLAN.md` replaced with results

`VERSION` is deliberately still `0.1.0`. Nothing should be tagged until at least
sections 1 to 5 pass: a signed release that cannot show a picture is worse than
no release.
