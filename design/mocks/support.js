// A minimal stand-in for the design-canvas runtime these mocks were authored in,
// so each .dc.html opens in an ordinary browser without pulling in that editor.
//
// It does only two things:
//   - moves <helmet> contents (the font <link> and page <style>) into <head>,
//     which is what the real runtime does;
//   - gives <x-dc> a box, since a browser treats an unknown tag as inline.
//
// The <script type="text/x-dc"> block in each file is inert here: a browser does
// not execute an unknown script type. That is fine — these mocks hold no {{holes}},
// every string in them is literal markup, so they render identically either way.
(function () {
  function hydrate() {
    document.querySelectorAll('helmet').forEach(function (helmet) {
      while (helmet.firstChild) {
        document.head.appendChild(helmet.firstChild);
      }
      helmet.remove();
    });
    document.querySelectorAll('x-dc').forEach(function (root) {
      root.style.display = 'block';
    });
  }

  if (document.readyState === 'loading') {
    document.addEventListener('DOMContentLoaded', hydrate);
  } else {
    hydrate();
  }
})();
