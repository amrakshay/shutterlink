# What two phones taught us

Bugs found by running ShutterLink on real hardware, with what caused each one.
They are recorded here rather than left in commit messages because most are not
mistakes in our logic — they are places where the documented behaviour of
Android or of Google's libraries differs from the actual behaviour, and the next
person to touch this code would walk into them again.

The test pair throughout: a **OnePlus 7** (Android 10, Qualcomm) as the camera
and a **Samsung Galaxy M04** (Android 14, MediaTek) as the controller. Nearly
every bug below needed *both* — a different OEM, a different chipset, and
opposite ends of the permission regime.

| # | Symptom | Cause | Status |
|---|---|---|---|
| 1 | Discovery never starts on Android 14 | Nearby needs both location permissions, always | **Fixed** |
| 2 | Encoder will not start on the OnePlus | `KEY_PROFILE` set without `KEY_LEVEL` | **Fixed** |
| 3 | Picture arrives on its side | Sensor buffers reach the encoder untransformed | **Fixed** |
| 4 | Picture squeezed into a strip | Rotation applied before the scale | **Fixed** |
| 5 | Top chips stack one letter per line | Row overflow on a 720px screen | **Fixed** |
| 6 | "That image could not be opened" | Scoped storage blocks Nearby's download path | **Fixed** |
| 7 | Filename shown as `-4709986883054979248` | Nearby names files after the payload id | **Fixed** |
| 8 | Stream stops when the screen goes off | The system takes the camera when the device locks | **Fix written, untested** |
| 9 | "Trouble looking for phones: 8002" shown as an error | Reconnect re-calls `discover()`; Nearby answers ALREADY_DISCOVERING | **Fixed** |
| 10 | Controller shows the picture rotated | Orientation sent before CameraX had reported it | **Fixed** |
| 11 | Preview lags behind reality | HD pushed down a Bluetooth link before Nearby upgrades to Wi-Fi | **Fixed** |

---

## 1 · Nearby Connections requires both location permissions, on every version

```
8034: MISSING_PERMISSION_ACCESS_COARSE_LOCATION
8036: MISSING_PERMISSION_ACCESS_FINE_LOCATION
```

Android's own documentation says a `BLUETOOTH_SCAN` declared `neverForLocation`
needs no location permission from Android 12 onward, and that
`NEARBY_WIFI_DEVICES` replaces location for Wi-Fi from 13. Both are true **of the
platform**. Neither is true of Nearby Connections, which refuses `startDiscovery()`
without coarse *and* fine location on every version including 14.

Writing the permission matrix the way the platform documentation implies —
`maxSdkVersion="32"` on the location permissions — produces an app that cannot
find anything on Android 13 or newer, and fails with a message that points at
permissions the user has already granted.

**This is the clearest cost of the Nearby dependency.** A camera app asking for
location is the most alarming line on its permission screen, and it is there
only because of this library. Backlog **T-7** (replacing Nearby with raw BLE and
`WifiP2pManager`) would remove it.

## 2 · `KEY_PROFILE` without `KEY_LEVEL` kills the encoder

```
venc_dev: Unsupported eColorFormat 0x7f000789
ExtendedACodec: [OMX.qcom.video.encoder.avc] configureCodec returning error -38
```

`0x7f000789` is `COLOR_FormatSurface` — the one thing a surface-input encoder
must support. The real cause is not the colour format at all: requesting
`AVCProfileBaseline` without a matching `KEY_LEVEL` makes this Qualcomm OMX
encoder fail to configure, and it reports that as an unsupported colour format.

Pinning the profile bought nothing — these encoders already default to baseline
or better — and cost the entire stream.

The encoder is now chosen by walking every AVC encoder the phone reports,
hardware first, until one configures. The two phones in the test pair already
disagree about which encoder works, so asking for a MIME type and hoping is not
enough.

## 3 and 4 · The picture arrives sideways, then squeezed

The camera sensor is landscape and its buffers reach the encoder untransformed,
so a phone propped up in portrait sends frames lying on their side. Only CameraX
knows the angle — it depends on the sensor mounting and how the phone is being
held — so it is sent in `Capabilities` and re-sent if the phone is turned.

The controller moved from `SurfaceView` to `TextureView`, because only a
`TextureView` can be transformed.

Then the transform was wrong. **A TextureView always draws its content stretched
to its own bounds**, so for a quarter turn the content must be scaled into a
height-by-width rectangle *before* being rotated. Rotating first and scaling
afterwards applies the scale along the already-turned axes and squeezes the
picture into a strip. Scale, then rotate.

## 5 · The chip row overflowed

On the 720px-wide Samsung the status row ran out of width and Android stacked
the last chip one letter per line vertically. It read as a rendering fault
rather than a layout one. Back is now pinned and the facts scroll.

Worth remembering: the mocks were drawn at 390dp. Anything laid out as a fixed
row of chips needs checking at a narrower width.

## 6 · Scoped storage blocks Nearby's own download folder

```
java.io.FileNotFoundException: /storage/emulated/0/Download/.nearby/<id>:
  open failed: EACCES (Permission denied)
```

Nearby drops received files in its own folder under `Download`. On Android 14
this app cannot open that path. `Payload.asFile().asJavaFile()` hands it over
anyway.

**The failure is disguised.** `File.length()` returns the correct size, because
`stat` is permitted and only `open` is denied — so the photo looked like it had
transferred correctly and then failed to decode, and the screen said "That image
could not be opened" about a perfectly good JPEG. Checking `length()` is not
evidence that a file is readable.

Use `Payload.asFile().asUri()` and read through the `ContentResolver`.

## 7 · Nearby names received files after the payload id

