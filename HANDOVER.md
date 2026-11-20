# Handover

State on 20 Nov 2026.

## Where things are

- Version 0.1.0, release builds signed with the odo key (path in `local.properties` through `signing.file`).
- Five tabs: Health, Battery, Storage (Clean / Files / Apps sections), Speed, Apps (Debloat / Installed / Privacy / Startup).
- Default look is Dot. Calm and Expressive remain in the palette sheet.
- `graphify-out/` holds a code graph (`GRAPH_REPORT.md`, `graph.html`); ignored by git. Rebuild with `/graphify . --update`.

## Recently settled

- Optimise apps compiles each remaining basic app with `cmd package compile -m speed`, one at a time, and reads the real state back with `cmd package art dump <pkg>`.
- Apps that cannot compile are remembered per version with a reason.
- Without Shizuku the engine cannot read `/proc/stat`; it returns no CPU share rather than inventing one.
- Battery design capacity is normalised for kernels that report it in other units.
- Trash moves are batched for progress; emptying deletes in batches through `trash-purge`.
- Keep warm: Free memory skips the keep list plus launcher, keyboard and Hone.
- Storage tab: one widget switches all / internal / SD card; the SD card gets its own scan.

## Added in this round (built, engine tests pass, installed and tried on the phone, Android 16)

- **SD card trash:** `.HoneTrash` per volume, `files::safe` accepts SD card paths with the same rules. Trash item ids are now the item's full path inside the trash.
- **Auto-empty:** `trash-expire 30`, opt-in switch on the Trash page and in Speed > Overnight upkeep; runs before each scan and overnight.
- **Cache clearing:** `clear-cache` uses `pm clear --cache-only` (Android 14+); falls back to the hidden binder call on older builds.
- **Light mode:** sheets used hard-coded black in Dot, which broke Dot light; now `sheetColor()`.
- **Battery tab:** battery use by app and "Wakes the phone" (`drain`: `dumpsys batterystats --charged` + `dumpsys alarm`), with Restrict background.
- **Apps tab:** Privacy (`privacy`: `dumpsys appops --op X` for camera, mic, location, clipboard; `revoke`), Startup (boot receivers from PackageManager; `autostart-off/on` via `pm disable` / `pm default-state`).
- **App sheet:** Freeze (`pm suspend`), Block internet (`cmd connectivity set-package-networking-enabled`, Android 14+, lasts until restart), Internet sort (NetworkStatsManager, needs usage access).
- **Cleanup groups:** WhatsApp media (sent / received sections), Telegram media, screenshots older than 30 days, similar photos (on demand, Kotlin dHash of MediaStore thumbnails, `Media.kt`).
- **Speed tab:** speed test (`bench`, run as Hone in its cache dir; previous run kept for comparison), Display (animations, top refresh rate, text size via `set-display`, whitelisted keys and ranges), Overnight upkeep (`Night.kt`, JobScheduler: charging + idle, 23:00-07:00).
- **Quick Settings tiles** Free memory and Optimise apps, **home screen widget** (battery / storage / memory, Dot look), in `Tiles.kt`.

## Known gaps

- Checked on the phone: drain (21 apps), wake-ups, privacy (49 apps, own apps first), startup switch (fallback, undone after), speed test, display chips (120.1 Hz and 0.85 text match), similar photos (135 found in about 90 s), `pm clear --cache-only` and `set-package-networking-enabled` both supported. Other OEM builds may still format dumpsys differently.
- `set-package-networking-enabled` and `pm clear --cache-only` need Android 14; both report when unsupported.
- Calm and Expressive and light mode were checked in code only; the phone locked during the switch. Check the look sheet still says Dot.
- Apps tab labels got tighter padding and file counts now say "1 file"; installed but not seen on screen.
- Similar photos is slow (about 90 s). Speed-ups: skip photos with no neighbour within two minutes before hashing, hash in parallel.
- Startup cannot fully stop apps that target Android 12 or older: they still get BOOT_COMPLETED, but cannot run in the background.
- Startup: Android 14+ refuses `pm disable` on single components of normal apps from the shell ("Shell cannot change component state"). `autostart-off` then sets Restricted instead (`RUN_ANY_IN_BACKGROUND ignore` + standby bucket `restricted`); apps targeting Android 13+ then get no BOOT_COMPLETED until opened. True per-receiver blocking needs root.
- Battery "Last 24 hours" chart drew only a dot and a short squiggle on the phone; check with more history.
- Per-app cache clearing on Android 13 and older still uses the hidden package-manager call that can stop answering.

## Feature ideas left

- Morning summary at a set time instead of when the night job finishes.
- Data usage per app over time, and mobile-only blocking.
- Similar-photo sets shown side by side instead of as a flat list.
