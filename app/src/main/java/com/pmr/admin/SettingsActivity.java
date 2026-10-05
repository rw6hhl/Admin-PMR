package com.pmr.admin;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;

import java.io.File;
import java.io.InputStream;

/* Экран настроек V5.6.
 *
 * Изменения V5.6:
 *   - убрано дублирующее поле «Порт приёма UDP» (portInput);
 *   - «Частота обновления» (refreshInput) перенесена в «Регистрационные данные»;
 *   - Call и QTH перенесены в конец «Регистрационных данных».
 */
public class SettingsActivity extends AppCompatActivity {

    private EditText passCurrent;
    private EditText passNew;
    private EditText passConfirm;
    private EditText refreshInput;
    private CheckBox requirePassBox;
    private CheckBox checkSystemBox;
    private Button btn26;
    private Button btnOpenLog;
    private Button btnLoadList;
    private Button btnUpdateCheck;

    private EditText regMailIndex;
    private EditText regPChannel;
    private EditText regPriznak;
    private EditText regIpServer;
    private EditText regIpServer2;
    private EditText regCallsign;
    private EditText regCity;
    private EditText regPortPrm;
    private EditText regPortPrd;
    private EditText regMicGain;
    private EditText regSpkGain;

    private ActivityResultLauncher<String[]> filePicker;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        setContentView(R.layout.activity_settings);

        passCurrent = findViewById(R.id.passCurrent);
        passNew     = findViewById(R.id.passNew);
        passConfirm = findViewById(R.id.passConfirm);
        refreshInput = findViewById(R.id.refreshInput);
        requirePassBox = findViewById(R.id.requirePassBox);
        checkSystemBox = findViewById(R.id.checkSystemBox);
        btn26 = findViewById(R.id.btn26);
        btnOpenLog = findViewById(R.id.btnOpenLog);
        btnLoadList = findViewById(R.id.btnLoadList);
        btnUpdateCheck = findViewById(R.id.btnUpdateCheck);

        regMailIndex = findViewById(R.id.regMailIndex);
        regPChannel  = findViewById(R.id.regPChannel);
        regPriznak   = findViewById(R.id.regPriznak);
        regIpServer  = findViewById(R.id.regIpServer);
        regIpServer2 = findViewById(R.id.regIpServer2);
        regCallsign  = findViewById(R.id.regCallsign);
        regCity      = findViewById(R.id.regCity);
        regPortPrm   = findViewById(R.id.regPortPrm);
        regPortPrd   = findViewById(R.id.regPortPrd);
        regMicGain   = findViewById(R.id.regMicGain);
        regSpkGain   = findViewById(R.id.regSpkGain);

        Button saveBtn = findViewById(R.id.btnSaveSettings);
        if (saveBtn != null) saveBtn.setOnClickListener(v -> saveSettings());

        if (btn26 != null) btn26.setOnClickListener(v -> toggle26());

        if (btnOpenLog != null) btnOpenLog.setOnClickListener(v -> {
            Intent i = new Intent(SettingsActivity.this, LogActivity.class);
            startActivity(i);
        });

        filePicker = registerForActivityResult(
                new ActivityResultContracts.OpenDocument(),
                uri -> {
                    if (uri != null) applyListFile(uri);
                });

        if (btnLoadList != null) {
            btnLoadList.setOnClickListener(v ->
                    filePicker.launch(new String[]{"text/plain", "*/*"}));
        }

        if (btnUpdateCheck != null) {
            btnUpdateCheck.setOnClickListener(v -> {
                String version = getString(R.string.app_version).replace("V", "");
                new UpdateChecker(SettingsActivity.this).checkAndUpdate(version);
            });
        }

        loadSettings();
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

