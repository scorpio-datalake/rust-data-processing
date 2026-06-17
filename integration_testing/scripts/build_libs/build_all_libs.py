#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from build_libs import build_java_lib, build_python_lib, build_rust_lib  # noqa: E402
from common import LIBS_DIR, log, prepare_integration_disk  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build all integration libs (Rust → Java → Python).")
    parser.add_argument("--force", action="store_true", help="Force rebuild.")
    parser.add_argument(
        "--skip-prebuild",
        action="store_true",
        help="Pass through to build_rust_lib.py (lib only, no connector test prebuild).",
    )
    args = parser.parse_args(argv)

    extra: list[str] = []
    if args.force:
        extra.append("--force")
    if args.skip_prebuild:
        os.environ["INTEG_SKIP_PREBUILD"] = "1"
        extra.append("--skip-prebuild")
    prepare_integration_disk(force=args.force)
    build_rust_lib.main(extra)
    build_java_lib.main(extra)
    build_python_lib.main(extra)
    log(f"All integration libs ready under {LIBS_DIR}/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
