# ─────────────────────────────────────────────────────────────────────────
# WhiteListCheck (ru.netstatus.app) — R8 keep rules
#
# R8 is enabled for the release build (isMinifyEnabled = true,
# isShrinkResources = true). These rules protect the handful of places that
# rely on names surviving minification; everything else is free to be
# shrunk / obfuscated.
#
# Not listed here on purpose:
#   * MainActivity, StatusWidget (AppWidgetProvider), FileProvider — referenced
#     from AndroidManifest.xml, so AAPT keeps them automatically.
#   * Jetpack Compose, WorkManager runtime — covered by the AndroidX consumer
#     ProGuard rules shipped with those libraries.
# ─────────────────────────────────────────────────────────────────────────

# --- Enums persisted by name ---------------------------------------------
# Verdict and CheckSource are stored by .name in SharedPreferences and in the
# exported CSV, and read back with valueOf(). Renaming their constants would
# make previously saved values and the whole check journal unreadable, so keep
# these two enums intact, constant names included.
-keep class ru.netstatus.app.Verdict { *; }
-keep class ru.netstatus.app.CheckSource { *; }

# Generic safety net for any enum's values()/valueOf() (already present in the
# default optimized config; repeated here to be explicit).
-keepclassmembers enum * {
    public static **[] values();
    public static ** valueOf(java.lang.String);
}

# --- WorkManager workers --------------------------------------------------
# WorkManager instantiates workers by their fully-qualified class name, which
# it stores in its own database — including for already-scheduled tasks that
# must keep resolving after an app update. Keep the real class names and the
# (Context, WorkerParameters) constructor so background checks and the widget
# survive minification and updates.
-keep class ru.netstatus.app.CheckWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
-keep class ru.netstatus.app.WidgetScanWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}

# Belt-and-suspenders for any other ListenableWorker subclass.
-keep class * extends androidx.work.ListenableWorker {
    public <init>(android.content.Context, androidx.work.WorkerParameters);
}
