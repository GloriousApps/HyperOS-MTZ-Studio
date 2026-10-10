#!/system/bin/sh

PACKAGE="com.android.thememanager"
GLOBAL_VERSION="3.4.1.23-global"
GLOBAL_CODE="3040123"
GLOBAL_SHA="d405e78fac1eae48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037"
GLOBAL_MIN_SDK=27
GLOBAL_MAX_SDK=36
NON_GLOBAL_VERSION="11.5.3.1"
NON_GLOBAL_CODE="11531"
NON_GLOBAL_SHA="3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a"
NON_GLOBAL_MIN_SDK=34
NON_GLOBAL_MAX_SDK=36
EXPECTED_CERT="c9009d01ebf9f5d0302bc71b2fe9aa9a47a432bba17308a3111b75d7b2149025"

fail_install() {
  module_dir="$1"
  stage="$2"
  shift 2
  {
    printf 'stage=%s\n' "$stage"
    printf 'target=%s\n' "${TARGET:-unknown}"
    printf 'package=%s\n' "$PACKAGE"
    printf 'message=%s\n' "$*"
  } > "$module_dir/runtime-install-failure" 2>/dev/null
  printf 'runtime-install: %s: %s\n' "$stage" "$*" >&2
  return 1
}

normalize_target() {
  case "$(printf '%s' "$1" | tr '[:lower:]' '[:upper:]')" in
    GLOBAL) printf '%s\n' GLOBAL ;;
    NON_GLOBAL|NONGLOBAL|NON-GLOBAL) printf '%s\n' NON_GLOBAL ;;
    *) return 1 ;;
  esac
}

sha256_file() {
  sha256sum "$1" 2>/dev/null | sed -n 's/^\([0-9A-Fa-f][0-9A-Fa-f]*\)[[:space:]].*$/\1/p' | tr '[:upper:]' '[:lower:]'
}

# Only the first indented Package block under the primary "Packages:" section
# is considered. Hidden system and disabled-package sections are ignored.
primary_versions() {
  package_name="$1"
  dumpsys package "$package_name" 2>/dev/null | awk -v wanted="$package_name" '
    function indent(s, n) { n=0; while (substr(s,n+1,1)==" ") n++; return n }
    BEGIN { primary=0; in_package=0; base=-1; code=""; name="" }
    /^Packages:[[:space:]]*$/ { primary=1; next }
    primary && /^[^[:space:]].*packages:/ { primary=0; in_package=0; next }
    primary && !in_package && /^[[:space:]]+Package \[/ {
      line=$0; sub(/^[[:space:]]+Package \[/,"",line)
      candidate=line; sub(/\].*$/,"",candidate)
      if (candidate == wanted) { in_package=1; base=indent($0); next }
    }
    in_package {
      current=indent($0)
      if (/^[[:space:]]+Package \[/ && current <= base) exit
      if ($0 !~ /^[[:space:]]/ && $0 != "") exit
      if (code == "" && $0 ~ /versionCode=/) {
        line=$0; sub(/^.*versionCode=/,"",line); sub(/[[:space:]].*$/, "", line); code=line
      }
      if (name == "" && $0 ~ /versionName=/) {
        line=$0; sub(/^.*versionName=/,"",line); sub(/[[:space:]].*$/, "", line); name=line
      }
      if (code != "" && name != "") { print name "|" code; exit }
    }
  ' | sed -n '1p'
}

# The global APK has a permitted -global suffix in versionName. Compare the
# canonical form while retaining the real expected version in module metadata.
canonical_version() {
  value="$1"
  case "$value" in 3.4.1.23-global) value="3.4.1.23" ;; esac
  printf '%s\n' "$value"
}

live_apk_path() {
  pm path "$PACKAGE" 2>/dev/null | sed -n 's/^package:\(\/.*\)$/\1/p' | sed -n '1p'
}

verify_live_runtime() {
  verify_target="$1"
  expected_version="$2"
  expected_code="$3"
  expected_sha="$4"
  [ -n "$expected_version" ] && [ -n "$expected_code" ] && [ -n "$expected_sha" ] || return 1

  live_path="$(live_apk_path)"
  [ -n "$live_path" ] && [ -f "$live_path" ] || return 1
  live_sha="$(sha256_file "$live_path")"
  [ "$live_sha" = "$expected_sha" ] || return 1

  live_pair="$(primary_versions "$PACKAGE")"
  live_version="${live_pair%%|*}"
  live_code="${live_pair#*|}"
  [ "$(canonical_version "$live_version")" = "$(canonical_version "$expected_version")" ] || return 1
  [ "$live_code" = "$expected_code" ] || return 1
  case "$verify_target:$live_code" in
    GLOBAL:$GLOBAL_CODE|NON_GLOBAL:$NON_GLOBAL_CODE) return 0 ;;
  esac
  return 1
}

verify_live_bounded() {
  # Only read-only queries are repeated, at most three observations. The
  # Package Manager installation itself is never retried.
  verification_attempt=0
  while [ "$verification_attempt" -lt 3 ]; do
    verify_live_runtime "$1" "$2" "$3" "$4" && return 0
    verification_attempt=$((verification_attempt + 1))
    [ "$verification_attempt" -lt 3 ] && sleep 1
  done
  return 1
}

write_runtime_metadata() {
  printf 'versionName=%s|versionCode=%s|sha256=%s|certificate=%s\n' \
    "$expected_version" "$expected_code" "$expected_sha" "$EXPECTED_CERT" \
    > "$MODULE_DIR/installed-runtime-version"
}

