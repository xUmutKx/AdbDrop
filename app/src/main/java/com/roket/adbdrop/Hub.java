package com.roket.adbdrop;
import android.app.*;
import android.content.*;
import android.os.*;
import android.provider.Settings;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Shared state + helpers for nearby sharing. */
public class Hub {
    public static Context app;
    public static volatile Peer usb;
    public static final List<String> outbox = new CopyOnWriteArrayList<>(); // "d:/path" or "f:/path"
    public static volatile String shellMode = "none";
    public static volatile String tState = "idle", tPeer = "", tMsg = "";
    public static volatile long tDone, tTotal, tStart, tEnd;
    public static volatile Offer pending;

    public static class Entry { String rel, path; long size; }
    public static class Offer {
        final String from, first; final int n; final long total; final ArrayBlockingQueue<Boolean> q = new ArrayBlockingQueue<>(1);
        Offer(String f, int n, long t, String first) { from = f; this.n = n; total = t; this.first = first; }
        String summary() { return n == 1 ? first + " (" + FileServer.fmt(total) + ")" : n + " files (" + FileServer.fmt(total) + ")"; }
    }

    static SharedPreferences sp(Context c) { return c.getSharedPreferences("s", 0); }
    static String myId(Context c) {
        SharedPreferences s = sp(c); String id = s.getString("id", null);
        if (id == null) { id = Long.toHexString(new Random().nextLong()); s.edit().putString("id", id).apply(); }
        return id;
    }
    static String defaultName(Context c) {
        String n = null; try { n = Settings.Global.getString(c.getContentResolver(), "device_name"); } catch (Exception e) {}
        return (n == null || n.isEmpty()) ? Build.MODEL : n;
    }
    static String myName(Context c) { return sp(c).getString("name", defaultName(c)).replace("|", " "); }
    static File destDir(Context c) {
        File d = new File(sp(c).getString("recv", "/sdcard/Download/AdbDrop")); d.mkdirs();
        if (d.isDirectory() && d.canWrite()) return d;
        File alt = new File(c.getExternalFilesDir(null), "received"); alt.mkdirs(); return alt;
    }

    // ---- transfer state ----
    static void begin(String st, String peer, long total) { tState = st; tPeer = peer; tTotal = total; tDone = 0; tStart = System.currentTimeMillis(); tMsg = ""; }
    static void go(String st) { tState = st; tDone = 0; tStart = System.currentTimeMillis(); }
    static void finish(String st, String msg) { tState = st; tMsg = msg; tEnd = System.currentTimeMillis(); }
    static boolean busy() { return tState.equals("waiting") || tState.equals("sending") || tState.equals("receiving"); }
    static void reset() { if (!busy()) tState = "idle"; }
    static String progressText() {
        long ms = System.currentTimeMillis() - tStart; String sp = ms > 800 && tDone > 0 ? "   " + FileServer.fmt(tDone * 1000 / ms) + "/s" : "";
        return FileServer.fmt(tDone) + " / " + FileServer.fmt(tTotal) + sp;
    }

    // ---- incoming prompt ----
    static boolean ask(String from, int n, long total, String first) {
        Offer o = new Offer(from, n, total, first); pending = o; notifyIncoming(o);
        try { Boolean r = o.q.poll(60, TimeUnit.SECONDS); return r != null && r; }
        catch (InterruptedException e) { return false; }
        finally { if (pending == o) pending = null; try { app.getSystemService(NotificationManager.class).cancel(42); } catch (Exception e) {} }
    }
    static void answer(boolean ok) { Offer o = pending; if (o != null) o.q.offer(ok); }
    static void notifyIncoming(Offer o) {
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class);
            nm.createNotificationChannel(new NotificationChannel("incoming", "Incoming files", NotificationManager.IMPORTANCE_HIGH));
            int f = PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT;
            PendingIntent a = PendingIntent.getBroadcast(app, 1, new Intent(app, ActionReceiver.class).setAction("accept"), f);
            PendingIntent d = PendingIntent.getBroadcast(app, 2, new Intent(app, ActionReceiver.class).setAction("decline"), f);
            PendingIntent open = PendingIntent.getActivity(app, 3, new Intent(app, MainActivity.class), f);
            Notification n = new Notification.Builder(app, "incoming").setSmallIcon(android.R.drawable.stat_sys_download)
                .setContentTitle(o.from + " wants to send").setContentText(o.summary()).setContentIntent(open).setAutoCancel(true)
                .addAction(new Notification.Action.Builder(0, "Accept", a).build())
                .addAction(new Notification.Action.Builder(0, "Decline", d).build()).build();
            nm.notify(42, n);
        } catch (Exception e) {}
    }

    // ---- outgoing: expand files/folders ----
    static List<Entry> expandOutbox() throws Exception {
        List<Entry> l = new ArrayList<>();
        for (String s : outbox) {
            boolean dir = s.startsWith("d:"); String p = s.substring(2);
            String nm = p.substring(p.lastIndexOf('/') + 1); if (nm.isEmpty()) nm = "root";
            expand(p, dir, nm, l);
        }
        return l;
    }
    static void expand(String path, boolean dir, String rel, List<Entry> out) throws Exception {
        File f = new File(path);
        if (!dir) { Entry e = new Entry(); e.path = path; e.rel = rel; e.size = f.exists() ? f.length() : statSize(path); out.add(e); return; }
        File[] fs = f.listFiles();
        if (fs != null) { for (File c : fs) expand(c.getPath(), c.isDirectory(), rel + "/" + c.getName(), out); }
        else if (!shellMode.equals("none")) {
            Shell s = new Shell(); s.mode = shellMode;
            for (String ln : s.out("find " + Shell.q(path) + " -type f -exec stat -c '%s|%n' {} +").split("\n")) {
                int i = ln.indexOf('|'); if (i < 0) continue;
                Entry e = new Entry(); e.size = Long.parseLong(ln.substring(0, i).trim()); e.path = ln.substring(i + 1); e.rel = rel + e.path.substring(path.length()); out.add(e);
            }
        }
    }
    static long statSize(String path) throws Exception {
        if (shellMode.equals("none")) return 0;
        Shell s = new Shell(); s.mode = shellMode; return Long.parseLong(s.out("stat -c %s " + Shell.q(path)).trim());
    }
    static InputStream open(Entry e) throws Exception {
        File f = new File(e.path);
        if (f.canRead()) return new FileInputStream(f);
        Shell s = new Shell(); s.mode = shellMode; return s.run("cat " + Shell.q(e.path)).getInputStream();
    }
}
