package com.roket.adbdrop;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.os.storage.StorageManager;
import android.os.storage.StorageVolume;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.activity.OnBackPressedCallback;
import androidx.recyclerview.widget.*;
import com.google.android.material.checkbox.MaterialCheckBox;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.color.MaterialColors;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.io.File;
import java.util.*;

public class MainActivity extends BaseActivity {
    static class Item { String name, path; boolean dir; long size = -1; }

    TextView tvStatus, tvSub, tvUsb, tvPath, tvEmpty, tvXfer; ImageButton btnUp;
    LinearProgressIndicator prog; ExtendedFloatingActionButton fab; RecyclerView rv; ScrollView homeScroll; LinearLayout homeList, details;
    String cur, mode = "none";
    final List<Item> items = new ArrayList<>();
    final Map<String, Boolean> sel = new LinkedHashMap<>(); // path -> isDir
    final Set<String> roots = new HashSet<>();
    Adapter ad; Boolean lastInd = null;
    final Handler ui = new Handler(Looper.getMainLooper());

    int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); body(R.layout.activity_main);
        tvStatus = findViewById(R.id.tvStatus); tvSub = findViewById(R.id.tvSub); tvUsb = findViewById(R.id.tvUsb); tvPath = findViewById(R.id.tvPath);
        tvEmpty = findViewById(R.id.tvEmpty); tvXfer = findViewById(R.id.tvXfer); btnUp = findViewById(R.id.btnUp);
        prog = findViewById(R.id.prog); fab = findViewById(R.id.fab); rv = findViewById(R.id.rv);
        homeScroll = findViewById(R.id.homeScroll); homeList = findViewById(R.id.homeList);
        prog.setMax(1000);
        rv.setLayoutManager(new LinearLayoutManager(this)); ad = new Adapter(); rv.setAdapter(ad);

        findViewById(R.id.btnSettings).setOnClickListener(v -> startActivity(new Intent(this, SettingsActivity.class)));
        btnUp.setOnClickListener(v -> goUp());
        fab.setOnClickListener(v -> send());
        tvEmpty.setOnClickListener(v -> askAllFiles());
        com.google.android.material.materialswitch.MaterialSwitch sw = findViewById(R.id.swVisible);
        sw.setChecked(sp.getBoolean("visible", true));
        sw.setOnCheckedChangeListener((v, c) -> { sp.edit().putBoolean("visible", c).apply(); applyVisible(); });
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override public void handleOnBackPressed() { if (cur == null) finish(); else goUp(); }
        });
        if (Build.VERSION.SDK_INT >= 33) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"}, 2);
        poll.run();
    }

    @Override protected void onResume() {
        super.onResume();
        if (am != sp.getBoolean("amoled", false) || dyn != sp.getBoolean("dyn", true)) { recreate(); return; }
        mode = sp.getString("mode", "none");
        applyVisible();
        if (cur == null) showHome(); else load(cur);
    }

    void askAllFiles() {
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager())
            startActivity(new Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION, Uri.parse("package:" + getPackageName())));
    }

    void goUp() {
        if (cur == null) return;
        File p = new File(cur).getParentFile();
        if (roots.contains(cur) || cur.equals("/sdcard") || p == null) showHome(); else load(p.getPath());
    }

    // ---------- storage home ----------
    TextView hdr(String t) {
        TextView tv = new TextView(this); tv.setText(t);
        tv.setTextAppearance(com.google.android.material.R.style.TextAppearance_Material3_TitleSmall);
        tv.setTextColor(MaterialColors.getColor(tv, com.google.android.material.R.attr.colorPrimary));
        tv.setPadding(dp(4), dp(12), 0, dp(8)); return tv;
    }
    View volCard(int icon, String title, String sub, int permille, String path) {
        View c = getLayoutInflater().inflate(R.layout.item_volume, homeList, false);
        ((ImageView) c.findViewById(R.id.vIcon)).setImageResource(icon);
        ((TextView) c.findViewById(R.id.vTitle)).setText(title);
        ((TextView) c.findViewById(R.id.vSub)).setText(sub);
        LinearProgressIndicator bar = c.findViewById(R.id.vBar);
        if (permille < 0) bar.setVisibility(View.GONE); else { bar.setMax(1000); bar.setProgress(permille); }
        c.setOnClickListener(v -> { if (path == null) askAllFiles(); else load(path); });
        return c;
    }
    void showHome() {
        cur = null; tvPath.setText("Storage"); btnUp.setVisibility(View.INVISIBLE);
        rv.setVisibility(View.GONE); tvEmpty.setVisibility(View.GONE); homeScroll.setVisibility(View.VISIBLE);
        homeList.removeAllViews(); roots.clear();
        if (Build.VERSION.SDK_INT >= 30 && !Environment.isExternalStorageManager() && mode.equals("none"))
            homeList.addView(volCard(R.drawable.ic_folder, "Allow access to all files", "Tap to grant, needed to browse and send files", -1, null));
        homeList.addView(hdr("Storage"));
        boolean any = false;
        try {
            StorageManager sm = getSystemService(StorageManager.class);
            for (StorageVolume v : sm.getStorageVolumes()) {
                String st = v.getState();
                if (!Environment.MEDIA_MOUNTED.equals(st) && !Environment.MEDIA_MOUNTED_READ_ONLY.equals(st)) continue;
                File dir = null;
                if (Build.VERSION.SDK_INT >= 30) dir = v.getDirectory();
                else try { dir = new File((String) StorageVolume.class.getMethod("getPath").invoke(v)); } catch (Exception e) {}
                if (dir == null) continue;
                String label = v.getDescription(this);
                int icon = v.isPrimary() ? R.drawable.ic_phone : label.toLowerCase().contains("usb") ? R.drawable.ic_usb : R.drawable.ic_sd;
                StatFs s = new StatFs(dir.getPath()); long tot = s.getTotalBytes(), free = s.getAvailableBytes();
                roots.add(dir.getPath()); any = true;
                homeList.addView(volCard(icon, label, FileServer.fmt(tot - free) + " used of " + FileServer.fmt(tot) + "  -  " + FileServer.fmt(free) + " free",
                    tot > 0 ? (int) ((tot - free) * 1000 / tot) : 0, dir.getPath()));
            }
        } catch (Exception e) { ServerService.log("storage: " + e.getMessage()); }
        File ext = Environment.getExternalStorageDirectory();
        if (!any) { roots.add(ext.getPath()); homeList.addView(volCard(R.drawable.ic_phone, "Internal storage", ext.getPath(), -1, ext.getPath())); }
        if (mode.equals("root")) { roots.add("/"); homeList.addView(volCard(R.drawable.ic_folder, "Root  /", "Full system access (su)", -1, "/")); }

        homeList.addView(hdr("Quick access"));
        ChipGroup cg = new ChipGroup(this); cg.setChipSpacingHorizontal(dp(8));
        Chip rc = new Chip(this); rc.setText("Received"); rc.setChipIconResource(R.drawable.ic_folder); rc.setCheckable(false);
        rc.setOnClickListener(v -> load(Hub.destDir(this).getPath())); cg.addView(rc);
        String[] names = {"Download", "DCIM", "Pictures", "Movies", "Music", "Documents", "Android/data", "Android/obb"};
        for (String n : names) {
            Chip c = new Chip(this); c.setText(n); c.setChipIconResource(R.drawable.ic_folder); c.setCheckable(false);
            c.setOnClickListener(v -> load(new File(ext, n).getPath()));
            cg.addView(c);
        }
        homeList.addView(cg);
        refreshFab();
    }

    // ---------- folder view ----------
    void load(String p) {
        cur = p; tvPath.setText(p); btnUp.setVisibility(View.VISIBLE);
        homeScroll.setVisibility(View.GONE); rv.setVisibility(View.VISIBLE);
        final String m = mode;
        new Thread(() -> {
            List<Item> l = new ArrayList<>(); File d = new File(p); File[] fs = d.listFiles();
            if (fs != null) {
                Arrays.sort(fs, (a, c) -> a.isDirectory() == c.isDirectory() ? a.getName().compareToIgnoreCase(c.getName()) : a.isDirectory() ? -1 : 1);
                for (File f : fs) { Item it = new Item(); it.name = f.getName(); it.path = f.getPath(); it.dir = f.isDirectory(); if (!it.dir) it.size = f.length(); l.add(it); }
            } else if (!m.equals("none")) {
                try {
                    Shell s = new Shell(); s.mode = m;
                    for (String ln : s.out("ls -1Ap " + Shell.q(p)).split("\n")) {
                        if (ln.isEmpty()) continue;
                        Item it = new Item(); it.dir = ln.endsWith("/"); it.name = it.dir ? ln.substring(0, ln.length() - 1) : ln; it.path = (p.equals("/") ? "" : p) + "/" + it.name; l.add(it);
                    }
                } catch (Exception e) { ServerService.log("list failed: " + e.getMessage()); }
            }
            runOnUiThread(() -> { items.clear(); items.addAll(l); ad.notifyDataSetChanged(); tvEmpty.setVisibility(l.isEmpty() ? View.VISIBLE : View.GONE); });
        }).start();
    }

    void refreshFab() {
        fab.setText("Send (" + sel.size() + ")");
        fab.setVisibility(sel.isEmpty() ? View.GONE : View.VISIBLE);
    }

    void applyVisible() {
        if (sp.getBoolean("visible", true)) startForegroundService(new Intent(this, ReceiverService.class));
        else if (ReceiverService.running) startService(new Intent(this, ReceiverService.class).setAction("stop"));
    }

    void send() {
        if (sel.isEmpty()) return;
        Hub.outbox.clear();
        for (Map.Entry<String, Boolean> e : sel.entrySet()) Hub.outbox.add((e.getValue() ? "d:" : "f:") + e.getKey());
        sel.clear(); refreshFab(); ad.notifyDataSetChanged();
        Hub.reset();
        startActivity(new Intent(this, NearbyActivity.class));
    }

    final Runnable poll = new Runnable() { public void run() {
        boolean vis = sp.getBoolean("visible", true);
        tvStatus.setText(vis ? "Visible to nearby devices" : "Hidden");
        tvSub.setText(Hub.myName(MainActivity.this) + "  \u00B7  saves to " + Hub.destDir(MainActivity.this).getPath());
        Peer u = Hub.usb;
        tvUsb.setText(u != null && u.alive ? "USB cable: connected to " + (u.peerName.isEmpty() ? "a phone" : u.peerName) : "USB cable: not connected");
        String st = Hub.tState; boolean busy = Hub.busy();
        boolean recent = (st.equals("done") || st.equals("declined") || st.equals("error")) && System.currentTimeMillis() - Hub.tEnd < 8000;
        if (busy) {
            boolean ind = Hub.tTotal <= 0 || st.equals("waiting");
            if (lastInd == null || lastInd != ind) { prog.setVisibility(View.GONE); prog.setIndeterminate(ind); lastInd = ind; }
            prog.setVisibility(View.VISIBLE); tvXfer.setVisibility(View.VISIBLE);
            if (!ind) prog.setProgressCompat((int) (Hub.tDone * 1000 / Hub.tTotal), true);
            tvXfer.setText((st.equals("sending") ? "Sending to " : st.equals("receiving") ? "Receiving from " : "Waiting for ") + Hub.tPeer + "   " + (ind ? "" : Hub.progressText()));
        } else if (recent) { prog.setVisibility(View.GONE); lastInd = null; tvXfer.setVisibility(View.VISIBLE); tvXfer.setText(Hub.tMsg); }
        else { prog.setVisibility(View.GONE); tvXfer.setVisibility(View.GONE); lastInd = null; }
        ui.postDelayed(this, 700);
    }};
    @Override protected void onDestroy() { ui.removeCallbacks(poll); super.onDestroy(); }

    static class VH extends RecyclerView.ViewHolder {
        ImageView icon; TextView name, sub; MaterialCheckBox cb;
        VH(View v) { super(v); icon = v.findViewById(R.id.icon); name = v.findViewById(R.id.name); sub = v.findViewById(R.id.sub); cb = v.findViewById(R.id.cb); }
    }
    class Adapter extends RecyclerView.Adapter<VH> {
        @Override public VH onCreateViewHolder(ViewGroup p, int t) { return new VH(getLayoutInflater().inflate(R.layout.item_file, p, false)); }
        @Override public void onBindViewHolder(VH h, int i) {
            Item it = items.get(i);
            h.name.setText(it.name); h.sub.setText(it.dir ? "Folder" : it.size >= 0 ? FileServer.fmt(it.size) : "");
            h.icon.setImageResource(it.dir ? R.drawable.ic_folder : R.drawable.ic_file);
            h.cb.setOnCheckedChangeListener(null); h.cb.setChecked(sel.containsKey(it.path));
            h.cb.setOnCheckedChangeListener((v, c) -> { if (c) sel.put(it.path, it.dir); else sel.remove(it.path); refreshFab(); });
            h.itemView.setOnClickListener(v -> { if (it.dir) load(it.path); else h.cb.toggle(); });
            h.itemView.setOnLongClickListener(v -> { h.cb.toggle(); return true; });
        }
        @Override public int getItemCount() { return items.size(); }
    }
}
