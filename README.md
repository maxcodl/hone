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

**Storage**
- All storage, internal and SD card at a glance
- Clean: cache cleaners for your apps and system apps, deep clean, what uses the space, cleanup groups (old WhatsApp backups, APK files, duplicates, leftovers of removed apps, temp files and logs, large files, empty folders)
- Files: browse internal storage and the SD card by size, biggest folders and files, previews
- Apps: biggest apps split into app, data and cache
- Nothing is deleted on the first step: removed files go to Hone's trash and can be restored until you empty it

**Speed**
- Optimise apps: fully compiles every app Android left basic, one at a time, with progress
- Free memory with a keep-warm list (launcher, keyboard and your picks stay running)
- Storage upkeep (Android's own trim and maintenance) and restart

**Apps**
- Debloat preinstalled extras from a reviewed list, always restorable
- Installed apps: sort by size, data, last used, newest or running; share or save the APK (split apps as one .apks), turn off, archive, restrict background, force stop, uninstall

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
