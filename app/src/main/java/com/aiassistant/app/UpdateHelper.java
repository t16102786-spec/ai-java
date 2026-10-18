package com.aiassistant.app;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import androidx.core.content.FileProvider;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.json.JSONObject;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class UpdateHelper {

    private static final String REPO = "t16102786-spec/ai-java";
    private static final String API_URL =
        "https://api.github.com/repos/" + REPO + "/releases/latest";
    private static final String CURRENT_VERSION = "v2026.10.18-0000";

    private final Activity activity;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ExecutorService executor = Executors.newSingleThreadExecutor();

    public UpdateHelper(Activity activity) {
        this.activity = activity;
    }

    public void checkForUpdate() {
        handler.post(() ->
            Toast.makeText(activity, "Проверяю обновления...",
                Toast.LENGTH_SHORT).show());

        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient();
                Response resp = client.newCall(new Request.Builder()
                    .url(API_URL)
                    .header("Accept", "application/vnd.github+json")
                    .build()).execute();

                if (!resp.isSuccessful() || resp.body() == null) {
                    showToast("Ошибка проверки обновлений");
                    return;
                }

                JSONObject json = new JSONObject(resp.body().string());
                String latestTag = json.getString("tag_name");
                String apkUrl = null;

                org.json.JSONArray assets = json.getJSONArray("assets");
                for (int i = 0; i < assets.length(); i++) {
                    JSONObject asset = assets.getJSONObject(i);
                    if (asset.getString("name").endsWith(".apk")) {
                        apkUrl = asset.getString("browser_download_url");
                        break;
                    }
                }

                if (apkUrl == null) {
                    showToast("APK не найден в релизе");
                    return;
                }

                if (latestTag.equals(CURRENT_VERSION)) {
                    showToast("У вас актуальная версия (" + latestTag + ")");
                    return;
                }

                final String finalApkUrl = apkUrl;
                final String finalTag = latestTag;
                handler.post(() -> showUpdateDialog(finalTag, finalApkUrl));

            } catch (Exception e) {
                showToast("Ошибка: " + e.getMessage());
            }
        });
    }

    private void showUpdateDialog(String tag, String apkUrl) {
        new AlertDialog.Builder(activity)
            .setTitle("Доступно обновление")
            .setMessage("Новая версия: " + tag + "\n\nСкачать и установить?")
            .setPositiveButton("Обновить", (d, w) -> downloadAndInstall(apkUrl))
            .setNegativeButton("Позже", null)
            .show();
    }

    private void downloadAndInstall(String apkUrl) {
        showToast("Скачиваю обновление...");
        executor.execute(() -> {
            try {
                OkHttpClient client = new OkHttpClient.Builder()
                    .readTimeout(5, java.util.concurrent.TimeUnit.MINUTES)
                    .build();

                Response resp = client.newCall(new Request.Builder()
                    .url(apkUrl).build()).execute();

                if (!resp.isSuccessful() || resp.body() == null) {
                    showToast("Ошибка скачивания");
                    return;
                }

                File dir = new File(activity.getCacheDir(), "updates");
                dir.mkdirs();
                File apkFile = new File(dir, "update.apk");

                try (InputStream in = resp.body().byteStream();
                     FileOutputStream out = new FileOutputStream(apkFile)) {
                    byte[] buf = new byte[8192];
                    int count;
                    while ((count = in.read(buf)) != -1) out.write(buf, 0, count);
                }

                handler.post(() -> installApk(apkFile));

            } catch (Exception e) {
                showToast("Ошибка скачивания: " + e.getMessage());
            }
        });
    }

    private void installApk(File apkFile) {
        try {
            Uri uri = FileProvider.getUriForFile(activity,
                activity.getPackageName() + ".provider", apkFile);
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setDataAndType(uri, "application/vnd.android.package-archive");
            intent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            activity.startActivity(intent);
        } catch (Exception e) {
            showToast("Ошибка установки: " + e.getMessage());
        }
    }

    private void showToast(String msg) {
        handler.post(() ->
            Toast.makeText(activity, msg, Toast.LENGTH_SHORT).show());
    }
                  }
