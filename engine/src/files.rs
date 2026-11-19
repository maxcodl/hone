//! Storage analyzer and a trash that never deletes on the first step.
//!
//! Everything stays inside shared storage. Removing moves files into `.HoneTrash` (same filesystem,
//! so it is an instant rename) with a manifest of where each came from; only "empty trash" deletes.

use serde_json::{json, Value};
use std::collections::{HashMap, HashSet};
use std::fs;
use std::hash::{Hash, Hasher};
use std::io::{Read, Seek, SeekFrom};
use std::path::{Component, Path, PathBuf};
use std::time::{SystemTime, UNIX_EPOCH};

pub const ROOT: &str = "/storage/emulated/0";
const TRASH: &str = "/storage/emulated/0/.HoneTrash";
const LIST_LIMIT: usize = 2000;

/// Top-level folders apps and the system expect to exist; they can be emptied but never removed.
const MAIN: &[&str] = &[
    "Alarms", "Android", "Audiobooks", "DCIM", "Documents", "Download", "Movies", "Music",
    "Notifications", "Pictures", "Podcasts", "Recordings", "Ringtones",
];

struct File {
    path: PathBuf,
    bytes: u64,
    modified: u64,
}

fn now() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn mtime(m: &fs::Metadata) -> u64 {
    m.modified().ok().and_then(|t| t.duration_since(UNIX_EPOCH).ok()).map(|d| d.as_secs()).unwrap_or(0)
}

fn ext(name: &str) -> String {
    name.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default()
}

fn category(name: &str) -> &'static str {
    match ext(name).as_str() {
        "jpg" | "jpeg" | "png" | "webp" | "heic" | "heif" | "gif" | "bmp" | "dng" | "raw" => "Images",
        "mp4" | "mkv" | "mov" | "webm" | "3gp" | "avi" | "m4v" | "ts" => "Videos",
        "mp3" | "m4a" | "flac" | "wav" | "ogg" | "opus" | "aac" | "amr" => "Audio",
        "pdf" | "doc" | "docx" | "xls" | "xlsx" | "ppt" | "pptx" | "txt" | "md" | "odt" | "csv" | "epub" => "Documents",
        "apk" | "apks" | "xapk" | "apkm" => "APKs",
        "zip" | "rar" | "7z" | "tar" | "gz" | "xz" | "zst" | "bz2" => "Archives",
        _ => "Other",
    }
}

fn is_temp(path: &Path) -> bool {
    let s = path.to_string_lossy();
    matches!(ext(&s).as_str(), "tmp" | "temp" | "log" | "part" | "crdownload" | "partial") || s.contains("/.thumbnails/")
}

fn file_json(f: &File) -> Value {
    json!({ "path": f.path.to_string_lossy(), "bytes": f.bytes, "modified": f.modified })
}

/// Every regular file under `root` (symlinks skipped, the trash skipped) plus folders with nothing in them.
/// Storage sits behind FUSE, where each call waits, so the top folders are walked in parallel.
fn walk(root: &Path) -> (Vec<File>, Vec<PathBuf>) {
    let mut files = vec![];
    let mut empty = vec![];
    let mut jobs = vec![];
    let Ok(rd) = fs::read_dir(root) else { return (files, empty) };
    for e in rd.flatten() {
        let path = e.path();
        if path.as_os_str() == TRASH {
            continue;
        }
        let Ok(m) = fs::symlink_metadata(&path) else { continue };
        if m.is_dir() && !m.file_type().is_symlink() {
            jobs.push(std::thread::spawn(move || {
                let (f, mut em) = walk_one(&path);
                if f.is_empty() && em.is_empty() && fs::read_dir(&path).map(|mut r| r.next().is_none()).unwrap_or(false) {
                    em.push(path);
                }
                (f, em)
            }));
        } else if m.is_file() {
            files.push(File { path, bytes: m.len(), modified: mtime(&m) });
        }
    }
    for j in jobs {
        if let Ok((f, em)) = j.join() {
            files.extend(f);
            empty.extend(em);
        }
    }
    (files, empty)
}

