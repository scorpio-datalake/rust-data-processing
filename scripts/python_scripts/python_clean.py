#!/usr/bin/env python3
"""Remove python-wrapper build artifacts (dist, maturin target, caches — not .venv)."""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from common import PYTHON_WRAPPER, banner


def clean() -> None:
    banner("Python clean: remove dist, target, caches under python-wrapper")
    root = PYTHON_WRAPPER
    if not root.is_dir():
        raise SystemExit(f"PYTHON_WRAPPER missing: {root}")

    for name in ("dist", ".ruff_cache", ".pytest_cache", ".mypy_cache", "target"):
        path = root / name
        if path.exists():
            print(f"  removing {path.relative_to(root)}", flush=True)
            shutil.rmtree(path)

    removed = 0
    for pycache in root.rglob("__pycache__"):
        if pycache.is_dir():
            shutil.rmtree(pycache)
            removed += 1
    if removed:
        print(f"  removed {removed} __pycache__ dirs", flush=True)


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    clean()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
