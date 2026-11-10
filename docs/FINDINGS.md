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
| 8 | Stream stops when the screen goes off | The system takes the camera when the device locks | **Recovery on wake fixed**; streaming while locked still unsupported |
| 9 | "Trouble looking for phones: 8002" shown as an error | Reconnect re-calls `discover()`; Nearby answers ALREADY_DISCOVERING | **Fixed** |
| 10 | Controller shows the picture rotated | Orientation sent before CameraX had reported it | **Fixed** |
| 20 | The whole app reduced to a Bluetooth keystroke | Nothing needed on the camera phone; 13 permissions down to 4; works with no network |
| 18 | Wi-Fi Direct never chosen; DISRUPTIVE did not help | The Samsung does not offer it, and Nearby will not upgrade off a LAN it already calls "high quality" |
| 19 | Zoom slider silently missing | A placeholder Capabilities overwrote the real one once faster binding flipped the ordering |
| 16 | Live preview removed; shoot-and-review instead | A congested router put the picture 1–2s behind, which no app-side fix can reach |
| 17 | Remote captures could come back sideways | The rig screen never set `targetRotation`; the preview had been masking it |
| 11 | Preview lags behind reality | HD pushed down a Bluetooth link before Nearby upgrades to Wi-Fi | **Fixed** |
| 12 | Gives up reconnecting after ~25s, and stays given-up when the phone returns | Retry budget counted attempts, not time; no rejoin on rediscovery | **Fixed** |
| 13 | Feed juddery — 2.9 fps on an idle Wi-Fi link | Auto-exposure lengthened exposures; the camera was never asked for a frame rate | **Fixed** — 28.6 fps |
| 14 | Picture ~2s behind reality, and growing | Frame reader was unbuffered; byte-at-a-time resync could not drain the stream | **Fixed** — lag now ±60ms |
| 15 | Which link gets used, and what it costs | Nearby picks the medium; the router path is the worst of them | **Measured** |

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

## 12 · Reconnection gave up before anyone could walk back

Two faults, found by cutting the controller's Wi-Fi and watching both phones.

**The retry budget was a time budget in disguise.** Five attempts with the
backoff capped at 8s is about 25 seconds of wall clock, and the controller was
already at "attempt 4 of 5" twenty-five seconds in. Nobody walks out of a room
and back in that time. The attempt count was never the meaningful number. It is
now 20 attempts — around two and a half minutes — and the test asserts the
**window in seconds** rather than the count, so tuning the backoff later cannot
silently shrink it again.

**It stayed given-up while the phone sat in front of it.** After the link
returned, the controller had rediscovered the camera and was showing a Connect
button. Someone had to tap it — while standing across the room, in the shot,
unable to reach either screen. That is exactly the situation the app exists for.
A phone already approved in this session is now rejoined automatically.

That second fix means a reconnection does not ask for the four-digit code
again, which is a deliberate relaxation and is written up in SECURITY.md rather
than left implicit. The code stops a stranger connecting to an unattended
phone; it does not serve that purpose when demanded from the person already in
the photo.

### Still open

After the rejoin the control round trip read 881 ms, then 554 ms, against 183 ms
before the cut. Probably Wi-Fi settling, but it was not watched long enough to
be sure. If latency stays high after every reconnect, something is not being
rebuilt cleanly.

## 13 · The camera was behaving like a stills camera, not a webcam

Reported as "the feed is not clean and smooth as on a real camera — it should
be fast since it is on Wi-Fi". The instinct was right and led straight to it.

Measured: **2.9 fps** at 720p, with **zero** blocked writes and the link
reported HIGH. 2.9 fps at 720p is about 50 KB/s; Wi-Fi Direct carries tens of
megabytes a second. We were using roughly a thousandth of the link. The
transport was never the constraint — there was nothing to send.

Nobody had told the camera what frame rate to produce. Camera2's auto-exposure
optimises for a good-looking photograph: in a dim room it lengthens the
exposure, and a 300 ms exposure cannot yield more than about three frames a
second however fast the network is.

