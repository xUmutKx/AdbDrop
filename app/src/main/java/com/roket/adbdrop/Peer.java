package com.roket.adbdrop;
import android.content.Context;
import java.io.*;
import java.net.Socket;
import java.util.*;
import java.util.concurrent.*;

/** One duplex link (USB or TCP). Frames: 9=hello 1=offer 2=reply 3=done. */
public class Peer {
    public interface Link { InputStream in() throws IOException; OutputStream out() throws IOException; void close(); }
    public static Link socket(Socket s) {
        return new Link() {
            public InputStream in() throws IOException { return s.getInputStream(); }
            public OutputStream out() throws IOException { return s.getOutputStream(); }
            public void close() { try { s.close(); } catch (Exception e) {} }
        };
    }

    final Context ctx; final Link link; final String kind;
    final DataInputStream in; final DataOutputStream out;
    final LinkedBlockingQueue<Integer> replies = new LinkedBlockingQueue<>();
    final Object wl = new Object();
    public volatile boolean alive = true; public volatile String peerName = "";

    public Peer(Context c, Link l, String kind) throws IOException {
        ctx = c.getApplicationContext(); link = l; this.kind = kind;
        in = new DataInputStream(new BufferedInputStream(l.in(), 1 << 16));
        out = new DataOutputStream(new BufferedOutputStream(l.out(), 1 << 16));
    }
    public void start() { new Thread(this::loop, "peer-" + kind).start(); }

    void loop() {
        try {
            synchronized (wl) { out.writeByte(9); out.writeUTF(Hub.myName(ctx)); out.flush(); }
            while (alive) {
                int t = in.readUnsignedByte();
                switch (t) {
                    case 9: peerName = in.readUTF(); break;
                    case 1: onOffer(); break;
                    case 2: replies.put(in.readUnsignedByte()); break;
                    case 3: replies.put(2); break;
                    default: throw new IOException("bad frame");
                }
            }
        } catch (Exception e) { if (Hub.busy()) Hub.finish("error", "Disconnected"); }
        finally { close(); }
    }
    public void close() { alive = false; link.close(); if (Hub.usb == this) Hub.usb = null; }

    void onOffer() throws Exception {
        String from = in.readUTF(); int n = in.readInt(); long total = in.readLong();
        String[] rel = new String[n]; long[] sz = new long[n];
        for (int i = 0; i < n; i++) { rel[i] = in.readUTF(); sz[i] = in.readLong(); }
        boolean ok = !Hub.busy() && Hub.ask(from, n, total, n > 0 ? rel[0] : "");
        synchronized (wl) { out.writeByte(2); out.writeByte(ok ? 1 : 0); out.flush(); }
        if (!ok) return;
        File dest = Hub.destDir(ctx);
        Hub.begin("receiving", from, total);
        try {
            byte[] buf = new byte[1 << 16];
            for (int i = 0; i < n; i++) {
                File f = target(dest, rel[i]); f.getParentFile().mkdirs();
                try (OutputStream os = new FileOutputStream(f)) {
                    long left = sz[i];
                    while (left > 0) {
                        int r = in.read(buf, 0, (int) Math.min(buf.length, left));
                        if (r < 0) throw new EOFException();
                        os.write(buf, 0, r); left -= r; Hub.tDone += r;
                    }
                }
            }
            synchronized (wl) { out.writeByte(3); out.flush(); }
            Hub.finish("done", "Saved to " + dest.getPath());
        } catch (Exception e) { Hub.finish("error", "Receive failed: " + e.getMessage()); throw e; }
    }

    static File target(File dest, String rel) {
        StringBuilder b = new StringBuilder();
        for (String s : rel.split("/")) { if (s.isEmpty() || s.equals(".") || s.equals("..")) continue; if (b.length() > 0) b.append('/'); b.append(s); }
        File f = new File(dest, b.length() == 0 ? "file" : b.toString());
        int i = 1; String name = f.getName(); int dot = name.lastIndexOf('.');
        while (f.exists()) { String nn = dot > 0 ? name.substring(0, dot) + " (" + i + ")" + name.substring(dot) : name + " (" + i + ")"; f = new File(f.getParentFile(), nn); i++; }
        return f;
    }

    /** Blocking. Returns true when the other side confirmed everything. */
    public boolean send(List<Hub.Entry> es) throws Exception {
        if (Hub.busy()) throw new IOException("Another transfer is running");
        long total = 0; for (Hub.Entry e : es) total += e.size;
        String who = peerName.isEmpty() ? "device" : peerName;
        Hub.begin("waiting", who, total); replies.clear();
        try {
            synchronized (wl) {
                out.writeByte(1); out.writeUTF(Hub.myName(ctx)); out.writeInt(es.size()); out.writeLong(total);
                for (Hub.Entry e : es) { out.writeUTF(e.rel); out.writeLong(e.size); }
                out.flush();
            }
            Integer r = replies.poll(70, TimeUnit.SECONDS);
            if (r == null || r != 1) { Hub.finish("declined", r == null ? "No answer" : who + " declined"); return false; }
            Hub.go("sending");
            byte[] buf = new byte[1 << 16];
            for (Hub.Entry e : es) {
                try (InputStream is = Hub.open(e)) {
                    long left = e.size; int n;
                    while (left > 0 && (n = is.read(buf, 0, (int) Math.min(buf.length, left))) > 0) {
                        synchronized (wl) { out.write(buf, 0, n); } left -= n; Hub.tDone += n;
                    }
                    if (left > 0) throw new EOFException("file changed: " + e.rel);
                }
            }
            synchronized (wl) { out.flush(); }
            r = replies.poll(120, TimeUnit.SECONDS);
            boolean ok = r != null && r == 2;
            Hub.finish(ok ? "done" : "error", ok ? "Sent to " + who : "No confirmation");
            return ok;
        } catch (Exception e) { Hub.finish("error", "Send failed: " + e.getMessage()); close(); throw e; }
    }
}
