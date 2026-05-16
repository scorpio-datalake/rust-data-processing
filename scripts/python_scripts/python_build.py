#!/usr/bin/env python3
"""Build the Python extension: optional clean, Ruff format/check, uv sync, maturin develop."""

from __future__ import annotations

import argparse
import sys

from common import PYTHON_WRAPPER, banner, require_uv, run

from python_clean import clean as python_clean_wrapper


def lint(*, check_only: bool) -> None:
    require_uv()
    banner("Python: Ruff format" + (" --check" if check_only else ""))
    fmt_cmd = ["uv", "run", "ruff", "format"]
    if check_only:
        fmt_cmd.append("--check")
    fmt_cmd.extend(["rust_data_processing", "tests"])
    run(fmt_cmd, cwd=PYTHON_WRAPPER)
    banner("Python: Ruff check")
    run(["uv", "run", "ruff", "check", "rust_data_processing", "tests"], cwd=PYTHON_WRAPPER)


def build(*, release: bool = True, skip_fmt: bool = False) -> None:
    require_uv()
    banner("Python: uv sync (dev group)")
    run(["uv", "sync", "--group", "dev"], cwd=PYTHON_WRAPPER)
    if not skip_fmt:
        lint(check_only=True)
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
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Remove python-wrapper caches and build dirs (not .venv).",
    )
    parser.add_argument("--skip-fmt", action="store_true", help="Skip Ruff format/check.")
    parser.add_argument(
        "--fmt-only",
        action="store_true",
        help="uv sync plus Ruff only (no maturin develop).",
    )
    args = parser.parse_args(argv)

    if args.clean:
        python_clean_wrapper()
    require_uv()
    if args.fmt_only:
        banner("Python: uv sync (dev group) — fmt-only mode")
        run(["uv", "sync", "--group", "dev"], cwd=PYTHON_WRAPPER)
        if not args.skip_fmt:
            lint(check_only=True)
        return 0

    build(release=not args.debug, skip_fmt=args.skip_fmt)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
