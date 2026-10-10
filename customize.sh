#!/system/bin/sh

ui_print "- Xiaomi Themes Runtime + MTZ Import"
ui_print "- Selecting the packaged runtime target"

MODPATH="${MODPATH:-${0%/*}}"

if [ "$(id -u 2>/dev/null)" != "0" ]; then
  abort "! Root is required"
fi

ABI="$(getprop ro.product.cpu.abi)"
SDK="$(getprop ro.build.version.sdk)"
MOD_DEVICE="$(getprop ro.product.mod_device | tr '[:upper:]' '[:lower:]')"
INCREMENTAL="$(getprop ro.build.version.incremental | tr '[:lower:]' '[:upper:]')"

if [ "$ABI" != "arm64-v8a" ]; then
  abort "! Unsupported ABI: $ABI (arm64-v8a required)"
fi

case "$SDK" in ''|*[!0-9]*) abort "! Invalid Android SDK: $SDK" ;; esac
if [ "$SDK" -lt 27 ] || [ "$SDK" -gt 36 ]; then
  abort "! Supported Android SDK range is API 27 through 36"
fi

if ! pm path com.android.thememanager >/dev/null 2>&1; then
  abort "! com.android.thememanager is not installed"
fi

normalize_target() {
  case "$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')" in
    GLOBAL) printf '%s\n' GLOBAL ;;
    NON_GLOBAL|NONGLOBAL|NON-GLOBAL) printf '%s\n' NON_GLOBAL ;;
    *) return 1 ;;
  esac
}

TARGET=""
PACKAGED_TARGET=""
if [ -e "$MODPATH/selected-target" ]; then
  PACKAGED_TARGET="$(normalize_target "$(sed -n '1p' "$MODPATH/selected-target")")" || \
    abort "! Invalid packaged selected-target; use GLOBAL or NON_GLOBAL"
fi

# Magisk-style callers do not define a target argument. If a caller does,
# the second argument is explicit and must agree with the packaged target.
if [ -n "${2:-}" ]; then
  TARGET="$(normalize_target "$2")" || abort "! Invalid target '$2'; use GLOBAL or NON_GLOBAL"
  if [ -n "$PACKAGED_TARGET" ] && [ "$TARGET" != "$PACKAGED_TARGET" ]; then
    abort "! Explicit target conflicts with packaged selected-target"
  fi
elif [ -n "$PACKAGED_TARGET" ]; then
  TARGET="$PACKAGED_TARGET"
else
  GLOBAL_EVIDENCE=0
  NON_GLOBAL_EVIDENCE=0

  case "$MOD_DEVICE" in
    *_global|*-global|global) GLOBAL_EVIDENCE=1 ;;
    *_cn|*-cn|cn) NON_GLOBAL_EVIDENCE=1 ;;
  esac

  case "$INCREMENTAL" in
    *CNXM*|*CN/*|*\.CN*) NON_GLOBAL_EVIDENCE=1 ;;
  esac
  case "$INCREMENTAL" in
    *MIXM*|*EUXM*|*TRXM*|*RUXM*|*INXM*|*IDXM*|*TWXM*|*VNXM*|*JPXM*|*KRXM*) GLOBAL_EVIDENCE=1 ;;
  esac

  if [ "$GLOBAL_EVIDENCE" = 1 ] && [ "$NON_GLOBAL_EVIDENCE" = 1 ]; then
    abort "! Ambiguous ROM target evidence (mod_device=$MOD_DEVICE incremental=$INCREMENTAL)"
  fi
  if [ "$GLOBAL_EVIDENCE" = 1 ]; then
    TARGET=GLOBAL
  elif [ "$NON_GLOBAL_EVIDENCE" = 1 ]; then
    TARGET=NON_GLOBAL
  else
    abort "! Cannot determine GLOBAL/NON_GLOBAL target; pass it as the second argument"
  fi
fi

# A packaged target is authoritative, but the payload must still belong to it.
case "$TARGET" in
  GLOBAL) EXPECTED_SHA="d405e78fac1eae48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037" ;;
  NON_GLOBAL) EXPECTED_SHA="3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a" ;;
esac
PAYLOAD="$MODPATH/payload/theme.apk"
[ -s "$PAYLOAD" ] || abort "! Missing payload/theme.apk"
ACTUAL_SHA="$(sha256sum "$PAYLOAD" 2>/dev/null | sed -n 's/^\([0-9A-Fa-f]*\)[[:space:]].*$/\1/p' | tr '[:upper:]' '[:lower:]')"
[ "$ACTUAL_SHA" = "$EXPECTED_SHA" ] || abort "! Payload conflicts with selected target (SHA-256 mismatch)"

printf '%s\n' "$TARGET" > "$MODPATH/selected-target" || abort "! Cannot record selected target"
ui_print "- Selected runtime: $TARGET"

chmod 0755 "$MODPATH/runtime-install.sh" 2>/dev/null
if ! "$MODPATH/runtime-install.sh" "$MODPATH" "$TARGET"; then
  if [ -s "$MODPATH/runtime-install-failure" ]; then
    ui_print "! Runtime install failure report:"
    while IFS= read -r line; do ui_print "! $line"; done < "$MODPATH/runtime-install-failure"
  fi
  abort "! Runtime installation failed; no automatic retry was performed"
fi

set_perm_recursive "$MODPATH" 0 0 0755 0644
set_perm "$MODPATH/customize.sh" 0 0 0755
set_perm "$MODPATH/runtime-install.sh" 0 0 0755
set_perm "$MODPATH/service.sh" 0 0 0755

ui_print "- Package Manager installation verified"
ui_print "- Reboot is not used for installation; service.sh performs read-only boot verification"
