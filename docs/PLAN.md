# ShutterLink — Implementation Plan

**Two Android phones. One is the camera, one is the viewfinder + shutter.**
The Apple Watch Camera Remote concept, rebuilt for Android.

| | |
|---|---|
| **Status** | Plan — nothing built yet |
| **Date** | 2026-11-09 |
| **Author** | Akshay |
| **Min Android** | 10 (API 29) |
| **Transport** | BLE discovery + Wi-Fi Direct data (decided) |

---

## 1. What we verified on real hardware

Tested against the phone currently attached over adb — a **OnePlus 7 (GM1901), Android 10 / API 29**,
which is exactly the floor of the supported range, so these are worst-case results:

| Check | Result | Why it matters |
|---|---|---|
| `android.hardware.wifi.direct` | **Present** | Wi-Fi Direct is available on the floor device. |
| `android.hardware.wifi.aware` | **Absent** | Wi-Fi Aware (NAN) is out. Confirms Wi-Fi Direct is the right choice. |
| `android.hardware.bluetooth_le` | **Present** | BLE advertise/scan for discovery is fine. |
| Hardware H.264 encoder | **Present** (`c2.qti.avc.encoder`, `OMX.qcom.video.encoder.avc`) | We can encode a real 720p video stream in hardware, not ship JPEGs. This is what makes "looks like a real camera" achievable. |
| Camera level | `camera.level.full`, front + back, flash, manual sensor | CameraX has everything it needs, including zoom control. |
| Google Play Services | **26.43.30 installed** | Nearby Connections API is usable (see §3). |

**Toolchain on this Mac:** JDK 21 (Temurin), Gradle 9.8.2, Android SDK via Homebrew
command-line tools — platform **android-34** and build-tools **34.0.0** only, plus an
android-34 arm64 emulator image.

Two gaps to close before Phase 0:
- **SDK 36 is not installed.** We target Android 16 (API 36); needs `sdkmanager "platforms;android-36" "build-tools;36.0.0"`.
- **Only one physical phone is attached.** The emulator cannot do Wi-Fi Direct, real BLE, or a real
  camera — so Phase 1 is testable today, and **Phases 2 onward need your second phone plugged in**
  (or on the same Wi-Fi via `adb connect`).

---

## 2. Decisions locked in

1. **BLE for discovery and control, Wi-Fi Direct for the video.** Confirmed viable on the floor device.
2. **Preview quality: real-camera feel.** 720p, 24–30 fps, hardware H.264. Not a JPEG slideshow.
3. **Zoom is in v1**, not deferred — you asked to see zoom while framing.
4. **Captured photo transfers back** to the controller at full resolution for review, so you can
   judge the shot and retake.
5. **One app, two roles.** A mode picker on launch (Camera / Controller), switchable without reinstall.
   Two separate apps would double the install friction for zero benefit.
6. **Kotlin + Jetpack Compose + CameraX.** minSdk 29, targetSdk 36, compileSdk 36.

---

## 3. The one recommendation I want your call on

You chose "BLE + Wi-Fi Direct", which is right. There are two ways to *implement* that:

**Option A — Google Nearby Connections API (my recommendation).**
It is BLE + Wi-Fi Direct, already assembled. Using `P2P_POINT_TO_POINT` strategy it advertises over
BLE, discovers over BLE, then automatically upgrades the connection to Wi-Fi Direct (or a Wi-Fi
hotspot) for bandwidth. It gives us three payload types out of the box: BYTES (control messages),
STREAM (the video feed), and FILE (the captured photo transferring back) — which maps exactly onto
our three needs. Play Services 26.43.30 is on the test device.
*Cost:* requires Google Play Services, so no de-Googled ROMs.

**Option B — raw `BluetoothLeAdvertiser`/`BluetoothLeScanner` + `WifiP2pManager` + TCP sockets.**
Full control, no Play Services dependency, works on any Android 10+ device. But we hand-write peer
discovery, group negotiation, the socket handshake, reconnection, and the transport upgrade — and
`WifiP2pManager` is notoriously fiddly across OEM skins (OnePlus/Xiaomi/Samsung all behave differently).
Realistically adds 2–3 weeks and most of the eventual bug reports.

**Recommendation: build Phase 2 on Option A, behind a `Transport` interface** so Option B can be
dropped in later as a fallback without touching the camera, UI, or streaming code. We get a working
app far sooner and keep the escape hatch. This is listed as a v2 item (T-7).

---

## 4. Architecture