fn walk_one(root: &Path) -> (Vec<File>, Vec<PathBuf>) {
    let mut files = vec![];
    let mut empty = vec![];
    let mut stack = vec![root.to_path_buf()];
    while let Some(dir) = stack.pop() {
        let Ok(rd) = fs::read_dir(&dir) else { continue };
        let mut any = false;
        for e in rd.flatten() {
            any = true;
            let path = e.path();
            if path.as_os_str() == TRASH {
                continue;
            }
            let Ok(m) = fs::symlink_metadata(&path) else { continue };
            if m.file_type().is_symlink() {
                continue;
            }
            if m.is_dir() {
                stack.push(path);
            } else if m.is_file() {
                files.push(File { path, bytes: m.len(), modified: mtime(&m) });
            }
        }
        if !any && dir != root {
            empty.push(dir);
        }
    }
    (files, empty)
}

pub fn dir_size(dir: &Path) -> (u64, u64) {
    let (files, _) = walk(dir);
    (files.iter().map(|f| f.bytes).sum(), files.len() as u64)
}

/// Hash of three 64 KB samples (start, middle, end). Same size plus same samples means a copy.
fn sample_hash(path: &Path, size: u64) -> Option<u64> {
    let mut f = fs::File::open(path).ok()?;
    let mut h = std::collections::hash_map::DefaultHasher::new();
    let mut buf = vec![0u8; 64 * 1024];
    for at in [0, size / 2, size.saturating_sub(64 * 1024)] {
        f.seek(SeekFrom::Start(at)).ok()?;
        let n = f.read(&mut buf).ok()?;
        buf[..n].hash(&mut h);
    }
    Some(h.finish())
}

fn group(id: &str, title: &str, detail: &str, mut files: Vec<&File>) -> Value {
    files.sort_by(|a, b| b.bytes.cmp(&a.bytes));
    let bytes: u64 = files.iter().map(|f| f.bytes).sum();
    let count = files.len();
    json!({
        "id": id, "title": title, "detail": detail, "bytes": bytes, "count": count,
        "files": files.iter().take(LIST_LIMIT).map(|f| file_json(f)).collect::<Vec<_>>(),
    })
}

