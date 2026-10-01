package com.roket.adbdrop;
import android.content.*;
import android.graphics.Bitmap;
import android.os.*;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import com.google.android.material.button.MaterialButtonToggleGroup;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.zxing.*;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import java.util.*;

public class SendActivity extends BaseActivity {
    RadarView radar; ImageView center, qr; TextView tvState, tvDetail, tvLink, tvUsb, tvItems;
    LinearProgressIndicator prog; View paneWifi, paneUsb, qrCard;
    int baseDone; long start; boolean usbOn; String lastQr = "", wifiUrl = "", usbUrl = ""; Boolean lastInd;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); body(R.layout.activity_send);
        if (ServerService.server == null) ServerService.startFrom(this);
        radar = findViewById(R.id.radar); center = findViewById(R.id.center); qr = findViewById(R.id.qr); qrCard = findViewById(R.id.qrCard);
        tvState = findViewById(R.id.tvState); tvDetail = findViewById(R.id.tvDetail); tvLink = findViewById(R.id.tvLink);
        tvUsb = findViewById(R.id.tvUsb); tvItems = findViewById(R.id.tvItems); prog = findViewById(R.id.prog);
        paneWifi = findViewById(R.id.paneWifi); paneUsb = findViewById(R.id.paneUsb);
        prog.setMax(1000);
        baseDone = FileServer.completed.get(); start = System.currentTimeMillis();

        MaterialButtonToggleGroup tg = findViewById(R.id.tg); tg.check(R.id.tWifi);
        tg.addOnButtonCheckedListener((g, id, on) -> {
            if (!on) return;
            paneWifi.setVisibility(id == R.id.tWifi ? View.VISIBLE : View.GONE);
            paneUsb.setVisibility(id == R.id.tUsb ? View.VISIBLE : View.GONE);
        });
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnCopy).setOnClickListener(v -> copy(wifiUrl));
        findViewById(R.id.btnUsbCopy).setOnClickListener(v -> copy(usbUrl.isEmpty() ? "http://localhost:" + ServerService.port + "/?k=" + ServerService.key : usbUrl));
        findViewById(R.id.btnUsb).setOnClickListener(v -> usbTether());
        findViewById(R.id.btnStop).setOnClickListener(v -> {
            startService(new Intent(this, ServerService.class).setAction("stop"));
            if (usbOn) shell("svc usb setFunctions");
            finish();
        });
        poll.run();
    }

    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }
    void copy(String s) {
        if (s == null || s.isEmpty()) return;
        ((ClipboardManager) getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("url", s)); toast("Link copied");
    }
    void shell(String cmd) {
        String m = sp.getString("mode", "none"); if (m.equals("none")) return;
        new Thread(() -> { try { Shell s = new Shell(); s.mode = m; s.out(cmd); } catch (Exception e) {} }).start();
    }
    void usbTether() {
        String m = sp.getString("mode", "none");
        if (!m.equals("none")) {
            usbOn = true; shell("svc usb setFunctions rndis");
            toast("Requested USB tethering. Give it a few seconds (cable must be plugged in).");
        } else {
            try { startActivity(new Intent("android.settings.TETHER_SETTINGS")); }
            catch (Exception e) { startActivity(new Intent(Settings.ACTION_WIRELESS_SETTINGS)); }
            toast("Turn on \"USB tethering\", then come back here.");
        }
    }

    static Bitmap makeQr(String s) {
        try {
            Map<EncodeHintType, Object> h = new EnumMap<>(EncodeHintType.class); h.put(EncodeHintType.MARGIN, 0);
            BitMatrix m = new QRCodeWriter().encode(s, BarcodeFormat.QR_CODE, 600, 600, h);
            int w = m.getWidth(), ht = m.getHeight(); int[] px = new int[w * ht];
            for (int y = 0; y < ht; y++) for (int x = 0; x < w; x++) px[y * w + x] = m.get(x, y) ? 0xFF000000 : 0xFFFFFFFF;
            Bitmap bm = Bitmap.createBitmap(w, ht, Bitmap.Config.ARGB_8888); bm.setPixels(px, 0, w, 0, 0, w, ht); return bm;
        } catch (Exception e) { return null; }
    }

    final Runnable poll = new Runnable() { public void run() {
        boolean on = ServerService.server != null;
        Net.A wifi = null, usb = null;
        for (Net.A a : Net.list()) { if (a.kind.equals("usb")) usb = a; else if (wifi == null || a.kind.equals("wifi")) wifi = a; }
        // Wi-Fi pane
        if (on && wifi != null) {
            wifiUrl = Net.url(wifi); tvLink.setText(wifiUrl); qrCard.setVisibility(View.VISIBLE);
            if (!wifiUrl.equals(lastQr)) { qr.setImageBitmap(makeQr(wifiUrl)); lastQr = wifiUrl; }
        } else { wifiUrl = ""; qrCard.setVisibility(View.GONE); tvLink.setText(on ? "Not on Wi-Fi. Connect to Wi-Fi or turn on your hotspot." : ""); }
        // USB pane
        if (on && usb != null) { usbUrl = Net.url(usb); tvUsb.setText("USB connected \u2713\n\nOpen this on the PC browser:\n" + usbUrl); }
        else {
            usbUrl = "";
            tvUsb.setText("1. Plug the cable into the PC.\n2. Tap \"Turn on USB tethering\" (Root/Shizuku does it for you, otherwise it opens the setting).\n3. A link will appear here. Open it on the PC.\n\nWith adb instead: run  adb forward tcp:" + ServerService.port + " tcp:" + ServerService.port + "  then open  http://localhost:" + ServerService.port + "/?k=" + ServerService.key);
        }
        // items
        List<String> names = new ArrayList<>();
        for (String e : ServerService.SHARED) names.add(e.substring(e.lastIndexOf('/') + 1));
        tvItems.setText(names.isEmpty() ? "" : names.size() + " item(s): " + String.join(", ", names.subList(0, Math.min(3, names.size()))) + (names.size() > 3 ? " +" + (names.size() - 3) : ""));
        // state
        boolean busy = FileServer.active.get() > 0; boolean fast = false;
        int icon = R.drawable.ic_send; boolean showProg = false;
        if (!on) {
            tvState.setText("Starting\u2026");
            String last = ServerService.LOG.isEmpty() ? "" : ServerService.LOG.get(ServerService.LOG.size() - 1);
            tvDetail.setText(last.contains("failed") ? last : "");
        } else if (busy) {
            fast = true; showProg = true; tvState.setText("Transferring");
            boolean ind = FileServer.total <= 0;
            if (lastInd == null || lastInd != ind) { prog.setVisibility(View.GONE); prog.setIndeterminate(ind); lastInd = ind; }
            if (!ind) prog.setProgressCompat((int) (FileServer.done * 1000 / FileServer.total), true);
            tvDetail.setText(FileServer.xfer + "  " + FileServer.fmt(FileServer.done) + (ind ? "" : " / " + FileServer.fmt(FileServer.total)));
        } else if (FileServer.completed.get() > baseDone) {
            icon = R.drawable.ic_check; tvState.setText("Transfer complete"); tvDetail.setText("with " + FileServer.lastClient + "  -  keep sharing or stop below");
        } else if (FileServer.lastClientAt >= start) {
            tvState.setText("Device connected"); tvDetail.setText(FileServer.lastClient);
        } else {
            tvState.setText("Waiting for a device\u2026");
            tvDetail.setText(names.isEmpty() ? "Ready to receive or browse" : "Open the link on the other device");
        }
        prog.setVisibility(showProg ? View.VISIBLE : View.GONE); if (!showProg) lastInd = null;
        radar.setFast(fast); center.setImageResource(icon);
        ui.postDelayed(this, 600);
    }};
    @Override protected void onDestroy() { ui.removeCallbacks(poll); super.onDestroy(); }
}
