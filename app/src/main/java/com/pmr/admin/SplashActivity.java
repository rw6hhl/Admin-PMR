package com.pmr.admin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

/* Splash-экран V6.5.
 *
 * Изменения V6.5:
 *   - показывает Call и QTH из настроек;
 *   - переход — сразу на PasswordActivity или MainActivity (CheckActivity убран).
 */
public class SplashActivity extends AppCompatActivity {

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
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
            boolean requirePass = sp.getBoolean(
                    PasswordActivity.KEY_REQUIRE_PASSWORD, true);
            Intent i;
            if (requirePass) {
                i = new Intent(SplashActivity.this, PasswordActivity.class);
            } else {
                i = new Intent(SplashActivity.this, MainActivity.class);
            }
            startActivity(i);
            finish();
        }, 2000);
    }
}