package com.pmr.admin;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.os.Build;
import android.os.IBinder;

import androidx.core.app.NotificationCompat;

import java.io.File;
import java.io.InputStream;

/* Foreground Service V3.2.
 *
 * Изменения V3.2:
 *   - создаётся AudioEngine и связывается с PmrSocket;
 *   - при уничтожении службы AudioEngine освобождает все AudioTrack и AudioRecord.
 */
public class PmrService extends Service {

    public static final String CHANNEL_ID = "pmr_admin_ch";
    public static final int NOTIF_ID = 1;

    public static ListFile listFile;
    public static ChanList chanList;
    public static ActiveLog activeLog;
    public static WebLog webLog;
    public static CmdQueue cmdQueue;
    public static PmrSocket pmrSocket;
    public static AudioEngine audioEngine;

    @Override
    public void onCreate() {
        super.onCreate();

        AppLog.add("PmrService.onCreate() — старт V3.2");

        File dir = getFilesDir();
        File listTxt = new File(dir, "list.txt");

        listFile  = new ListFile();
        chanList  = new ChanList();
        activeLog = new ActiveLog();
        webLog    = new WebLog();
        cmdQueue  = new CmdQueue();

        if (!listTxt.exists()) {
            try {
                InputStream is = getResources().openRawResource(R.raw.list);
                listFile.loadFromStream(is);
                listFile.save(listTxt);
                is.close();
                AppLog.add("list.txt распакован из res/raw");
            } catch (Exception e) {
                AppLog.add("ошибка распаковки list.txt: " + e);
            }
        } else {
            listFile.load(listTxt);
            AppLog.add("list.txt загружен (" + listFile.count() + " записей)");
        }

        pmrSocket = new PmrSocket(getApplicationContext(),
                listFile, chanList, activeLog, webLog,
                cmdQueue, dir);

        audioEngine = new AudioEngine(getApplicationContext(), pmrSocket);
        audioEngine.startPlaying();
        pmrSocket.setAudioEngine(audioEngine);

        pmrSocket.start();

        createChannel();
        startForeground(NOTIF_ID, buildNotification());

        AppLog.add("PmrService: служба запущена, аудио инициализировано");
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    @Override
    public void onDestroy() {
        AppLog.add("PmrService.onDestroy()");
        if (audioEngine != null) {
            try { audioEngine.stopRecording(); } catch (Exception ignored) {}
            try { audioEngine.stopPlaying(); } catch (Exception ignored) {}
        }
        if (pmrSocket != null) pmrSocket.stop();
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) { return null; }

    private void createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            NotificationChannel ch = new NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.notif_channel),
                    NotificationManager.IMPORTANCE_LOW);
            NotificationManager nm = getSystemService(NotificationManager.class);
            if (nm != null) nm.createNotificationChannel(ch);
        }
    }

    private Notification buildNotification() {
        Intent i = new Intent(this, MainActivity.class);
        PendingIntent pi = PendingIntent.getActivity(
                this, 0, i, PendingIntent.FLAG_IMMUTABLE);

        return new NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }
}