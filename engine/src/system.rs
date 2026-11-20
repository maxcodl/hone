//! Readings from Android's own records (battery stats, alarms, app ops) and the per-app switches
//! that act on them: permissions, boot start, freezing, network access, display settings.
//! Everything except `bench` needs the shell.

use crate::{arg, run};
use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};

pub const HONE: &str = "io.max.hone";

// ---- small parsers, each tested below ----

/// "u0a171" -> 10171, "1000" -> 1000; per Android's UserHandle formatting.
fn parse_uid(tok: &str) -> Option<u32> {
    if let Some(rest) = tok.strip_prefix('u') {
        let (user, app) = rest.split_once('a')?;
        return Some(user.parse::<u32>().ok()? * 100_000 + 10_000 + app.parse::<u32>().ok()?);
    }
    tok.parse().ok()
}

/// "1h 2m 3s 4ms", "4m40s93ms", "-1d2h3m4s5ms" or "+5s" -> milliseconds.
fn parse_duration(text: &str) -> u64 {
    let mut total = 0u64;
    let mut num = String::new();
    let mut chars = text.trim_start_matches(['-', '+']).chars().peekable();
    while let Some(c) = chars.next() {
        if c.is_ascii_digit() {
            num.push(c);
            continue;
        }
        let n: u64 = num.parse().unwrap_or(0);
        num.clear();
        total += match c {
            'd' => n * 86_400_000,
            'h' => n * 3_600_000,
            'm' if chars.peek() == Some(&'s') => {
                chars.next();
                n
            }
            'm' => n * 60_000,
            's' => n * 1000,
            _ => 0,
        };
    }
    total
}

/// uid -> first package, from `cmd package list packages -U`.
fn uid_packages() -> HashMap<u32, String> {
    let mut m = HashMap::new();
    for l in run("cmd", &["package", "list", "packages", "-U"]).lines() {
        let Some(rest) = l.strip_prefix("package:") else { continue };
        let Some((pkg, uid)) = rest.split_once(" uid:") else { continue };
        for u in uid.split(',') {
            if let Ok(u) = u.trim().parse::<u32>() {
                m.entry(u).or_insert_with(|| pkg.trim().to_string());
            }
        }
    }
    m
}

fn third_party() -> HashSet<String> {
    run("cmd", &["package", "list", "packages", "-3"]).lines().filter_map(|l| l.strip_prefix("package:")).map(|s| s.trim().to_string()).collect()
}

/// Apps a switch may touch: installed by the user, never Hone.
fn allowed(pkg: &str) -> Result<(), Value> {
    if pkg.is_empty() || pkg == HONE || !pkg.contains('.') || !third_party().contains(pkg) {
        return Err(json!({ "ok": false, "output": "only apps you installed, never Hone" }));
    }
    Ok(())
}

// ---- battery drain and wake-ups ----

/// From `dumpsys batterystats`: estimated mAh per uid, total computed drain.
fn parse_power(text: &str) -> (HashMap<u32, f64>, f64) {
    let mut out = HashMap::new();
    let mut total = 0.0;
    let mut inside = false;
    for l in text.lines() {
        let t = l.trim();
        if t.starts_with("Estimated power use (mAh)") {
            inside = true;
            continue;
        }
        if !inside {
            continue;
        }
        if t.is_empty() {
            break;
        }
        if let Some(i) = t.find("Computed drain:") {
            total = t[i + 15..].trim().split([',', ' ']).next().and_then(|v| v.parse().ok()).unwrap_or(0.0);
            continue;
        }
        let Some(rest) = t.strip_prefix("UID ").or_else(|| t.strip_prefix("Uid ")) else { continue };
        let Some((tok, val)) = rest.split_once(':') else { continue };
        let (Some(uid), Some(mah)) = (parse_uid(tok.trim()), val.split_whitespace().next().and_then(|v| v.parse::<f64>().ok())) else { continue };
        *out.entry(uid).or_insert(0.0) += mah;
    }
    (out, total)
}

