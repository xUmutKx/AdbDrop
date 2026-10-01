package com.roket.adbdrop;
import android.content.Intent;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.materialswitch.MaterialSwitch;
import rikka.shizuku.Shizuku;

public class SettingsActivity extends BaseActivity {
    EditText etPort, etHome, etName, etRecv; String mode;

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); body(R.layout.activity_settings);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());

        MaterialSwitch sa = findViewById(R.id.swAmoled), sd = findViewById(R.id.swDyn);
        sa.setChecked(am); sd.setChecked(dyn);
        sa.setOnCheckedChangeListener((v, c) -> { sp.edit().putBoolean("amoled", c).apply(); recreate(); });
        sd.setOnCheckedChangeListener((v, c) -> { sp.edit().putBoolean("dyn", c).apply(); recreate(); });

        mode = sp.getString("mode", "none");
        MaterialButtonToggleGroup tg = findViewById(R.id.tg);
        tg.check(mode.equals("root") ? R.id.bRoot : mode.equals("shizuku") ? R.id.bShizuku : R.id.bNone);
        tg.addOnButtonCheckedListener((g, id, on) -> {
            if (!on) return;
            mode = id == R.id.bRoot ? "root" : id == R.id.bShizuku ? "shizuku" : "none";
            sp.edit().putString("mode", mode).apply();
            if (mode.equals("root")) new Thread(() -> {
                try { Shell s = new Shell(); s.mode = "root"; String o = s.out("id").trim(); runOnUiThread(() -> toast("root: " + o)); }
                catch (Exception e) { runOnUiThread(() -> toast("root failed: " + e.getMessage())); }
            }).start();
            if (mode.equals("shizuku")) {
                try {
                    if (!Shizuku.pingBinder()) toast("Start Shizuku first");
                    else if (Shizuku.checkSelfPermission() != android.content.pm.PackageManager.PERMISSION_GRANTED) Shizuku.requestPermission(1);
                    else toast("Shizuku ready");
                } catch (Throwable t) { toast("Shizuku not available"); }
            }
        });

        etPort = findViewById(R.id.etPort); etHome = findViewById(R.id.etHome); etName = findViewById(R.id.etName); etRecv = findViewById(R.id.etRecv);
        etName.setText(sp.getString("name", Hub.defaultName(this))); etRecv.setText(sp.getString("recv", "/sdcard/Download/AdbDrop"));
        findViewById(R.id.btnPc).setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        etPort.setText(String.valueOf(sp.getInt("port", 8080)));
        etHome.setText(sp.getString("home", "/sdcard/Download"));

        findViewById(R.id.btnFiles).setOnClickListener(v -> {
            if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
                startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
            else toast("Already granted");
        });
        findViewById(R.id.btnAdb).setOnClickListener(v -> {
            if (!mode.equals("root")) { toast("Needs Root. Otherwise: Developer options > Wireless debugging"); return; }
            new Thread(() -> {
                try { Shell s = new Shell(); s.mode = "root"; s.out("setprop service.adb.tcp.port 5555; stop adbd; start adbd");
                    runOnUiThread(() -> toast("PC: adb connect PHONE_IP:5555")); }
                catch (Exception e) { runOnUiThread(() -> toast("failed: " + e.getMessage())); }
            }).start();
        });
    }
    @Override protected void onPause() {
        super.onPause();
        try { int p = Integer.parseInt(etPort.getText().toString().trim()); if (p >= 1024 && p <= 65535) sp.edit().putInt("port", p).apply(); } catch (Exception e) {}
        String nm = etName.getText().toString().trim(); if (!nm.isEmpty()) sp.edit().putString("name", nm).apply();
        String rc = etRecv.getText().toString().trim(); if (!rc.isEmpty()) sp.edit().putString("recv", rc).apply();
        String h = etHome.getText().toString().trim(); if (!h.isEmpty()) sp.edit().putString("home", h).apply();
    }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
