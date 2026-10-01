package com.roket.adbdrop;
import android.content.Intent;
import android.hardware.usb.UsbDevice;
import android.hardware.usb.UsbManager;
import android.os.*;
import android.view.View;
import android.widget.*;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import java.net.*;
import java.util.*;

public class NearbyActivity extends BaseActivity {
    static class Dev { String key, name, sub; int icon; Runnable act; }

    RadarView radar; ImageView center; TextView tvState, tvDetail, tvItems, tvHelp;
    LinearLayout devList; LinearProgressIndicator prog; Button btnDone;
    Discovery disc; String sig = ""; Boolean lastInd;
    final Handler ui = new Handler(Looper.getMainLooper());

    @Override protected void onCreate(Bundle b) {
        super.onCreate(b); body(R.layout.activity_nearby);
        radar = findViewById(R.id.radar); center = findViewById(R.id.center); tvState = findViewById(R.id.tvState);
        tvDetail = findViewById(R.id.tvDetail); tvItems = findViewById(R.id.tvItems); tvHelp = findViewById(R.id.tvHelp);
        devList = findViewById(R.id.devList); prog = findViewById(R.id.prog); btnDone = findViewById(R.id.btnDone);
        prog.setMax(1000);
        findViewById(R.id.btnBack).setOnClickListener(v -> finish());
        findViewById(R.id.btnLink).setOnClickListener(v -> startActivity(new Intent(this, SendActivity.class)));
        btnDone.setOnClickListener(v -> Hub.reset());
        int n = Hub.outbox.size();
        tvItems.setText(n == 0 ? "Nothing selected" : n + " item(s) selected");
        disc = new Discovery(Hub.myId(this)); disc.start();
        poll.run();
    }

    Dev dev(String key, int icon, String name, String sub, Runnable act) {
        Dev d = new Dev(); d.key = key; d.icon = icon; d.name = name; d.sub = sub; d.act = act; return d;
    }
    void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_LONG).show(); }

    void sendVia(Peer p) {
        if (Hub.outbox.isEmpty()) { toast("Select files first (back, then tick files)"); return; }
        if (Hub.busy()) return;
        new Thread(() -> { try { p.send(Hub.expandOutbox()); } catch (Exception e) { Hub.finish("error", String.valueOf(e.getMessage())); } }).start();
    }
    void sendWifi(Discovery.Found f) {
        if (Hub.outbox.isEmpty()) { toast("Select files first (back, then tick files)"); return; }
        if (Hub.busy()) return;
        Hub.begin("waiting", f.name, 0);
        new Thread(() -> {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(f.ip, f.port), 4000); s.setTcpNoDelay(true);
                Peer p = new Peer(getApplicationContext(), Peer.socket(s), "Wi-Fi"); p.peerName = f.name; p.start();
                p.send(Hub.expandOutbox()); p.close();
            } catch (Exception e) { Hub.finish("error", "Could not reach " + f.name + ": " + e.getMessage()); try { s.close(); } catch (Exception x) {} }
        }).start();
    }
    void connectUsb(UsbDevice d) {
        UsbManager um = (UsbManager) getSystemService(USB_SERVICE);
        if (!um.hasPermission(d)) { um.requestPermission(d, UsbHub.pi(this)); toast("Allow USB access, then tap the device again"); return; }
        new Thread(() -> { String err = UsbHub.probeSwitch(um, d); runOnUiThread(() -> toast(err == null ? "Connecting over USB\u2026 allow the prompts on both phones" : err)); }).start();
    }

    final Runnable poll = new Runnable() { public void run() {
        List<Dev> devs = new ArrayList<>();
        Peer u = Hub.usb;
        if (u != null && u.alive) devs.add(dev("usb:c", R.drawable.ic_usb, u.peerName.isEmpty() ? "Connected phone" : u.peerName, "USB cable \u00B7 connected", () -> sendVia(u)));
        else for (UsbDevice d : UsbHub.candidates(NearbyActivity.this)) devs.add(dev("usb:" + d.getDeviceName(), R.drawable.ic_usb, UsbHub.title(d), "USB cable \u00B7 tap to connect", () -> connectUsb(d)));
        for (Discovery.Found f : disc.list()) devs.add(dev("w:" + f.id, R.drawable.ic_wifi, f.name, "Wi-Fi \u00B7 " + f.ip, () -> sendWifi(f)));

        StringBuilder sg = new StringBuilder(); for (Dev d : devs) sg.append(d.key).append(d.name).append(d.sub).append('|');
        if (!sg.toString().equals(sig)) {
            sig = sg.toString(); devList.removeAllViews();
            for (Dev d : devs) {
                View c = getLayoutInflater().inflate(R.layout.item_device, devList, false);
                ((ImageView) c.findViewById(R.id.dIcon)).setImageResource(d.icon);
                ((TextView) c.findViewById(R.id.dName)).setText(d.name);
                ((TextView) c.findViewById(R.id.dSub)).setText(d.sub);
                c.setOnClickListener(v -> d.act.run());
                devList.addView(c);
            }
        }
        String st = Hub.tState; boolean busy = Hub.busy();
        boolean fin = st.equals("done") || st.equals("declined") || st.equals("error");
        devList.setVisibility(busy ? View.GONE : View.VISIBLE);
        tvHelp.setVisibility(devs.isEmpty() && !busy ? View.VISIBLE : View.GONE);
        btnDone.setVisibility(fin ? View.VISIBLE : View.GONE);
        radar.setFast(busy);
        int icon = R.drawable.ic_send; boolean showProg = false;
        if (st.equals("waiting")) { tvState.setText("Waiting for " + Hub.tPeer + "\u2026"); tvDetail.setText("Ask them to tap Accept"); }
        else if (st.equals("sending") || st.equals("receiving")) {
            showProg = true; tvState.setText((st.equals("sending") ? "Sending to " : "Receiving from ") + Hub.tPeer);
            boolean ind = Hub.tTotal <= 0;
            if (lastInd == null || lastInd != ind) { prog.setVisibility(View.GONE); prog.setIndeterminate(ind); lastInd = ind; }
            if (!ind) prog.setProgressCompat((int) (Hub.tDone * 1000 / Hub.tTotal), true);
            tvDetail.setText(Hub.progressText());
        } else if (st.equals("done")) { icon = R.drawable.ic_check; tvState.setText("Done"); tvDetail.setText(Hub.tMsg); }
        else if (fin) { tvState.setText(st.equals("declined") ? "Declined" : "Failed"); tvDetail.setText(Hub.tMsg); }
        else { tvState.setText(devs.isEmpty() ? "Looking for nearby devices\u2026" : "Choose a device"); tvDetail.setText(""); }
        prog.setVisibility(showProg ? View.VISIBLE : View.GONE); if (!showProg) lastInd = null;
        center.setImageResource(icon);
        ui.postDelayed(this, 700);
    }};
    @Override protected void onDestroy() { ui.removeCallbacks(poll); disc.stop(); super.onDestroy(); }
}
