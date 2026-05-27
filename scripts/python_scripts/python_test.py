#!/usr/bin/env python3
"""Run Python wrapper pytest (unit markers) and optional wheel install smoke."""

from __future__ import annotations

import argparse
import platform
import sys

from common import (
    PYTHON_WRAPPER,
    banner,
    python_venv_executable,
    python_wrapper_cargo_env,
    require_uv,
    run,
)


def test(*, extra_pytest_args: list[str] | None = None) -> None:
    require_uv()
    banner("Python: pytest")
    cmd = [
        str(python_venv_executable("pytest")),
        "-m",
        "not deep and not benchmark",
        "-q",
    ]
    if extra_pytest_args:
        cmd.extend(extra_pytest_args)
    run(cmd, cwd=PYTHON_WRAPPER)


def wheel_install_smoke() -> None:
    """PEP 517 wheel path — same as python_ci.yml (Ubuntu matrix cell)."""
    if platform.system() != "Linux":
        print("  (Skipping wheel smoke: CI runs on ubuntu-latest only)", flush=True)
        return
    require_uv()
    dist = PYTHON_WRAPPER / "dist"
    banner("Python: maturin build wheel + pip install smoke")
    run(
        [
            str(python_venv_executable("maturin")),
            "build",
            "--release",
            "-o",
            "dist",
        ],
        cwd=PYTHON_WRAPPER,
        env=python_wrapper_cargo_env(),
    )
    wheels = sorted(dist.glob("*.whl"))
    if not wheels:
        raise SystemExit(f"No wheel produced under {dist}")
    run(
        ["uv", "pip", "install", "--force-reinstall", str(wheels[-1])],
        cwd=PYTHON_WRAPPER,
    )
    run(
        [
            "uv",
            "run",
            "python",
            "-c",
            "import rust_data_processing as r; assert r.extension_version(); "
            "print('pip wheel smoke ok', r.extension_version())",
        ],
        cwd=PYTHON_WRAPPER,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "pytest_args",
        nargs=argparse.REMAINDER,
        help="Extra args passed to pytest after '--'.",
    )
    parser.add_argument(
        "--skip-wheel-smoke",
        action="store_true",
        help="Skip maturin build + pip install smoke (runs on Linux by default).",
    )
    args = parser.parse_args(argv)
    extra = [a for a in args.pytest_args if a != "--"]
    test(extra_pytest_args=extra or None)
    if not args.skip_wheel_smoke:
        wheel_install_smoke()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
