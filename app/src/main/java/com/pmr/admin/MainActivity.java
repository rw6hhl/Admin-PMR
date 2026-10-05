package com.pmr.admin;

import android.Manifest;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.MotionEvent;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

/* Главный экран V5.9.
 *
 * Изменения V5.9:
 *   - кнопка «ОБНОВИТЬ СПИСОК» учитывает источник списка (server / local).
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_NOTIF = 100;
    private static final int REQ_MIC = 101;

    private RecyclerView recycler;
    private ChanAdapter adapter;
    private Handler handler;
    private int refreshMs = 2000;

    private Button btnPtt;
    private Button btnRefreshList;

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

        btnRefreshList = findViewById(R.id.btnRefreshList);
        if (btnRefreshList != null) {
            btnRefreshList.setOnClickListener(v -> {
                if (PmrService.pmrSocket == null) {
                    Toast.makeText(MainActivity.this,
                            R.string.toast_ban_null, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (!PmrService.pmrSocket.isRunning()) {
                    Toast.makeText(MainActivity.this,
                            R.string.toast_ban_not_running, Toast.LENGTH_SHORT).show();
                    return;
                }
                if (PmrService.pmrSocket.isListSourceLocal()) {
                    Toast.makeText(MainActivity.this,
                            "Источник: локальный list.txt", Toast.LENGTH_SHORT).show();
                    return;
                }
                PmrService.pmrSocket.sendL();
                Toast.makeText(MainActivity.this,
                        R.string.list_refreshing, Toast.LENGTH_SHORT).show();
            });
        }

        Button settingsBtn = findViewById(R.id.btnSettings);
        if (settingsBtn != null) {
            settingsBtn.setOnClickListener(v -> {
                Intent i = new Intent(MainActivity.this, SettingsActivity.class);
                startActivity(i);
            });
        }

        btnPtt = findViewById(R.id.btnPtt);

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