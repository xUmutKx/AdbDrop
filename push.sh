#!/data/data/com.termux/files/usr/bin/bash
# Run from inside the AdbDrop folder in Termux: bash push.sh [repo-name]
set -e
case "$(pwd -P)" in /sdcard*|/storage/*) echo "Copy this folder into Termux home first: cp -r \"$(pwd -P)\" ~/AdbDrop && cd ~/AdbDrop"; exit 1;; esac
REPO=${1:-AdbDrop}
pkg install -y git gh >/dev/null
gh auth status >/dev/null 2>&1 || gh auth login
git config user.name  >/dev/null || git config --global user.name  "$(gh api user -q .login)"
git config user.email >/dev/null || git config --global user.email "$(gh api user -q .login)@users.noreply.github.com"
[ -d .git ] || git init -b main
git add -A && git commit -m "AdbDrop" || true
git remote get-url origin >/dev/null 2>&1 || gh repo create "$REPO" --private --source=. --remote=origin
git push -u origin main
sleep 8
ID=$(gh run list --limit 1 --json databaseId -q '.[0].databaseId')
gh run watch "$ID" --exit-status
mkdir -p /sdcard/Download/AdbDrop-APK
gh run download "$ID" -n AdbDrop-apk -D /sdcard/Download/AdbDrop-APK
echo "APK: /sdcard/Download/AdbDrop-APK/"; ls /sdcard/Download/AdbDrop-APK
