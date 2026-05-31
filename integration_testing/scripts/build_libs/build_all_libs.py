#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from build_libs import build_java_lib, build_python_lib, build_rust_lib  # noqa: E402
from common import LIBS_DIR, log  # noqa: E402


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Build all integration libs (Rust → Java → Python).")
    parser.add_argument("--force", action="store_true", help="Force rebuild.")
    args = parser.parse_args(argv)

    extra = ["--force"] if args.force else []
    build_rust_lib.main(extra)
    build_java_lib.main(extra)
    build_python_lib.main(extra)
    log(f"All integration libs ready under {LIBS_DIR}/")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