/// From `dumpsys batterystats`: partial wake lock time per uid.
fn parse_wakelocks(text: &str) -> HashMap<u32, u64> {
    let mut out = HashMap::new();
    let mut inside = false;
    for l in text.lines() {
        let t = l.trim();
        if t.starts_with("All partial wake locks") {
            inside = true;
            continue;
        }
        if !inside {
            continue;
        }
        let Some(rest) = t.strip_prefix("Wake lock ") else {
            if !t.is_empty() {
                break;
            }
            continue;
        };
        let Some((tok, after)) = rest.split_once(' ') else { continue };
        let Some(uid) = parse_uid(tok) else { continue };
        // "<tag>: 1m 2s 3ms (4 times) ..." - the tag itself may hold colons, the time follows the last ": " before "(".
        let head = after.split(" (").next().unwrap_or("");
        let Some((_, time)) = head.rsplit_once(": ") else { continue };
        *out.entry(uid).or_insert(0) += parse_duration(&time.replace(' ', ""));
    }
    out
}

/// From `dumpsys alarm`'s "Alarm Stats": wake-up alarms per package since the phone started.
fn parse_alarm_wakeups(text: &str) -> HashMap<String, u64> {
    let mut out = HashMap::new();
    let Some(section) = text.split("Alarm Stats:").nth(1) else { return out };
    for l in section.lines() {
        let t = l.trim();
        if !(t.contains(" running, ") && t.contains(" wakeups")) {
            continue;
        }
        let first = t.split_whitespace().next().unwrap_or("");
        let pkg = first.rsplit(':').next().unwrap_or(first);
        let n = t.split(" wakeups").next().and_then(|h| h.rsplit(' ').next()).and_then(|v| v.parse::<u64>().ok()).unwrap_or(0);
        if pkg.contains('.') && n > 0 {
            *out.entry(pkg.to_string()).or_insert(0) += n;
        }
    }
    out
}

/// Battery use, awake time and wake-ups per app, since the last full charge (alarms: since restart).
pub fn drain() -> Value {
    let stats = std::thread::spawn(|| run("dumpsys", &["batterystats", "--charged"]));
    let alarms = std::thread::spawn(|| run("dumpsys", &["alarm"]));
    let uids = uid_packages();
    let stats = stats.join().unwrap_or_default();
    let (power, total) = parse_power(&stats);
    let locks = parse_wakelocks(&stats);
    let wakeups = parse_alarm_wakeups(&alarms.join().unwrap_or_default());

    let mut apps: HashMap<String, (f64, u64, u64)> = HashMap::new();
    for (uid, mah) in &power {
        if let Some(p) = uids.get(uid) {
            apps.entry(p.clone()).or_default().0 += mah;
        }
    }
    for (uid, ms) in &locks {
        if let Some(p) = uids.get(uid) {
            apps.entry(p.clone()).or_default().1 += ms;
        }
    }
    for (p, n) in &wakeups {
        apps.entry(p.clone()).or_default().2 += n;
    }
    let mut list: Vec<Value> = apps
        .into_iter()
        .filter(|(p, (m, a, w))| p != HONE && (*m >= 0.5 || *a >= 60_000 || *w >= 10))
        .map(|(p, (m, a, w))| {
            json!({ "pkg": p, "mah": (m * 10.0).round() / 10.0, "pct": if total > 0.0 { (m / total * 1000.0).round() / 10.0 } else { 0.0 }, "awakeMs": a, "wakeups": w })
        })
        .collect();
    list.sort_by(|a, b| b["mah"].as_f64().unwrap_or(0.0).total_cmp(&a["mah"].as_f64().unwrap_or(0.0)));
    json!({ "apps": list, "totalMah": total, "measured": !power.is_empty() })
}

// ---- privacy ----

const PRIVACY_OPS: &[&str] = &["CAMERA", "RECORD_AUDIO", "FINE_LOCATION", "COARSE_LOCATION", "READ_CLIPBOARD"];