```
┌─────────────── CAMERA PHONE ───────────────┐      ┌──────────── CONTROLLER PHONE ───────────┐
│                                            │      │                                         │
│  CameraX                                   │      │              Compose viewfinder UI      │
│   ├─ Preview ──────────► local surface     │      │                      ▲                  │
│   ├─ ImageAnalysis ──► YUV frames          │      │                      │                  │
│   │        │                               │      │              MediaCodec DECODER         │
│   │        ▼                               │      │                      ▲                  │
│   │   MediaCodec ENCODER (H.264 HW)        │      │                      │                  │
│   │        │                               │      │                      │                  │
│   │        ▼                               │      │                      │                  │
│   │   ┌─────────── Transport ──────────────┼──────┼──────────────────────┘                  │
│   │   │  STREAM payload  : H.264 NAL units │      │                                         │
│   │   │  BYTES payload   : control msgs ◄──┼──────┼───── shutter / zoom / flip / timer       │
│   │   │  FILE payload    : captured JPEG ──┼──────┼────► review screen (keep / retake)       │
│   │   └── BLE discovery → Wi-Fi Direct ────┼──────┼──┐                                       │
│   │                                        │      │  │                                       │
│   └─ ImageCapture ──► full-res JPEG ───────┘      └──┘                                       │
└────────────────────────────────────────────┘      └─────────────────────────────────────────┘
```

### Module layout

```
shutterlink/
├── app/                    # Android app: UI, camera, transport impl, permissions
│   └── .../camera, controller, transport, ui
└── protocol/               # PLAIN JVM — message schema, serialization, version negotiation
```