Video-call apps look grainy in poor light precisely because they refuse this
trade — they pin the frame rate, which caps exposure, so the picture gets
darker and noisier rather than slower. For a viewfinder someone is framing a
shot with, smooth and dim beats bright and stuttering.

The range is read from `CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES` and the one
with the highest **lower** bound is chosen. Both parts matter: asking for a
range the device does not advertise makes binding fail outright, and a range
like `[15, 30]` still lets auto-exposure sink back to 15 — only the floor holds
it up. The OnePlus accepted `[30, 30]`.

CameraX 1.6.2 has no `setTargetFrameRate` on `Preview.Builder`, so this goes
through Camera2 interop.

```
before:  2.9 fps
after:  28.6 fps   (949 frames over 33.2s, against real elapsed time)
```

### Two corrections this produced

The first measurement read 38.6 fps — above the pinned 30, so it could not be
true. The sampling interval was longer than the `sleep` it was divided by,
because collecting each sample takes a couple of seconds. Re-measured against
wall-clock timestamps.

And the `write blocked 1001ms` lines from FINDINGS §11 were suspiciously
*exactly* 1000 ms: that is the wait quantum inside Java's `PipedInputStream`,
not a bandwidth measurement. The Bluetooth conclusion there still stands on its
own evidence, but that particular number was over-read as if it measured the
link.

### Still open

The control round trip occasionally spikes — 1597 ms and 1118 ms were seen
among otherwise 90-170 ms readings. Not understood. Worth watching during the
endurance run.

## 14 · The picture fell further behind every second

Reported as "I waved my hand and it appeared two seconds later on the
controller". This is the one measurement nothing in the app had ever taken:
frame counters, round trips and throughput all read healthy throughout.

### Ruling things out

A probe on how many bytes sat **unread** showed only 120-170 ms of queued video
— not two seconds. Adding the sender's pipe, the camera and the decoder
accounted for roughly half a second. The conclusion drawn was that the rest
must be inside Nearby's transport, and the fix would be to abandon stream
payloads for discrete messages. **That was wrong**, and it would have been
major surgery.

### Building the instrument instead

Frames now carry the sender's wall clock, and `Pong` carries the responder's,
so the two clocks can be aligned and a frame's true age measured on arrival.

It reported **minus five seconds** on the first run. A negative latency is
impossible, which is the useful kind of wrong: the offset is how far the
sender's clock is *ahead*, so it must be **subtracted** when converting their
timestamp into ours. Adding it doubled the error rather than cancelling it. A
smaller sign error would have produced a plausible, wrong number and sent the
next fix in the wrong direction — so the offset is now logged beside every lag
reading, where a bad correction cannot hide inside the answer.

### What it actually showed

```
lag  440ms   ->  693ms  ->  783ms  ->  1239ms      climbing
backlog 20850 -> 60180 -> 47149 -> 63065 bytes     climbing
```

Latency that **grows** is not a buffer sitting in the path — it is the consumer
running slower than the producer. Nothing was stuck in Nearby; we simply could
not read fast enough.

`FrameCodec.read` resynchronises by reading **one byte at a time**, then pulls
the header in small chunks. Against an unbuffered transport stream that is
several syscalls per frame, thirty times a second, on the slower phone in the
pair. Wrapping it in a `BufferedInputStream` fixed it.

```
after:  lag -16ms to +61ms, stable;  backlog mostly 0
        clock offset steady within 30ms (was swinging ~700ms)
```

### Two things added alongside, and what is honest about them

**Late pictures are now discarded rather than displayed.** Every frame is still
decoded, because the decoder needs them all for reference, but one that is
already late is released without rendering. This bounds latency whatever the
underlying cause. It **never activated during this test** — the lag never
reached its 400 ms threshold — so it is written but unexercised.

**The clock offset is taken only from the fastest round trip seen.** The
midpoint assumption holds only on an uncongested link; sampling every ping made
the offset swing ~700 ms, noise larger than the measurement.

