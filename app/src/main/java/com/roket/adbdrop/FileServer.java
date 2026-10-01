package com.roket.adbdrop;
import android.net.Uri;
import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

public class FileServer {
    public interface Log { void log(String s); }
    public static volatile long done, total;
    public static volatile String xfer = "";
    public static final AtomicInteger active = new AtomicInteger(), completed = new AtomicInteger();
    public static volatile String lastClient = ""; public static volatile long lastClientAt;

    final int port; final String key, home; final Shell sh; final Log log;
    ServerSocket ss;
    final ExecutorService pool = Executors.newCachedThreadPool();

    public FileServer(int port, String key, Shell sh, String home, Log log) {
        this.port = port; this.key = key; this.sh = sh; this.home = (home == null || home.isEmpty()) ? "/sdcard/Download" : home; this.log = log;
    }
    public void start() throws IOException {
        ss = new ServerSocket(port);
        new Thread(() -> {
            while (!ss.isClosed()) { try { Socket s = ss.accept(); pool.execute(() -> handle(s)); } catch (IOException e) { break; } }
        }).start();
    }
    public void stop() { try { ss.close(); } catch (Exception e) {} pool.shutdownNow(); }

    static void begin(String n, long t) { xfer = n; done = 0; total = t; active.incrementAndGet(); }
    static void end() { active.decrementAndGet(); completed.incrementAndGet(); }

