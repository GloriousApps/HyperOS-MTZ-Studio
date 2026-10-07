#!/system/bin/sh

ui_print "- Xiaomi Themes Global MTZ Import"
ui_print "- Checking device compatibility"

ABI="$(getprop ro.product.cpu.abi)"
SDK="$(getprop ro.build.version.sdk)"

if [ "$ABI" != "arm64-v8a" ]; then
  abort "! Unsupported ABI: $ABI (arm64-v8a required)"
fi

if [ -z "$SDK" ] || [ "$SDK" -lt 27 ]; then
  abort "! Android 8.1 / API 27 or newer is required"
fi

if ! pm path com.android.thememanager >/dev/null 2>&1; then
  abort "! com.android.thememanager is not installed"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/zygisk/arm64-v8a.so" 0 0 0644
set_perm "$MODPATH/dex/classes.dex" 0 0 0644

ui_print "- Original Themes APK and its data were not modified"
ui_print "- Reboot, then open Themes; use the blue MTZ import button"
ui_print "- Supports Global 3.0.5.6, 3.0.6.8 and 3.4.x"
