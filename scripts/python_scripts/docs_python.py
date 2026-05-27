#!/usr/bin/env python3
"""Generate Python API docs (pdoc) under _site/python."""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

from common import (
    PYTHON_WRAPPER,
    REPO_ROOT,
    banner,
    cleanup_disk_for_python,
    python_venv_executable,
    python_wrapper_cargo_env,
    require_uv,
    run,
)


def generate() -> None:
    cleanup_disk_for_python()
    require_uv()
    banner("Python docs: uv sync + maturin (for pdoc imports)")
    env = python_wrapper_cargo_env()
    run(
        ["uv", "sync", "--group", "dev", "--no-install-project"],
        cwd=PYTHON_WRAPPER,
        env=env,
    )
    run(
        [str(python_venv_executable("maturin")), "develop"],
        cwd=PYTHON_WRAPPER,
        env=env,
    )

    py_out = REPO_ROOT / "_site" / "python"
    if py_out.is_dir():
        shutil.rmtree(py_out)
    py_out.mkdir(parents=True, exist_ok=True)

    banner("Python docs: pdoc")
    run(
        [
            str(python_venv_executable("pdoc")),
            "-d",
            "google",
            "-o",
            str(py_out),
            "rust_data_processing",
            "rust_data_processing.examples",
        ],
        cwd=PYTHON_WRAPPER,
    )

    img_src = REPO_ROOT / "docs" / "images"
    py_images = py_out / "images"
    site_images = REPO_ROOT / "_site" / "images"
    py_images.mkdir(parents=True, exist_ok=True)
    site_images.mkdir(parents=True, exist_ok=True)
    if img_src.is_dir():
        for item in img_src.iterdir():
            if item.is_file():
                shutil.copy2(item, py_images / item.name)
                shutil.copy2(item, site_images / item.name)

    nested = py_out / "rust_data_processing" / "examples.html"
    flat = py_out / "examples.html"
    if not nested.is_file():
        raise SystemExit("pdoc did not emit rust_data_processing/examples.html")
    shutil.copy2(nested, flat)
    print(f"Open: {py_out / 'index.html'}", flush=True)


def main(argv: list[str] | None = None) -> int:
    argparse.ArgumentParser(description=__doc__).parse_args(argv)
    generate()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
