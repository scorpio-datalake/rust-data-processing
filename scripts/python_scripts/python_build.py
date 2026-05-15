#!/usr/bin/env python3
"""Build the Python extension (uv sync + maturin develop)."""

from __future__ import annotations

import argparse
import sys

from common import PYTHON_WRAPPER, banner, require_tool, run


def build(*, release: bool = True) -> None:
    require_tool("uv")
    banner("Python: uv sync (dev group)")
    run(["uv", "sync", "--group", "dev"], cwd=PYTHON_WRAPPER)
    maturin_args = ["uv", "run", "maturin", "develop"]
    if release:
        maturin_args.append("--release")
    banner("Python: maturin develop")
    run(maturin_args, cwd=PYTHON_WRAPPER)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--debug",
        action="store_true",
        help="maturin develop without --release.",
    )
    args = parser.parse_args(argv)
    build(release=not args.debug)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
