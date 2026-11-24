//! Hone engine: runs as the shell user (through Shizuku), reads the phone's state and runs the tune-up
//! commands. Every command prints one JSON object on stdout.

mod battery;
mod debloat;
mod files;
mod system;

use serde_json::{json, Value};
use std::collections::{BTreeMap, HashMap, HashSet};
use std::process::Command;
use std::time::Duration;
use std::{fs, thread};

fn main() {
    let args: Vec<String> = std::env::args().skip(1).collect();
    let cmd = args.first().map(String::as_str).unwrap_or("");
    let rest: &[String] = if args.is_empty() { &[] } else { &args[1..] };
    let out = match cmd {
        "health" => health(),
        "live" => live(),
        "dexopt" => dexopt(),
        "compiling" => json!({ "compiling": compiling(), "jobRunning": job_running() }),
        // Detached: its own session, so it keeps going when Hone or its shell connection goes away.
        "optimize-start" => {
            let r = Command::new("setsid")
                .args(["cmd", "package", "bg-dexopt-job"])
                .stdin(std::process::Stdio::null())
                .stdout(std::process::Stdio::null())
                .stderr(std::process::Stdio::null())
                .spawn();
            json!({ "ok": r.is_ok() })
        }
        "packages" => packages(),
        // The safe list alone, for the app to match against its own package list when there is no shell.
        "tags" => json!({ "packages": debloat::LIST.iter().map(|(p, t, n)| json!({ "pkg": p, "tag": t, "note": n, "state": "unknown" })).collect::<Vec<_>>() }),
        "trim-caches" => trim_caches(),
        "free-ram" => free_ram(rest),
        "optimize" => optimize(),
        "optimize-cancel" => output(run("cmd", &["package", "bg-dexopt-job", "--cancel"])),
        "compile" => compile(rest),
        // Internal storage also checks for leftovers of removed apps, which needs the package list.
        "scan" => match arg(rest, 0) {
            "" | files::ROOT => files::scan(files::ROOT, &list(&["-u"])),
            r if r.starts_with("/storage/") && !r.starts_with("/storage/emulated") => files::scan(r, &HashSet::new()),
            r => json!({ "error": format!("not a volume: {r}") }),
        },
        "ls" => files::ls(arg(rest, 0), arg(rest, 1) == "quick"),
        "du" => files::du(rest),
        "trash" => files::trash(rest),
        "trash-list" => files::trash_list(),
        "trash-restore" => files::restore(rest),
        "empty-trash" => files::empty_trash(),
        "trash-purge" => files::purge(rest),
        "trash-expire" => files::expire(arg(rest, 0)),
        "clear-cache" => clear_cache(rest),
        "drain" => system::drain(),
        "privacy" => system::privacy(),
        "revoke" => system::revoke(rest),
        "autostart-off" => system::autostart(rest, false),
        "autostart-on" => system::autostart(rest, true),
        "freeze" => system::freeze(rest, true),
        "unfreeze" => system::freeze(rest, false),
        "net-block" => system::network(rest, false),
        "net-allow" => system::network(rest, true),
        "display" => system::display(),
        "set-display" => system::set_display(rest),
        // Runs as Hone itself, in its own cache folder.
        "bench" => system::bench(arg(rest, 0), 128),
        "maintenance" => output(run("sm", &["idle-maint", "run"])),
        "remove" => change(rest, true),
        "running" => running(),
        "battery-log" => battery::log(arg(rest, 0), &rest[1.min(rest.len())..]),
        "battery-history" => battery::history(arg(rest, 0), arg(rest, 1).parse().unwrap_or(0)),
        "battery-info" => battery::info(),
        // Background: RUN_ANY_IN_BACKGROUND is what Settings' "Restricted" battery option sets.
        "bg-restricted" => json!({ "packages": run("cmd", &["appops", "query-op", "RUN_ANY_IN_BACKGROUND", "ignore"]).lines().map(str::trim).filter(|l| l.contains('.') && !l.contains(' ')).collect::<Vec<_>>() }),
        "bg-restrict" => bg(rest, "ignore"),
        "bg-allow" => bg(rest, "allow"),
        "force-stop" => app_action(rest, &[], ""),
        "uninstall" => app_action(rest, &["uninstall"], "Success"),
        "disable" => app_action(rest, &["disable-user", "--user", "0"], "disabled"),
        "enable" => app_action(rest, &["enable", "--user", "0"], "enabled"),
        "archive" => app_action(rest, &["archive"], "Success"),
        "unarchive" => app_action(rest, &["request-unarchive"], ""),
        "restore" => change(rest, false),
        "grant-files" => output(run("appops", &["set", arg(rest, 0), "MANAGE_EXTERNAL_STORAGE", "allow"])),
        // Makes Android fold the latest per-app traffic into its records, so a live view sees it now.
        "net-poll" => { run("dumpsys", &["netstats", "--poll"]); json!({}) }
        "grant-usage" => output(run("appops", &["set", arg(rest, 0), "GET_USAGE_STATS", "allow"])),
        "reboot" => output(run("svc", &["power", "reboot"])),
        _ => json!({ "error": format!("unknown command '{cmd}'") }),
    };
    // A closed pipe (reader gone) must not panic: with panic=abort that leaves a crash report on the phone.
    use std::io::Write;
    let _ = writeln!(std::io::stdout(), "{out}");
}

