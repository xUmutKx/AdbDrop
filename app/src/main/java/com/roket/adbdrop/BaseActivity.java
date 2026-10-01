package com.roket.adbdrop;
import android.content.SharedPreferences;
import android.os.*;
import android.view.View;
import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;
import com.google.android.material.color.DynamicColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;

public abstract class BaseActivity extends AppCompatActivity {
    protected SharedPreferences sp;
    protected boolean am, dyn;
    final Handler bh = new Handler(Looper.getMainLooper());
    Hub.Offer shown; AlertDialog dlg;

    @Override protected void onCreate(Bundle b) {
        sp = getSharedPreferences("s", 0);
        am = sp.getBoolean("amoled", false); dyn = sp.getBoolean("dyn", true);
        Hub.app = getApplicationContext(); Hub.shellMode = sp.getString("mode", "none");
        AppCompatDelegate.setDefaultNightMode(am ? AppCompatDelegate.MODE_NIGHT_YES : AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM);
        if (dyn) DynamicColors.applyToActivityIfAvailable(this);
        EdgeToEdge.enable(this); // transparent status + nav bars
        super.onCreate(b);
        if (am) getTheme().applyStyle(R.style.AmoledOverlay, true);
    }

    protected void body(int layout) {
        setContentView(layout);
        View r = findViewById(R.id.root);
        int l = r.getPaddingLeft(), t = r.getPaddingTop(), rr = r.getPaddingRight(), b = r.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(r, (v, w) -> {
            Insets s = w.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            v.setPadding(l + s.left, t + s.top, rr + s.right, b + s.bottom);
            return WindowInsetsCompat.CONSUMED;
        });
    }

    final Runnable bp = new Runnable() { public void run() {
        UsbHub.tick(BaseActivity.this);
        Hub.Offer o = Hub.pending;
        if (o != null && o != shown) {
            shown = o;
            dlg = new MaterialAlertDialogBuilder(BaseActivity.this).setTitle("Receive files?")
                .setMessage(o.from + " wants to send " + o.summary())
                .setCancelable(false)
                .setPositiveButton("Accept", (d, w) -> Hub.answer(true))
                .setNegativeButton("Decline", (d, w) -> Hub.answer(false)).show();
        } else if (o == null) { if (dlg != null && dlg.isShowing()) dlg.dismiss(); shown = null; }
        bh.postDelayed(this, 600);
    }};
    @Override protected void onResume() { super.onResume(); bh.post(bp); }
    @Override protected void onPause() { bh.removeCallbacks(bp); super.onPause(); }
}
