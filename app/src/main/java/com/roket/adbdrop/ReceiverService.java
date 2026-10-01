package com.roket.adbdrop;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Makes this phone visible to nearby devices (Wi-Fi/hotspot beacons + TCP listener). */
public class ReceiverService extends Service {
    public static volatile boolean running;
    ServerSocket ss; DatagramSocket ds; volatile boolean stop;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i != null && "stop".equals(i.getAction())) { shutdown(); stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel("recv", "Visible to nearby", NotificationManager.IMPORTANCE_LOW));
        Notification n = new Notification.Builder(this, "recv").setContentTitle("AdbDrop is visible to nearby devices")
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(2, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC); else startForeground(2, n);
        if (running) return START_NOT_STICKY;
        Hub.app = getApplicationContext();
        try { ss = new ServerSocket(0); } catch (Exception e) { stopForeground(true); stopSelf(); return START_NOT_STICKY; }
        running = true; stop = false;
        new Thread(this::acceptLoop).start();
        new Thread(this::beacon).start();
        return START_NOT_STICKY;
    }

    void acceptLoop() {
        while (!stop) {
            try {
                Socket s = ss.accept(); s.setTcpNoDelay(true);
                new Peer(getApplicationContext(), Peer.socket(s), "Wi-Fi").start();
            } catch (Exception e) { if (stop) break; }
        }
    }
    void beacon() {
        try { ds = new DatagramSocket(); ds.setBroadcast(true); } catch (Exception e) { return; }
        while (!stop) {
            try {
                byte[] d = ("ADBDROP1|" + Hub.myName(this) + "|" + ss.getLocalPort() + "|" + Hub.myId(this)).getBytes(StandardCharsets.UTF_8);
                for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (!n.isUp() || n.isLoopback()) continue;
                    for (InterfaceAddress ia : n.getInterfaceAddresses()) {
                        InetAddress b = ia.getBroadcast();
                        if (b != null) ds.send(new DatagramPacket(d, d.length, b, 8766));
                    }
                }
            } catch (Exception e) {}
            try { Thread.sleep(1500); } catch (InterruptedException e) { break; }
        }
    }
    void shutdown() {
        stop = true; running = false;
        try { if (ss != null) ss.close(); } catch (Exception e) {}
        if (ds != null) ds.close();
    }
    @Override public void onDestroy() { shutdown(); super.onDestroy(); }
}
