#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import os
import shutil
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
_REPO_SCRIPTS = _SCRIPTS.parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
if str(_REPO_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_REPO_SCRIPTS))

from connector_features import PYTHON_INTEGRATION_FEATURES  # noqa: E402
from common import (  # noqa: E402
    LIBS_DIR,
    PYTHON_STAMP,
    REPO_ROOT,
    die,
    ensure_linux_native_deps,
    find_python_extension,
    log,
    mark_built,
    needs_rebuild,
    prepare_integration_disk,
    require_tool,
    run,
    setup_integration_build_env,
    write_python_env,
)

PYTHON_WATCH_PATHS = ["python-wrapper", "src", "Cargo.toml", "Cargo.lock"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Build Python wrapper with integration_full (db + cloud) for connector integration tests."
        )
    )
    parser.add_argument("--force", action="store_true", help="Force rebuild.")
    args = parser.parse_args(argv)

    if args.force:
        os.environ["INTEG_FORCE_REBUILD"] = "1"

    setup_integration_build_env()
    prepare_integration_disk(force=args.force)
    log(f"Using CARGO_TARGET_DIR={os.environ['CARGO_TARGET_DIR']}")
    ensure_linux_native_deps()
    require_tool("uv")

    dest_so: Path | None = None

    if needs_rebuild(PYTHON_STAMP, PYTHON_WATCH_PATHS):
        log(
            f"Building Python wrapper (maturin develop --release --features {PYTHON_INTEGRATION_FEATURES})..."
        )
        run(
            ["uv", "sync", "--group", "dev", "--quiet"],
            cwd=REPO_ROOT / "python-wrapper",
        )
        run(
            [
                "uv",
                "run",
                "maturin",
                "develop",
                "--release",
                "--features",
                PYTHON_INTEGRATION_FEATURES,
            ],
            cwd=REPO_ROOT / "python-wrapper",
        )
        ext = find_python_extension()
        if ext is None:
            die("Python extension .so not found after maturin develop")
        shutil.copy2(ext, LIBS_DIR / "python" / ext.name)
        dest_so = LIBS_DIR / "python" / ext.name
        mark_built(PYTHON_STAMP)
        log(f"Python extension → {dest_so}")
    else:
        log("Python lib up to date (skip rebuild). Use --force to rebuild.")
        matches = list((LIBS_DIR / "python").glob("_rust_data_processing*.so"))
        if not matches:
            die("stamp exists but extension missing — run with --force")
        dest_so = matches[0]

    write_python_env(dest_so)
    log(f"Wrote {LIBS_DIR / 'python' / 'env.sh'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