The review screen showed `-4709986883054979248`. Nearby has no idea what a file
was called; it names it after the payload. The sender already says so in
`PhotoReady`, so the receiver matches the two up by payload id.

## 8 · The stream stops when the screen goes off — **open**

Established by measurement: with the camera phone's screen off, the controller's
frame counter advanced briefly (buffered frames draining) and then **froze** —
457 → 529 over the first seconds, then no change across 18 seconds. Meanwhile
the control channel stayed alive and kept answering pings at ~80 ms. So the
connection survives and the camera does not.

The cause is structural: CameraX is bound to the activity's lifecycle, and the
screen going off stops the activity, which unbinds the camera. The foreground
service keeps the *process* alive, not the camera.

Re-tested with **nothing else competing for the foreground** — the earlier run
was confounded by another app taking the screen on wake, so that result was
discarded. Clean result, app verified in the foreground throughout:

```
before:        5385 f
after 20s off: 5450 f    (+65 — buffered frames draining, then still)
after wake:    5450 f    (frozen)
10s later:     5450 f    (still frozen)
```

So it does **not** recover on its own. More interesting is *why*: the activity
never leaves RESUMED when the screen goes off, so a lifecycle observer on
ON_RESUME never fires at all. The camera is not being unbound by CameraX — it is
being **taken away by the system** when the device locks, and CameraX cannot get
it back by itself once the encoder's input surface has gone with it.

That makes the lifecycle the wrong thing to watch. `ACTION_SCREEN_ON` and
`ACTION_USER_PRESENT` are the events that actually arrive.

### What has been done

The screen is now held on wherever the camera is live, which covers the case the
app actually cares about: a phone propped up and untouched, timing out. A
rebind-on-resume was also added, on the reasoning that an activity returning to
the foreground should restore its camera. **It is written but unverified** for
the reason above.

### What has not

Streaming through a locked screen — which is what Phase 6 actually asked for —
needs the camera session moved into `StreamingService` and bound to a
`LifecycleService` rather than to the activity. That is a structural change and
is not done.

Until it is, treat "streaming survives the screen going off" as **unsupported**,
not as a bug waiting to be rediscovered.

### How to test it properly

Make sure no other app can take the foreground on the camera phone, then: open
ShutterLink, connect, press power, wait 20s, press power again and unlock.
Watch the controller's frame counter across all three stages.

## 9 · "Already discovering" is not a failure

The reconnect loop calls `advertise()` or `discover()` on each attempt without
stopping the previous one, so Nearby answers `8002 STATUS_ALREADY_DISCOVERING`
(or `8001` for advertising). That is confirmation the thing we want is already
running — but it was surfaced to the user as "Trouble looking for phones: 8002",
which reads as broken while the app is working correctly. Both codes are now
treated as success.

## 10 · The orientation arrived after the message that carried it

The camera sends its rotation inside `Capabilities`, but CameraX reports that
angle **asynchronously** — normally after `bindForStreaming` has returned and
the message has gone, so the first `Capabilities` says 0°. The re-send was keyed
only on the angle and was guarded on camera facts that were not populated yet,
so it bailed out early and never ran again: the controller believed the picture
was upright and drew it on its side.

It worked when first tested and broke later, because which of two asynchronous
events landed first was down to timing. Keying the re-send on **both** the angle
and the facts fixes it.

The lesson is not about rotation. Anything derived from an async callback must
not be captured once at setup time and assumed final.

## 11 · The preview lagged because we flooded a Bluetooth link

The reported symptom was that the picture ran behind reality and stayed there.
Two plausible explanations were wrong, and the instrumentation is what settled
it — worth saying, because both wrong guesses looked obviously right.

**Wrong guess 1: the keyframe interval.** Config bytes are only sent ahead of
keyframes, which are a second apart, so the decoder should have waited up to a
second for its first picture. Measured: the decoder configured in 251 ms having
dropped **zero** frames waiting. Not the cause, and `requestKeyframe()` would
have changed nothing.

**Wrong guess 2: the send buffer.** 512 KB is 1.4 s of video at 3 Mbps, so a
full buffer would put the picture exactly that far behind. Capping it at 64 KB
changed nothing — because the buffer was a symptom, not the cause.

**The actual cause**, from the encoder's own write timings:

```
12:35:14  stream starts
12:35:18  write blocked 180ms
12:35:29  write blocked 1001ms   (11 so far)
12:35:38  write blocked 1001ms   (21 so far)
12:35:49  onBandwidthChanged(HIGH)   <- 35 seconds in
```

**Nearby runs every connection over Bluetooth first** and upgrades to Wi-Fi when
it decides to. We began sending 720p at 3 Mbps from the first frame. Bluetooth
cannot carry that, so writes blocked for a full second at a time and the picture
fell behind and never recovered.

There is no public way to force the upgrade — `initiateBandwidthUpgrade` is not
on `ConnectionsClient`; Nearby decides, prompted by a stream payload appearing.
So the fix is to not send HD until the link can carry it: the stream starts at
360p and steps up to 720p when `onBandwidthChanged` reports HIGH.

### After the fix

```
blocked writes:            0   (was 31+ in 35s)
link reaches HIGH:       1.3s  (was 35s)
stream open -> picture:  155ms (was 501ms)
```

**The upgrade itself got ~27× faster**, which was not the point of the change.
The likely explanation is that saturating Bluetooth with 3 Mbps of video was
starving Nearby's own upgrade negotiation — the flood delayed the very thing
that would have ended it. That reading fits the evidence but has been seen once;
treat it as plausible rather than established.

### The lesson

Two careful, plausible diagnoses were both wrong, and each would have produced a
confident commit that fixed nothing. The thing that found the cause was logging
how long a write blocked — a direct measurement of the suspected mechanism
rather than of its consequences.
