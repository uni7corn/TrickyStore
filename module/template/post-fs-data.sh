#!/system/bin/sh

MODDIR=${0%/*}

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
