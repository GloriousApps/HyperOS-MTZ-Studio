#!/usr/bin/env python3
"""Build deterministic, target-specific Xiaomi Themes Magisk module ZIPs."""

from __future__ import annotations

import argparse
import hashlib
import io
import sys
import zipfile
from pathlib import Path


GLOBAL_SHA = "d405e78fac1eae48e105e57f5e3422ec0a0a6d53f779973d06e131e85b016037"
NON_GLOBAL_SHA = "3888058041439577aadbb933c9bed1fb3ef16acda20d15404fb620314a8edd0a"
BRIDGE_DEFAULT = Path(__file__).resolve().parents[1] / "app/src/main/assets/xiaomi_themes_global_mtz_import_v1_0_0.zip"
UPDATE_BINARY = b"""#!/sbin/sh

umask 022
ui_print() { echo \"$1\"; }
require_new_magisk() {
  ui_print \"*******************************\"
  ui_print \" Please install Magisk v20.4+! \"
  ui_print \"*******************************\"
  exit 1
}
OUTFD=$2
ZIPFILE=$3
mount /data 2>/dev/null
[ -f /data/adb/magisk/util_functions.sh ] || require_new_magisk
. /data/adb/magisk/util_functions.sh
[ \"$MAGISK_VER_CODE\" -lt 20400 ] && require_new_magisk
install_module
exit 0
"""


def sha256(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def normalized_text(data: bytes) -> bytes:
    """Make packaged shell/metadata text stable and Android-friendly."""
    return data.replace(b"\r\n", b"\n").replace(b"\r", b"\n")


def zip_info(name: str, mode: int, compress_type: int = zipfile.ZIP_DEFLATED) -> zipfile.ZipInfo:
    info = zipfile.ZipInfo(name, (1980, 1, 1, 0, 0, 0))
    info.create_system = 3
    info.external_attr = (mode & 0xFFFF) << 16
    info.compress_type = compress_type
    return info


def read_bridge(path: Path) -> dict[str, bytes]:
    with zipfile.ZipFile(path) as bridge:
        return {name.replace("\\", "/"): bridge.read(name) for name in bridge.namelist()}


def build(*, target: str, apk_path: Path, bridge_path: Path, output: Path) -> str:
    expected_sha = GLOBAL_SHA if target == "GLOBAL" else NON_GLOBAL_SHA
    apk = apk_path.read_bytes()
    actual_sha = sha256(apk)
    if actual_sha != expected_sha:
        raise ValueError(f"{target} APK SHA-256 mismatch: {actual_sha} != {expected_sha}")

    bridge = read_bridge(bridge_path)
    files: dict[str, tuple[bytes, int]] = {}
    # Keep the existing MTZ import runtime for GLOBAL only. NON_GLOBAL is
    # intentionally a direct PM module without dex/zygisk bridge payloads.
    if target == "GLOBAL":
        for name in ("action.sh", "dex/classes.dex", "zygisk/arm64-v8a.so"):
            if name not in bridge:
                raise ValueError(f"bridge archive is missing {name}")
            data = normalized_text(bridge[name]) if name.endswith(".sh") else bridge[name]
            files[name] = (data, 0o755 if name.endswith(".sh") else 0o644)

    root = Path(__file__).resolve().parents[1]
    for name in ("customize.sh", "module.prop", "runtime-install.sh", "service.sh"):
        data = normalized_text((root / name).read_bytes())
        files[name] = (data, 0o755 if name.endswith(".sh") else 0o644)

    module_prop = files["module.prop"][0].rstrip(b"\n") + b"\n"
    files["module.prop"] = (module_prop, 0o644)
    files["selected-target"] = (f"{target}\n".encode("ascii"), 0o644)
    files["payload/theme.apk"] = (apk, 0o644)
    files["META-INF/com/google/android/update-binary"] = (UPDATE_BINARY, 0o755)
    files["META-INF/com/google/android/updater-script"] = (b"#MAGISK\n", 0o644)

    output.parent.mkdir(parents=True, exist_ok=True)
    temporary = io.BytesIO()
    with zipfile.ZipFile(temporary, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name in sorted(files):
            data, mode = files[name]
            archive.writestr(zip_info(name, mode), data, compress_type=zipfile.ZIP_DEFLATED, compresslevel=9)
    output.write_bytes(temporary.getvalue())
    return sha256(output.read_bytes())


def main(argv: list[str]) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--global-apk", type=Path, required=True, help="Global 3.4.1.23-global APK")
    parser.add_argument("--non-global-apk", type=Path, required=True, help="Non-Global 11.5.3.1 APK")
    parser.add_argument("--bridge-zip", type=Path, default=BRIDGE_DEFAULT)
    parser.add_argument("--output-dir", type=Path, default=BRIDGE_DEFAULT.parent)
    args = parser.parse_args(argv)
    for path in (args.global_apk, args.non_global_apk, args.bridge_zip):
        if not path.is_file():
            parser.error(f"file not found: {path}")
    outputs = (
        ("GLOBAL", args.global_apk, args.output_dir / "xiaomi_themes_runtime_global_v2_0_0.zip"),
        ("NON_GLOBAL", args.non_global_apk, args.output_dir / "xiaomi_themes_runtime_non_global_v2_0_0.zip"),
    )
    try:
        for target, apk, output in outputs:
            print(f"{output.as_posix()} {build(target=target, apk_path=apk, bridge_path=args.bridge_zip, output=output)}")
    except (OSError, ValueError, zipfile.BadZipFile) as error:
        print(f"error: {error}", file=sys.stderr)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))