fn arg(rest: &[String], i: usize) -> &str {
    rest.get(i).map(String::as_str).unwrap_or("")
}

fn run(prog: &str, args: &[&str]) -> String {
    match Command::new(prog).args(args).output() {
        Ok(o) => format!("{}{}", String::from_utf8_lossy(&o.stdout), String::from_utf8_lossy(&o.stderr)).trim().to_string(),
        Err(e) => format!("{prog}: {e}"),
    }
}

fn output(text: String) -> Value {
    json!({ "output": text })
}

// ---- readings ----

fn meminfo() -> HashMap<String, u64> {
    fs::read_to_string("/proc/meminfo")
        .unwrap_or_default()
        .lines()
        .filter_map(|l| {
            let (k, v) = l.split_once(':')?;
            Some((k.to_string(), v.split_whitespace().next()?.parse::<u64>().ok()? * 1024))
        })
        .collect()
}

fn mem_available() -> u64 {
    meminfo().get("MemAvailable").copied().unwrap_or(0)
}

#[cfg(not(unix))] // host builds only run the unit tests
fn disk(_: &str) -> (u64, u64) {
    (0, 0)
}

#[cfg(not(unix))]
fn page_size() -> u64 {
    4096
}

#[cfg(unix)]
fn page_size() -> u64 {
    (unsafe { libc::sysconf(libc::_SC_PAGESIZE) }).max(4096) as u64
}

/// (total, free) bytes of the filesystem holding `path`.
#[cfg(unix)]
fn disk(path: &str) -> (u64, u64) {
    let c = std::ffi::CString::new(path).unwrap();
    let mut s: libc::statvfs = unsafe { std::mem::zeroed() };
    if unsafe { libc::statvfs(c.as_ptr(), &mut s) } != 0 {
        return (0, 0);
    }
    let f = s.f_frsize as u64;
    (s.f_blocks as u64 * f, s.f_bavail as u64 * f)
}

fn first_number(path: &str) -> f64 {
    fs::read_to_string(path).ok().and_then(|s| s.split_whitespace().next()?.parse().ok()).unwrap_or(0.0)
}