### What this number does and does not cover

It measures **encoder output to decoder render**. It excludes camera exposure
and capture (~33 ms at 30 fps) and the display pipeline (~16-33 ms), so true
glass-to-glass is perhaps 80-120 ms. Readings of −16 ms are not time travel;
they are the residual error in the clock estimate, which bounds the resolution
at roughly ±50 ms.

### The lesson, again

Three diagnoses were wrong before this one, and the third was wrong *after* a
real measurement — the backlog probe was accurate and the inference drawn from
it was not. What settled it was measuring the quantity actually complained
about, rather than quantities nearby.

## 15 · Bluetooth at 360p beats the home router at 720p

The app's main scenario — two phones in a park, no network — had never been
tested. It was worth testing, and it overturned an assumption.

With **Wi-Fi switched off on both phones**:

```
medium:          ENCRYPTED_BLUETOOTH   (no Wi-Fi Direct at all)
bandwidth:       MEDIUM -> the app held at 640x360
latency:         27, 87, 28, 43, 51, 76 ms
blocked writes:  0
```

Steadier and far lower-latency than the same app over a busy home Wi-Fi at
720p, which had been spiking past a second. The picture is softer; it is
current and smooth, which is what a viewfinder is for.

### What each link actually gives you

| Link | How it is reached | Measured |
|---|---|---|
| `ENCRYPTED_WIFI_LAN` | both phones on the same Wi-Fi network | 720p, but latency spikes past 1s on a busy router |
| `ENCRYPTED_BLUETOOTH` | Wi-Fi switched off | 360p, latency 27-87ms, rock steady |
| `WIFI_DIRECT` | **not observed yet** | untested |

The ordering is the surprise. Sharing a router with a household is worse than
having Bluetooth to yourself, and the app cannot choose — Nearby picks, and it
prefers the LAN when both phones are on one.

Wi-Fi Direct was never selected in any test. With Wi-Fi off it is unavailable;
with Wi-Fi on and both phones associated, Nearby took the LAN. The untested
case is **Wi-Fi radio on with no network joined**, which is the real outdoor
condition and could not be simulated here: neither phone offers a way to
disconnect from a network without forgetting it.

### What this changed

The "picture is rough for a moment" banner promised that it would "sharpen when
Wi-Fi takes over". With Wi-Fi off that is a promise the app cannot keep. It now
says so, and says the useful thing instead: the radio has to be **on**, but it
does **not** need a network to join — which is not obvious, and is the
difference between the app working well outdoors and being written off.

### Still unverified

That advice rests on Wi-Fi Direct being chosen when the radio is on and
unassociated. It has not been observed once. Until someone tries it in a field,
it is reasoning, not a result.


## 16 · The live preview was removed, and the shutter got better

Every streaming fix in this document worked. The frame rate went from 2.9 to
28.6 fps, the latency from two seconds and growing to about sixty milliseconds,
and the feed degraded instead of freezing when the link collapsed. On a quiet
link the viewfinder was good.

It was removed anyway, because on a busy home router it was not good, and a
viewfinder that is one to two seconds behind is worse than no viewfinder: you
frame against a moment that has already passed while believing you are looking
at the present. Nothing in the app's control could fix that — the congestion
was in the router, and Nearby chose `WIFI_LAN` rather than a direct link every
single time it was observed.

### What replaced it

Press the shutter, wait a moment, look at the photo, shoot again if it is
wrong. Measured on the two test phones:

| | |
|---|---|
| Shutter press to the camera acting on it | **19 ms and 165 ms** on two clean runs |
| Camera phone: message received to `takePicture` | **18 ms** |
| Capture itself (`MAXIMIZE_QUALITY`) | about 600 ms |
| A 3 MB photo back over Wi-Fi | about 3 s |
| Whole loop, tap to photo on screen | about 7 s |

### The measurement that was wrong first

