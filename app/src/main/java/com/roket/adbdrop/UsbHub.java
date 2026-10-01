package com.roket.adbdrop;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.hardware.usb.*;
import android.os.ParcelFileDescriptor;
import java.io.*;
import java.util.*;

/** Android-to-Android over a USB cable using Android Open Accessory (AOA). Host side switches the other phone into accessory mode. */
public class UsbHub {
    static final Set<String> asked = new HashSet<>();

    static PendingIntent pi(Context c) {
        return PendingIntent.getBroadcast(c, 0, new Intent("com.roket.adbdrop.USB_PERM").setPackage(c.getPackageName()), PendingIntent.FLAG_MUTABLE);
    }
    static boolean isAoa(UsbDevice d) { return d.getVendorId() == 0x18D1 && d.getProductId() >= 0x2D00 && d.getProductId() <= 0x2D05; }
    static String title(UsbDevice d) {
        String n = null; try { n = d.getProductName(); } catch (Throwable e) {}
        return (n == null || n.isEmpty()) ? "USB device " + Integer.toHexString(d.getVendorId()) : n;
    }
    static List<UsbDevice> candidates(Context c) {
        List<UsbDevice> l = new ArrayList<>(); UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE);
        if (um == null) return l;
        for (UsbDevice d : um.getDeviceList().values()) if (d.getDeviceClass() != UsbConstants.USB_CLASS_HUB && !isAoa(d)) l.add(d);
        return l;
    }

    /** Called often from the UI loop. Opens the link when the cable partner is in accessory mode. */
    static synchronized void tick(Context c) {
        Peer cur = Hub.usb; if (cur != null && cur.alive) return;
        UsbManager um = (UsbManager) c.getSystemService(Context.USB_SERVICE); if (um == null) return;
        try {
            UsbAccessory[] al = um.getAccessoryList();
            if (al != null && al.length > 0) {
                UsbAccessory a = al[0];
                if (um.hasPermission(a)) openAcc(c, um, a); else if (asked.add("acc")) um.requestPermission(a, pi(c));
                return;
            } else asked.remove("acc");
            for (UsbDevice d : um.getDeviceList().values()) if (isAoa(d)) {
                if (um.hasPermission(d)) openHost(c, um, d); else if (asked.add(d.getDeviceName())) um.requestPermission(d, pi(c));
                return;
            }
        } catch (Throwable t) { ServerService.log("usb: " + t); }
    }

    /** Asks the other phone to switch to accessory mode. Returns an error text or null. */
    static String probeSwitch(UsbManager um, UsbDevice d) {
        UsbDeviceConnection c = um.openDevice(d); if (c == null) return "Cannot open USB device";
        try {
            byte[] pr = new byte[2];
            int r = c.controlTransfer(0xC0, 51, 0, 0, pr, 2, 2000);
            if (r < 2 || ((pr[1] << 8) | (pr[0] & 0xFF)) < 1) return "This device does not support USB link";
            String[] s = {"AdbDrop", "Link", "AdbDrop USB link", "1.0", "https://github.com", "1"};
            for (int i = 0; i < s.length; i++) { byte[] b = (s[i] + "\0").getBytes(); c.controlTransfer(0x40, 52, 0, i, b, b.length, 2000); }
            c.controlTransfer(0x40, 53, 0, 0, null, 0, 2000);
            return null;
        } finally { c.close(); }
    }

    static void openHost(Context ctx, UsbManager um, UsbDevice d) throws Exception {
        UsbDeviceConnection cn = um.openDevice(d); if (cn == null) return;
        UsbInterface pick = null; UsbEndpoint ei = null, eo = null;
        for (int i = 0; i < d.getInterfaceCount(); i++) {
            UsbInterface it = d.getInterface(i); UsbEndpoint a = null, b = null;
            for (int k = 0; k < it.getEndpointCount(); k++) {
                UsbEndpoint e = it.getEndpoint(k);
                if (e.getType() == UsbConstants.USB_ENDPOINT_XFER_BULK) { if (e.getDirection() == UsbConstants.USB_DIR_IN) a = e; else b = e; }
            }
            if (a != null && b != null && (it.getInterfaceSubclass() == 0xFF || pick == null)) { pick = it; ei = a; eo = b; if (it.getInterfaceSubclass() == 0xFF) break; }
        }
        if (pick == null || !cn.claimInterface(pick, true)) { cn.close(); return; }
        final UsbEndpoint fi = ei, fo = eo; final UsbDeviceConnection fc = cn;
        Peer.Link l = new Peer.Link() {
            public InputStream in() { return new InputStream() {
                public int read() { byte[] b = new byte[1]; int n = read(b, 0, 1); return n < 0 ? -1 : b[0] & 0xFF; }
                public int read(byte[] b, int o, int len) {
                    if (len == 0) return 0;
                    for (;;) { int n = fc.bulkTransfer(fi, b, o, Math.min(len, 16384), 0); if (n < 0) return -1; if (n > 0) return n; }
                }
            }; }
            public OutputStream out() { return new OutputStream() {
                public void write(int v) throws IOException { write(new byte[]{(byte) v}, 0, 1); }
                public void write(byte[] b, int o, int len) throws IOException {
                    while (len > 0) { int n = fc.bulkTransfer(fo, b, o, Math.min(len, 16384), 10000); if (n < 0) throw new IOException("usb write"); o += n; len -= n; }
                }
            }; }
            public void close() { try { fc.close(); } catch (Exception e) {} }
        };
        Peer p = new Peer(ctx, l, "USB"); Hub.usb = p; p.start();
    }

    static void openAcc(Context ctx, UsbManager um, UsbAccessory a) throws Exception {
        final ParcelFileDescriptor pfd = um.openAccessory(a); if (pfd == null) return;
        final FileInputStream fi = new FileInputStream(pfd.getFileDescriptor()); final FileOutputStream fo = new FileOutputStream(pfd.getFileDescriptor());
        Peer.Link l = new Peer.Link() {
            public InputStream in() { return fi; }
            public OutputStream out() { return fo; }
            public void close() { try { pfd.close(); } catch (Exception e) {} }
        };
        Peer p = new Peer(ctx, l, "USB"); Hub.usb = p; p.start();
    }
}
