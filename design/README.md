# Design

The UI mocks the app is being built against, and the small set of rules they follow.

These are **mocks, not the app**. Nothing in them is wired up. They exist so the screens could be
argued about before any of them was built, and so a contributor can see what a screen is meant to
become without reading the code.

## Looking at them

```bash
cd design/mocks
python3 -m http.server
# then open http://localhost:8000/
```

Serve the folder rather than opening `index.html` from disk — browsers block the frames when the
page is loaded over `file://`.

Each screen is also a standalone page: `design/mocks/Viewfinder.dc.html` opens on its own.

## What is here

| Screen | What it is |
|---|---|
| `Main` | Role picker — camera or controller |
| `Permissions` | What the app asks for, and why |
| `CameraWaiting` | The far phone, waiting to be found |
| `ControllerFind` | Nearby phones, with the "not showing up?" help |
| `PairConfirm` | The four-digit code shown on both phones |
| `Viewfinder` | **The main screen.** Live preview, zoom, shutter, capture-engine picker |
| `EnginePicker` | Shoot with ShutterLink, or another camera app on the far phone |
| `Countdown` | Self-timer |
| `ThirdPartyShooting` | The blackout while another app holds the camera |
| `Transferring` | The photo coming over |
| `Review` | Keep, retake or delete |
| `CameraLive` | The far phone while it is streaming |
| `Disconnected` | Lost the other phone, and why that usually happens |
| `Settings` | Preview quality, auto-save, shutter key, latency overlay |
| `Unsupported` | A phone without Wi-Fi Direct, and what it can still do |
| `Icon` | The app icon at four sizes, with the adaptive safe zone |

Not mocked yet: the Bluetooth HID pairing wizard (Phase 2.5) and a full session gallery. Both
belong to later phases — see [`../docs/PLAN.md`](../docs/PLAN.md).

## The rules these follow

**Dark, always.** The app is used with a camera running, usually in dim light. A bright screen on
the far phone would light the subject; a bright screen in your hand would ruin your own night
vision while you frame.

**One accent.** Cyan `#5AC8E8` means *connected, live, this is the thing to press*. Amber
`#F2A33C` means *warning* and is used nowhere else — so when it appears, it reads. Green appears
only for a completed state, red only for destructive actions.

| Token | Value | Used for |
|---|---|---|
| Ground | `#0B0E12` | Page background |
| Surface | `#161B21` | Cards |
| Line | `#262F38` | Borders |
| Text | `#F2F5F7` | Primary text |
| Muted | `#A3AFBA` | Secondary text |
| Accent | `#5AC8E8` | Connected, live, primary action |
| Warning | `#F2A33C` | Warnings only |
| Positive | `#5FD39B` | Completed states |
| Destructive | `#E8857A` | Delete |

**Inter**, with tight letter-spacing on headings only.

**Say the awkward thing on the screen.** The preview blacks out when another app shoots; location
is needed on Android 10 to scan over Bluetooth; the framing shifts a little between apps. Each of
those is written into the screen where it happens rather than left for the user to discover. If a
screen has to apologise, that is usually a sign the design is wrong — but when the constraint is
real and unavoidable, explaining it beats hiding it.

**Accessible as drawn.** Real `<button>`, `<a>` and `<label>` elements, 44px minimum touch
targets, `aria-label` on every icon-only control, and text contrast checked against the ground
rather than guessed.

## How these files work

They are plain HTML with two unusual tags, `<x-dc>` and `<helmet>`, left over from the canvas they
were drawn in. [`support.js`](mocks/support.js) is a twenty-line shim that makes a normal browser
render them: it moves `<helmet>` contents into `<head>` and gives `<x-dc>` a box. The
`<script type="text/x-dc">` block at the bottom of each file is inert in a browser.

`canvas.json` is the canvas layout — artboard positions and titles. It is kept so the set can be
loaded back into a design canvas; it does nothing when the files are served as pages.

## Changing them

If a screen changes in the app, change the mock in the same pull request. A mock that disagrees
with the app is worse than no mock, because someone will trust it.
