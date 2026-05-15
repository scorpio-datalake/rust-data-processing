#!/usr/bin/env python3
"""Build rdp_jvm_sys and run JVM consistency checks."""

from __future__ import annotations

import argparse
import platform
import sys
from pathlib import Path

from common import (
    JVM_SYS_DIR,
    REPO_ROOT,
    banner,
    native_lib_release,
    require_tool,
    run,
)


def check_manifests() -> None:
    banner("JVM: ffi manifest + Java version consistency")
    run([sys.executable, "scripts/check_jvm_ffi_manifest.py"], cwd=REPO_ROOT)
    run([sys.executable, "scripts/check_java_version_consistency.py"], cwd=REPO_ROOT)


def build_native(*, release: bool = True) -> Path:
    require_tool("cargo")
    banner("JVM: build rdp_jvm_sys (--features full)")
    cmd = [
        "cargo",
        "build",
        "--manifest-path",
        str(JVM_SYS_DIR / "Cargo.toml"),
        "--features",
        "full",
    ]
    if release:
        cmd.append("--release")
    run(cmd, cwd=REPO_ROOT)
    lib = native_lib_release() if release else _native_lib_debug()
    if not lib.is_file():
        raise SystemExit(f"Native library missing after build: {lib}")
    return lib


def _native_lib_debug() -> Path:
    root = JVM_SYS_DIR / "target" / "debug"
    if platform.system() == "Windows":
        return root / "rdp_jvm_sys.dll"
    if platform.system() == "Darwin":
        return root / "librdp_jvm_sys.dylib"
    return root / "librdp_jvm_sys.so"


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skip-checks",
        action="store_true",
        help="Skip ffi_manifest / version consistency scripts.",
    )
    parser.add_argument(
        "--debug",
        action="store_true",
        help="Debug build instead of release.",
    )
    args = parser.parse_args(argv)

    if not args.skip_checks:
        check_manifests()
    build_native(release=not args.debug)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