install_runtime() {
  MODULE_DIR="$1"
  TARGET="$(normalize_target "$2")" || {
    fail_install "$1" target "target must be GLOBAL or NON_GLOBAL"
    return 1
  }
  [ -d "$MODULE_DIR" ] || return 1
  MODULE_DIR="$(cd "$MODULE_DIR" 2>/dev/null && pwd)" || return 1
  rm -f "$MODULE_DIR/runtime-install-failure" 2>/dev/null
  if [ -e "$MODULE_DIR/selected-target" ]; then
    packaged_target="$(normalize_target "$(sed -n '1p' "$MODULE_DIR/selected-target")")" || {
      fail_install "$MODULE_DIR" target "invalid packaged selected-target"
      return 1
    }
    [ "$packaged_target" = "$TARGET" ] || { fail_install "$MODULE_DIR" target "conflicting packaged target"; return 1; }
  fi
  if ! printf '%s\n' "$TARGET" > "$MODULE_DIR/selected-target"; then
    fail_install "$MODULE_DIR" metadata "cannot write selected-target"
    return 1
  fi

  [ "$(id -u 2>/dev/null)" = "0" ] || { fail_install "$MODULE_DIR" permissions "root is required"; return 1; }
  [ "$(getprop ro.product.cpu.abi)" = "arm64-v8a" ] || { fail_install "$MODULE_DIR" abi "arm64-v8a is required"; return 1; }
  sdk="$(getprop ro.build.version.sdk)"
  case "$sdk" in ''|*[!0-9]*) fail_install "$MODULE_DIR" sdk "invalid SDK: $sdk"; return 1 ;; esac

  case "$TARGET" in
    GLOBAL) expected_version="$GLOBAL_VERSION"; expected_code="$GLOBAL_CODE"; expected_sha="$GLOBAL_SHA"; min_sdk=$GLOBAL_MIN_SDK; max_sdk=$GLOBAL_MAX_SDK ;;
    NON_GLOBAL) expected_version="$NON_GLOBAL_VERSION"; expected_code="$NON_GLOBAL_CODE"; expected_sha="$NON_GLOBAL_SHA"; min_sdk=$NON_GLOBAL_MIN_SDK; max_sdk=$NON_GLOBAL_MAX_SDK ;;
  esac
  [ "$sdk" -ge "$min_sdk" ] || { fail_install "$MODULE_DIR" sdk "SDK $sdk is below target minimum $min_sdk"; return 1; }
  [ "$sdk" -le "$max_sdk" ] || { fail_install "$MODULE_DIR" sdk "SDK $sdk is above target maximum $max_sdk"; return 1; }

  apk="$MODULE_DIR/payload/theme.apk"
  [ -s "$apk" ] || { fail_install "$MODULE_DIR" input "missing payload/theme.apk"; return 1; }
  apk_sha="$(sha256_file "$apk")"
  [ "$apk_sha" = "$expected_sha" ] || { fail_install "$MODULE_DIR" input "payload SHA-256 mismatch"; return 1; }
  previous_apk="$(live_apk_path)"
  [ -n "$previous_apk" ] && [ -f "$previous_apk" ] || {
    fail_install "$MODULE_DIR" package "com.android.thememanager has no readable installed APK"
    return 1
  }

  # Idempotence: prove the exact APK is live before the sole mutating command.
  if verify_live_runtime "$TARGET" "$expected_version" "$expected_code" "$expected_sha"; then
    write_runtime_metadata || { fail_install "$MODULE_DIR" metadata "cannot write installed-runtime-version"; return 1; }
    rm -f "$MODULE_DIR/runtime-install-failure" 2>/dev/null
    return 0
  fi

  apk_size="$(wc -c < "$apk" | tr -d '[:space:]')"
  case "$apk_size" in ''|*[!0-9]*) fail_install "$MODULE_DIR" input "cannot determine APK byte size"; return 1 ;; esac

  # Exactly one mutating Package Manager command. The final '-' makes stdin
  # the APK source. No retry is attempted after success, failure, or timeout.
  pm install -r -d --user 0 -S "$apk_size" - < "$apk"
  pm_status=$?
  if [ "$pm_status" -ne 0 ]; then
    fail_install "$MODULE_DIR" pm "pm install returned $pm_status; no retry was attempted"
    return 1
  fi

  [ "$(sha256_file "$apk")" = "$expected_sha" ] || {
    fail_install "$MODULE_DIR" input "payload changed during installation; no retry was attempted"
    return 1
  }
  if ! verify_live_bounded "$TARGET" "$expected_version" "$expected_code" "$expected_sha"; then
    fail_install "$MODULE_DIR" verify "live Package Manager version/path hash did not match; no retry was attempted"
    return 1
  fi

  write_runtime_metadata || { fail_install "$MODULE_DIR" metadata "cannot write installed-runtime-version"; return 1; }
  rm -f "$MODULE_DIR/runtime-install-failure" 2>/dev/null
  return 0
}

# Sourcing this file provides verify_live_runtime to the read-only service.
case "${0##*/}" in
  runtime-install.sh) [ "$#" -eq 2 ] || { printf 'usage: %s <module-dir> <GLOBAL|NON_GLOBAL>\n' "$0" >&2; exit 2; }; install_runtime "$1" "$2"; exit $? ;;
esac