Two Gradle modules, not six. `protocol/` is a plain JVM module with **no Android dependency**,
enforced by CI, so the whole message layer is testable on a desktop in seconds rather than needing
two phones. Everything else lives in `app/` as packages until a second consumer actually justifies
a module — matching the sibling [android-tv-remote](https://github.com/amrakshay/android-tv-remote)
repository, where the same split earned its keep.

```
```

### Protocol sketch (`protocol/`)

Length-prefixed, versioned. Kotlin sealed classes + `kotlinx.serialization` (CBOR for compactness).

| Direction | Message | Payload |
|---|---|---|
| C→Cam | `Hello` | protocol version, device name |
| Cam→C | `Capabilities` | camera list, zoom range, resolutions, flash availability |
| C→Cam | `StartPreview` / `StopPreview` | resolution, target fps, bitrate |
| C→Cam | `SetZoom` | linearZoom 0.0–1.0 |
| C→Cam | `SwitchCamera` | front / back |
| C→Cam | `Capture` | timer seconds (0 / 3 / 10) |
| Cam→C | `CaptureCountdown` | seconds remaining |
| Cam→C | `CaptureStarted` / `CaptureFailed` | reason |
| Cam→C | `PhotoReady` | payloadId, filename, size, dimensions |
| Cam→C | `Status` | battery %, thermal state, storage free |

Version negotiation in `Hello`/`Capabilities` from day one — the two phones will inevitably run
different app versions.

---

## 5. Phases

Each phase ends in something you can actually run on a phone.

### Phase 0 — Foundations *(~1 day)*
- Install missing SDK: `sdkmanager "platforms;android-36" "build-tools;36.0.0"`.
- Gradle project, version catalog, Compose + CameraX + Nearby dependencies.
- Module skeletons per §4.
- GitHub repo + Actions workflow running `./gradlew assembleDebug lint test` on push.
- `scripts/dev.sh` — build, install to both devices by serial, tail filtered logcat.
- **Done when:** empty app installs and launches on the OnePlus 7.

### Phase 1 — Standalone camera — **done** *(see §12)*
- Role picker screen (Camera / Controller), remembered in DataStore.
- CameraX `Preview` + `ImageCapture`, lifecycle-bound.
- Front/back switch, pinch-to-zoom + zoom slider, 0/3/10s self-timer.
- Runtime permissions: CAMERA, and the storage path that differs across API 29→36.
- Saves to MediaStore, visible in Gallery.
- **Done when:** the app is a usable standalone camera. Everything after this adds remoteness.

### Phase 2 — Transport — **done, verified on two phones**
- `Transport` interface: `advertise()`, `discover()`, `connect()`, `send()`, `onReceive`, `onStateChange`.
- `NearbyTransport` on `P2P_POINT_TO_POINT`.
- Permission matrix handled properly — this is the biggest source of silent failure:
  - API 29–30: `ACCESS_FINE_LOCATION` required for BLE scan **and** Wi-Fi Direct discovery.
  - API 31+: `BLUETOOTH_SCAN`, `BLUETOOTH_ADVERTISE`, `BLUETOOTH_CONNECT`.
  - API 33+: `NEARBY_WIFI_DEVICES` (with `neverForLocation` where honest).
- Pairing UI: camera phone advertises a friendly name, controller lists peers, tap to connect,
  4-digit confirmation code on both screens.
- `Hello`/`Capabilities` handshake; connection state surfaced in the UI.
- **Done when:** a control message round-trips between phones and latency is logged.

### Phase 3 — Live preview — **working on two phones** (latency measured as round trip, not yet glass-to-glass)
- Camera side: `ImageAnalysis` (YUV_420_888) → `MediaCodec` H.264 encoder in Surface mode
  (feed the encoder's input Surface directly from CameraX — avoids a YUV copy per frame).
- Config: 1280×720, 24 fps, ~3 Mbps VBR, 2s keyframe interval, baseline profile for compatibility.
- Framing: length-prefixed NAL units over the STREAM payload; SPS/PPS sent on connect and re-sent
  on every keyframe so a late-joining controller can start decoding.
- Controller side: `MediaCodec` decoder → Compose `AndroidView(SurfaceView)`.
- Adaptive bitrate: drop toward 480p/15fps when the transport reports backpressure.
- On-screen debug overlay (toggleable): fps, bitrate, end-to-end latency, dropped frames.
- **Done when:** you can hold both phones, wave at the camera phone, and see it on the controller
  with latency low enough to frame a shot. **Target: under 200 ms glass-to-glass.** If we can't get
  under ~400 ms, I'll tell you plainly and we'll discuss trade-offs rather than quietly shipping lag.

### Phase 4 — Remote control — **working on two phones**
- Shutter, zoom (slider + pinch on the controller's viewfinder), camera flip, timer — all driven
  from the controller via `BYTES` messages.
- Countdown mirrored on both screens; the camera phone beeps/flashes so people being photographed
  know when it fires.
- Preview stream pauses cleanly during capture and resumes.
- **Done when:** the controller is a complete remote. The camera phone is never touched.

### Phase 5 — Photo comes back — **working on two phones**
- `ImageCapture` writes full-res JPEG on the camera phone (always kept there — source of truth).
- Transferred as a Nearby `FILE` payload with progress shown on the controller.
- Review screen on the controller: pinch-zoom the result, Keep / Delete / Retake.
- Delete sends a message back so the camera phone removes it too.
- Session filmstrip of everything shot this session.
- **Done when:** full loop works — frame, shoot, review, retake.

### Phase 6 — Make it survive real use — **written, unverified**
- Reconnect automatically after a drop; preview resumes without losing the session.
- Camera phone keeps running with screen off / app backgrounded → foreground service with a
  persistent notification (this is required, and also honest to the user).
- Thermal + battery monitoring; degrade resolution before the phone throttles itself.
- "Camera phone is at 12% battery" warnings surfaced on the controller.
- Graceful failure messages for every denied permission, instead of a blank screen.
- Instrumented tests for the protocol and transport state machine.
- **Done when:** a 20-minute shoot doesn't require restarting anything.

### Phase 7 — Polish and ship — **partly done**
- Visual design pass, app icon, onboarding explaining the two-phone setup.
- README with screenshots + GIF.
- Signed release build; GitHub Actions release workflow.
- Optional: Play Store listing (needs a privacy policy — worth noting the app sends no data anywhere,
  which is a genuine selling point).

**Rough v1 total: 5–6 weeks of part-time evenings; ~3 weeks focused.**

---

## 6. v2 and beyond

Ordered by what I think you'll actually want first.

| ID | Feature | Notes |
|---|---|---|
| **T-1** | **Video recording** | Record on the camera phone, start/stop and live duration from the controller. Biggest obvious gap after v1. |
| **T-2** | **Tap-to-focus and exposure** | Tap the controller's viewfinder → focus point sent to the camera phone. Exposure compensation slider. |
| **T-3** | **Flash / torch control** | Simple once the control channel exists; left out of v1 only to keep Phase 4 small. |
| **T-4** | **Composition overlays** | Rule-of-thirds grid, horizon level using the camera phone's accelerometer, face-detection boxes. |
| **T-5** | **Burst and bracketing** | Burst of N shots; the controller shows them as a strip to pick from. |
| **T-6** | **Multi-camera** | Three or more phones — one controller, several cameras, switch the preview feed between them. Nearby's `P2P_STAR` strategy supports this; the protocol already carries device identity. |
| **T-7** | **Raw BLE + Wi-Fi Direct transport** | The Option B fallback from §3 — removes the Google Play Services dependency. Drops in behind the existing `Transport` interface. |
| **T-8** | **Wear OS controller** | A watch app as a second controller. Closes the loop on the original inspiration. |
| **T-9** | **Manual controls** | ISO, shutter speed, white balance, focus distance. The test device reports `camera.capability.manual_sensor`, so this is real. |
| **T-10** | **RAW / DNG capture** | Device reports `camera.capability.raw`. Large files — needs transfer-on-demand rather than automatic. |
| **T-11** | **Remote gallery** | Browse and pull anything on the camera phone, not just this session's shots. |
| **T-12** | **QR pairing** | Camera phone shows a QR code, controller scans it — faster than list-and-tap. |
| **T-13** | **Audio monitoring** | Hear what the camera phone's mic picks up. Mostly useful once T-1 lands. |

---

## 7. Risks, honestly

| Risk | Severity | Mitigation |
|---|---|---|
| **Wi-Fi Direct behaves differently per OEM** | High | Nearby Connections absorbs most of this; test on both your actual phones early (Phase 2, not Phase 6). |
| **Latency too high to frame a shot** | High | Phase 3 has an explicit measured target. We measure before we build Phase 4 on top of it. |
| **Permission maze across API 29→36** | Medium | Treated as first-class work in Phase 2, not an afterthought. Android 10 and Android 14+ need genuinely different flows. |
| **Camera phone overheats on long sessions** | Medium | Phase 6 thermal handling; 720p rather than 1080p by default. |
| **Nearby requires Play Services** | Low | Accepted for v1; T-7 removes it. |
| **I cannot test this end to end** | Certain | Only one phone is attached and emulators can't do Wi-Fi Direct, BLE, or a real camera. Phase 1 I can verify; Phases 2+ need both your phones connected, and I'll write the test scripts to make that fast. |

---

## 8. Immediate next steps

**Phase 0 is done** — see §12 for what was built and verified.

1. ~~Create the GitHub repo~~ — done: `amrakshay/shutterlink`, package `dev.amrakshay.shutterlink`.
2. ~~Install the missing SDK pieces~~ — done: `platforms;android-37.2`, `build-tools;37.0.0`.
3. ~~Create the release signing key~~ — done: 4096-bit RSA, `CN=shutterlink`, valid to 2054.
   In the login Keychain under account `shutterlink`, and in the repository's Actions secrets as
   `SHUTTERLINK_KEYSTORE_BASE64`, `SHUTTERLINK_KEYSTORE_PASSWORD`, `SHUTTERLINK_KEY_PASSWORD`.
4. **Connect the second phone** over USB or `adb connect`. Phase 2 cannot be tested without it.
5. Phase 1 — the standalone camera — is next, and is fully testable on the OnePlus 7 alone.

---

## 9. Can the user use a third-party camera app? (e.g. the OEM camera, GCam)

**Short answer: no, not in v1 — and Android gives us no clean way to ever do it properly.**

Android has no public API that lets one app read another app's viewfinder, drive its zoom, or press
its shutter. The camera hardware is held exclusively by whichever app has it open. So ShutterLink
has to *be* the camera app on the camera phone.

What *doesn't* work, and why:

| Approach | Why it fails |
|---|---|
| `ACTION_IMAGE_CAPTURE` intent into the OEM camera | Launches their app, but we get no preview stream and no way to trigger the shutter remotely. The whole point is lost. |
| Camera2 "shared session" | Not a thing. Exclusive access by design. |
| Reading their preview buffer | No API, and blocked by sandboxing. |

### The one path that could work — and its real cost

**MediaProjection (screen mirroring) + an AccessibilityService.** We mirror the camera phone's
entire *screen* to the controller — which would show the OEM camera app's viewfinder — and use an
accessibility service to inject a tap on its shutter button. The photo could then be picked up by
watching `DCIM/Camera` (confirmed present on the test device) and transferred back.

It's technically real, but it's a different and much worse product:

- **Accessibility services are heavily restricted on the Play Store.** Google requires a strong
  justification for the permission; "remote camera convenience" is likely to be rejected. You'd be
  distributing by APK.
- **The user must manually enable it in Settings → Accessibility**, which is a scary, multi-step flow.
- **It breaks whenever a camera app updates its UI.** We'd be tapping screen coordinates or hunting
  for view nodes in someone else's app. OnePlus, Samsung, and GCam all differ, and all change.
- **No zoom control, no capability handshake, no clean countdown** — we'd be puppeteering a UI, not
  controlling a camera.
- **Higher latency and worse quality**: we'd encode the whole screen including their UI chrome,
  instead of a clean camera feed.

### The real motivation behind the question — and the honest answer

The reason to want the OEM app is usually **image quality**: `com.oneplus.camera` applies tuning
that a plain CameraX app does not.

Normally the fix is **CameraX Extensions**, which expose the vendor's own HDR / Night / Bokeh
processing through a standard API. I checked for it on the attached device: **this OnePlus 7 on
Android 10 ships no vendor extension library** (nothing in `/system/framework` or `/vendor/lib64`).
So on *this* phone, extensions are not available and our image quality will be plain CameraX —
honestly, somewhat behind the stock OnePlus camera app in difficult light.

Newer phones (Android 12+, most Samsung/Pixel/Xiaomi flagships) *do* ship extensions, so the
situation improves on better hardware.

### Decision

- **v1: ShutterLink is the camera.** Own the pipeline end to end — it's the only way to deliver a
  real remote viewfinder with zoom and a reliable shutter.
- **v2 item T-14: CameraX Extensions support** — detect at runtime and enable HDR / Night / Bokeh
  where the device offers them. Closes most of the quality gap on phones that support it, with no
  architectural change.
- **Explicitly not planned: screen-mirroring mode.** Recorded here so the option isn't silently
  forgotten, but the fragility and Play Store risk make it a poor trade. Revisit only if you decide
  OEM image quality matters more than reliable control.

---

## 10. "Any camera app" mode — the Bluetooth selfie-stick trick

**Correction to §9.** Section 9 said third-party camera apps can't be triggered remotely. That's
true for *controlling* them, but it misses how commercial Bluetooth selfie remotes actually work —
and that method is open to us too.

### How selfie remotes really work

They don't talk to the camera app. They pair as a **Bluetooth HID keyboard** and send a single
keystroke — normally `VOLUME_UP` (sometimes `ENTER`). Virtually every camera app maps volume keys to
the shutter. The remote isn't reaching into the app's sandbox; it's injecting a key event at the
system input layer, which is routed to the foreground app by design. No special permission, no
accessibility service, no Play Store risk.

### We can do the same

Android 9+ exposes `BluetoothHidDevice`, letting a phone register itself as an HID keyboard and send
key reports to a paired host.

**Verified on the attached OnePlus 7 (Android 10):** `com.android.bluetooth/.hid.HidDeviceService`
is present in the Bluetooth stack, with `IBluetoothHidDevice` and the
`hiddevice.profile.action.CONNECTION_STATE_CHANGED` broadcast registered. The profile exists on the
floor device. (`HidHostService` is also running — the phone can act as both host and device.)

So the **controller phone** registers as an HID keyboard, pairs with the camera phone over classic
Bluetooth, and sends `VOLUME_UP` on shutter press. Whatever camera app is open — PicsArt, the OnePlus
camera, GCam — fires.

### What this mode can and cannot do

| | ShutterLink mode (§4) | Any-camera mode |
|---|---|---|
| Camera app on far phone | Ours | **Any third-party app** |
| Live preview | **Yes, 720p** | **No — blind shutter** |
| Remote zoom / flip / timer | Yes | No |
| Photo returns to controller | Yes (FILE payload) | Yes (via MediaStore watcher, see below) |
| Image quality | Plain CameraX | **The OEM/PicsArt app's own processing** |
| Transport | BLE + Wi-Fi Direct | Classic Bluetooth HID only |

The blind-shutter limitation is unavoidable: the third-party app holds the camera exclusively, so
nothing can tap its preview stream. This mode is a *better selfie stick*, not a remote viewfinder.

**Photo return still works.** Our app stays alive in the background on the camera phone with a
`ContentObserver` on `MediaStore.Images`. A new image appears → send it to the controller over the
existing Wi-Fi Direct link → user reviews it and decides whether to retake. Requires
`READ_MEDIA_IMAGES` (API 33+) / `READ_EXTERNAL_STORAGE` (API 29–32). `DCIM/Camera` confirmed present
on the test device.

### Risks specific to this mode

| Risk | Mitigation |
|---|---|
| OEM HID-device support is inconsistent — some skins stub out `BluetoothHidDevice` | Test on both target phones in Phase 2.5; detect at runtime and hide the mode where unsupported. |
| Some camera apps ignore volume keys | Send `VOLUME_UP`, fall back to `ENTER`, and offer a key-choice setting. Document which apps are confirmed working. |
| Classic Bluetooth HID pairing is a separate pairing from the Nearby connection | Pairing wizard walks the user through it once; remembered after. |
| Volume keys may change the ringer instead if no camera app is foreground | Only arm HID mode when the user explicitly enters it; warn that a camera app must be open on the far phone. |

### Decision

Build it as **Phase 2.5**, right after the transport layer, before the streaming work in Phase 3.
It's small (~2–3 days), independent of the video pipeline, and it delivers a complete, useful
feature early — a working selfie remote for any camera app — while the harder streaming work
continues.

### New backlog items

| ID | Item |
|---|---|
| **T-14** | CameraX Extensions (HDR / Night / Bokeh) where the device supports them — closes the quality gap that motivates any-camera mode. |
| **T-15** | Confirmed-apps list for any-camera mode: test PicsArt, OnePlus camera, GCam, Samsung, Open Camera; ship the results in-app. |
| **T-16** | Configurable key for HID mode (`VOLUME_UP` / `VOLUME_DOWN` / `ENTER`), for apps with unusual mappings. |

---

## 11. Hybrid capture — frame in ShutterLink, shoot with any app

**Supersedes the either/or framing of §10.** §10 treated "our camera" and "any camera app" as two
separate modes. They can be combined, and should be: the far phone runs **only our app**, so we hold
the camera and can stream a live preview — and the controller offers a *choice of capture engine*
at the moment of the shot.

### Flow

```
1. Far phone: our app only. CameraX preview → streamed to controller (Phase 3).
2. Controller: user frames the shot, adjusts zoom, picks the pose. Full live view.
3. Controller shows capture buttons, built from the far phone's installed apps:
      [ Capture with ShutterLink ]  [ OnePlus Camera ]  [ PicsArt ]
4a. ShutterLink  → Phase 4/5 path. Instant, keeps preview, best control.
4b. Third-party  → our app releases the camera, launches the chosen app,
                   HID VOLUME_UP fires the shutter, photo returns, our app resumes.
```

### Discovering the far phone's camera apps

`PackageManager.queryIntentActivities()` for two intents, sent to the controller in the
`Capabilities` handshake (§4):

- `android.media.action.STILL_IMAGE_CAMERA` — app can be launched as a camera.
- `android.media.action.IMAGE_CAPTURE` — app accepts launch-for-result and **hands the photo back
  directly**.

**Verified on the attached OnePlus 7:** `com.oneplus.camera` responds to **both** intents.
PicsArt is not installed on this device, so its behaviour is untested (T-15).

Needs a `<queries>` block in the manifest for API 30+ package visibility.

### Two capture paths, chosen per app at runtime

**Path A — clean (app supports `ACTION_IMAGE_CAPTURE`):**
`startActivityForResult` into the app → HID `VOLUME_UP` fires the shutter → the app returns the
image to us directly and control comes back to our app automatically.
No MediaStore watching, no overlay permission, no background-activity-start problem. Confirmed
available for `com.oneplus.camera`.

**Path B — fallback (app ignores `ACTION_IMAGE_CAPTURE`):**
Launch the app normally → HID `VOLUME_UP` → `ContentObserver` on `MediaStore.Images` catches the new
file → our app re-foregrounds itself.
Requires `SYSTEM_ALERT_WINDOW` ("Display over other apps"), which exempts the app from Android 10+
background-activity-start restrictions, and `PACKAGE_USAGE_STATS` to detect when the target app is
actually ready for the keystroke.

Detect per app, prefer A, fall back to B. Surface neither to the user beyond a one-time permission
prompt.

### Known limitations — state these in the UI, do not hide them

| Limitation | Detail | Mitigation |
|---|---|---|
| **Preview blacks out during third-party capture** | The other app holds the camera exclusively for ~2–4s. | The phone is stationary, so framing is preserved. Show "Shooting with PicsArt…" with a spinner rather than a frozen/black frame. |
| **Framing is approximate, not identical** | Different default aspect ratio (4:3 vs 16:9) and zoom resets on app switch. | Overlay crop guides on the controller preview showing the target app's framing. Remember per-app aspect ratio after first use. |
| **App launch latency** | 2–4s before the target app's camera is ready; firing the key early does nothing. | Use `PACKAGE_USAGE_STATS` to detect foreground state, then enable the shutter button on the controller. Never fire blind on a fixed timer. |
| **Not every app honours volume-key shutter** | Unverified for PicsArt. | T-15 confirmed-apps list; T-16 configurable key; hide untested apps behind an "experimental" label. |
| **Extra permissions for Path B** | `SYSTEM_ALERT_WINDOW`, `PACKAGE_USAGE_STATS` | Request only when the user first picks a Path B app, with a plain explanation. Path A apps need neither. |

### Revised phase placement

This replaces the standalone Phase 2.5 from §10:

- **Phase 2.5 — HID shutter foundation** — **written, unverified**: `BluetoothHidDevice` registration, pairing
  wizard, `VOLUME_UP` delivery. Testable standalone as a plain selfie remote.
- **Phase 5.5 — Hybrid capture** — **written, unverified**, after photo-return (Phase 5) exists: app discovery and
  `Capabilities` extension, the capture-engine picker UI, Path A, Path B, crop guides, and clean
  resume of the preview afterwards.

Placing it after Phase 5 means the photo-return plumbing already exists and is reused rather than
duplicated.

### New backlog items

| ID | Item |
|---|---|
| **T-17** | Per-app crop guides on the controller preview, learned from the first capture with each app. |
| **T-18** | Remember the user's preferred capture engine per shooting session. |

### 11.1 Where the photo ends up — guaranteeing exactly one gallery copy

`ACTION_IMAGE_CAPTURE` behaviour is inconsistent across camera apps and is a known source of
duplicate-or-missing photos:

- **Without `EXTRA_OUTPUT`** the app returns only a low-res thumbnail in the `data` extra. Useless
  for us — we always pass `EXTRA_OUTPUT`.
- **With `EXTRA_OUTPUT`** the app writes full resolution to our URI, but whether it *also* writes its
  own copy to `DCIM` is entirely app-dependent. Google Camera typically does not. OEM apps usually
  do. PicsArt is untested (T-15).

Naively pointing `EXTRA_OUTPUT` at a MediaStore `DCIM` entry therefore risks two gallery entries for
one shot.

**Algorithm (far phone), gives exactly one gallery copy for every app:**

1. Point `EXTRA_OUTPUT` at a private file in the app's own cache dir — *not* the gallery.
2. Register a `ContentObserver` on `MediaStore.Images` before launching the target app.
3. Fire the capture (HID `VOLUME_UP`) and wait for the activity result.
4. Window of ~3s after the result:
   - **A new `DCIM` entry appeared** → the app saved its own copy. Treat that as canonical (keeps the
     app's own naming, EXIF and processing). Transfer it to the controller. Delete our cache file.
   - **No new entry** → insert our cache file into `MediaStore.Images` ourselves, then transfer.
5. Either way: exactly one gallery entry on the far phone, and the far phone always retains the
   full-resolution original as the source of truth.

Path B (§11, no `ACTION_IMAGE_CAPTURE` support) needs none of this — the app always saves to the
gallery itself, and the `ContentObserver` is already the detection mechanism.

**Controller-side policy (default, user-overridable in settings):** received photos live inside the
app's review UI and are **not** written to the controller's gallery until the user taps Save.
Rationale: the purpose of sending the photo over is deciding whether to retake, so auto-saving would
fill the gallery with rejects. Setting: *"Automatically save received photos to gallery"* — off by
default.

| ID | Item |
|---|---|
| **T-19** | Duplicate-detection hardening: match on size + dimensions + capture timestamp, not just "a new row appeared", to survive apps that write slowly or write twice. |

---

## 12. What has actually been verified

> Phases 3–7 were written without a second phone. See [VERIFY.md](VERIFY.md) for the ordered
> list of what that leaves to check.

### Phase 0 — done

Built and verified on 2026-11-09.

### What exists

| | |
|---|---|
| Build | Gradle 9.8.2 (wrapper checked in), AGP 9.5.0, Kotlin 2.4.21, JDK 21 toolchain |
| SDK | `compileSdk 37.2`, `targetSdk 36`, `minSdk 29` |
| Modules | `:app` (Android), `:protocol` (plain JVM, Android-free, CI-enforced) |
| Version | `VERSION` file → `versionName`, and `versionCode` derived (`1.2.3` → `10203`) |
| CI | `.github/workflows/ci.yml` — protocol tests, the no-Android check, debug APK, and a check that every manifest permission is documented in `SECURITY.md` |
| Release | `.github/workflows/release.yml` — tag/VERSION match, test, sign, `apksigner` verify against `CN=shutterlink`, changelog extraction, GitHub release |
| Signing | `tools/release-env.sh`, keys in the macOS Keychain under account `shutterlink`; nothing secret in the repository |
| Docs | README, CONTRIBUTING, SECURITY, CODE_OF_CONDUCT, CHANGELOG, issue and PR templates |

### Verified on hardware

Running record of what has actually been exercised on a device, as opposed to assumed. Keep this
up to date — CONTRIBUTING.md asks PRs to.

| Date | Device | What was checked | Result |
|---|---|---|---|
| 2026-11-09 | OnePlus 7 (GM1901), Android 10 / API 29 | `wifi.direct`, `bluetooth_le`, hardware H.264 encoder, camera `level.full` + manual sensor + RAW, no `wifi.aware` | All present as needed; Wi-Fi Aware correctly absent |
| 2026-11-09 | OnePlus 7 | `HidDeviceService` + `IBluetoothHidDevice` in the Bluetooth stack | Present — the HID shutter path (§10) is viable |
| 2026-11-09 | OnePlus 7 | `com.oneplus.camera` answers both `STILL_IMAGE_CAMERA` and `IMAGE_CAPTURE` | Present — Path A of §11 is viable for it |
| 2026-11-09 | OnePlus 7 | Phase 0 debug APK installs, launches, resumes, no crash | Passed |
| 2026-11-09 | OnePlus 7 | Phase 0 **release** APK (R8-minified, signed) installs, launches, no crash | Passed |
| 2026-11-09 | macOS | Release signing chain: Keychain → `tools/release-env.sh` → `assembleRelease` → `apksigner` reports `CN=shutterlink` | Passed |
| 2026-11-09 | macOS | Keychain round-trip: the stored base64 rebuilds a keystore the stored password opens | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: role picker renders, choice remembered | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: CameraX preview binds and runs | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: capture writes a 3.6 MB JPEG to `DCIM/ShutterLink`, `is_pending` cleared, visible in MediaStore | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: 3s timer counts down on screen, then fires | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: last-shot thumbnail appears after a capture | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: zoom stops offered match the device's range (no 0.6× — this camera's min zoom is 1.0) | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: front/back flip switches cameras; front preview and front capture both work | Passed |
| 2026-11-09 | OnePlus 7 | Phase 1: capture rotation follows the physical phone, not the locked-portrait activity | Passed — a sideways-held shot saved 4000×3000 with EXIF orientation 1 and renders upright. Before the fix a portrait-held shot was tagged orientation 7 (mirrored **and** rotated 90°) |
| 2026-11-09 | **Samsung Galaxy M04 (SM-M045F), Android 14 / API 34** | `wifi.direct`, `bluetooth_le`, `camera.level.full` + front + flash, hardware AVC encoder (`c2.mtk.avc.encoder`), `HidDeviceService`, Play Services 26.43.30; no `wifi.aware` | All present — the second phone can do everything the design needs |
| 2026-11-09 | Samsung M04 | The app installs, launches and runs on Android 14 | Passed |
| 2026-11-09 | Samsung M04 | On API 34 the runtime permissions are `BLUETOOTH_SCAN`/`ADVERTISE`/`CONNECT` and `NEARBY_WIFI_DEVICES`, and `ACCESS_FINE_LOCATION` is **not** requested — the `maxSdkVersion="32"` cap works | Passed |
| 2026-11-09 | Both | **§1 passes.** Discovery, matching 4-digit codes on both screens, accept on both, connection, handshake, live round trip (78–172 ms) | Passed |
| 2026-11-09 | Both | **§2 passes.** Live 720p preview from the OnePlus shown on the Samsung, upright and full frame; ~1100 frames with 6 dropped | Passed |
| 2026-11-09 | Both | **§3 passes.** Camera flip and the self-timer driven from the controller; the far phone rebinds and reports back | Passed |
| 2026-11-09 | Both | **§4 passes.** Remote shutter fires, the photo lands in the camera phone's gallery full size, transfers (~4.4 MB), opens in the review screen, and reaches the controller's gallery **only** on Save | Passed |
| 2026-11-09 | OnePlus 7 | Phase 2: the permission list is computed from the running Android version — on API 29 it asks for Location only, with the reason on screen, and never for the API 31+ Bluetooth permissions | Passed |
| 2026-11-09 | OnePlus 7 | Phase 2: Nearby advertising starts — `startAdvertising` succeeds over BLE/Bluetooth/WIFI_LAN with Wi-Fi Direct among the upgrade mediums | Passed |
| 2026-11-09 | OnePlus 7 | Phase 2: the waiting screen shows the name the other phone should look for | Passed |
| 2026-11-09 | macOS | The built APK carries **no INTERNET permission**; CI now fails if one appears | Passed |
| 2026-11-09 | — | Phase 2: discovery, connecting, the confirmation code, the Hello/Capabilities handshake and the ping round trip | **Untested — needs the second phone** |

### Not verified, and blocking which phase

| Unknown | Blocks |
|---|---|
| Whether both phones support `BluetoothHidDevice` as a *device* | Phase 2.5 |
| Whether PicsArt responds to the volume-key shutter | Phase 5.5 (T-15) |
| Real glass-to-glass preview latency | Phase 3 — the whole design rests on this being under ~200 ms |
| Whether the *CI* release job signs correctly (only verified locally so far) | The first tagged release |
