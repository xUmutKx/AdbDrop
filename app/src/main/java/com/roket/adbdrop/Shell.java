package com.roket.adbdrop;
import java.io.*;
import java.lang.reflect.Method;
import rikka.shizuku.Shizuku;

public class Shell {
    public String mode = "none"; // none | root | shizuku
    public boolean enabled() { return !mode.equals("none"); }
    public static String q(String s) { return "'" + s.replace("'", "'\\''") + "'"; }

    public Process run(String cmd) throws Exception {
        if (mode.equals("root")) return new ProcessBuilder("su", "-c", cmd).start();
        if (mode.equals("shizuku")) {
            Method m = Shizuku.class.getDeclaredMethod("newProcess", String[].class, String[].class, String.class);
            m.setAccessible(true);
            return (Process) m.invoke(null, new String[]{"sh", "-c", cmd}, null, null);
        }
        throw new IOException("no privileged backend");
    }
    public String out(String cmd) throws Exception {
        Process p = run(cmd);
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        byte[] buf = new byte[8192]; int n;
        InputStream in = p.getInputStream();
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        p.waitFor();
        return b.toString("UTF-8");
    }
}