fn temps() -> (Vec<Value>, i64) {
    let text = run("dumpsys", &["thermalservice"]);
    let status = text.lines().find_map(|l| l.trim().strip_prefix("Thermal Status:")?.trim().parse().ok()).unwrap_or(0);
    let section = text.split("Current temperatures from HAL:").nth(1).unwrap_or(&text);
    let mut seen = HashSet::new();
    let mut out = vec![];
    for l in section.lines().map(str::trim).filter(|l| l.starts_with("Temperature{")) {
        let field = |k: &str| l.split(&format!("{k}=")).nth(1).and_then(|r| r.split([',', '}']).next()).map(str::to_string);
        if let (Some(v), Some(n)) = (field("mValue").and_then(|v| v.parse::<f64>().ok()), field("mName")) {
            if v > 0.0 && seen.insert(n.clone()) {
                out.push(json!({ "name": n, "celsius": (v * 10.0).round() / 10.0 }));
            }
        }
    }
    (out, status)
}

fn battery() -> Value {
    let t = run("dumpsys", &["battery"]);
    let get = |k: &str| t.lines().find_map(|l| l.trim().strip_prefix(&format!("{k}: ")).map(|v| v.trim().to_string()));
    let num = |k: &str| get(k).and_then(|v| v.parse::<f64>().ok()).unwrap_or(0.0);
    json!({
        "level": num("level"),
        "celsius": num("temperature") / 10.0,
        "volts": num("voltage") / 1000.0,
        "charging": num("status") == 2.0,
        "health": num("health"),
    })
}

fn cpus() -> Vec<Value> {
    (0..32)
        .map_while(|i| {
            let base = format!("/sys/devices/system/cpu/cpu{i}");
            if !std::path::Path::new(&base).exists() {
                return None;
            }
            let read = |f: &str| first_number(&format!("{base}/cpufreq/{f}")) as u64 / 1000;
            Some(json!({ "cur": read("scaling_cur_freq"), "max": read("cpuinfo_max_freq") }))
        })
        .collect()
}

/// "cpu" (all cores) and "cpuN" lines of /proc/stat: name -> (total ticks, idle + iowait ticks).
fn cpu_times() -> HashMap<String, (u64, u64)> {
    fs::read_to_string("/proc/stat")
        .unwrap_or_default()
        .lines()
        .filter(|l| l.starts_with("cpu"))
        .filter_map(|l| {
            let mut it = l.split_whitespace();
            let name = it.next()?.to_string();
            let v: Vec<u64> = it.filter_map(|x| x.parse().ok()).collect();
            Some((name, (v.iter().sum(), v.get(3).unwrap_or(&0) + v.get(4).unwrap_or(&0))))
        })
        .collect()
}

/// Busy share (0-100) of `name` between two samples.
fn busy(a: &HashMap<String, (u64, u64)>, b: &HashMap<String, (u64, u64)>, name: &str) -> f64 {
    let (Some(x), Some(y)) = (a.get(name), b.get(name)) else { return 0.0 };
    let total = y.0.saturating_sub(x.0).max(1) as f64;
    let idle = y.1.saturating_sub(x.1) as f64;
    ((1.0 - idle / total).clamp(0.0, 1.0) * 1000.0).round() / 10.0
}

/// pid -> (cpu ticks, resident pages)
fn proc_ticks() -> HashMap<u32, (u64, u64)> {
    let mut m = HashMap::new();
    let Ok(dir) = fs::read_dir("/proc") else { return m };
    for e in dir.flatten() {
        let Ok(pid) = e.file_name().to_string_lossy().parse::<u32>() else { continue };
        let Ok(stat) = fs::read_to_string(format!("/proc/{pid}/stat")) else { continue };
        let Some((_, after)) = stat.rsplit_once(')') else { continue };
        // After the name: state is field 3, utime 14, stime 15, rss 24.
        let f: Vec<&str> = after.split_whitespace().collect();
        let n = |i: usize| f.get(i).and_then(|v| v.parse::<u64>().ok()).unwrap_or(0);
        // Own memory: resident pages minus pages shared with other processes (framework, fonts,
        // graphics driver). Plain RSS counts those shared pages in every app and looks huge.
        let statm = fs::read_to_string(format!("/proc/{pid}/statm")).unwrap_or_default();
        let sm: Vec<u64> = statm.split_whitespace().filter_map(|v| v.parse().ok()).collect();
        let own = match (sm.get(1), sm.get(2)) {
            (Some(res), Some(shared)) => res.saturating_sub(*shared),
            _ => n(21),
        };
        m.insert(pid, (n(11) + n(12), own));
    }
    m
}

