package com.pmr.admin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.WindowManager;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/* Splash-экран V6.8.
 *
 * Изменения V6.8:
 *   - показывает Call и QTH из настроек.
 */
public class SplashActivity extends AppCompatActivity {

    private static final long SPLASH_DELAY_MS = 2000L;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_splash);

        SharedPreferences sp = getSharedPreferences(
                PasswordActivity.PREFS, MODE_PRIVATE);

        TextView versionView = findViewById(R.id.splashVersion);
        if (versionView != null) {
            versionView.setText(getString(R.string.app_version));
        }

        TextView callView = findViewById(R.id.splashCall);
        if (callView != null) {
            String call = sp.getString(PasswordActivity.KEY_CALLSIGN,
                    PasswordActivity.DEFAULT_CALLSIGN);
            String qth = sp.getString(PasswordActivity.KEY_CITY,
                    PasswordActivity.DEFAULT_CITY);
            callView.setText(call + " / " + qth);
        }

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent i = new Intent(SplashActivity.this, MainActivity.class);
            startActivity(i);
            finish();
        }, SPLASH_DELAY_MS);
    }
}