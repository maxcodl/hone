# Hone

A phone tune-up app for Android, built for the CMF Phone 1 but useful on any phone with Android 10 or newer. The interface is Jetpack Compose with Material 3 Expressive; the work is done by a small Rust engine.

Hone works best with [Shizuku](https://shizuku.rikka.app/), which lets it run its engine with shell access, no root needed. Without Shizuku it still shows everything Android allows a normal app to see.

## What it does

**Health**
- Score for how the phone is doing, with fixes for what pulls it down
- Live CPU and memory graphs, per-core load, heat sensors, battery
- Busiest processes, with their own memory (not shared framework pages)

**Battery**
- Health, capacity against new, charge cycles, live current and watts
- Optional monitoring: 24-hour level chart, charge sessions, charger quality, drain per hour with the screen on and off
- Alerts: charge limit, slow charging, low battery, fast drain with the screen off, hot battery
- Battery use by app since the last full charge, and apps that wake the phone, with one-tap background restriction

**Storage**
- All storage, internal and SD card at a glance
- Clean: cache cleaners for your apps and system apps, deep clean, what uses the space, cleanup groups (WhatsApp and Telegram media, old screenshots, similar photos, old WhatsApp backups, APK files, duplicates, leftovers of removed apps, temp files and logs, large files, empty folders)
- Files: browse internal storage and the SD card by size, biggest folders and files, previews
- Apps: biggest apps split into app, data and cache
- Nothing is deleted on the first step: removed files go to Hone's trash (one per volume, SD card included) and can be restored until you empty it; optional auto-empty after 30 days
- Needs all-files access to see and move files

**Speed**
- Optimise apps: fully compiles every app Android left basic, one at a time, with progress
- Free memory with a keep-warm list (launcher, keyboard and your picks stay running)
- Storage upkeep (Android's own trim and maintenance) and restart
- Speed test for storage and CPU, compared with the run before
- Display: animation speed, top refresh rate and text size in one place
- Overnight upkeep while charging: compiles apps, runs storage upkeep, clears old trash

**Apps**
- Debloat preinstalled extras from a reviewed list, always restorable
- Installed apps: sort by size, data, last used, newest or running; share or save the APK (split apps as one .apks), turn off, archive, restrict background, freeze, block internet (Android 14+, until restart), force stop, uninstall
- Privacy: who used the camera, microphone, location or clipboard in the last week, with one tap to take it away
- Startup: apps that start after a restart, with a switch to stop that (on Android 14+ without root, by restricting the app in the background)

**Extras**
- Quick Settings tiles for Free memory and Optimise apps
- Home screen widget with battery, free storage and free memory

## Looks

Three styles in the palette menu: **Dot** (black, white and red with dot-matrix numbers), **Calm** and **Expressive**, plus an AMOLED black switch.

## Build

Needs JDK 17, the Android SDK and NDK, Rust with the `aarch64-linux-android` target and [cargo-ndk](https://github.com/bbqsrc/cargo-ndk).

```
./gradlew :app:assembleRelease
```

Gradle builds the Rust engine first (`engine/`) and packs it as `libhone.so`. Signing reads a properties file named by `signing.file` in `local.properties`; without it the release uses the debug key.

## Licence

Icons are generated from Google's Material Symbols Rounded (Apache License 2.0).