fn proc_name(pid: u32) -> String {
    let raw = fs::read(format!("/proc/{pid}/cmdline")).unwrap_or_default();
    let first = String::from_utf8_lossy(raw.split(|b| *b == 0).next().unwrap_or(&[])).to_string();
    if !first.is_empty() {
        return first.rsplit('/').next().unwrap_or(&first).to_string();
    }
    fs::read_to_string(format!("/proc/{pid}/comm")).unwrap_or_default().trim().to_string()
}

/// Top processes, plus CPU use overall and per core, over one short sample.
fn processes() -> (Vec<Value>, f64, Vec<f64>) {
    let (c0, p0) = (cpu_times(), proc_ticks());
    thread::sleep(Duration::from_millis(800));
    let (c1, p1) = (cpu_times(), proc_ticks());
    // Without the shell Android hides /proc/stat; with no total to divide by, any share would be invented.
    let Some(dt) = c1.get("cpu").zip(c0.get("cpu")).map(|(b, a)| b.0.saturating_sub(a.0)).filter(|d| *d > 0) else {
        return (vec![], 0.0, vec![]);
    };
    let dt = dt as f64;
    let me = std::process::id();
    let cores = (0..32).take_while(|i| std::path::Path::new(&format!("/sys/devices/system/cpu/cpu{i}")).exists())
        .map(|i| busy(&c0, &c1, &format!("cpu{i}"))).collect();
    let page = page_size();
    let mut v: Vec<(f64, u64, u32)> = p1
        .iter()
        .filter(|(pid, _)| **pid != me) // the engine itself is not something to act on
        .map(|(pid, (c, rss))| {
            let d = c.saturating_sub(p0.get(pid).map(|x| x.0).unwrap_or(*c));
            (d as f64 / dt * 100.0, rss * page, *pid)
        })
        .collect();
    v.sort_by(|a, b| b.0.total_cmp(&a.0).then(b.1.cmp(&a.1)));
    let top = v.into_iter()
        .take(10)
        .map(|(cpu, rss, pid)| json!({ "pid": pid, "name": proc_name(pid), "cpu": (cpu * 10.0).round() / 10.0, "rss": rss }))
        .collect();
    (top, busy(&c0, &c1, "cpu"), cores)
}

/// Cheap reading for the live graphs: only /proc and sysfs, nothing that wakes system services.
fn live() -> Value {
    let c0 = cpu_times();
    thread::sleep(Duration::from_millis(500));
    let c1 = cpu_times();
    let mem = meminfo();
    let mut cpus = cpus();
    for (i, c) in cpus.iter_mut().enumerate() {
        c["busy"] = json!(busy(&c0, &c1, &format!("cpu{i}")));
    }
    json!({
        "cpuBusy": busy(&c0, &c1, "cpu"),
        "cpus": cpus,
        "memory": { "total": mem.get("MemTotal").copied().unwrap_or(0), "available": mem.get("MemAvailable").copied().unwrap_or(0) },
    })
}

fn setting(ns: &str, key: &str) -> f64 {
    run("cmd", &["settings", "get", ns, key]).parse().unwrap_or(1.0)
}

