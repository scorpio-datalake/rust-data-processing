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

from common import LIBS_DIR, REPO_ROOT, RUST_STAMP, ensure_linux_native_deps, log, mark_built, needs_rebuild, prepare_integration_disk, require_tool, run, setup_integration_build_env, write_rust_env  # noqa: E402

RUST_WATCH_PATHS = ["Cargo.toml", "Cargo.lock", "src"]


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description="Build rust-data-processing with db_connectorx for Oracle integration tests."
    )
    parser.add_argument("--force", action="store_true", help="Force rebuild.")
    args = parser.parse_args(argv)

    if args.force:
        os.environ["INTEG_FORCE_REBUILD"] = "1"

    setup_integration_build_env()
    prepare_integration_disk(force=args.force)
    log(f"Using CARGO_TARGET_DIR={os.environ['CARGO_TARGET_DIR']}")
    ensure_linux_native_deps()
    require_tool("cargo")

    if needs_rebuild(RUST_STAMP, RUST_WATCH_PATHS):
        log("Building Rust workspace (--release --features db_connectorx)...")
        run(
            [
                "cargo",
                "build",
                "--release",
                "--locked",
                "--features",
                "db_connectorx",
                "--manifest-path",
                str(REPO_ROOT / "Cargo.toml"),
            ]
        )
        mark_built(RUST_STAMP)
        log("Rust build complete.")
    else:
        log("Rust lib up to date (skip rebuild). Use --force to rebuild.")

    write_rust_env()
    log(f"Wrote {LIBS_DIR / 'rust' / 'env.sh'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
