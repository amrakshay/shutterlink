## What does this change?

<!-- What is different for someone using the app, not only what changed in the code. -->

## Why?

<!-- Link an issue if there is one. -->

## How was it tested?

<!--
This project breaks on timing, permissions and OEM behaviour — a preview that stalls when the
screen dims, a Wi-Fi Direct group that forms on one phone brand and not another, a permission
that is optional on Android 10 and mandatory on 14. The test suite cannot catch any of that, so
this section matters more than the CI badge.

Name BOTH phones. For example: "OnePlus 7 (Android 10) as camera, Pixel 8 (Android 15) as
controller. Held the preview for ten minutes, took twelve shots, walked out of Wi-Fi range once
and let it reconnect."
-->

- [ ] `./gradlew :protocol:test` passes
- [ ] `./gradlew :app:assembleDebug` passes
- [ ] Tried on two real phones

**Phones tested:**

## Checklist

- [ ] `protocol/` still has no `android.*` import, so its tests still run without a device
- [ ] Any new transport code sits behind the `Transport` interface, not beside it
- [ ] Any new permission is added to the table in `SECURITY.md` with a reason
- [ ] Preview-pipeline changes include before/after numbers from the debug overlay
- [ ] `docs/PLAN.md` updated if what is verified on hardware changed
- [ ] `CHANGELOG.md` updated if behaviour changed
- [ ] No new dependency, or a paragraph in the PR saying why there is one