fn health() -> Value {
    let sampler = thread::spawn(processes);
    let mem = meminfo();
    let g = |k: &str| mem.get(k).copied().unwrap_or(0);
    let (disk_total, disk_free) = disk("/data");
    let (temps, thermal) = temps();
    let battery = battery();
    let mut cpus = cpus();
    let load: Vec<f64> = fs::read_to_string("/proc/loadavg")
        .unwrap_or_default()
        .split_whitespace()
        .take(3)
        .filter_map(|v| v.parse().ok())
        .collect();
    let uptime = first_number("/proc/uptime");
    let (procs, cpu_busy, core_busy) = sampler.join().unwrap_or_default();
    for (c, b) in cpus.iter_mut().zip(core_busy) {
        c["busy"] = json!(b);
    }

    let mut findings = vec![];
    let mut add = |level: &str, title: String, detail: String, action: &str| {
        findings.push(json!({ "level": level, "title": title, "detail": detail, "action": action }))
    };
    let free_pct = disk_free as f64 / disk_total.max(1) as f64 * 100.0;
    if free_pct < 10.0 {
        add("bad", format!("Storage {:.0}% full", 100.0 - free_pct),
            "Under 10% free slows every write. Clear cache and remove big apps or media.".into(), "storage");
    } else if free_pct < 20.0 {
        add("warn", format!("Storage {:.0}% full", 100.0 - free_pct), "Keep at least 15% free for full speed.".into(), "storage");
    }
    let avail_pct = g("MemAvailable") as f64 / g("MemTotal").max(1) as f64 * 100.0;
    if avail_pct < 20.0 {
        add("warn", format!("Only {avail_pct:.0}% memory free"), "Close background apps to make room.".into(), "free-ram");
    }
    if uptime > 3.0 * 86400.0 {
        add("warn", format!("Running for {:.0} days", uptime / 86400.0), "A restart clears built-up memory and stuck services.".into(), "reboot");
    }
    let hot = temps.iter().filter_map(|t| t["celsius"].as_f64()).fold(0.0, f64::max);
    if thermal >= 2 || battery["celsius"].as_f64().unwrap_or(0.0) > 42.0 {
        add("bad", "Phone is hot".into(), format!("Hottest sensor {hot:.0} °C; the phone slows itself down to cool off."), "");
    }
    if battery["health"].as_f64().unwrap_or(2.0) != 2.0 {
        add("warn", "Battery health is not good".into(), "The battery reports a fault or wear.".into(), "");
    }
    if let Some(p) = procs.first().filter(|p| p["cpu"].as_f64().unwrap_or(0.0) > 20.0) {
        add("warn", format!("{} is busy", p["name"].as_str().unwrap_or("?")),
            format!("Using {}% of the CPU right now.", p["cpu"]), "");
    }

    // One number for the hero ring: start at 100 and take off for each thing slowing the phone down.
    let mut score = 100.0_f64;
    score -= if free_pct < 10.0 { 30.0 } else if free_pct < 20.0 { 12.0 } else { 0.0 };
    score -= if avail_pct < 15.0 { 15.0 } else if avail_pct < 25.0 { 6.0 } else { 0.0 };
    score -= (thermal as f64 * 10.0).min(30.0);
    score -= if battery["celsius"].as_f64().unwrap_or(0.0) > 40.0 { 10.0 } else { 0.0 };
    score -= if cpu_busy > 80.0 { 10.0 } else { 0.0 };
    score -= if uptime > 3.0 * 86400.0 { 5.0 } else { 0.0 };

    json!({
        "score": score.max(0.0).round(),
        "memory": { "total": g("MemTotal"), "available": g("MemAvailable"), "swapTotal": g("SwapTotal"), "swapFree": g("SwapFree") },
        "storage": { "total": disk_total, "free": disk_free },
        "load": load,
        "cpuBusy": cpu_busy,
        "uptime": uptime,
        "temps": temps,
        "thermalStatus": thermal,
        "battery": battery,
        "cpus": cpus,
        "processes": procs,
        "display": {
            "peakRefresh": setting("system", "peak_refresh_rate"),
            "animation": setting("global", "animator_duration_scale"),
        },
        "findings": findings,
    })
}

// ---- app optimisation ----

/// Per package, the compile state of its base APK.
fn dexopt_scan() -> BTreeMap<String, String> {
    let text = run("cmd", &["package", "art", "dump"]);
    let mut out = BTreeMap::new();
    let mut current: Option<String> = None;
    for l in text.lines() {
        let t = l.trim();
        if (l.starts_with('[') || l.starts_with("  [")) && t.ends_with(']') && !t.contains(' ') && !t.contains('=') {
            current = Some(t[1..t.len() - 1].to_string());
            continue;
        }
        if let (Some(pkg), Some(i)) = (&current, t.find("[status=")) {
            out.insert(pkg.clone(), t[i + 8..].split(']').next().unwrap_or("").to_string());
            current = None;
        }
    }
    out
}

