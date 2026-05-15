#!/usr/bin/env python3
"""Run Python wrapper pytest (unit markers)."""

from __future__ import annotations

import argparse
import sys

from common import PYTHON_WRAPPER, banner, require_tool, run


def test(*, extra_pytest_args: list[str] | None = None) -> None:
    require_tool("uv")
    banner("Python: pytest")
    cmd = ["uv", "run", "pytest", "-m", "not deep and not benchmark", "-q"]
    if extra_pytest_args:
        cmd.extend(extra_pytest_args)
    run(cmd, cwd=PYTHON_WRAPPER)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "pytest_args",
        nargs=argparse.REMAINDER,
        help="Extra args passed to pytest after '--'.",
    )
    args = parser.parse_args(argv)
    extra = [a for a in args.pytest_args if a != "--"]
    test(extra_pytest_args=extra or None)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
