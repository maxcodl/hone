//! Battery history, charge sessions, charger quality and alerts.
//!
//! The app's monitor hands each reading to `log`, which appends one line to `battery.csv` in the
//! app's own files folder and answers which alerts to show. `history` turns the file into the
//! chart, sessions and charger verdicts. Everything runs as Hone itself; no shell needed.

use serde_json::{json, Value};
use std::collections::HashMap;
use std::fs;
use std::io::Write;
use std::path::Path;

const KEEP_SECS: u64 = 14 * 86_400;
const DAY: u64 = 86_400;

#[derive(Clone, Copy, Debug, PartialEq)]
struct Sample {
    ts: u64,
    level: f64,
    status: i64, // 2 charging, 3 discharging, 4 not charging, 5 full
    plugged: i64, // 0 none, 1 AC, 2 USB, 4 wireless, 8 dock
    celsius: f64,
    mv: i64,
    ma: i64, // current, sign as the phone reports it
    screen: bool,
}

fn parse(line: &str) -> Option<Sample> {
    let f: Vec<&str> = line.split(',').collect();
    Some(Sample {
        ts: f.first()?.parse().ok()?,
        level: f.get(1)?.parse().ok()?,
        status: f.get(2)?.parse().ok()?,
        plugged: f.get(3)?.parse().ok()?,
        celsius: f.get(4)?.parse().ok()?,
        mv: f.get(5)?.parse().ok()?,
        ma: f.get(6)?.parse().ok()?,
        screen: f.get(7)? == &"1",
    })
}

fn load(dir: &str) -> Vec<Sample> {
    fs::read_to_string(Path::new(dir).join("battery.csv")).unwrap_or_default().lines().filter_map(parse).collect()
}

fn source(plugged: i64) -> &'static str {
    match plugged {
        1 => "AC",
        2 => "USB",
        4 => "Wireless",
        8 => "Dock",
        _ => "Battery",
    }
}

/// Charging current in mA whatever sign the phone uses.
fn charge_ma(s: &Sample) -> i64 {
    s.ma.abs()
}

fn read_json(path: &Path) -> Value {
    fs::read_to_string(path).ok().and_then(|t| serde_json::from_str(&t).ok()).unwrap_or(json!({}))
}

/// args: ts level status plugged celsius mv ma screen(0/1)
pub fn log(dir: &str, args: &[String]) -> Value {
    let line = args.join(",");
    let Some(now) = parse(&line) else { return json!({ "error": "bad reading" }) };
    let path = Path::new(dir).join("battery.csv");
    let _ = fs::create_dir_all(dir);
    if let Ok(mut f) = fs::OpenOptions::new().create(true).append(true).open(&path) {
        let _ = writeln!(f, "{line}");
    }
    let mut samples = load(dir);
    // Keep two weeks; rewrite only when the oldest line is a day past that, so most calls just append.
    if samples.first().map(|s| s.ts + KEEP_SECS + DAY < now.ts).unwrap_or(false) {
        samples.retain(|s| s.ts + KEEP_SECS >= now.ts);
        let text: String = samples
            .iter()
            .map(|s| format!("{},{},{},{},{},{},{},{}\n", s.ts, s.level, s.status, s.plugged, s.celsius, s.mv, s.ma, if s.screen { 1 } else { 0 }))
            .collect();
        let _ = fs::write(&path, text);
    }
    json!({ "notify": alerts(dir, &samples, &now) })
}

