#!/system/bin/sh

# This service never installs, uninstalls, clears data, or changes Package
# Manager state.  It only records whether the already-installed runtime is
# still the selected, verified live APK after boot.
MODPATH="${0%/*}"

module_inactive() {
  [ ! -d "$MODPATH" ] || [ -e "$MODPATH/disable" ] || \
    [ -e "$MODPATH/remove" ] || [ -e "$MODPATH/.remove" ]
}

if module_inactive; then
  rm -f "$MODPATH/runtime-active" 2>/dev/null
  exit 0
fi

i=0
while [ "$(getprop sys.boot_completed 2>/dev/null)" != "1" ] && [ "$i" -lt 60 ]; do
  if module_inactive; then
    rm -f "$MODPATH/runtime-active" 2>/dev/null
    exit 0
  fi
  sleep 1
  i=$((i + 1))
done
[ "$(getprop sys.boot_completed 2>/dev/null)" = "1" ] || exit 0

. "$MODPATH/runtime-install.sh" || exit 0

TARGET="$(sed -n '1p' "$MODPATH/selected-target" 2>/dev/null)"
case "$TARGET" in GLOBAL|NON_GLOBAL) ;; *) rm -f "$MODPATH/runtime-active"; exit 0 ;; esac

EXPECTED_LINE="$(sed -n '1p' "$MODPATH/installed-runtime-version" 2>/dev/null)"
EXPECTED_VERSION="$(printf '%s' "$EXPECTED_LINE" | sed -n 's/^versionName=\([^|]*\).*$/\1/p')"
EXPECTED_CODE="$(printf '%s' "$EXPECTED_LINE" | sed -n 's/.*|versionCode=\([^|]*\).*/\1/p')"
EXPECTED_SHA="$(printf '%s' "$EXPECTED_LINE" | sed -n 's/.*|sha256=\([^|]*\).*/\1/p')"

if ! module_inactive && verify_live_bounded "$TARGET" "$EXPECTED_VERSION" "$EXPECTED_CODE" "$EXPECTED_SHA" && ! module_inactive; then
  printf 'true\n' > "$MODPATH/runtime-active"
else
  rm -f "$MODPATH/runtime-active"
fi