/// Whether Android's optimisation job started from a shell is still running.
fn job_running() -> bool {
    let Ok(dir) = fs::read_dir("/proc") else { return false };
    dir.flatten().any(|e| {
        let raw = fs::read(e.path().join("cmdline")).unwrap_or_default();
        let line = String::from_utf8_lossy(&raw).replace('\0', " ");
        line.contains("bg-dexopt-job") && !line.contains("--cancel") && !line.contains("libhone")
    })
}

/// The app dex2oat is compiling right now, from its command line.
fn compiling() -> Option<String> {
    for e in fs::read_dir("/proc").ok()?.flatten() {
        let raw = fs::read(e.path().join("cmdline")).unwrap_or_default();
        let mut parts = raw.split(|b| *b == 0).map(|p| String::from_utf8_lossy(p).to_string());
        if !parts.next().is_some_and(|p| p.contains("dex2oat")) {
            continue;
        }
        if let Some(loc) = parts.find_map(|p| p.strip_prefix("--zip-location=").map(str::to_string)) {
            let dir = loc.rsplit('/').nth(1).unwrap_or(&loc);
            return Some(dir.split('-').next().unwrap_or(dir).to_string());
        }
    }
    None
}

fn dexopt() -> Value {
    let scan = dexopt_scan();
    let mut counts: BTreeMap<&str, u32> = BTreeMap::new();
    for s in scan.values() {
        *counts.entry(s.as_str()).or_insert(0) += 1;
    }
    let slow: Vec<&String> = scan.iter().filter(|(_, s)| matches!(s.as_str(), "verify" | "run-from-apk" | "extract")).map(|(p, _)| p).collect();
    json!({ "counts": counts, "status": scan, "slow": slow, "compiling": compiling() })
}

fn optimize() -> Value {
    let out = run("cmd", &["package", "bg-dexopt-job"]);
    let mut v = dexopt();
    v["output"] = json!(out);
    v
}

fn compile(rest: &[String]) -> Value {
    let mode = arg(rest, 0);
    if !matches!(mode, "speed" | "speed-profile" | "verify") {
        return json!({ "error": format!("bad mode '{mode}'") });
    }
    let results: Vec<Value> = rest[1..]
        .iter()
        .map(|p| {
            let out = run("cmd", &["package", "compile", "-m", mode, "-f", p]);
            let dump = run("cmd", &["package", "art", "dump", p]);
            let status = dump.find("[status=").map(|i| dump[i + 8..].split(']').next().unwrap_or("").to_string()).unwrap_or_default();
            let fast = matches!(status.as_str(), "speed" | "speed-profile" | "everything");
            let reason = dump.find("[reason=").map(|i| dump[i + 8..].split(']').next().unwrap_or("").to_string()).unwrap_or_default();
            let why = if fast {
                ""
            } else if out.contains("Failure") || dump.contains("location is error") {
                "rejected"
            } else if reason == "vdex-dm" {
                "play"
            } else {
                "other"
            };
            let detail = if why == "rejected" {
                dex2oat_error(&run("logcat", &["-d", "-t", "200", "-s", "dex2oat64:E", "dex2oat32:E", "dex2oat:E"]), p)
            } else {
                String::new()
            };
            json!({ "pkg": p, "ok": out.contains("Success") && fast, "status": status, "why": why, "detail": detail, "output": out })
        })
        .collect();
    json!({ "results": results })
}