pub fn scan(installed: &HashSet<String>) -> Value {
    let root = Path::new(ROOT);
    let (files, empty) = walk(root);
    // Cleanup suggestions never reach into Android/: those files belong to apps, and removing them
    // can break an app. Folders of uninstalled apps are the one exception, handled below.
    let android = root.join("Android");
    let user: Vec<&File> = files.iter().filter(|f| !f.path.starts_with(&android)).collect();

    let mut cats: HashMap<&str, (u64, u64)> = HashMap::new();
    let mut folders: HashMap<PathBuf, (u64, u64)> = HashMap::new();
    for f in &files {
        let name = f.path.file_name().map(|n| n.to_string_lossy().to_string()).unwrap_or_default();
        let c = cats.entry(category(&name)).or_default();
        c.0 += f.bytes;
        c.1 += 1;
        // Charge each file to its top folder and to the folder one level below.
        if let Ok(rel) = f.path.strip_prefix(root) {
            let comps: Vec<_> = rel.components().collect();
            for depth in 1..=2.min(comps.len().saturating_sub(1)) {
                let key = root.join(comps[..depth].iter().collect::<PathBuf>());
                let e = folders.entry(key).or_default();
                e.0 += f.bytes;
                e.1 += 1;
            }
        }
    }
    let total: u64 = files.iter().map(|f| f.bytes).sum();

    let mut categories: Vec<Value> = cats.iter().map(|(n, (b, c))| json!({ "name": n, "bytes": b, "count": c })).collect();
    categories.sort_by(|a, b| b["bytes"].as_u64().cmp(&a["bytes"].as_u64()));
    let mut top: Vec<(&PathBuf, &(u64, u64))> = folders.iter().collect();
    top.sort_by(|a, b| b.1 .0.cmp(&a.1 .0));
    let folders: Vec<Value> = top.iter().take(30).map(|(p, (b, c))| json!({ "path": p.to_string_lossy(), "bytes": b, "files": c })).collect();
    let mut largest: Vec<&File> = files.iter().collect();
    largest.sort_by(|a, b| b.bytes.cmp(&a.bytes));
    let largest: Vec<Value> = largest.iter().take(50).map(|f| file_json(f)).collect();

    // ---- cleanup groups ----
    let apks: Vec<&File> = user.iter().copied().filter(|f| category(&f.path.to_string_lossy()) == "APKs").collect();
    let large: Vec<&File> = user.iter().copied().filter(|f| f.bytes >= 100 * 1024 * 1024).collect();
    let mut thumbs: HashMap<PathBuf, (u64, u64)> = HashMap::new();
    let mut temp: Vec<&File> = vec![];
    for f in user.iter().copied().filter(|f| is_temp(&f.path)) {
        let s = f.path.to_string_lossy();
        if let Some(i) = s.find("/.thumbnails/") {
            let e = thumbs.entry(PathBuf::from(&s[..i + "/.thumbnails".len()])).or_default();
            e.0 += f.bytes;
            e.1 = e.1.max(f.modified);
        } else {
            temp.push(f);
        }
    }
    let thumb_dirs: Vec<File> = thumbs.into_iter().map(|(path, (bytes, modified))| File { path, bytes, modified }).collect();
    temp.extend(thumb_dirs.iter());

    let mut by_size: HashMap<u64, Vec<&File>> = HashMap::new();
    for f in user.iter().copied().filter(|f| f.bytes >= 1024 * 1024) {
        by_size.entry(f.bytes).or_default().push(f);
    }
    let mut copies: Vec<&File> = vec![];
    for same in by_size.values().filter(|v| v.len() > 1) {
        let mut by_hash: HashMap<u64, Vec<&File>> = HashMap::new();
        for f in same {
            if let Some(h) = sample_hash(&f.path, f.bytes) {
                by_hash.entry(h).or_default().push(f);
            }
        }
        for mut set in by_hash.into_values().filter(|v| v.len() > 1) {
            // Keep the oldest copy, offer the rest.
            set.sort_by_key(|f| f.modified);
            copies.extend(set.into_iter().skip(1));
        }
    }

    // Folders apps leave in Android/data and Android/obb after they are uninstalled.
    let mut leftovers: Vec<File> = vec![];
    if installed.len() > 20 {
        for base in ["Android/data", "Android/obb", "Android/media"] {
            let Ok(rd) = fs::read_dir(root.join(base)) else { continue };
            for e in rd.flatten() {
                let name = e.file_name().to_string_lossy().to_string();
                if name.starts_with('.') || installed.contains(&name) {
                    continue;
                }
                let (bytes, _) = dir_size(&e.path());
                leftovers.push(File { path: e.path(), bytes, modified: e.metadata().map(|m| mtime(&m)).unwrap_or(0) });
            }
        }
    }
    let empty: Vec<File> = empty
        .into_iter()
        // Skip top folders, hidden folders (other apps' bookkeeping) and anything under Android/.
        .filter(|p| p.strip_prefix(root).map(|r| r.components().count() > 1).unwrap_or(false))
        .filter(|p| !p.starts_with(&android))
        .filter(|p| !p.strip_prefix(root).map(|r| r.components().any(|c| c.as_os_str().to_string_lossy().starts_with('.'))).unwrap_or(true))
        .map(|path| File { path, bytes: 0, modified: 0 })
        .collect();

    // WhatsApp keeps a week of dated chat backups next to the current one (msgstore.db.crypt*).
    let wa_dbs = ["com.whatsapp/WhatsApp", "com.whatsapp.w4b/WhatsApp Business"].map(|d| root.join("Android/media").join(d).join("Databases"));
    let old_backups: Vec<&File> = files
        .iter()
        .filter(|f| wa_dbs.iter().any(|d| f.path.starts_with(d)))
        .filter(|f| f.path.file_name().map(|n| n.to_string_lossy().starts_with("msgstore-")).unwrap_or(false))
        .collect();

    let groups = vec![
        group("wabackups", "Old WhatsApp backups", "Older daily chat backups. The newest backup, msgstore.db, is always kept.", old_backups),
        group("apks", "APK files", "Install files left behind after installing apps. Safe to remove.", apks),
        group("dupes", "Duplicates", "Exact copies. The oldest copy of each file is kept.", copies),
        group("leftovers", "Leftovers from removed apps", "Folders of apps that are no longer installed.", leftovers.iter().collect()),
        group("temp", "Temp files and logs", "Unfinished downloads, logs, thumbnails cache.", temp),
        group("large", "Large files", "Files over 100 MB. Check before removing.", large),
        group("empty", "Empty folders", "Folders with nothing in them.", empty.iter().collect()),
    ];

    json!({
        "root": ROOT, "total": total, "files": files.len(),
        "categories": categories, "folders": folders, "largest": largest, "groups": groups,
        "trash": trash_size(),
    })
}

