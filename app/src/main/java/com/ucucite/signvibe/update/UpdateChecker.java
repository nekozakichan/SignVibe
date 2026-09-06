package com.ucucite.signvibe.update;

import android.app.Activity;
import android.app.AlertDialog;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.Settings;
import android.util.Log;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Lightweight in-app updater that checks the project's GitHub Releases for a
 * newer APK, then downloads and installs it. No third-party libraries.
 *
 * <p>Flow: {@link #checkForUpdate(Activity)} → GitHub "latest release" API →
 * compare the release tag to the installed versionName → if newer, offer to
 * update → DownloadManager fetches the APK into the app's external files dir →
 * the system package installer opens it.</p>
 *
 * <p>The release APK MUST be signed with the same key as the installed app, or
 * Android refuses the update. The GitHub Actions release workflow handles that.</p>
 */
public final class UpdateChecker {

    private static final String TAG = "UpdateChecker";

    // GitHub repo that hosts the releases.
    private static final String OWNER = "nekozakichan";
    private static final String REPO = "SignVibe";
    private static final String LATEST_RELEASE_API =
            "https://api.github.com/repos/" + OWNER + "/" + REPO + "/releases/latest";

    private static final String APK_FILE_NAME = "SignVibe-update.apk";

    private UpdateChecker() {}

    /** Silently checks for a newer release; if one exists, prompts the user. */
    public static void checkForUpdate(Activity activity) {
        checkForUpdate(activity, false);
    }

    /**
     * @param showNoUpdateToast when true, tell the user when they are already up
     *                          to date (use it for a manual "Check for updates" tap).
     */
    public static void checkForUpdate(Activity activity, boolean showNoUpdateToast) {
        ExecutorService pool = Executors.newSingleThreadExecutor();
        pool.execute(() -> {
            try {
                JSONObject release = fetchLatestRelease();
                if (release == null) return;

                String latest = stripV(release.optString("tag_name", ""));   // e.g. "1.1"
                String current = currentVersionName(activity);
                String apkUrl = firstApkUrl(release);
                String notes = release.optString("body", "");

                if (apkUrl == null) {
                    Log.w(TAG, "Latest release has no APK asset.");
                    return;
                }

                if (isNewer(latest, current)) {
                    activity.runOnUiThread(() -> promptUpdate(activity, latest, apkUrl, notes));
                } else if (showNoUpdateToast) {
                    activity.runOnUiThread(() -> Toast.makeText(
                            activity, "You're on the latest version.", Toast.LENGTH_SHORT).show());
                }
            } catch (Exception e) {
                Log.w(TAG, "Update check failed (offline or API error).", e);
            } finally {
                pool.shutdown();
            }
        });
    }

    private static JSONObject fetchLatestRelease() throws Exception {
        HttpURLConnection conn = (HttpURLConnection) new URL(LATEST_RELEASE_API).openConnection();
        conn.setRequestProperty("Accept", "application/vnd.github+json");
        conn.setRequestProperty("User-Agent", "SignVibe-Updater");
        conn.setConnectTimeout(10000);
        conn.setReadTimeout(10000);
        try {
            if (conn.getResponseCode() != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "GitHub API returned HTTP " + conn.getResponseCode());
                return null;
            }
            StringBuilder sb = new StringBuilder();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(conn.getInputStream()))) {
                String line;
                while ((line = r.readLine()) != null) sb.append(line);
            }
            return new JSONObject(sb.toString());
        } finally {
            conn.disconnect();
        }
    }

    private static String firstApkUrl(JSONObject release) {
        JSONArray assets = release.optJSONArray("assets");
        if (assets == null) return null;
        for (int i = 0; i < assets.length(); i++) {
            JSONObject a = assets.optJSONObject(i);
            if (a == null) continue;
            if (a.optString("name", "").toLowerCase().endsWith(".apk")) {
                String url = a.optString("browser_download_url", "");
                return url.isEmpty() ? null : url;
            }
        }
        return null;
    }

    private static String currentVersionName(Context ctx) {
        try {
            PackageInfo pi = ctx.getPackageManager().getPackageInfo(ctx.getPackageName(), 0);
            return pi.versionName != null ? pi.versionName : "0";
        } catch (PackageManager.NameNotFoundException e) {
            return "0";
        }
    }

    private static String stripV(String tag) {
        if (tag == null) return "";
        tag = tag.trim();
        if (tag.startsWith("v") || tag.startsWith("V")) tag = tag.substring(1);
        return tag;
    }

    /** Compares dotted numeric versions ("1.2" vs "1.10"); true if remote > local. */
    static boolean isNewer(String remote, String local) {
        if (remote == null || remote.isEmpty()) return false;
        String[] r = remote.split("\\.");
        String[] l = local.split("\\.");
        int n = Math.max(r.length, l.length);
        for (int i = 0; i < n; i++) {
            int rv = i < r.length ? leadingInt(r[i]) : 0;
            int lv = i < l.length ? leadingInt(l[i]) : 0;
            if (rv != lv) return rv > lv;
        }
        return false;
    }

    /** Parses the leading digits of a part, tolerating suffixes like "1-beta". */
    private static int leadingInt(String s) {
        StringBuilder digits = new StringBuilder();
        for (char c : s.trim().toCharArray()) {
            if (Character.isDigit(c)) digits.append(c);
            else break;
        }
        if (digits.length() == 0) return 0;
        try {
            return Integer.parseInt(digits.toString());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static void promptUpdate(Activity activity, String version, String apkUrl, String notes) {
        if (activity.isFinishing()) return;
        StringBuilder msg = new StringBuilder("A new version (v" + version + ") is available.");
        if (notes != null && !notes.trim().isEmpty()) {
            String trimmed = notes.trim();
            if (trimmed.length() > 300) trimmed = trimmed.substring(0, 300) + "…";
            msg.append("\n\nWhat's new:\n").append(trimmed);
        }
        new AlertDialog.Builder(activity)
                .setTitle("Update available")
                .setMessage(msg.toString())
                .setPositiveButton("Update", (d, w) -> startUpdate(activity, apkUrl))
                .setNegativeButton("Later", null)
                .setCancelable(true)
                .show();
    }

    private static void startUpdate(Activity activity, String apkUrl) {
        // On Android 8+ the app needs the user's permission to install apps.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O
                && !activity.getPackageManager().canRequestPackageInstalls()) {
            new AlertDialog.Builder(activity)
                    .setTitle("Allow updates")
                    .setMessage("To install the update, allow SignVibe to install apps, "
                            + "then tap Update again.")
                    .setPositiveButton("Open settings", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:" + activity.getPackageName()));
                        activity.startActivity(i);
                    })
                    .setNegativeButton("Cancel", null)
                    .show();
            return;
        }
        downloadAndInstall(activity, apkUrl);
    }

    private static void downloadAndInstall(Activity activity, String apkUrl) {
        Context app = activity.getApplicationContext();

        File dir = app.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
        if (dir == null) {
            Toast.makeText(app, "Storage unavailable; can't download the update.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        // Clear any previous download so the installer never sees a stale file.
        final File dest = new File(dir, APK_FILE_NAME);
        if (dest.exists()) //noinspection ResultOfMethodCallIgnored
            dest.delete();

        DownloadManager dm = (DownloadManager) app.getSystemService(Context.DOWNLOAD_SERVICE);
        if (dm == null) {
            Toast.makeText(app, "Can't start the download on this device.", Toast.LENGTH_LONG).show();
            return;
        }

        DownloadManager.Request req = new DownloadManager.Request(Uri.parse(apkUrl))
                .setTitle("SignVibe update")
                .setDescription("Downloading the latest version…")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalFilesDir(app, Environment.DIRECTORY_DOWNLOADS, APK_FILE_NAME)
                .setMimeType("application/vnd.android.package-archive");

        final long downloadId = dm.enqueue(req);
        Toast.makeText(app, "Downloading update…", Toast.LENGTH_SHORT).show();

        BroadcastReceiver receiver = new BroadcastReceiver() {
            @Override
            public void onReceive(Context context, Intent intent) {
                long id = intent.getLongExtra(DownloadManager.EXTRA_DOWNLOAD_ID, -1);
                if (id != downloadId) return;
                try {
                    context.getApplicationContext().unregisterReceiver(this);
                } catch (Exception ignored) { /* already unregistered */ }
                installApk(context.getApplicationContext(), dest);
            }
        };

        IntentFilter filter = new IntentFilter(DownloadManager.ACTION_DOWNLOAD_COMPLETE);
        // Android 13+ requires the exported flag on runtime-registered receivers.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            app.registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            app.registerReceiver(receiver, filter);
        }
    }

    private static void installApk(Context context, File apk) {
        if (apk == null || !apk.exists()) {
            Toast.makeText(context, "Update download failed.", Toast.LENGTH_LONG).show();
            return;
        }
        Uri uri = FileProvider.getUriForFile(
                context, context.getPackageName() + ".fileprovider", apk);
        Intent install = new Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        context.startActivity(install);
    }
}
