# Working on Hone

Rules for anyone changing this project, people and coding agents alike. Read `HANDOVER.md` for the current state first.

## Layout

- `engine/` Rust engine, one binary (`hone`). Each command prints one JSON object. `main.rs` dispatches; `files.rs` is the analyzer and trash; `battery.rs` is history, sessions and alerts; `debloat.rs` is the removable list.
- `app/` Android app, Kotlin and Compose. `Hone.kt` talks to the engine and holds app-wide state; one file per tab screen; `Widgets.kt` and `Ui.kt` hold the shared pieces; `Look.kt` the three styles; `Sym.kt` the generated icons.
- The engine ships as `lib/arm64/libhone.so`. With Shizuku it runs as the shell user through `ShellService`; without it the app runs it as itself.

## Build and install

- JDK 17, one Gradle run at a time, `--no-daemon`, filter output to errors.
- `./gradlew :app:assembleRelease` builds the engine (cargo ndk) and the app. Engine tests: `cargo test` in `engine/`.
- Phone: `adb install -r` only. Never uninstall, launch apps, take screenshots or read files on the phone unless asked; the owner uses the phone while you work.
- After installing, run `adb shell cmd package compile -m speed -f io.max.hone`.

## Safety rules (do not loosen)

- Files are never deleted on the first step. They move to `.HoneTrash` with a manifest; only emptying the trash deletes.
- `files::safe` guards every move: inside shared storage only, never the root, main folders, `Android`, `Android/data|obb|media` themselves, or the trash.
- Cleanup suggestions never reach into `Android/`, except folders of uninstalled apps.
- Debloat removes only packages on the `debloat.rs` list, for the current user only, always restorable.
- App actions refuse Hone itself. Every destructive action asks first.
- Free memory uses Android's background kill, which never touches what is on screen or playing.

## Engine rules

- One JSON object per command on stdout; nothing else. A closed pipe must not panic.
- No invented numbers: if a reading is not available (for example CPU without the shell), return none and let the app say so.
- Prefer one cheap reading over many; the Health tab polls, and what it costs shows up in its own numbers.
- Every non-trivial piece of logic has a test that fails without it.

## Interface rules

- Icons come from `Sym` (bold, filled, rounded). Add new ones by extending the generator list, not by importing icon libraries.
- Side-by-side cards share one height (`IntrinsicSize.Min` row, `fillMaxHeight` cards). Nested corners: outer radius = inner radius + padding.
- Dot style: dot-matrix only for large numbers, monospace titles and labels, red only for warnings, pure black.
- Long text wraps; pills are single words or short values.
- Progress is shown for anything that takes more than a second, and buttons stay disabled until it finishes.

## Git

- Author `maxcodl <maxwantstohangout@gmail.com>`, short plain messages, no trailers, no tool names.
- Never commit secrets: `local.properties` and the signing file stay out of git.
- Commit and push only when asked.