/// From `dumpsys appops --op X`: package -> how long ago it last used the op.
fn parse_appops_access(text: &str) -> HashMap<String, u64> {
    let mut out: HashMap<String, u64> = HashMap::new();
    let mut pkg: Option<String> = None;
    // Access already taken away still shows its past use; skip it so the list holds only what can still happen.
    let mut blocked = false;
    for l in text.lines() {
        let t = l.trim();
        if let Some(p) = t.strip_prefix("Package ").and_then(|p| p.strip_suffix(':')) {
            pkg = Some(p.to_string());
            blocked = false;
            continue;
        }
        if t.starts_with("Uid ") {
            pkg = None;
            continue;
        }
        if let Some(mode) = t.split_once(" (").filter(|(op, _)| op.chars().all(|c| c.is_ascii_uppercase() || c == '_')).map(|(_, m)| m) {
            blocked = ["ignore)", "deny)", "errored)"].iter().any(|b| mode.starts_with(b));
            continue;
        }
        let (Some(p), true, false) = (&pkg, t.starts_with("Access:"), blocked) else { continue };
        let Some(ago) = t.split("(-").nth(1).and_then(|r| r.split(')').next()) else { continue };
        let ms = parse_duration(ago);
        let e = out.entry(p.clone()).or_insert(u64::MAX);
        *e = (*e).min(ms);
    }
    out
}

/// Which apps used the camera, microphone, location or clipboard in the last week.
pub fn privacy() -> Value {
    let jobs: Vec<_> = PRIVACY_OPS.iter().map(|op| (op, std::thread::spawn(move || run("dumpsys", &["appops", "--op", op])))).collect();
    let mut apps: HashMap<String, Vec<Value>> = HashMap::new();
    for (op, j) in jobs {
        for (pkg, ago) in parse_appops_access(&j.join().unwrap_or_default()) {
            if ago <= 7 * 86_400_000 && pkg != HONE {
                apps.entry(pkg).or_default().push(json!({ "op": op, "agoMs": ago }));
            }
        }
    }
    let mine = third_party();
    let mut list: Vec<Value> = apps.into_iter().map(|(p, ops)| json!({ "pkg": p, "ops": ops, "user": mine.contains(&p) })).collect();
    list.sort_by(|a, b| a["pkg"].as_str().cmp(&b["pkg"].as_str()));
    json!({ "apps": list })
}

/// Takes one kind of access away. The app can ask for it again, so it is always restorable.
pub fn revoke(rest: &[String]) -> Value {
    let (pkg, op) = (arg(rest, 0), arg(rest, 1));
    if let Err(e) = allowed(pkg) {
        return e;
    }
    let perms: &[&str] = match op {
        "CAMERA" => &["android.permission.CAMERA"],
        "RECORD_AUDIO" => &["android.permission.RECORD_AUDIO"],
        "FINE_LOCATION" | "COARSE_LOCATION" => &["android.permission.ACCESS_FINE_LOCATION", "android.permission.ACCESS_COARSE_LOCATION", "android.permission.ACCESS_BACKGROUND_LOCATION"],
        "READ_CLIPBOARD" => {
            let out = run("cmd", &["appops", "set", pkg, "READ_CLIPBOARD", "ignore"]);
            return json!({ "ok": !out.contains("Error"), "output": out });
        }
        _ => return json!({ "ok": false, "output": format!("unknown access '{op}'") }),
    };
    let outs: Vec<String> = perms.iter().map(|p| run("pm", &["revoke", pkg, p])).collect();
    // A permission the app never asked for answers with an error; one that went away is enough.
    let ok = outs.iter().any(|o| o.is_empty());
    json!({ "ok": ok, "output": outs.join("\n") })
}

// ---- boot start, freezing, network ----

/// Turns an app's boot receivers off ("off") or back to how the app shipped them ("on").
pub fn autostart(rest: &[String], on: bool) -> Value {
    let pkg = arg(rest, 0);
    if let Err(e) = allowed(pkg) {
        return e;
    }
    let mut outs = vec![];
    let mut ok = true;
    for comp in rest.iter().skip(1) {
        if !comp.starts_with(&format!("{pkg}/")) {
            return json!({ "ok": false, "output": format!("{comp} is not part of {pkg}") });
        }
        let out = run("pm", &[if on { "default-state" } else { "disable" }, "--user", "0", comp]);
        if shell_refused(&out) {
            // Android 14+ stops the shell from switching single components of normal apps. Background
            // restriction is the nearest thing it allows: the app gets no jobs, alarms or services after boot.
            // On Android 13+ a restricted app gets no BOOT_COMPLETED until it is opened; this is what Settings'
            // "Restricted" battery option sets (app op plus standby bucket).
            let out = run("cmd", &["appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", if on { "allow" } else { "ignore" }])
                + &run("am", &["set-standby-bucket", pkg, if on { "active" } else { "restricted" }]);
            return json!({ "ok": !out.contains("Error") && !out.contains("Exception"), "restricted": true, "output": out });
        }
        ok &= out.contains("new state");
        outs.push(out);
    }
    json!({ "ok": ok && !outs.is_empty(), "output": outs.join("\n") })
}

