#!/usr/bin/env python3
"""Build rdp_jvm_sys and run JVM consistency checks.

Gradle: optional ``clean``, Spotless (Google Java Format), then native ``cargo`` build.
Maven modules also run Spotless on ``validate`` (``mvn verify``).
"""

from __future__ import annotations

import argparse
import platform
import sys
from pathlib import Path

from common import (
    JVM_GRADLE_DIR,
    JVM_SYS_DIR,
    REPO_ROOT,
    banner,
    gradlew_path,
    native_lib_release,
    require_tool,
    run,
)


def gradle_clean() -> None:
    gw = gradlew_path()
    if not gw.is_file():
        raise SystemExit(f"Gradle wrapper not found: {gw}")
    banner("Java: Gradle clean")
    run([str(gw), "clean", "--no-daemon"], cwd=JVM_GRADLE_DIR)


def spotless_check() -> None:
    gw = gradlew_path()
    if not gw.is_file():
        raise SystemExit(f"Gradle wrapper not found: {gw}")
    banner("Java: Spotless (Google Java Format check)")
    run([str(gw), "spotlessCheck", "--no-daemon"], cwd=JVM_GRADLE_DIR)


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
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Run ./gradlew clean in bindings/java/rust-data-processing-jvm.",
    )
    parser.add_argument(
        "--skip-fmt",
        action="store_true",
        help="Skip Spotless check (Gradle).",
    )
    parser.add_argument(
        "--fmt-only",
        action="store_true",
        help="Spotless + ffi/version checks only (no Cargo jvm-sys build).",
    )
    args = parser.parse_args(argv)

    if args.clean:
        gradle_clean()

    if not args.skip_fmt:
        spotless_check()

    if not args.skip_checks:
        check_manifests()

    if args.fmt_only:
        return 0

    build_native(release=not args.debug)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
