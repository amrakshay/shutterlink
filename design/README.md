# Design

One file: [`mocks/Icon.dc.html`](mocks/Icon.dc.html), the launcher icon's design source.

It ships as `app/src/main/res/drawable/ic_launcher_foreground.xml`, which is the
real artwork — this page is where the shape and the colours were worked out, and
it is kept so the next change to the icon starts from the same geometry rather
than from tracing the vector.

## Looking at it

```bash
cd design/mocks
python3 -m http.server
# then open http://localhost:8000/Icon.dc.html
```

Serve the folder rather than opening the file from disk; browsers block the
frames over `file://`.

## What used to be here

Fifteen other artboards — a viewfinder, a review screen, a transfer progress
screen, a capture-engine picker and the rest. They were accurate mocks of an app
that no longer exists: ShutterLink streamed a live preview between two phones
and sent photos back, and all of that was removed after measurement. See
[`../docs/FINDINGS.md`](../docs/FINDINGS.md). The git history has them.

The app as it stands has three screens — a permission prompt, a pairing panel
and a shutter — and they were built directly rather than mocked first, because
at that size a mock is slower than the real thing.