fn shell_refused(out: &str) -> bool {
    out.contains("Shell cannot change component state")
}

/// Freeze: Android's own app suspension. The app cannot run and its icon greys out; data stays.
pub fn freeze(rest: &[String], frozen: bool) -> Value {
    let pkg = arg(rest, 0);
    if let Err(e) = allowed(pkg) {
        return e;
    }
    let out = run("cmd", &["package", if frozen { "suspend" } else { "unsuspend" }, "--user", "0", pkg]);
    json!({ "ok": out.contains(&format!("suspended state: {frozen}")), "output": out })
}

/// Blocks or allows all network access for one app (Android 14's per-app firewall chain; resets on restart).
pub fn network(rest: &[String], allow: bool) -> Value {
    let pkg = arg(rest, 0);
    if let Err(e) = allowed(pkg) {
        return e;
    }
    if !allow {
        let on = run("cmd", &["connectivity", "set-chain3-enabled", "true"]);
        if on.contains("Unknown") || on.contains("Error") || on.contains("Exception") {
            return json!({ "ok": false, "output": "This Android version cannot block one app's network" });
        }
    }
    let out = run("cmd", &["connectivity", "set-package-networking-enabled", if allow { "true" } else { "false" }, pkg]);
    json!({ "ok": !(out.contains("Unknown") || out.contains("Error") || out.contains("Exception")), "output": out })
}

// ---- display ----

const DISPLAY: &[(&str, &str, f64, f64)] = &[
    ("global", "animator_duration_scale", 0.0, 2.0),
    ("global", "transition_animation_scale", 0.0, 2.0),
    ("global", "window_animation_scale", 0.0, 2.0),
    ("system", "peak_refresh_rate", 30.0, 165.0),
    ("system", "min_refresh_rate", 0.0, 165.0),
    ("system", "font_scale", 0.85, 2.0),
];

/// Display settings; a value is missing when the phone has never set it.
pub fn display() -> Value {
    let mut m = serde_json::Map::new();
    for (ns, key, _, _) in DISPLAY {
        let v = run("settings", &["get", ns, key]);
        m.insert(key.to_string(), v.parse::<f64>().map(|f| json!(f)).unwrap_or(Value::Null));
    }
    json!({ "settings": m, "modes": refresh_modes() })
}

/// Refresh rates the screen supports, from the display's mode list.
fn refresh_modes() -> Vec<f64> {
    let text = run("dumpsys", &["display"]);
    let mut v: Vec<f64> = vec![];
    for part in text.split("fps=").skip(1) {
        if let Some(f) = part.split([',', ' ', '}']).next().and_then(|x| x.parse::<f64>().ok()) {
            let r = f.round();
            if r >= 30.0 && !v.contains(&r) {
                v.push(r);
            }
        }
    }
    v.sort_by(f64::total_cmp);
    v
}

/// Sets one listed display setting within its range; anything else is refused.
pub fn set_display(rest: &[String]) -> Value {
    let (key, value) = (arg(rest, 0), arg(rest, 1));
    let Some((ns, _, lo, hi)) = DISPLAY.iter().find(|d| d.1 == key) else {
        return json!({ "ok": false, "output": format!("{key} cannot be changed here") });
    };
    let Some(v) = value.parse::<f64>().ok().filter(|v| (*lo..=*hi).contains(v)) else {
        return json!({ "ok": false, "output": format!("{value} is out of range") });
    };
    let out = run("settings", &["put", ns, key, &v.to_string()]);
    json!({ "ok": out.is_empty(), "output": out })
}

// ---- speed test ----