/// dex2oat's own last error for `pkg` from its log lines, without the long file path.
fn dex2oat_error(log: &str, pkg: &str) -> String {
    let line = log.lines().rev().find(|l| l.contains(&format!("/{pkg}-"))).unwrap_or("");
    let msg = line.rsplit("': ").next().unwrap_or("");
    let msg = if msg == line { line.split_once(": ").map_or("", |(_, m)| m).rsplit(": ").next().unwrap_or("") } else { msg };
    msg.trim().chars().take(160).collect()
}

// ---- memory, cache, packages ----

/// Closes background apps. With a keep list, closes them one by one and skips those; `am kill` only
/// touches background processes, so whatever is on screen or playing is safe either way.
fn free_ram(keep: &[String]) -> Value {
    let before = mem_available();
    let closed = if keep.is_empty() {
        run("am", &["kill-all"]);
        0
    } else {
        let keep: HashSet<&str> = keep.iter().map(String::as_str).collect();
        let targets: Vec<String> = running_set()
            .into_iter()
            .filter(|p| !keep.contains(p.as_str()) && !matches!(p.as_str(), "android" | "system" | "com.android.systemui" | "com.android.phone"))
            .collect();
        let count = targets.len();
        let jobs: Vec<_> = targets
            .chunks(count.div_ceil(6).max(1))
            .map(|c| {
                let c = c.to_vec();
                thread::spawn(move || c.iter().for_each(|p| { run("cmd", &["activity", "kill", p]); }))
            })
            .collect();
        jobs.into_iter().for_each(|j| { let _ = j.join(); });
        count
    };
    thread::sleep(Duration::from_millis(1500));
    let after = mem_available();
    json!({ "freed": after.saturating_sub(before), "available": after, "apps": closed })
}

fn trim_caches() -> Value {
    let before = disk("/data").1;
    let out = run("cmd", &["package", "trim-caches", "999G"]);
    let after = disk("/data").1;
    json!({ "freed": after.saturating_sub(before), "free": after, "output": out })
}

/// Clears each app's cache with `pm clear --cache-only` (Android 14 and newer). On older builds the
/// first answer is a usage error, and the app falls back to the package manager call.
fn clear_cache(pkgs: &[String]) -> Value {
    let (mut cleared, mut failed) = (0, 0);
    for p in pkgs.iter().filter(|p| p.as_str() != system::HONE) {
        let out = run("cmd", &["package", "clear", "--cache-only", p]);
        if out.contains("Success") {
            cleared += 1;
        } else if cleared == 0 && failed == 0 && unsupported_option(&out) {
            return json!({ "unsupported": true });
        } else {
            failed += 1;
        }
    }
    json!({ "cleared": cleared, "failed": failed })
}

fn unsupported_option(out: &str) -> bool {
    let o = out.to_ascii_lowercase();
    o.contains("unknown option") || o.contains("--cache-only") || o.contains("usage:")
}

fn list(extra: &[&str]) -> HashSet<String> {
    let args = [&["list", "packages"][..], extra].concat();
    run("cmd", &[&["package"][..], &args].concat())
        .lines()
        .filter_map(|l| l.strip_prefix("package:"))
        .map(|s| s.trim().to_string())
        .collect()
}

fn packages() -> Value {
    // Three lists at once: each one is a separate binder round trip.
    let (installed, all, disabled) = {
        let a = thread::spawn(|| list(&["-s"]));
        let b = thread::spawn(|| list(&["-s", "-u"]));
        let c = list(&["-d"]);
        (a.join().unwrap_or_default(), b.join().unwrap_or_default(), c)
    };
    let mut v: Vec<Value> = all
        .iter()
        .filter_map(|p| {
            let removed = !installed.contains(p);
            let (tag, note) = debloat::tag(p).or(removed.then_some(("other", "")))?;
            let state = if removed { "removed" } else if disabled.contains(p) { "disabled" } else { "installed" };
            Some(json!({ "pkg": p, "tag": tag, "note": note, "state": state }))
        })
        .collect();
    v.sort_by(|a, b| a["pkg"].as_str().cmp(&b["pkg"].as_str()));
    json!({ "packages": v })
}

