package com.pmr.admin;

import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import androidx.core.content.FileProvider;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/* Проверка и установка обновлений Admin PMR V1.0.
 *
 * Источник: https://github.com/rw6hhl/Admin-PMR/raw/main/apk/app-vX_Y.apk
 * Приложение проверяет наличие файла APK по URL, скачивает во внешнюю
 * папку приложения и открывает системный установщик через FileProvider.
 */
public class UpdateChecker {

    /* Базовый URL папки с APK в репозитории. */
    private static final String BASE_URL =
            "https://github.com/rw6hhl/Admin-PMR/raw/main/apk/";

    private final Context ctx;

    public UpdateChecker(Context ctx) {
        this.ctx = ctx;
    }

    /* Запуск проверки версии vStr (например, "5.2").
     * Ищет APK для следующей версии — пробует vStr+0.1, потом vStr+0.2 и т.д.
     * При нахождении — предлагает скачать и установить. */
    public void checkAndUpdate(String currentVersion) {
        String[] candidates = buildCandidateNames(currentVersion);
        new CheckTask(candidates).execute();
    }

    /* Формирует список имён APK-файлов, которые могут быть новее текущей версии.
     * Например, для "5.1.1" пробуем "app-v5_2.apk", "app-v5_2_0.apk", "app-v5_2_1.apk" и т.д. */
    private String[] buildCandidateNames(String currentVersion) {
        try {
            String[] parts = currentVersion.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = (parts.length > 1) ? Integer.parseInt(parts[1]) : 0;
            /* Пробуем следующую минорную версию. */
            String candidate = "app-v" + major + "_" + (minor + 1) + ".apk";
            return new String[]{candidate};
        } catch (Exception e) {
            return new String[]{"app-v5_2.apk"};
        }
    }

    private class CheckTask extends AsyncTask<Void, Void, String> {
        private final String[] candidates;

        CheckTask(String[] candidates) {
            this.candidates = candidates;
        }

        @Override
        protected String doInBackground(Void... voids) {
            for (String name : candidates) {
                try {
                    URL url = new URL(BASE_URL + name);
                    HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                    conn.setRequestMethod("HEAD");
                    conn.setConnectTimeout(10000);
                    conn.setReadTimeout(10000);
                    conn.connect();
                    int code = conn.getResponseCode();
                    conn.disconnect();
                    if (code == 200) return name;
                } catch (Exception ignored) {}
            }
            return null;
        }

        @Override
        protected void onPostExecute(String found) {
            if (found == null) {
                Toast.makeText(ctx, "Обновлений нет", Toast.LENGTH_SHORT).show();
                AppLog.add("UpdateChecker: обновлений нет");
                return;
            }
            AppLog.add("UpdateChecker: найдено обновление " + found);
            askDownload(found);
        }
    }

    private void askDownload(final String fileName) {
        new AlertDialog.Builder(ctx)
                .setTitle("Обновление")
                .setMessage("Найдено обновление: " + fileName + ". Скачать и установить?")
                .setPositiveButton("Скачать", (d, w) -> download(fileName))
                .setNegativeButton("Отмена", null)
                .show();
    }

    private void download(final String fileName) {
        new DownloadTask(fileName).execute();
    }

    private class DownloadTask extends AsyncTask<Void, Void, File> {
        private final String fileName;

        DownloadTask(String fileName) {
            this.fileName = fileName;
        }

        @Override
        protected File doInBackground(Void... voids) {
            try {
                File dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) dir = ctx.getFilesDir();
                if (!dir.exists()) dir.mkdirs();
                File out = new File(dir, fileName);

                URL url = new URL(BASE_URL + fileName);
                HttpURLConnection conn = (HttpURLConnection) url.openConnection();
                conn.setConnectTimeout(15000);
                conn.setReadTimeout(15000);
                conn.connect();

                InputStream is = conn.getInputStream();
                FileOutputStream fos = new FileOutputStream(out);
                byte[] buf = new byte[8192];
                int n;
                while ((n = is.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
                fos.close();
                is.close();
                conn.disconnect();
                AppLog.add("UpdateChecker: скачано " + out.getAbsolutePath());
                return out;
            } catch (Exception e) {
                AppLog.add("UpdateChecker: ошибка скачивания — " + e);
                return null;
            }
        }

        @Override
        protected void onPostExecute(File file) {
            if (file == null) {
                Toast.makeText(ctx, "Ошибка скачивания", Toast.LENGTH_SHORT).show();
                return;
            }
            install(file);
        }
    }

    private void install(File apk) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW);
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK
                    | Intent.FLAG_GRANT_READ_URI_PERMISSION);

            Uri uri;
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
                uri = FileProvider.getUriForFile(ctx,
                        ctx.getPackageName() + ".fileprovider", apk);
            } else {
                uri = Uri.fromFile(apk);
            }
            intent.setDataAndType(uri,
                    "application/vnd.android.package-archive");
            ctx.startActivity(intent);
            AppLog.add("UpdateChecker: открыт установщик");
        } catch (Exception e) {
            AppLog.add("UpdateChecker: ошибка запуска установщика — " + e);
            Toast.makeText(ctx, "Ошибка установки: " + e.getMessage(),
                    Toast.LENGTH_LONG).show();
        }
    }
}