/// Storage write/read speed, random reads and CPU score, measured in `dir` (Hone's own cache).
pub fn bench(dir: &str, megabytes: usize) -> Value {
    use std::io::{Read, Seek, SeekFrom, Write};
    use std::time::Instant;
    let path = std::path::Path::new(dir).join("hone-bench.bin");
    let block = vec![0x5Au8; 1 << 20];
    let mb = megabytes.max(1);

    let t = Instant::now();
    let write_ok = (|| -> std::io::Result<()> {
        let mut f = std::fs::File::create(&path)?;
        for i in 0..mb {
            let mut b = block.clone();
            b[0] = i as u8; // not all-equal blocks, so compressing storage cannot cheat
            f.write_all(&b)?;
        }
        f.sync_all()
    })();
    let write_s = t.elapsed().as_secs_f64();
    if write_ok.is_err() {
        let _ = std::fs::remove_file(&path);
        return json!({ "error": "could not write the test file" });
    }
    drop_cache(&path);

    let t = Instant::now();
    let mut buf = vec![0u8; 1 << 20];
    let mut f = match std::fs::File::open(&path) {
        Ok(f) => f,
        Err(_) => return json!({ "error": "could not read the test file" }),
    };
    while f.read(&mut buf).map(|n| n > 0).unwrap_or(false) {}
    let read_s = t.elapsed().as_secs_f64();
    drop_cache(&path);

    // Random 4 KB reads across the file for about a second.
    let mut small = [0u8; 4096];
    let blocks = (mb as u64 * 256).max(1);
    let mut seed = 0x9E37_79B9_7F4A_7C15u64;
    let t = Instant::now();
    let mut reads = 0u64;
    while t.elapsed().as_millis() < 1000 {
        seed ^= seed << 13;
        seed ^= seed >> 7;
        seed ^= seed << 17;
        if f.seek(SeekFrom::Start(seed % blocks * 4096)).is_err() || f.read(&mut small).is_err() {
            break;
        }
        reads += 1;
    }
    let iops = reads as f64 / t.elapsed().as_secs_f64();
    let _ = std::fs::remove_file(&path);

    let single = cpu_score(1);
    let threads = std::thread::available_parallelism().map(|n| n.get()).unwrap_or(1);
    let multi = cpu_score(threads);
    json!({
        "writeMBs": (mb as f64 / write_s.max(1e-6)).round(),
        "readMBs": (mb as f64 / read_s.max(1e-6)).round(),
        "randomIops": iops.round(),
        "cpuSingle": single.round(),
        "cpuMulti": multi.round(),
        "threads": threads,
    })
}

#[cfg(unix)]
fn drop_cache(path: &std::path::Path) {
    use std::os::unix::io::AsRawFd;
    if let Ok(f) = std::fs::File::open(path) {
        unsafe { libc::posix_fadvise(f.as_raw_fd(), 0, 0, libc::POSIX_FADV_DONTNEED) };
    }
}

#[cfg(not(unix))]
fn drop_cache(_: &std::path::Path) {}

