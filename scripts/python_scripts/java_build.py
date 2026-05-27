#!/usr/bin/env python3
"""Build rdp_jvm_sys and run JVM consistency checks.

Gradle + Maven Spotless on all JVM modules, optional ``clean``, then native ``cargo`` build
and ``people.xlsx`` fixture generation. Full Maven/Gradle CI parity (``mvn verify``, JMH,
etc.) runs in ``java_test.py``.
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
    cleanup_disk_for_jvm,
    ensure_libclang_linux,
    generate_people_xlsx_fixture,
    gradlew_argv,
    native_lib_release,
    require_tool,
    run,
    run_jvm_manifest_checks,
    run_jvm_spotless,
)


def gradle_clean() -> None:
    banner("Java: Gradle clean")
    run(gradlew_argv("clean", "--no-daemon"), cwd=JVM_GRADLE_DIR)


def build_native(*, release: bool = True) -> Path:
    require_tool("cargo")
    ensure_libclang_linux()
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
        help="Skip Spotless check (Gradle + Maven on all JVM modules).",
    )
    parser.add_argument(
        "--fix-fmt",
        action="store_true",
        help="Run Spotless apply then check on all JVM modules (writes files; use before commit).",
    )
    parser.add_argument(
        "--fmt-only",
        action="store_true",
        help="Spotless + ffi/version checks only (no Cargo jvm-sys build).",
    )
    args = parser.parse_args(argv)

    cleanup_disk_for_jvm()
    if args.clean:
        gradle_clean()

    if not args.skip_checks:
        run_jvm_manifest_checks()

    if not args.skip_fmt:
        run_jvm_spotless(apply=args.fix_fmt)

    if args.fmt_only:
        return 0

    build_native(release=not args.debug)
    generate_people_xlsx_fixture()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