pub fn ls(path: &str) -> Value {
    let dir = Path::new(if path.is_empty() { ROOT } else { path });
    if !dir.starts_with(ROOT) {
        return json!({ "error": "outside shared storage" });
    }
    let mut entries: Vec<Value> = vec![];
    if let Ok(rd) = fs::read_dir(dir) {
        for e in rd.flatten() {
            let p = e.path();
            if p.as_os_str() == TRASH {
                continue;
            }
            let Ok(m) = fs::symlink_metadata(&p) else { continue };
            if m.file_type().is_symlink() {
                continue;
            }
            let (bytes, items) = if m.is_dir() { dir_size(&p) } else { (m.len(), 0) };
            entries.push(json!({
                "path": p.to_string_lossy(), "name": e.file_name().to_string_lossy(),
                "dir": m.is_dir(), "bytes": bytes, "items": items, "modified": mtime(&m),
                "locked": safe(&p.to_string_lossy()).is_err(),
            }));
        }
    }
    entries.sort_by(|a, b| b["bytes"].as_u64().cmp(&a["bytes"].as_u64()));
    json!({ "path": dir.to_string_lossy(), "entries": entries })
}

/// The path, resolved, if it may be moved to the trash; otherwise why not.
fn safe(path: &str) -> Result<PathBuf, String> {
    let raw = Path::new(path);
    if !raw.is_absolute() || raw.components().any(|c| c == Component::ParentDir) {
        return Err("not a plain full path".into());
    }
    let p = fs::canonicalize(raw).map_err(|e| format!("{e}"))?;
    let root = Path::new(ROOT);
    let rel = p.strip_prefix(root).map_err(|_| "outside shared storage".to_string())?;
    let parts: Vec<String> = rel.components().map(|c| c.as_os_str().to_string_lossy().to_string()).collect();
    match parts.as_slice() {
        [] => Err("the storage root".into()),
        [one] if MAIN.contains(&one.as_str()) => Err(format!("{one} is a main folder")),
        [first, ..] if first == ".HoneTrash" => Err("already in the trash".into()),
        [first] if first == "Android" => Err("system folder".into()),
        [first, _] if first == "Android" => Err("system folder".into()),
        _ => Ok(p),
    }
}

pub fn trash(paths: &[String]) -> Value {
    let batch = Path::new(TRASH).join(now().to_string());
    let _ = fs::create_dir_all(&batch);
    let _ = fs::File::create(Path::new(TRASH).join(".nomedia")); // keep trashed photos out of the gallery
    let mut moved = 0u64;
    let mut bytes = 0u64;
    let mut manifest = String::new();
    let mut errors = vec![];
    let mut gone = 0u64;
    for path in paths {
        // Already moved or deleted elsewhere: nothing left to do, so it is not an error.
        if fs::symlink_metadata(path).is_err() {
            gone += 1;
            continue;
        }
        match safe(path) {
            Err(why) => errors.push(json!({ "path": path, "error": why })),
            Ok(p) => {
                let rel = p.strip_prefix(ROOT).unwrap().to_path_buf();
                let dest = batch.join(&rel);
                let size = if p.is_dir() { dir_size(&p).0 } else { fs::metadata(&p).map(|m| m.len()).unwrap_or(0) };
                let _ = fs::create_dir_all(dest.parent().unwrap());
                match fs::rename(&p, &dest) {
                    Ok(()) => {
                        moved += 1;
                        bytes += size;
                        manifest.push_str(&rel.to_string_lossy());
                        manifest.push('\n');
                    }
                    Err(e) => errors.push(json!({ "path": path, "error": e.to_string() })),
                }
            }
        }
    }
    if manifest.is_empty() {
        let _ = fs::remove_dir_all(&batch);
    } else {
        let _ = fs::write(batch.join(".manifest"), manifest);
    }
    json!({ "moved": moved, "bytes": bytes, "gone": gone, "errors": errors })
}