    static String line(InputStream in) throws IOException {
        StringBuilder b = new StringBuilder(); int c;
        while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.append((char) c); }
        return (c == -1 && b.length() == 0) ? null : b.toString();
    }
    static String enc(String s) { try { return URLEncoder.encode(s, "UTF-8"); } catch (Exception e) { return s; } }
    static String esc(String s) { return s.replace("&", "&amp;").replace("<", "&lt;"); }
    static String fmt(long n) { return n < 1048576 ? (n / 1024) + " KB" : n < 1073741824 ? String.format("%.1f MB", n / 1048576.0) : String.format("%.2f GB", n / 1073741824.0); }

    void send(OutputStream out, int code, String type, byte[] body) throws IOException {
        out.write(("HTTP/1.1 " + code + " X\r\nContent-Type: " + type + "; charset=utf-8\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes());
        out.write(body); out.flush();
    }

    void handle(Socket s) {
        try (s) {
            s.setTcpNoDelay(true); s.setSoTimeout(30000);
            InputStream in = new BufferedInputStream(s.getInputStream(), 1 << 16);
            OutputStream out = new BufferedOutputStream(s.getOutputStream(), 1 << 16);
            String rl = line(in); if (rl == null) return;
            String[] p = rl.split(" "); if (p.length < 2) return;
            long len = 0; String h;
            while ((h = line(in)) != null && !h.isEmpty())
                if (h.toLowerCase().startsWith("content-length:")) len = Long.parseLong(h.substring(15).trim());
            Uri u = Uri.parse("http://x" + p[1]);
            if (!key.equals(u.getQueryParameter("k"))) { send(out, 403, "text/plain", "Forbidden".getBytes()); return; }
            lastClient = s.getInetAddress().getHostAddress(); lastClientAt = System.currentTimeMillis();
            String fp = u.getQueryParameter("p"); String path = u.getPath();
            if ("/dl".equals(path) && fp != null) download(out, fp);
            else if ("/zip".equals(path) && fp != null) zip(out, fp);
            else if ("/up".equals(path) && p[0].equals("PUT")) upload(in, out, fp == null ? home : fp, u.getQueryParameter("n"), len);
            else if (fp == null) home(out);
            else page(out, fp);
            out.flush();
        } catch (Exception e) { log.log("err: " + e.getMessage()); }
    }

    void download(OutputStream out, String fp) throws Exception {
        File f = new File(fp); long size; InputStream is; Process pr = null;
        if (f.isFile() && f.canRead()) { size = f.length(); is = new FileInputStream(f); }
        else if (sh.enabled()) {
            size = Long.parseLong(sh.out("stat -c %s " + Shell.q(fp)).trim());
            pr = sh.run("cat " + Shell.q(fp)); is = pr.getInputStream();
        } else { send(out, 404, "text/plain", "Not readable".getBytes()); return; }
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\nContent-Length: " + size
            + "\r\nContent-Disposition: attachment; filename*=UTF-8''" + enc(f.getName()).replace("+", "%20") + "\r\nConnection: close\r\n\r\n").getBytes());
        begin(f.getName(), size);
        try {
            byte[] buf = new byte[1 << 18]; int n;
            while ((n = is.read(buf)) > 0) { out.write(buf, 0, n); done += n; }
        } finally { end(); is.close(); if (pr != null) pr.destroy(); }
        log.log("sent " + f.getName());
    }

    static long dirSize(File d) {
        if (d.isFile()) return d.length();
        long t = 0; File[] fs = d.listFiles(); if (fs == null) return 0;
        for (File f : fs) t += dirSize(f);
        return t;
    }
    void copyInto(ZipOutputStream z, InputStream is, byte[] buf) throws IOException {
        int n; while ((n = is.read(buf)) > 0) { z.write(buf, 0, n); done += n; }
    }
    void walk(ZipOutputStream z, File d, String prefix, byte[] buf) throws IOException {
        File[] fs = d.listFiles(); if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) walk(z, f, prefix + "/" + f.getName(), buf);
            else try (InputStream is = new FileInputStream(f)) { z.putNextEntry(new ZipEntry(prefix + "/" + f.getName())); copyInto(z, is, buf); z.closeEntry(); }
            catch (IOException e) { /* skip unreadable */ }
        }
    }
    /** Streams a folder (or file) as a zip, no temp file, no size limit. */
    void zip(OutputStream out, String fp) throws Exception {
        File d = new File(fp); String name = d.getName().isEmpty() ? "root" : d.getName();
        out.write(("HTTP/1.1 200 OK\r\nContent-Type: application/zip\r\nContent-Disposition: attachment; filename*=UTF-8''"
            + enc(name).replace("+", "%20") + ".zip\r\nConnection: close\r\n\r\n").getBytes());
        begin(name + ".zip", dirSize(d));
        try {
            ZipOutputStream z = new ZipOutputStream(out); z.setLevel(Deflater.NO_COMPRESSION);
            byte[] buf = new byte[1 << 18];
            if (d.isFile()) { try (InputStream is = new FileInputStream(d)) { z.putNextEntry(new ZipEntry(name)); copyInto(z, is, buf); z.closeEntry(); } }
            else if (d.list() != null) walk(z, d, name, buf);
            else if (sh.enabled()) {
                for (String f : sh.out("find " + Shell.q(fp) + " -type f").split("\n")) {
                    if (f.isEmpty()) continue;
                    Process pr = sh.run("cat " + Shell.q(f));
                    z.putNextEntry(new ZipEntry(name + f.substring(fp.length())));
                    copyInto(z, pr.getInputStream(), buf); z.closeEntry(); pr.destroy();
                }
            }
            z.finish(); z.flush();
        } finally { end(); }
        log.log("zipped " + name);
    }

    void upload(InputStream in, OutputStream out, String dir, String n, long len) throws Exception {
        if (n == null) { send(out, 400, "text/plain", "missing n".getBytes()); return; }
        n = new File(n).getName();
        File d = new File(dir); OutputStream os; Process pr = null;
        if (d.isDirectory() && d.canWrite()) os = new FileOutputStream(new File(d, n));
        else if (sh.enabled()) { pr = sh.run("cat > " + Shell.q(dir + "/" + n)); os = pr.getOutputStream(); }
        else { send(out, 403, "text/plain", "Not writable".getBytes()); return; }
        begin(n, len);
        try {
            byte[] buf = new byte[1 << 18]; long left = len; int r;
            while (left > 0 && (r = in.read(buf, 0, (int) Math.min(buf.length, left))) > 0) { os.write(buf, 0, r); left -= r; done += r; }
        } finally { end(); os.close(); if (pr != null) pr.waitFor(); }
        send(out, 200, "text/plain", "ok".getBytes());
        log.log("received " + n);
    }

    // ---------- web UI ----------
    StringBuilder head() {
        return new StringBuilder("<!doctype html><meta name=viewport content='width=device-width,initial-scale=1'><title>AdbDrop</title><style>"
            + "body{background:#000;color:#e6e6e6;font:16px system-ui,sans-serif;max-width:820px;margin:auto;padding:16px}a{color:#8fe8b0;text-decoration:none}"
            + ".c{background:#111;border-radius:20px;padding:6px 18px;margin:12px 0}.r{display:flex;justify-content:space-between;gap:12px;padding:12px 0;border-bottom:1px solid #222}"
            + ".r:last-child{border:0}.b{display:inline-block;background:#8fe8b0;color:#000;padding:10px 20px;border-radius:22px;font-weight:600}"
            + ".u{border:2px dashed #8fe8b0;border-radius:20px;padding:16px;margin:12px 0}progress{width:100%;height:8px}span{color:#999;white-space:nowrap}</style>");
    }
    void upBox(StringBuilder b, String dir) {
        b.append("<div class=u>Upload to ").append(esc(dir)).append("<br><br><input type=file multiple onchange='up(this.files)'><progress id=pg value=0 max=100 hidden></progress><div id=s></div></div>"
            + "<script>function up(fl){const s=document.getElementById('s'),pg=document.getElementById('pg');pg.hidden=false;let n=fl.length;for(const f of fl){const x=new XMLHttpRequest();"
            + "x.open('PUT','/up?k=").append(key).append("&p=").append(enc(dir)).append("&n='+encodeURIComponent(f.name));"
            + "x.upload.onprogress=e=>{pg.value=e.loaded*100/e.total;s.textContent=f.name+' '+Math.round(e.loaded*100/e.total)+'%'};"
            + "x.onload=()=>{if(--n==0)location.reload()};x.send(f)}}</script>");
    }
    void row(StringBuilder b, String full, String name, boolean dir, String size) {
        String k = "k=" + key;
        b.append("<div class=r><a href='").append(dir ? "/?" : "/dl?").append(k).append("&p=").append(enc(full)).append("'>")
            .append(dir ? "&#128193; " : "").append(esc(name)).append("</a><span>").append(size);
        if (dir) b.append(" <a href='/zip?").append(k).append("&p=").append(enc(full)).append("'>&#11015; zip</a>");
        b.append("</span></div>");
    }
    void home(OutputStream out) throws Exception {
        StringBuilder b = head(); b.append("<h2>AdbDrop</h2>");
        if (!ServerService.SHARED.isEmpty()) {
            b.append("<h3>Shared from phone</h3><div class=c>");
            for (String e : ServerService.SHARED) {
                String pth = e.substring(2);
                String nm = pth.substring(pth.lastIndexOf('/') + 1);
                b.append("<div class=r><a href='").append(e.startsWith("d:") ? "/zip?" : "/dl?").append("k=").append(key).append("&p=").append(enc(pth)).append("'>")
                    .append(e.startsWith("d:") ? "&#128193; " : "").append(esc(nm)).append("</a><span>").append(e.startsWith("d:") ? "&#11015; zip" : "&#11015;").append("</span></div>");
            }
            b.append("</div>");
        }
        b.append("<a class=b href='/?k=").append(key).append("&p=").append(enc(home)).append("'>Browse phone storage</a>");
        upBox(b, home);
        send(out, 200, "text/html", b.toString().getBytes("UTF-8"));
    }
    void page(OutputStream out, String fp) throws Exception {
        File d = new File(fp); StringBuilder b = head();
        b.append("<h3>").append(esc(fp)).append("</h3>");
        if (d.getParent() != null) b.append("<a href='/?k=").append(key).append("&p=").append(enc(d.getParent())).append("'>&#8593; up</a> &nbsp; ");
        b.append("<a href='/?k=").append(key).append("'>home</a>");
        upBox(b, fp); b.append("<div class=c>");
        File[] fs = d.listFiles();
        if (fs != null) {
            Arrays.sort(fs, (a, c) -> a.isDirectory() == c.isDirectory() ? a.getName().compareToIgnoreCase(c.getName()) : a.isDirectory() ? -1 : 1);
            for (File f : fs) row(b, f.getPath(), f.getName(), f.isDirectory(), f.isDirectory() ? "" : fmt(f.length()));
        } else if (sh.enabled()) {
            for (String l : sh.out("ls -1Ap " + Shell.q(fp)).split("\n")) {
                if (l.isEmpty()) continue;
                boolean dir = l.endsWith("/"); String nm = dir ? l.substring(0, l.length() - 1) : l;
                row(b, fp + "/" + nm, nm, dir, "");
            }
        } else b.append("<div class=r>Cannot read: grant all-files access or pick Root/Shizuku</div>");
        b.append("</div>");
        send(out, 200, "text/html", b.toString().getBytes("UTF-8"));
    }
}
