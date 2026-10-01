package com.roket.adbdrop;
import java.net.*;
import java.util.*;

class Net {
    static class A { String kind, name, ip; }
    static List<A> list() {
        List<A> l = new ArrayList<>();
        try {
            for (NetworkInterface n : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                String nm = n.getName();
                if (!n.isUp() || n.isLoopback() || nm.contains("rmnet") || nm.contains("ccmni")) continue;
                for (InetAddress a : Collections.list(n.getInetAddresses())) {
                    if (!(a instanceof Inet4Address)) continue;
                    A x = new A(); x.name = nm; x.ip = a.getHostAddress();
                    boolean usb = nm.startsWith("rndis") || nm.startsWith("usb") || nm.startsWith("ncm");
                    x.kind = usb ? "usb" : (nm.startsWith("wlan") || nm.startsWith("ap") || nm.startsWith("swlan") || nm.startsWith("eth")) ? "wifi" : "other";
                    l.add(x);
                }
            }
        } catch (Exception e) {}
        return l;
    }
    static String url(A a) { return "http://" + a.ip + ":" + ServerService.port + "/?k=" + ServerService.key; }
}
