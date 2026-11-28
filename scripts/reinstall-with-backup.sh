#!/usr/bin/env bash
# Back up app data on a rooted device, uninstall, install a new APK, restore.
#
# Usage:
#   ./scripts/reinstall-with-backup.sh /path/to/app-debug.apk
#
# Environment overrides:
#   PKG=com.yeapguy.volumescroll   package to reinstall
#   BACKUP_DIR=./backups/...       where to store the backup
#
# This uses root (`adb shell su`) and a tar of /data/data/<pkg>, so it does
# not depend on Backup Manager (which is disabled on this device).
set -euo pipefail

PKG="${PKG:-com.yeapguy.volumescroll}"
NEW_APK="${1:-}"
STAMP="$(date +%Y%m%d-%H%M%S)"
BACKUP_DIR="${BACKUP_DIR:-./backups/$STAMP}"

die() { printf 'ERROR: %s\n' "$1" >&2; exit 1; }

if [[ -z "$NEW_APK" || ! -f "$NEW_APK" ]]; then
    printf 'Usage: %s /path/to/app-debug.apk\n' "$0" >&2
    exit 2
fi

command -v adb >/dev/null 2>&1 || die "adb not found on PATH"
adb get-state >/dev/null 2>&1 || die "no adb device connected (check 'adb devices')"

if ! adb shell pm list packages "$PKG" | tr -d '\r' | grep -qx "package:$PKG"; then
    die "$PKG is not installed on the device"
fi
adb shell su -c id >/dev/null 2>&1 || die "root (su) is required for this backup method"

mkdir -p "$BACKUP_DIR"

printf 'Force-stopping %s ...\n' "$PKG"
adb shell am force-stop "$PKG"

printf 'Backing up /data/data/%s ...\n' "$PKG"
adb exec-out su -c "cd /data/data && tar -cf - $PKG" > "$BACKUP_DIR/appdata.tar"
[[ -s "$BACKUP_DIR/appdata.tar" ]] || die "backup is empty; refusing to uninstall"
tar -tf "$BACKUP_DIR/appdata.tar" >/dev/null 2>&1 || die "backup tar is invalid; refusing to uninstall"
printf 'Backup saved to %s\n' "$BACKUP_DIR/appdata.tar"

# The daily log is in getExternalFilesDir(); adb pull works without root.
if adb shell test -d "/sdcard/Android/data/$PKG/files" 2>/dev/null; then
    printf 'Backing up external files ...\n'
    adb pull "/sdcard/Android/data/$PKG/files" "$BACKUP_DIR/external_files" >/dev/null 2>&1 || true
fi

printf 'Uninstalling %s ...\n' "$PKG"
adb shell pm uninstall "$PKG" || die "uninstall failed; backup is at $BACKUP_DIR"

printf 'Installing %s ...\n' "$NEW_APK"
adb install -r "$NEW_APK" || die "install failed; backup is at $BACKUP_DIR"

printf 'Restoring data ...\n'
adb push "$BACKUP_DIR/appdata.tar" /data/local/tmp/appdata.tar >/dev/null
adb shell su -c "cd /data/data && tar -xf /data/local/tmp/appdata.tar" \
    || die "restore failed; backup is at $BACKUP_DIR"
adb shell su -c "restorecon -R /data/data/$PKG" >/dev/null 2>&1 || true
adb shell su -c "rm -f /data/local/tmp/appdata.tar" >/dev/null 2>&1 || true

if [[ -d "$BACKUP_DIR/external_files" ]]; then
    adb push "$BACKUP_DIR/external_files/." "/sdcard/Android/data/$PKG/files/" >/dev/null 2>&1 || true
fi

adb shell am force-stop "$PKG"
printf 'Done. Backup kept at %s\n' "$BACKUP_DIR"
