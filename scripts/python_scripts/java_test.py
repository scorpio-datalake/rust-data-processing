#!/usr/bin/env python3
"""Run JVM Gradle check (requires RDP_JVM_SYS or a built release native lib)."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

from common import JVM_GRADLE_DIR, banner, gradlew_argv, native_lib_release, run


def test(native_lib: Path | None = None) -> None:

    lib = native_lib
    if lib is None:
        env_lib = os.environ.get("RDP_JVM_SYS")
        if env_lib:
            lib = Path(env_lib)
        else:
            lib = native_lib_release()

    if not lib.is_file():
        raise SystemExit(
            "Native library not found. Set RDP_JVM_SYS or run java_build.py first.\n"
            f"  Expected release artifact: {native_lib_release()}"
        )

    banner("Java: Gradle check")
    run(
        gradlew_argv("check", "--no-daemon", "--stacktrace"),
        cwd=JVM_GRADLE_DIR,
        env={"RDP_JVM_SYS": str(lib.resolve())},
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--native-lib",
        type=Path,
        default=None,
        help="Path to rdp_jvm_sys (default: RDP_JVM_SYS env or release build).",
    )
    args = parser.parse_args(argv)
    test(native_lib=args.native_lib)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