fn trash_size() -> u64 {
    dir_size(Path::new(TRASH)).0
}

fn batches() -> Vec<(PathBuf, Vec<String>)> {
    let mut out = vec![];
    if let Ok(rd) = fs::read_dir(TRASH) {
        for e in rd.flatten() {
            if let Ok(text) = fs::read_to_string(e.path().join(".manifest")) {
                out.push((e.path(), text.lines().filter(|l| !l.is_empty()).map(str::to_string).collect()));
            }
        }
    }
    out
}

pub fn trash_list() -> Value {
    let mut items = vec![];
    for (batch, rels) in batches() {
        let when: u64 = batch.file_name().and_then(|n| n.to_string_lossy().parse().ok()).unwrap_or(0);
        for rel in rels {
            let at = batch.join(&rel);
            let bytes = if at.is_dir() { dir_size(&at).0 } else { fs::metadata(&at).map(|m| m.len()).unwrap_or(0) };
            items.push(json!({
                "id": format!("{}/{rel}", batch.file_name().unwrap().to_string_lossy()),
                "original": Path::new(ROOT).join(&rel).to_string_lossy(),
                "bytes": bytes, "trashed": when,
            }));
        }
    }
    items.sort_by(|a, b| b["trashed"].as_u64().cmp(&a["trashed"].as_u64()));
    json!({ "items": items, "bytes": trash_size() })
}

/// Puts items back where they were; a name already taken gets " (restored)".
pub fn restore(ids: &[String]) -> Value {
    let mut restored = 0;
    let mut errors = vec![];
    for (batch, rels) in batches() {
        let tag = batch.file_name().unwrap().to_string_lossy().to_string();
        let mut keep = vec![];
        for rel in rels {
            if !ids.contains(&format!("{tag}/{rel}")) {
                keep.push(rel);
                continue;
            }
            let mut dest = Path::new(ROOT).join(&rel);
            if dest.exists() {
                let stem = dest.file_stem().unwrap_or_default().to_string_lossy().to_string();
                let ext = dest.extension().map(|e| format!(".{}", e.to_string_lossy())).unwrap_or_default();
                dest = dest.with_file_name(format!("{stem} (restored){ext}"));
            }
            if let Some(parent) = dest.parent() {
                let _ = fs::create_dir_all(parent);
            }
            match fs::rename(batch.join(&rel), &dest) {
                Ok(()) => restored += 1,
                Err(e) => {
                    errors.push(json!({ "path": rel, "error": e.to_string() }));
                    keep.push(rel);
                }
            }
        }
        if keep.is_empty() {
            let _ = fs::remove_dir_all(&batch);
        } else {
            let _ = fs::write(batch.join(".manifest"), keep.join("\n") + "\n");
        }
    }
    json!({ "restored": restored, "errors": errors })
}

pub fn empty_trash() -> Value {
    let bytes = trash_size();
    let ok = fs::remove_dir_all(TRASH).is_ok() || !Path::new(TRASH).exists();
    json!({ "ok": ok, "freed": bytes })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn refuses_unsafe_paths() {
        for p in ["/storage/emulated/0", "/storage/emulated/0/../", "relative/file", "/system/app", "/data/data"] {
            assert!(safe(p).is_err(), "{p} must be refused");
        }
    }

    #[test]
    fn sorts_files_into_categories() {
        assert_eq!(category("a.APK"), "APKs");
        assert_eq!(category("clip.mp4"), "Videos");
        assert_eq!(category("noext"), "Other");
        assert!(is_temp(Path::new("/x/y.crdownload")));
        assert!(is_temp(Path::new("/x/.thumbnails/1.jpg")));
    }
}
