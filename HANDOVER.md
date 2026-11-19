# Handover

State on 20 Nov 2026.

## Where things are

- Version 0.1.0, release builds signed with the odo key (path in `local.properties` through `signing.file`). The phone has the latest release installed and a copy in `Download/Hone-0.1.0-release.apk`.
- Five tabs: Health, Battery, Storage (Clean / Files / Apps sections), Speed, Apps.
- Default look is Dot. Calm and Expressive remain in the palette sheet.

## Recently settled

- Optimise apps compiles each remaining basic app with `cmd package compile -m speed`, one at a time, and reads the real state back with `cmd package art dump <pkg>`. Android's own optimiser only refreshes apps that already have profiles, so it never moved the count.
- Apps that cannot compile are remembered per version with a reason: debug builds, "broken by developer" (the dex is rejected by the verifier, e.g. SD Maid SE), "uses Play's file" (vdex from a Play dex-metadata file).
- Compile state comes from `cmd package art dump`, not `dumpsys package dexopt`, which times out after ten seconds and returned partial lists.
- Without Shizuku the engine cannot read `/proc/stat`; it returns no CPU share rather than inventing one, and never lists itself.
- Battery design capacity is normalised for kernels that report it in other units. On the CMF Phone 1 `charge_full` equals the design value, so the health percent is probably not measured there; cycles are real.
- Trash moves are batched for progress; emptying deletes in batches through `trash-purge` with a progress bar.
- Keep warm: Free memory closes background apps one by one and skips the keep list plus launcher, keyboard and Hone.

- Storage tab: one widget on top switches all storage / internal / SD card (remembered in prefs `mode.storageScope`); the SD card gets its own engine scan (`scan <sd root>`, no leftovers check). Below it, Clean / Files / Apps sections.
- Smoothness: package lookups and volume reads are cached in `Hone`; cards animate in only within 600 ms of a tab opening (`LocalEnterStart`), not when scrolled into view.

## Known gaps

- SD card browsing is read-only; the trash only works on internal shared storage.
- Per-app cache clearing goes through a hidden package-manager call; on some builds it stops answering until a restart (Hone reports that).
- The Storage tab, merged screens and the newest icons have not been checked in Calm and Expressive or in light mode.

## Feature ideas, most useful first

1. **Battery drain by app:** which apps used the most battery and kept the phone awake. With Shizuku, Android's battery stats can be read.
2. **Night wake-up finder:** apps that wake the phone while the screen is off, from Android's alarm and wakelock records. Pairs with Restrict background.
3. **Privacy check:** which apps used the camera, mic, location or clipboard recently, with one-tap permission removal.
4. **Autostart control:** stop chosen apps launching after a restart.
5. **Freeze apps:** hide and pause an app without uninstalling it, and unfreeze in one tap. Lighter than turning it off.
6. **Night auto-maintenance:** while charging at night, optimise apps, empty old trash, run storage upkeep and send a morning summary.
7. **Data usage by app,** plus blocking an app's internet access (possible with Shizuku on recent Android).
8. **Media cleaners:** WhatsApp and Telegram sent and received media, old screenshots, and near-duplicate photos (the Rust engine can compare pictures quickly).
9. **Trash on the SD card,** and auto-emptying anything older than 30 days.
10. **Speed tests:** storage read/write and a quick CPU test, to compare before and after cleanup.
11. **Quick Settings tiles and home screen widgets in the Dot style:** Free memory and Optimise tiles, and a battery/storage/RAM widget.
12. **Display tweaks:** animation speed, peak refresh rate and font scale, all in one place.
