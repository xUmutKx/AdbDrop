package com.roket.adbdrop;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/** Listens for UDP beacons from nearby receivers (Wi-Fi / hotspot). */
public class Discovery {
    public static class Found { String id, name, ip; int port; long seen; }
    final Map<String, Found> found = new ConcurrentHashMap<>();
    volatile boolean stop; DatagramSocket sock; final String myId;
    public Discovery(String myId) { this.myId = myId; }

    public void start() {
        new Thread(() -> {
            try {
                sock = new DatagramSocket(null); sock.setReuseAddress(true); sock.bind(new InetSocketAddress(8766)); sock.setSoTimeout(1000);
                byte[] b = new byte[512];
                while (!stop) {
                    DatagramPacket p = new DatagramPacket(b, b.length);
                    try { sock.receive(p); } catch (SocketTimeoutException e) { continue; }
                    String[] f = new String(p.getData(), 0, p.getLength(), StandardCharsets.UTF_8).split("\\|");
                    if (f.length < 4 || !f[0].equals("ADBDROP1") || f[3].equals(myId)) continue;
                    Found x = new Found(); x.name = f[1]; x.port = Integer.parseInt(f[2]); x.id = f[3];
                    x.ip = p.getAddress().getHostAddress(); x.seen = System.currentTimeMillis(); found.put(x.id, x);
                }
            } catch (Exception e) {}
        }).start();
    }
    public void stop() { stop = true; if (sock != null) sock.close(); }
    public List<Found> list() {
        List<Found> l = new ArrayList<>(); long now = System.currentTimeMillis();
        for (Found f : found.values()) if (now - f.seen < 6000) l.add(f);
        Collections.sort(l, (a, c) -> a.name.compareToIgnoreCase(c.name));
        return l;
    }
}
