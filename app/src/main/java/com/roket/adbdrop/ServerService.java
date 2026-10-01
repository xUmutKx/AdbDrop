package com.roket.adbdrop;
import android.app.*;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

public class ServerService extends Service {
    public static volatile FileServer server;
    public static volatile String key = "";
    public static volatile int port = 8080;
    public static volatile String mode = "none";
    /** entries are "d:/path" (folder) or "f:/path" (file) */
    public static final List<String> SHARED = new CopyOnWriteArrayList<>();
    public static final List<String> LOG = Collections.synchronizedList(new LinkedList<>());

    static void startFrom(android.content.Context c) {
        android.content.SharedPreferences sp = c.getSharedPreferences("s", 0);
        c.startForegroundService(new Intent(c, ServerService.class).putExtra("mode", sp.getString("mode", "none"))
            .putExtra("home", sp.getString("home", "/sdcard/Download")).putExtra("port", sp.getInt("port", 8080))
            .putExtra("key", String.valueOf(100000 + new Random().nextInt(900000))));
    }
    static void log(String s) { LOG.add(s); while (LOG.size() > 12) LOG.remove(0); }

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent i, int f, int id) {
        if (i == null || "stop".equals(i.getAction())) { stopAll(); return START_NOT_STICKY; }
        NotificationChannel ch = new NotificationChannel("srv", "Server", NotificationManager.IMPORTANCE_LOW);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "srv").setContentTitle("AdbDrop server running")
            .setSmallIcon(android.R.drawable.stat_sys_upload)
            .setContentIntent(PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class), PendingIntent.FLAG_IMMUTABLE)).build();
        if (Build.VERSION.SDK_INT >= 29) startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        else startForeground(1, n);
        try {
            if (server != null) server.stop();
            Shell sh = new Shell(); sh.mode = i.getStringExtra("mode"); mode = sh.mode;
            key = i.getStringExtra("key"); port = i.getIntExtra("port", 8080);
            server = new FileServer(port, key, sh, i.getStringExtra("home"), ServerService::log);
            server.start(); log("server started on :" + port);
        } catch (Exception e) { log("start failed: " + e.getMessage()); stopAll(); }
        return START_NOT_STICKY;
    }
    void stopAll() {
        if (server != null) server.stop(); server = null; log("server stopped");
        stopForeground(true); stopSelf();
    }
}