/// Fires each alert once when its condition starts; it re-arms when the condition ends.
fn alerts(dir: &str, samples: &[Sample], now: &Sample) -> Vec<Value> {
    let cfg = read_json(&Path::new(dir).join("alerts.json"));
    let state_path = Path::new(dir).join("alert_state.json");
    let mut state: HashMap<String, bool> = serde_json::from_value(read_json(&state_path)).unwrap_or_default();
    let on = |k: &str| cfg[k]["on"].as_bool().unwrap_or(false);
    let num = |k: &str, d: f64| cfg[k]["value"].as_f64().unwrap_or(d);
    let plugged = now.plugged > 0;

    let recent = |secs: u64| samples.iter().filter(move |s| s.ts + secs >= now.ts);
    let slow = {
        let r: Vec<&Sample> = recent(300).filter(|s| s.plugged > 0 && s.status == 2).collect();
        r.len() >= 3 && now.level < 90.0 && (r.iter().map(|s| charge_ma(s)).sum::<i64>() / r.len() as i64) < num("slowCharge", 500.0) as i64
    };
    let drain = drain_per_hour(&recent(3600).filter(|s| s.plugged == 0 && !s.screen).copied().collect::<Vec<_>>());

    let checks: Vec<(&str, bool, String, String)> = vec![
        ("chargeLimit", plugged && now.level >= num("chargeLimit", 80.0),
            format!("Charged to {:.0}%", now.level), "Unplug now to go easy on the battery.".into()),
        ("slowCharge", plugged && slow,
            "Charging slowly".into(), format!("Under {:.0} mA. Try another cable or charger.", num("slowCharge", 500.0))),
        ("low", !plugged && now.level <= num("low", 20.0),
            format!("Battery at {:.0}%", now.level), "Plug in soon.".into()),
        ("fastDrain", !plugged && !now.screen && drain.map(|d| d >= num("fastDrain", 8.0)).unwrap_or(false),
            "Draining fast with the screen off".into(), format!("{:.1}% an hour. Something is awake in the background.", drain.unwrap_or(0.0))),
        ("hot", now.celsius >= num("hot", 40.0),
            format!("Battery at {:.0} °C", now.celsius), "Let the phone cool down; heat wears the battery.".into()),
    ];
    let mut out = vec![];
    for (id, active, title, text) in checks {
        let was = state.get(id).copied().unwrap_or(false);
        if active && !was && on(id) {
            out.push(json!({ "id": id, "title": title, "text": text }));
        }
        state.insert(id.to_string(), active);
    }
    let _ = fs::write(&state_path, serde_json::to_string(&state).unwrap_or_default());
    out
}

/// Percent per hour lost over a run of unplugged samples; none if it spans under 20 minutes.
fn drain_per_hour(run: &[Sample]) -> Option<f64> {
    let (first, last) = (run.first()?, run.last()?);
    let hours = (last.ts.saturating_sub(first.ts)) as f64 / 3600.0;
    if hours < 0.33 {
        return None;
    }
    Some(((first.level - last.level).max(0.0)) / hours)
}

pub fn history(dir: &str, now: u64) -> Value {
    let samples = load(dir);
    let day: Vec<&Sample> = samples.iter().filter(|s| s.ts + DAY >= now).collect();
    let step = (day.len() / 240).max(1);
    let points: Vec<Value> = day.iter().step_by(step).map(|s| json!([s.ts, s.level])).collect();

    // Charge sessions: plugged-in runs, broken by unplugging or a gap over ten minutes.
    let mut sessions: Vec<Vec<&Sample>> = vec![];
    for s in samples.iter().filter(|s| s.plugged > 0) {
        match sessions.last_mut() {
            Some(cur) if cur.last().map(|p| s.ts - p.ts <= 600 && p.plugged == s.plugged).unwrap_or(false) => cur.push(s),
            _ => sessions.push(vec![s]),
        }
    }
    let sessions: Vec<Value> = sessions
        .iter()
        .filter(|run| run.len() >= 2 && run.last().unwrap().ts > run[0].ts)
        .map(|run| {
            let (a, b) = (run[0], run[run.len() - 1]);
            let charging: Vec<i64> = run.iter().filter(|s| s.status == 2).map(|s| charge_ma(s)).collect();
            let avg_ma = if charging.is_empty() { 0 } else { charging.iter().sum::<i64>() / charging.len() as i64 };
            let mins = (b.ts - a.ts) as f64 / 60.0;
            let watts = run.iter().filter(|s| s.status == 2).map(|s| charge_ma(s) as f64 * s.mv as f64 / 1e6).sum::<f64>() / charging.len().max(1) as f64;
            json!({
                "start": a.ts, "end": b.ts, "from": a.level, "to": b.level, "source": source(a.plugged),
                "minutes": mins.round(), "avgMa": avg_ma, "watts": (watts * 10.0).round() / 10.0,
                "perHour": if mins > 1.0 { ((b.level - a.level) / mins * 60.0 * 10.0).round() / 10.0 } else { 0.0 },
                "maxCelsius": run.iter().map(|s| s.celsius).fold(0.0, f64::max),
            })
        })
        .collect();

    // Charger verdict per source, from the average charging current across its sessions.
    let mut by_source: HashMap<String, Vec<i64>> = HashMap::new();
    for s in &sessions {
        let ma = s["avgMa"].as_i64().unwrap_or(0);
        if ma > 0 {
            by_source.entry(s["source"].as_str().unwrap_or("").to_string()).or_default().push(ma);
        }
    }
    let chargers: Vec<Value> = by_source
        .iter()
        .map(|(src, mas)| {
            let avg = mas.iter().sum::<i64>() / mas.len() as i64;
            let verdict = if avg >= 2000 { "Fast" } else if avg >= 1000 { "Normal" } else { "Slow" };
            json!({ "source": src, "avgMa": avg, "sessions": mas.len(), "verdict": verdict })
        })
        .collect();

    // Drain over the last day, split by screen state.
    let unplugged: Vec<&Sample> = day.iter().copied().filter(|s| s.plugged == 0).collect();
    let mut used = 0.0;
    let (mut on_drop, mut on_secs, mut off_drop, mut off_secs) = (0.0, 0u64, 0.0, 0u64);
    for w in unplugged.windows(2) {
        let (a, b) = (w[0], w[1]);
        let dt = b.ts - a.ts;
        if dt > 900 {
            continue; // a gap: monitor was off or the phone slept without readings
        }
        let drop = (a.level - b.level).max(0.0);
        used += drop;
        if a.screen { on_drop += drop; on_secs += dt } else { off_drop += drop; off_secs += dt }
    }
    let rate = |d: f64, s: u64| if s >= 600 { Some((d / (s as f64 / 3600.0) * 10.0).round() / 10.0) } else { None };

    let mut recent = sessions.clone();
    recent.reverse();
    recent.truncate(10);
    json!({
        "points": points,
        "sessions": recent,
        "chargers": chargers,
        "used24h": used.round(),
        "screenOnPerHour": rate(on_drop, on_secs),
        "screenOffPerHour": rate(off_drop, off_secs),
        "since": samples.first().map(|s| s.ts),
        "samples": samples.len(),
    })
}

