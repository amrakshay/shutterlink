## What this changes

<!-- One or two sentences. What is different afterwards, and why. -->

## How it was tested

<!--
Real hardware beats CI here. The app rests on one hardware assumption — that the
phone running it can act as a Bluetooth HID device — and on camera apps honouring
a key. Neither can be checked by a build.

"Pixel 8 (Android 15) paired with a Redmi Note 12, fired twelve shots in the stock
camera" is worth more than a green tick.
-->

- [ ] `./gradlew :app:assembleDebug` passes
- [ ] Tried on two real phones, named above
- [ ] If it touches pairing: unpaired both phones and went through setup from scratch

## Checks

- [ ] No new permission. If there is one, `SECURITY.md` explains it and CI's audit will agree
- [ ] No new dependency that reaches the network
- [ ] Nothing new is required on the camera phone
- [ ] `docs/VERIFY.md` updated if what has been checked on hardware changed
- [ ] `CHANGELOG.md` updated if behaviour changed
