package com.pmr.admin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;

/* Экран ввода пароля Admin PMR V4.5.
 *
 * Изменения V4.5:
 *   - KEY_MIC_GAIN = "mic_gain" (0..100, дефолт 50);
 *   - VOX отключён — запись только по PTT.
 */
public class PasswordActivity extends AppCompatActivity {

    public static final String PREFS = "admin_pmr_prefs";
    public static final String KEY_PASSWORD = "password";
    public static final String KEY_REFRESH = "refresh_sec";
    public static final String KEY_REQUIRE_PASSWORD = "require_password";
    public static final String KEY_PORT_PRM = "port_prm";
    public static final String KEY_PORT_PRD = "port_prd";
    public static final String KEY_CHECK_SYSTEM = "check_system";
    public static final String KEY_26_STATE = "state_26";

    public static final String KEY_MY_MAIL_INDEX = "my_mail_index";
    public static final String KEY_MY_PCHANNEL   = "my_pchannel";
    public static final String KEY_PRIZNAK_PMR   = "priznak_pmr";
    public static final String KEY_IP_SERVER     = "ip_server";
    public static final String KEY_CALLSIGN      = "callsign";
    public static final String KEY_CITY          = "city";

    public static final String KEY_LIST_SOURCE   = "list_source";
    public static final String KEY_DIAG_ENABLED  = "diag_enabled";

    /* V4.5: усиление микрофона. */
    public static final String KEY_MIC_GAIN      = "mic_gain";

    public static final String DEFAULT_PASSWORD = "Rostov2026";
    public static final int    DEFAULT_REFRESH = 2;
    public static final int    DEFAULT_PORT_PRM = 5323;
    public static final int    DEFAULT_PORT_PRD = 16000;

    public static final String DEFAULT_MY_MAIL_INDEX = "51953";
    public static final String DEFAULT_MY_PCHANNEL   = "5";
    public static final String DEFAULT_PRIZNAK_PMR   = "26000";
    public static final String DEFAULT_IP_SERVER     = "185.221.154.39";
    public static final String DEFAULT_CALLSIGN      = "RW6HHL";
    public static final String DEFAULT_CITY          = "Мин-Воды";

    public static final String DEFAULT_LIST_SOURCE  = "server";
    public static final boolean DEFAULT_DIAG_ENABLED = true;

    /* 0..100, 50 = 1.0x */
    public static final int    DEFAULT_MIC_GAIN = 50;

    private EditText passInput;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_password);

        passInput = findViewById(R.id.passInput);
        Button passBtn = findViewById(R.id.passBtn);

        if (passBtn != null) {
            passBtn.setOnClickListener(v -> checkPassword());
        }
    }

    private void checkPassword() {
        if (passInput == null) return;
        String entered = passInput.getText().toString();
        SharedPreferences sp = getSharedPreferences(PREFS, MODE_PRIVATE);
        String saved = sp.getString(KEY_PASSWORD, DEFAULT_PASSWORD);

        if (saved.equals(entered)) {
            Intent i = new Intent(PasswordActivity.this, MainActivity.class);
            startActivity(i);
            finish();
        } else {
            Toast.makeText(this, R.string.password_error,
                    Toast.LENGTH_SHORT).show();
            passInput.setText("");
        }
    }
}