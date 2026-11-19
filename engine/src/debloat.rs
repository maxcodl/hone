//! Preinstalled apps that can be removed for the current user. Anything not listed here is refused,
//! so the app can never take out a part the phone needs to boot or call.

/// (package, "safe" | "caution", what it is)
pub const LIST: &[(&str, &str, &str)] = &[
    // Safe: test tools, promo installers and leftovers nobody uses.
    ("com.aura.oobe.solutions", "safe", "Installs promoted apps during setup"),
    ("android.autoinstalls.config.Nothing", "safe", "List of promoted apps to auto-install"),
    ("com.nothing.ntfactorytest", "safe", "Factory test tool"),
    ("com.focaltech.ft_terminal_test", "safe", "Touchscreen factory test"),
    ("com.debug.loggerui", "safe", "MediaTek debug logger"),
    ("com.nothing.logkit", "safe", "Collects logs for Nothing support"),
    ("com.mediatek.mdmlsample", "safe", "MediaTek sample app"),
    ("com.mediatek.lbs.em2.ui", "safe", "MediaTek location test menu"),
    ("com.nothing.userguide", "safe", "User guide"),
    ("com.nothing.introduction16", "safe", "What's new tour"),
    ("com.android.egg", "safe", "Android version Easter egg"),
    ("com.android.dreams.basic", "safe", "Basic colour screensaver"),
    ("com.google.android.printservice.recommendation", "safe", "Suggests print plugins"),
    ("com.google.android.videos", "safe", "Google TV"),
    ("com.google.android.feedback", "safe", "Sends crash reports to Google"),
    ("com.android.bookmarkprovider", "safe", "Old bookmark store, unused by current browsers"),
    ("com.google.android.apps.restore", "safe", "Restores data during first setup only"),
    // Caution: real apps; remove only if something else does the job.
    ("com.google.android.youtube", "caution", "YouTube"),
    ("com.google.android.apps.youtube.music", "caution", "YouTube Music"),
    ("com.android.chrome", "caution", "Chrome; set another browser as default first"),
    ("com.google.android.apps.tachyon", "caution", "Google Meet; video calls from the dialer use it"),
    ("com.google.android.apps.bard", "caution", "Gemini"),
    ("com.google.android.apps.docs", "caution", "Google Drive"),
    ("com.google.android.keep", "caution", "Google Keep"),
    ("com.google.android.gm", "caution", "Gmail"),
    ("com.google.android.apps.maps", "caution", "Google Maps; other apps open maps through it"),
    ("com.google.android.apps.photos", "caution", "Google Photos"),
    ("com.google.android.calendar", "caution", "Google Calendar"),
    ("com.google.android.projection.gearhead", "caution", "Android Auto"),
    ("com.google.android.apps.wellbeing", "caution", "Digital Wellbeing: screen time, bedtime mode"),
    ("com.google.android.apps.safetyhub", "caution", "Personal Safety: crash detection, emergency sharing"),
    ("com.google.ambient.streaming", "caution", "Streams apps to your other devices"),
    ("com.google.android.marvin.talkback", "caution", "TalkBack screen reader"),
    ("com.google.android.accessibility.switchaccess", "caution", "Switch Access"),
    ("com.android.hotwordenrollment.okgoogle", "caution", "Hey Google voice match"),
    ("com.android.hotwordenrollment.xgoogle", "caution", "Hey Google voice match"),
    ("com.android.bips", "caution", "Built-in printing"),
    ("com.android.wallpaper.livepicker", "caution", "Live wallpaper picker"),
    ("com.android.traceur", "caution", "System tracing for performance reports"),
    ("com.mediatek.miravision.ui", "caution", "MiraVision display tuning"),
    ("com.nothing.weather", "caution", "Nothing Weather and its widget"),
    ("com.nothing.soundrecorder", "caution", "Nothing Recorder"),
    ("com.nothing.aiwidget", "caution", "Nothing AI widget"),
    ("com.nothing.essentialintelligence", "caution", "Essential Space"),
    ("com.nothing.universal.search", "caution", "Nothing launcher search"),
    ("com.nothing.ntgamedashboard", "caution", "Game mode dashboard"),
];

pub fn tag(pkg: &str) -> Option<(&'static str, &'static str)> {
    LIST.iter().find(|(p, _, _)| *p == pkg).map(|(_, t, n)| (*t, *n))
}

pub fn removable(pkg: &str) -> bool {
    tag(pkg).is_some()
}
