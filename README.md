# AdbDrop — fast file transfer without MTP

Material You app (dynamic colors) with an optional **AMOLED pure-black** switch.
The APK runs a small HTTP server on the phone. Anything with a browser (Windows, Linux, Mac, another Android) can browse, download and upload at full Wi-Fi / USB-tether speed. No PC software needed.
**Root** or **Shizuku** lets it read/write folders normal apps can't (e.g. `/sdcard/Android/data`, `/data/...`).

## 1. Build the APK from your phone (Termux)
1. Install Termux (F-Droid/GitHub build), then:
   ```
   termux-setup-storage
   pkg install -y unzip
   unzip -o /sdcard/Download/AdbDrop.zip -d ~
   cd ~/AdbDrop
   bash push.sh
   ```
2. `push.sh` installs git + gh, logs you into GitHub (choose GitHub.com > HTTPS > browser/token), creates a private repo, pushes, watches the Actions build (`gh run watch`), and downloads the APK to `/sdcard/Download/AdbDrop/`.
3. Install `app-debug.apk`. Later updates: edit files, then `git add -A && git commit -m x && git push && gh run watch`.
Manual alternative: run the "Build APK" workflow from the repo's Actions tab and download the artifact.

## 2. Use it
1. Open AdbDrop, pick a backend: **Normal**, **Root** (grant su prompt) or **Shizuku** (start Shizuku first, then allow).
2. Tap **Grant all-files access** (needed for Normal mode on /sdcard).
3. Tap **Start server**. It shows `http://PHONE_IP:8080/?k=123456`. Tap it to copy. The key changes each start.
4. Open that URL on the other device (same Wi-Fi, hotspot, or USB tethering — USB tethering is the fastest). Browse, tap a file to download, drop files in the green box to upload.
   Android to Android works the same: open the URL in the other phone's browser.

Command line (Windows `curl.exe`, or curl elsewhere):
```
curl.exe -o file.zip "http://IP:8080/dl?k=KEY&p=/sdcard/Download/file.zip"
curl.exe -T bigfile.mkv "http://IP:8080/up?k=KEY&p=/sdcard/Download&n=bigfile.mkv"
```

## 2b. Send files/folders from the in-app file viewer
Browse in the app, tick files or folders (checkbox or long-press), press **Send**. The server starts and the PC page shows a "Shared from phone" list; folders download as a streamed .zip. The card lists every link: "USB tethering", "Wi-Fi / hotspot" and "USB via ADB".
- **USB tethering** (no PC software): phone Settings > Hotspot & tethering > USB tethering, then open the USB tethering link in the PC browser.
- **USB via ADB**: enable USB debugging, run `adb forward tcp:8080 tcp:8080` on the PC, open the localhost link.
- Root/Shizuku is NOT needed for USB. It is only needed on the phone whose protected folders you want to read. The receiving device only needs a browser.
- Android to Android: use one phone's hotspot (works without internet) and open the link on the other.

## 3. Real ADB mode (optional, PC needs platform-tools)
- Root: tap **Enable ADB over Wi-Fi (root)**, then on PC `adb connect PHONE_IP:5555`.
- No root: Developer options > Wireless debugging > pair (`adb pair IP:PORT`) then `adb connect`.
- Then: `adb push file /sdcard/Download/` and `adb pull /sdcard/DCIM .`

## Security
The server exposes your storage (with Root/Shizuku: much more). Only run it on networks you trust, stop it when done. Every request needs the random key. Traffic is plain HTTP.
