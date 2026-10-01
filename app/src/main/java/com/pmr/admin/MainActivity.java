package com.pmr.admin;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.io.InputStream;

/* Главный экран V3.3.
 *
 * Изменения V3.3:
 *   - убраны индикаторы TX/RX (квадратики);
 *   - кнопка PTT меняет цвет: синяя → красная при передаче.
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 100;
    private static final int REQ_MIC = 101;

    private RecyclerView recycler;
    private ChanAdapter adapter;
    private Handler handler;
    private int refreshMs = 2000;

    private Button btnPtt;
    private Button btnLoadList;

    private ActivityResultLauncher<String[]> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_main);

        TextView title = findViewById(R.id.listTitle);
        if (title != null) title.setText(R.string.list_title);

        recycler = findViewById(R.id.recyclerChan);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        adapter = new ChanAdapter(this);
        recycler.setAdapter(adapter);

        Button settingsBtn = findViewById(R.id.btnSettings);
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(v -> {
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(i);
            });
        }

        btnPtt      = findViewById(R.id.btnPtt);
        btnLoadList = findViewById(R.id.btnLoadList);

        if (btnPtt != null) {
            btnPtt.setOnTouchListener((v, event) -> {
                int action = event.getAction();
                if (action == MotionEvent.ACTION_DOWN) {
                    if (PmrService.audioEngine != null) {
                        PmrService.audioEngine.startRecording();
                    }
                    btnPtt.setBackgroundTintList(
                            ContextCompat.getColorStateList(
                                    MainActivity.this, R.color.c_red));
                } else if (action == MotionEvent.ACTION_UP
                        || action == MotionEvent.ACTION_CANCEL) {
                    if (PmrService.audioEngine != null) {
                        PmrService.audioEngine.stopRecording();
                    }
                    btnPtt.setBackgroundTintList(
                            ContextCompat.getColorStateList(
                                    MainActivity.this, R.color.c_blue));
                }
                return true;
            });
        }

        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) applyListFile(uri);
                });

        if (btnLoadList != null) {
            btnLoadList.setOnClickListener(v ->
                    filePicker.launch(new String[]{"text/plain", "*/*"}));
        }

        handler = new Handler(Looper.getMainLooper());

        requestMicPermission();
        requestNotifPermission();
        startServiceSafe();

        handler.post(uiLoop);
    }

    @Override
    protected void onResume() {
        super.onResume();
        SharedPreferences sp = getSharedPreferences(
                PasswordActivity.PREFS, MODE_PRIVATE);
        refreshMs = sp.getInt(PasswordActivity.KEY_REFRESH,
                PasswordActivity.DEFAULT_REFRESH) * 1000;
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        if (isFinishing()) {
            stopService(new Intent(this, PmrService.class));
        }
    }

    private final Runnable uiLoop = new Runnable() {
        @Override
        public void run() {
            refreshUI();
            handler.postDelayed(this, refreshMs);
        }
    };

    private void refreshUI() {
        if (PmrService.pmrSocket == null) return;
        java.util.List<ChanList.Item> lst = PmrService.chanList.snapshot();
        int activeClient = PmrService.pmrSocket.getActiveClient();
        adapter.setData(lst, activeClient, PmrService.listFile);
    }

    private void applyListFile(Uri uri) {
        try {
            InputStream is = getContentResolver().openInputStream(uri);
            if (is == null) {
                Toast.makeText(this, R.string.toast_list_error,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            int n = PmrService.listFile.loadFromStream(is);
            is.close();

            File dest = new File(getFilesDir(), "list.txt");
            PmrService.listFile.save(dest);

            if (PmrService.chanList != null) PmrService.chanList.clear();
            if (PmrService.pmrSocket != null) PmrService.pmrSocket.sendList();

            Toast.makeText(this,
                    getString(R.string.toast_list_loaded, n),
                    Toast.LENGTH_SHORT).show();
        } catch (Exception e) {
            AppLog.add("applyListFile error: " + e);
            Toast.makeText(this, R.string.toast_list_error,
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void requestMicPermission() {
        if (ContextCompat.checkSelfPermission(this,
                Manifest.permission.RECORD_AUDIO)
                != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.RECORD_AUDIO}, REQ_MIC);
        }
    }

    private void requestNotifPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this,
                    Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS},
                        REQ_NOTIF);
            }
        }
    }

    private void startServiceSafe() {
        Intent svc = new Intent(this, PmrService.class);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(svc);
        } else {
            startService(svc);
        }
    }
}