/// Fixed integer work per thread; the score is rounds per second across all threads.
fn cpu_score(threads: usize) -> f64 {
    const ROUNDS: u64 = 3_000_000;
    let t = std::time::Instant::now();
    let jobs: Vec<_> = (0..threads)
        .map(|k| {
            std::thread::spawn(move || {
                let mut x = 0x2545_F491_4F6C_DD1Du64 ^ k as u64;
                for i in 0..ROUNDS {
                    x ^= x << 13;
                    x ^= x >> 7;
                    x ^= x << 17;
                    x = x.wrapping_mul(i | 1);
                }
                std::hint::black_box(x)
            })
        })
        .collect();
    jobs.into_iter().for_each(|j| { let _ = j.join(); });
    (ROUNDS * threads as u64) as f64 / t.elapsed().as_secs_f64() / 1000.0
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn spots_shell_refusal() {
        assert!(shell_refused("java.lang.SecurityException: Shell cannot change component state for ComponentInfo{a/b} to 2"));
        assert!(!shell_refused("Component {a/b} new state: disabled"));
    }

    #[test]
    fn reads_uids_and_durations() {
        assert_eq!(parse_uid("u0a171"), Some(10171));
        assert_eq!(parse_uid("u10a5"), Some(1_010_005));
        assert_eq!(parse_uid("1000"), Some(1000));
        assert_eq!(parse_uid("bogus"), None);
        assert_eq!(parse_duration("1h2m3s4ms"), 3_723_004);
        assert_eq!(parse_duration("-1d2h3m4s5ms"), 93_784_005);
        assert_eq!(parse_duration("4m40s93ms"), 280_093);
        assert_eq!(parse_duration("+5s"), 5000);
    }

    #[test]
    fn reads_battery_stats() {
        let text = "\
  Estimated power use (mAh):
    Capacity: 5000, Computed drain: 400, actual drain: 380-420
    Screen: 120 apps: 120 duration: 2h
    UID u0a171: 45.5 fg: 30 bg: 15.5 ( cpu=20 )
    UID 1000: 30.2 ( cpu=10 )
    Uid u0a171: 4.5 ( wifi=4 )

  All partial wake locks:
  Wake lock u0a171 *job*/com.x/.Sync: 1m 2s 3ms (4 times) max=5000 realtime
  Wake lock 1000 NetworkStats: 10s 0ms (1 times) realtime
  Wake lock u0a171 tag:with:colons: 1s 0ms (1 times) realtime

  Other section";
        let (p, total) = parse_power(text);
        assert_eq!(total, 400.0);
        assert_eq!(p[&10171], 50.0);
        assert_eq!(p[&1000], 30.2);
        let w = parse_wakelocks(text);
        assert_eq!(w[&10171], 63_003);
        assert_eq!(w[&1000], 10_000);
    }

    #[test]
    fn reads_alarm_wakeups() {
        let text = "Pending alarms\n  Alarm Stats:\n  u0a171:com.google.android.gms +4m40s93ms running, 1373 wakeups:\n    +1s *walarm*:x\n  1000:android +2s running, 0 wakeups:\n  com.old.style +1s running, 12 wakeups:\n";
        let w = parse_alarm_wakeups(text);
        assert_eq!(w["com.google.android.gms"], 1373);
        assert_eq!(w["com.old.style"], 12);
        assert!(!w.contains_key("android"));
    }

    #[test]
    fn reads_app_op_access() {
        let text = "\
  Uid u0a171:
    state=cch
    Package com.foo:
      CAMERA (allow):
        Access: [top-s] 2026-11-20 10:00:00.000 (-2h0m0s0ms) duration=+5s
        Access: [fg-s] 2026-11-20 11:00:00.000 (-1h0m0s0ms)
  Uid u0a172:
    Package com.bar:
      CAMERA (ignore):
        Reject: [top-s] 2026-11-19 10:00:00.000 (-1d0h0m0s0ms)";
        let a = parse_appops_access(text);
        assert_eq!(a["com.foo"], 3_600_000);
        assert!(!a.contains_key("com.bar"), "a rejected attempt is not a use");
    }

    #[test]
    fn skips_access_already_taken_away() {
        let text = "\
  Uid u0a311:
    Package com.vivaldi.browser:
      READ_CLIPBOARD (ignore):
        null=[
          Access: [top-s] 2026-11-20 01:29:55.030 (-15h46m18s352ms)
        ]
  Uid u0a312:
    Package com.bt.bms:
      READ_CLIPBOARD (allow):
        null=[
          Access: [top-s] 2026-11-11 11:23:56.522 (-1h0m0s0ms)
        ]";
        let a = parse_appops_access(text);
        assert!(!a.contains_key("com.vivaldi.browser"));
        assert_eq!(a["com.bt.bms"], 3_600_000);
    }

    #[test]
    fn display_settings_stay_in_range() {
        let r = set_display(&["font_scale".into(), "5".into()]);
        assert_eq!(r["ok"], false);
        let r = set_display(&["adb_enabled".into(), "1".into()]);
        assert_eq!(r["ok"], false);
    }

    #[test]
    fn bench_measures_something() {
        let dir = std::env::temp_dir();
        let r = bench(&dir.to_string_lossy(), 2);
        assert!(r["writeMBs"].as_f64().unwrap() > 0.0 && r["cpuSingle"].as_f64().unwrap() > 0.0, "{r}");
        assert!(!dir.join("hone-bench.bin").exists());
    }
}