    private void loadSettings() {
        SharedPreferences sp = getSharedPreferences(
                PasswordActivity.PREFS, MODE_PRIVATE);

        int r = sp.getInt(PasswordActivity.KEY_REFRESH,
                PasswordActivity.DEFAULT_REFRESH);
        if (refreshInput != null) refreshInput.setText(String.valueOf(r));

        boolean requirePass = sp.getBoolean(
                PasswordActivity.KEY_REQUIRE_PASSWORD, true);
        if (requirePassBox != null) requirePassBox.setChecked(requirePass);

        boolean checkSystem = sp.getBoolean(
                PasswordActivity.KEY_CHECK_SYSTEM, true);
        if (checkSystemBox != null) checkSystemBox.setChecked(checkSystem);

        boolean on26 = sp.getBoolean(PasswordActivity.KEY_26_STATE, false);
        updateBtn26(on26);

        if (regMailIndex != null)
            regMailIndex.setText(sp.getString(
                    PasswordActivity.KEY_MY_MAIL_INDEX,
                    PasswordActivity.DEFAULT_MY_MAIL_INDEX));
        if (regPChannel != null)
            regPChannel.setText(sp.getString(
                    PasswordActivity.KEY_MY_PCHANNEL,
                    PasswordActivity.DEFAULT_MY_PCHANNEL));
        if (regPriznak != null)
            regPriznak.setText(sp.getString(
                    PasswordActivity.KEY_PRIZNAK_PMR,
                    PasswordActivity.DEFAULT_PRIZNAK_PMR));
        if (regIpServer != null)
            regIpServer.setText(sp.getString(
                    PasswordActivity.KEY_IP_SERVER,
                    PasswordActivity.DEFAULT_IP_SERVER));
        if (regIpServer2 != null)
            regIpServer2.setText(sp.getString(
                    PasswordActivity.KEY_IP_SERVER2,
                    PasswordActivity.DEFAULT_IP_SERVER2));
        if (regCallsign != null)
            regCallsign.setText(sp.getString(
                    PasswordActivity.KEY_CALLSIGN,
                    PasswordActivity.DEFAULT_CALLSIGN));
        if (regCity != null)
            regCity.setText(sp.getString(
                    PasswordActivity.KEY_CITY,
                    PasswordActivity.DEFAULT_CITY));
        if (regPortPrm != null)
            regPortPrm.setText(String.valueOf(sp.getInt(
                    PasswordActivity.KEY_PORT_PRM,
                    PasswordActivity.DEFAULT_PORT_PRM)));
        if (regPortPrd != null)
            regPortPrd.setText(String.valueOf(sp.getInt(
                    PasswordActivity.KEY_PORT_PRD,
                    PasswordActivity.DEFAULT_PORT_PRD)));
        if (regMicGain != null)
            regMicGain.setText(String.valueOf(sp.getInt(
                    PasswordActivity.KEY_MIC_GAIN,
                    PasswordActivity.DEFAULT_MIC_GAIN)));
        if (regSpkGain != null)
            regSpkGain.setText(String.valueOf(sp.getInt(
                    PasswordActivity.KEY_SPK_GAIN,
                    PasswordActivity.DEFAULT_SPK_GAIN)));
    }

    private void toggle26() {
        SharedPreferences sp = getSharedPreferences(
                PasswordActivity.PREFS, MODE_PRIVATE);
        boolean on26 = sp.getBoolean(PasswordActivity.KEY_26_STATE, false);
        boolean newState = !on26;

        if (PmrService.pmrSocket == null) {
            Toast.makeText(this, R.string.toast_26_null,
                    Toast.LENGTH_SHORT).show();
            return;
        }
        PmrService.pmrSocket.send260(newState ? 1 : 0);

        sp.edit().putBoolean(PasswordActivity.KEY_26_STATE, newState).apply();
        updateBtn26(newState);

        Toast.makeText(this,
                newState ? R.string.toast_26_on : R.string.toast_26_off,
                Toast.LENGTH_SHORT).show();
    }

    private void updateBtn26(boolean on) {
        if (btn26 == null) return;
        if (on) {
            btn26.setText(R.string.btn_26_on);
            btn26.setBackgroundTintList(getColorStateList(R.color.c_green));
        } else {
            btn26.setText(R.string.btn_26_off);
            btn26.setBackgroundTintList(getColorStateList(R.color.c_red));
        }
    }

