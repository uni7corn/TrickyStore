#!/system/bin/sh

DEBUG=@DEBUG@

MODDIR=${0%/*}
CONFIG_DIR=/data/adb/tricky_store
PIDFILE=$CONFIG_DIR/service.pid

cd "$MODDIR" || exit 1
mkdir -p "$CONFIG_DIR"

set_boot_props() {
  RESETPROP_BIN=
  for BIN in resetprop /data/adb/ksu/bin/resetprop /data/adb/magisk/resetprop /sbin/resetprop; do
    if command -v "$BIN" >/dev/null 2>&1; then
      RESETPROP_BIN=$(command -v "$BIN")
      break
    fi
    if [ -x "$BIN" ]; then
      RESETPROP_BIN="$BIN"
      break
    fi
  done

  [ -n "$RESETPROP_BIN" ] || return 0
  "$RESETPROP_BIN" ro.boot.verifiedbootstate green
  "$RESETPROP_BIN" ro.boot.flash.locked 1
  "$RESETPROP_BIN" ro.boot.vbmeta.device_state locked
}

set_boot_props

is_tricky_store_pid() {
  [ -n "$1" ] || return 1
  [ -d "/proc/$1" ] || return 1

  CMDLINE=$(tr '\0' ' ' < "/proc/$1/cmdline" 2>/dev/null)
  case "$CMDLINE" in
    *TrickyStore*|*"service.sh"*) return 0 ;;
  esac
  return 1
}

if [ -f "$PIDFILE" ]; then
  OLD_PID=$(cat "$PIDFILE")
  if is_tricky_store_pid "$OLD_PID"; then
    exit 0
  fi
  rm -f "$PIDFILE"
fi

(
while true; do
  [ -f "$MODDIR/disable" ] && exit 0
  [ -f "$MODDIR/remove" ] && exit 0
  ./daemon
  sleep 1
done
) &
echo $! > "$PIDFILE"