The first readings said 2.8 to 4.4 seconds of shutter lag, and that was an
artifact, not the app. The tap was timestamped on the Mac and the capture was
read from the camera phone's logcat — and **that phone's clock runs 2.59
seconds ahead of the Mac's**. Subtract the skew and the numbers collapse to a
few hundred milliseconds, which matches the ping latency and the in-app
instrumentation that was added to settle it.

This is the second time in this project that a cross-device clock has produced
a confident wrong number — the first produced a latency of minus five seconds,
which at least announced itself. A plausible wrong number is more dangerous
than an impossible one. Any figure that spans two devices now gets its skew
measured before it is believed.

### What it cost and what it bought

Lost: seeing the shot before taking it. Framing is now iterative, and the first
frame of a new setup is a guess.

Gained: the camera phone holds the camera idle between shots instead of
encoding H.264 continuously, so it stays cool and keeps its battery; there is
no quality ladder, no congestion control and no decoder to go wrong; and the
picture you judge is the actual photo rather than a lossy preview of it.

Deleted with it: `PreviewEncoder`, `VideoDecoder`, `FrameCodec`/`VideoFrame`,
the Nearby stream payload, the quality ladder and the controller's adaptive
loop — about 1,100 lines.

## 17 · With no live view, capture rotation stops being cosmetic

The camera phone's activity is locked to portrait, so CameraX tags every
capture as portrait however the phone is actually being held. The standalone
camera screen already corrected this with an `OrientationEventListener` feeding
`imageCapture.targetRotation`; the remote rig screen never did, because the
live preview carried its own rotation angle and nobody noticed the still was
relying on a different path.

With the preview gone the returned photo is the only output, so a sideways one
is not a blemish — it is the entire result being wrong. The listener is now on
both screens. Confirmed on hardware: the OnePlus lying on its side on a desk
returned an upright landscape photo.


## 18 · Why Wi-Fi Direct was never chosen, and why asking harder did not help

Open since the first two-phone test: Nearby always picked `WIFI_LAN` — both
phones through the router — and Wi-Fi Direct was never observed. Two causes,
found by reading what Nearby logs about its own decision.

**The Samsung does not offer Wi-Fi Direct to Nearby.** The negotiated medium
set is the intersection of what each phone offers:

```
their supported mediums ([WIFI_LAN, WIFI_DIRECT, WIFI_HOTSPOT, BLE_L2CAP, BLUETOOTH, BLE])
my    supported mediums ([WIFI_LAN,               WIFI_HOTSPOT, BLUETOOTH, BLE, NFC])
decided on              ([WIFI_LAN,               WIFI_HOTSPOT, BLUETOOTH, BLE])
```

The OnePlus offers `WIFI_DIRECT`; the Samsung M04 does not, so it drops out.
`WIFI_HOTSPOT` survives and is the fast direct path available to this pair.

**Nearby will not upgrade off the LAN, because it already calls the LAN fast:**

```
BandwidthUpgradeManager skipped retry upgrade [High quality medium WIFI_LAN]
```

That is the heart of it. Nearby judges a medium by its *type*, not by its
measured throughput. A congested home router is `WIFI_LAN`, `WIFI_LAN` is a
high-quality medium, so there is nothing to upgrade to — no matter how slow the
transfer actually is.

### Asking for a disruptive link

`ConnectionType.DISRUPTIVE` plus `setDisruptiveUpgrade(true)` and
`setLowPower(false)` were set on both the advertising and the connection
options. This is the switch that lets Nearby drop the phone's current Wi-Fi
association and stand up a direct hotspot — what Quick Share does for a large
file, on this same API.

**It changed nothing while both phones were on the same Wi-Fi network**, for
the reason above: no upgrade is attempted at all when the current medium is
already classed as high quality. `WIFI_HOTSPOT` and `WIFI_DIRECT` began
appearing in the logs where they never had, so the request is being considered
— it is simply never acted on.