    private void saveSettings() {
        SharedPreferences sp = getSharedPreferences(
                PasswordActivity.PREFS, MODE_PRIVATE);

        String cur = sp.getString(PasswordActivity.KEY_PASSWORD,
                PasswordActivity.DEFAULT_PASSWORD);

        String enteredCur = passCurrent.getText().toString();
        String newPass = passNew.getText().toString();
        String confirmPass = passConfirm.getText().toString();

        if (!enteredCur.isEmpty() || !newPass.isEmpty() || !confirmPass.isEmpty()) {
            if (!cur.equals(enteredCur)) {
                Toast.makeText(this, R.string.settings_error_current,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (newPass.isEmpty()) {
                Toast.makeText(this, R.string.settings_error_empty,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            if (!newPass.equals(confirmPass)) {
                Toast.makeText(this, R.string.settings_error_mismatch,
                        Toast.LENGTH_SHORT).show();
                return;
            }
            sp.edit().putString(PasswordActivity.KEY_PASSWORD, newPass).apply();
        }

        int refresh = PasswordActivity.DEFAULT_REFRESH;
        try {
            String rs = refreshInput.getText().toString().trim();
            if (!rs.isEmpty()) {
                int v = Integer.parseInt(rs);
                if (v >= 1 && v <= 60) refresh = v;
                else {
                    Toast.makeText(this, "Частота: 1..60",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            }
        } catch (NumberFormatException e) {
            Toast.makeText(this, "Частота: число",
                    Toast.LENGTH_SHORT).show();
            return;
        }
        sp.edit().putInt(PasswordActivity.KEY_REFRESH, refresh).apply();

        boolean requirePass = requirePassBox != null && requirePassBox.isChecked();
        sp.edit().putBoolean(PasswordActivity.KEY_REQUIRE_PASSWORD,
                requirePass).apply();

        boolean checkSystem = checkSystemBox != null && checkSystemBox.isChecked();
        sp.edit().putBoolean(PasswordActivity.KEY_CHECK_SYSTEM,
                checkSystem).apply();

        String myMailIndex = (regMailIndex != null)
                ? regMailIndex.getText().toString().trim() : "";
        String myPChannel = (regPChannel != null)
                ? regPChannel.getText().toString().trim() : "";
        String priznak = (regPriznak != null)
                ? regPriznak.getText().toString().trim() : "";
        String ipServer = (regIpServer != null)
                ? regIpServer.getText().toString().trim() : "";
        String ipServer2 = (regIpServer2 != null)
                ? regIpServer2.getText().toString().trim() : "";
        String callsign = (regCallsign != null)
                ? regCallsign.getText().toString().trim() : "";
        String city = (regCity != null)
                ? regCity.getText().toString().trim() : "";
        String portPrmStr = (regPortPrm != null)
                ? regPortPrm.getText().toString().trim() : "";
        String portPrdStr = (regPortPrd != null)
                ? regPortPrd.getText().toString().trim() : "";
        String micGainStr = (regMicGain != null)
                ? regMicGain.getText().toString().trim() : "";
        String spkGainStr = (regSpkGain != null)
                ? regSpkGain.getText().toString().trim() : "";

        if (!myMailIndex.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_MY_MAIL_INDEX, myMailIndex).apply();
        if (!myPChannel.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_MY_PCHANNEL, myPChannel).apply();
        if (!priznak.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_PRIZNAK_PMR, priznak).apply();
        if (!ipServer.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_IP_SERVER, ipServer).apply();
        if (!ipServer2.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_IP_SERVER2, ipServer2).apply();
        if (!callsign.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_CALLSIGN, callsign).apply();
        if (!city.isEmpty()) sp.edit().putString(
                PasswordActivity.KEY_CITY, city).apply();

        if (!portPrmStr.isEmpty()) {
            try {
                int v = Integer.parseInt(portPrmStr);
                if (v > 0 && v < 65536) {
                    sp.edit().putInt(PasswordActivity.KEY_PORT_PRM, v).apply();
                } else {
                    Toast.makeText(this, "PORT_prm: 1..65535",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "PORT_prm: число",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (!portPrdStr.isEmpty()) {
            try {
                int v = Integer.parseInt(portPrdStr);
                if (v > 0 && v < 65536) {
                    sp.edit().putInt(PasswordActivity.KEY_PORT_PRD, v).apply();
                } else {
                    Toast.makeText(this, "PORT_prd: 1..65535",
                            Toast.LENGTH_SHORT).show();
                    return;
                }
            } catch (NumberFormatException e) {
                Toast.makeText(this, "PORT_prd: число",
                        Toast.LENGTH_SHORT).show();
                return;
            }
        }
        if (!micGainStr.isEmpty()) {
            try {
                int v = Integer.parseInt(micGainStr);
                if (v < 0) v = 0;
                if (v > 100) v = 100;
                sp.edit().putInt(PasswordActivity.KEY_MIC_GAIN, v).apply();
            } catch (NumberFormatException ignored) {}
        }
        if (!spkGainStr.isEmpty()) {
            try {
                int v = Integer.parseInt(spkGainStr);
                if (v < 0) v = 0;
                if (v > 200) v = 200;
                sp.edit().putInt(PasswordActivity.KEY_SPK_GAIN, v).apply();
            } catch (NumberFormatException ignored) {}
        }

        if (PmrService.pmrSocket != null) {
            PmrService.pmrSocket.reloadFromPrefs(this);
        }

        if (PmrService.pmrSocket != null
                && !priznak.isEmpty()
                && !callsign.isEmpty()
                && !city.isEmpty()) {
            String cmd = priznak + " " + callsign + " " + city;
            PmrService.pmrSocket.sendRename(cmd);
        }

        Toast.makeText(this, R.string.settings_saved,
                Toast.LENGTH_SHORT).show();

        passCurrent.setText("");
        passNew.setText("");
        passConfirm.setText("");
    }
}