/// What the kernel exposes about the cell; each field is missing when the phone hides it.
pub fn info() -> Value {
    let base = Path::new("/sys/class/power_supply/battery");
    let read = |f: &str| fs::read_to_string(base.join(f)).ok().map(|s| s.trim().to_string()).filter(|s| !s.is_empty());
    let num = |f: &str| read(f).and_then(|v| v.parse::<f64>().ok());
    let full = num("charge_full");
    // Kernels mix units (uAh, 10 uAh, mAh); bring the design figure to the same scale as the measured one.
    let design = match (full, num("charge_full_design")) {
        (Some(f), Some(mut d)) if d > 0.0 => {
            while f / d > 5.0 { d *= 10.0 }
            while d / f > 5.0 { d /= 10.0 }
            Some(d)
        }
        (_, d) => d,
    };
    json!({
        "cycles": num("cycle_count"),
        "technology": read("technology"),
        "chargeFullMah": full.map(|v| (v / 1000.0).round()),
        "designMah": design.map(|v| (v / 1000.0).round()),
        "healthPercent": match (full, design) { (Some(f), Some(d)) if d > 0.0 => Some(((f / d).min(1.0) * 1000.0).round() / 10.0), _ => None },
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    fn s(ts: u64, level: f64, plugged: i64, ma: i64, screen: bool) -> Sample {
        Sample { ts, level, status: if plugged > 0 { 2 } else { 3 }, plugged, celsius: 30.0, mv: 4000, ma, screen }
    }

    #[test]
    fn drain_needs_twenty_minutes_and_measures_per_hour() {
        assert_eq!(drain_per_hour(&[s(0, 50.0, 0, 0, false), s(600, 49.0, 0, 0, false)]), None);
        assert_eq!(drain_per_hour(&[s(0, 50.0, 0, 0, false), s(3600, 45.0, 0, 0, false)]), Some(5.0));
    }

    #[test]
    fn reading_round_trips_through_a_line() {
        let line = "100,80,2,2,31.5,4200,-1500,1";
        let p = parse(line).unwrap();
        assert_eq!((p.level, p.plugged, p.ma, p.screen), (80.0, 2, -1500, true));
        assert_eq!(charge_ma(&p), 1500);
    }
}
