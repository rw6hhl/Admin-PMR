package com.pmr.admin;

import android.app.AlertDialog;
import android.content.Context;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Environment;
import android.widget.Toast;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;

/* Проверка и скачивание обновлений Admin PMR V2.0.
 *
 * Изменения V2.0 (V5.3.1):
 *   - APK сохраняется в публичную папку Download (Environment.DIRECTORY_DOWNLOADS);
 *   - файл доступен через проводник телефона и не удаляется при удалении приложения;
 *   - установщик НЕ запускается автоматически — вместо этого показывается
 *     подсказка: «Удалите старую версию, откройте проводник, найдите APK и установите».
 */
public class UpdateChecker {

    private static final String BASE_URL =
            "https://github.com/rw6hhl/Admin-PMR/raw/main/apk/";

    private final Context ctx;

    public UpdateChecker(Context ctx) {
        this.ctx = ctx;
    }

    public void checkAndUpdate(String currentVersion) {
        String[] candidates = buildCandidateNames(currentVersion);
        new CheckTask(candidates).execute();
    }

    private String[] buildCandidateNames(String currentVersion) {
        try {
            String[] parts = currentVersion.split("\\.");
            int major = Integer.parseInt(parts[0]);
            int minor = (parts.length > 1) ? Integer.parseInt(parts[1]) : 0;
            String candidate = "app-v" + major + "_" + (minor + 1) + ".apk";
            return new String[]{candidate};
        } catch (Exception e) {
            return new String[]{"app-v5_3.apk"};
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
                .setMessage("Найдено обновление: " + fileName
                        + "\n\nСкачать в папку Download?")
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
                /* Публичная папка Download — доступна через проводник. */
                File dir = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (dir == null) {
                    dir = ctx.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS);
                }
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
            showInstallHint(file);
        }
    }

    /* Показывает подсказку — не запускает установщик. */
    private void showInstallHint(File apk) {
        String path = apk.getAbsolutePath();
        String msg = "Файл сохранён:\n" + path + "\n\n"
                + "Как установить:\n"
                + "1. Удалите старую версию Admin PMR "
                + "(Настройки → Приложения → Admin PMR → Удалить).\n"
                + "2. Откройте проводник (Files, Мои файлы).\n"
                + "3. Перейдите в папку Download.\n"
                + "4. Найдите файл " + apk.getName() + ".\n"
                + "5. Нажмите на него и установите.";

        new AlertDialog.Builder(ctx)
                .setTitle("Готово к установке")
                .setMessage(msg)
                .setPositiveButton("Понятно", null)
                .show();

        AppLog.add("UpdateChecker: подсказка показана, APK=" + path);
    }
}