The flags are kept, because the case they exist for is the one that matters
outdoors: phones on different networks, or on none, where there is no
"high quality" medium already in hand.

### What is actually left

A 3.2 MB photo took about 13 seconds over a congested `WIFI_LAN` — roughly
250 KB/s, poor for Wi-Fi and consistent with the router being the bottleneck.
Nothing in Nearby's own API appears able to override its medium choice while a
shared network exists. The remaining levers are to send fewer bytes, or to stop
using Nearby for bulk transfer and open a socket of our own.

## 19 · A placeholder overwrote the real camera capabilities

The zoom slider vanished. `Capabilities` carries the far camera's zoom range,
and the controller hides the slider when `maxZoom == minZoom`.

Two different places sent `Capabilities`. The camera rig screen sent the real
figures once the camera was bound; `Pairing.describeThisCamera()` answered the
controller's `Hello` with a hardcoded `minZoom = 1f, maxZoom = 1f` placeholder,
on the assumption that the real ones would arrive later and replace it.

That assumption was load-bearing and undocumented, and **removing the preview
encoder broke it**. Binding used to take long enough that the real figures
always came second. Without an encoder to build, the camera bound almost
immediately and often sent the truth *before* the controller's Hello arrived —
so the placeholder landed last and won.

The fix is for the camera to remember what it published and answer Hello with
that, falling back to the placeholder only before the camera is bound. Worth
noting the shape of this bug: nothing failed, no error was logged, and the only
symptom was a control quietly missing. A deleted feature changed the timing of
an unrelated one.


## 20 · The app that was left when everything that could fail was removed

ShutterLink is now a Bluetooth keyboard with one key. Nothing is installed on
the camera phone at all.

The path here was four deletions, each forced by a measurement rather than a
preference:

1. **The live viewfinder** (FINDINGS 16). Worked at 28.6 fps and 60 ms on a
   quiet link; one to two seconds behind on a congested router, which no
   app-side fix could reach.
2. **Our own camera.** Once the preview was gone, operating CameraX bought
   nothing a third-party camera app did not do better, and actively prevented
   that app from opening the camera.
3. **The photo transfer.** 3 MB over a congested LAN took 13 seconds, and over
   Bluetooth outdoors it would be far worse. Walking over to look at the other
   phone costs nothing and always works.
4. **Nearby Connections itself.** With no photo to carry and no preview to
   stream, the only thing left to send was a keystroke — and a keystroke does
   not need a data link, a discovery protocol, Play Services, or a pairing
   handshake of our own. It needs a Bluetooth bond, which the platform already
   knows how to make.

### What that removed

| | Before | Now |
|---|---|---|
| Permissions | 13 | 4, all Bluetooth |
| Camera, location, storage, Wi-Fi permissions | all held | none |
| Google Play Services | required | not used |
| App needed on the camera phone | yes | **no** |
| Works with no network | poorly | yes, by construction |

### The thing worth remembering

Every deleted piece worked. The preview really did run at 28.6 fps; the photo
transfer really did deliver the file; the capability handshake really did carry
the zoom range. Each was correct and each was load-bearing for a design that
could not survive a busy router.

The failure was never in the code. It was in choosing a design whose critical
path ran over a medium we did not control, and then defending it with
increasingly clever machinery — a quality ladder, congestion control, clock
alignment, frame shedding — all of which were good engineering aimed at the
wrong problem. The question that eventually fixed it was not "how do we make
this faster" but "what is the smallest thing that does the job", and the answer
had been sitting in the design all along as a secondary feature: a selfie-stick
keystroke.

### Measured, on the two test phones

| | |
|---|---|
| Pairing | Found and bonded from the OnePlus's own Bluetooth settings, first try |
| HID host attaches | `connection state=2, connected=true` |
| Tap to photo on disk | 1.3 s, 2.4 s, 1.3 s over three shots |
| Keys the OnePlus stock camera honours | Volume up, volume down, camera, enter, D-pad centre |
| Keys it ignores | Headset hook, focus |