/// Package names with a live process (a ":service" suffix belongs to the same app).
fn running() -> Value {
    json!({ "packages": running_set().into_iter().collect::<Vec<_>>() })
}

fn running_set() -> HashSet<String> {
    let mut set = HashSet::new();
    if let Ok(dir) = fs::read_dir("/proc") {
        for e in dir.flatten() {
            if !e.file_name().to_string_lossy().chars().all(|c| c.is_ascii_digit()) {
                continue;
            }
            let raw = fs::read(e.path().join("cmdline")).unwrap_or_default();
            let first = String::from_utf8_lossy(raw.split(|b| *b == 0).next().unwrap_or(&[])).to_string();
            let name = first.split(':').next().unwrap_or("");
            if name.contains('.') && !name.starts_with('/') {
                set.insert(name.to_string());
            }
        }
    }
    set
}

fn bg(rest: &[String], mode: &str) -> Value {
    let pkg = arg(rest, 0);
    if pkg.is_empty() || pkg == "io.max.hone" || !pkg.contains('.') {
        return json!({ "ok": false, "output": "not allowed" });
    }
    let out = run("cmd", &["appops", "set", pkg, "RUN_ANY_IN_BACKGROUND", mode]);
    json!({ "ok": out.is_empty() || !out.contains("Error"), "output": out })
}

/// One package-manager action on an app the user installed. Hone itself is never a target.
fn app_action(rest: &[String], cmd: &[&str], ok_word: &str) -> Value {
    let pkg = arg(rest, 0);
    if pkg.is_empty() || pkg == "io.max.hone" || !pkg.contains('.') {
        return json!({ "ok": false, "output": "not allowed" });
    }
    let out = if cmd.is_empty() {
        run("am", &["force-stop", pkg])
    } else {
        run("cmd", &[&["package"][..], cmd, &[pkg]].concat())
    };
    let ok = !out.contains("Exception") && !out.contains("Error") && !out.contains("Failure") && (ok_word.is_empty() || out.contains(ok_word));
    json!({ "ok": ok, "output": out })
}

fn change(rest: &[String], remove: bool) -> Value {
    let pkg = arg(rest, 0);
    if remove && !debloat::removable(pkg) {
        return json!({ "ok": false, "output": format!("{pkg} is not on the safe list") });
    }
    let out = if remove {
        run("cmd", &["package", "uninstall", "-k", "--user", "0", pkg])
    } else {
        run("cmd", &["package", "install-existing", pkg])
    };
    json!({ "ok": out.contains("Success") || out.contains("installed for user"), "output": out })
}

#[cfg(test)]
mod tests {
    #[test]
    fn safe_list_blocks_core_parts() {
        for core in ["android", "com.android.systemui", "com.google.android.gms", "com.android.phone", "com.nothing.launcher"] {
            assert!(!super::debloat::removable(core), "{core} must never be removable");
        }
        assert!(super::debloat::removable("com.android.egg"));
    }

    #[test]
    fn spots_a_build_without_cache_only() {
        assert!(super::unsupported_option("Error: Unknown option: --cache-only"));
        assert!(!super::unsupported_option("Failed"));
    }

    #[test]
    fn reads_dex2oat_error_for_the_package() {
        let log = "11-24 11:33:57.964 23595 23595 E dex2oat64: Failed to verify /data/app/~~x==/eu.darken.sdmse-J==/base.apk!4: Failure to verify dex file '/data/app/~~x==/eu.darken.sdmse-J==/base.apk!4': Offset(1233428) should be zero when size is zero for class-defs.\n\
                   11-24 11:34:00.000 1 1 E dex2oat64: Failed to verify /data/app/~~y==/com.other-K==/base.apk: boom";
        assert_eq!(super::dex2oat_error(log, "eu.darken.sdmse"), "Offset(1233428) should be zero when size is zero for class-defs.");
        assert_eq!(super::dex2oat_error(log, "com.other"), "boom");
        assert_eq!(super::dex2oat_error(log, "com.none"), "");
    }
}
