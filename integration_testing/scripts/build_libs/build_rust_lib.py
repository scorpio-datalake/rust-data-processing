#!/usr/bin/env python3
"""SCRIPT: integration tooling — not a pytest/cargo/junit test target."""

from __future__ import annotations

import argparse
import os
import sys
from pathlib import Path

_SCRIPTS = Path(__file__).resolve().parent.parent
_REPO_SCRIPTS = _SCRIPTS.parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))
if str(_REPO_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_REPO_SCRIPTS))

from connector_features import RUST_INTEGRATION_FEATURES  # noqa: E402
from common import (  # noqa: E402
    LIBS_DIR,
    REPO_ROOT,
    RUST_STAMP,
    ensure_linux_native_deps,
    integration_cargo_build_lock,
    integration_rust_watch_paths,
    log,
    mark_built,
    needs_rebuild,
    prebuild_integration_rust_tests,
    prepare_integration_disk,
    require_tool,
    run,
    setup_integration_build_env,
    write_rust_env,
)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=(
            "Build rust-data-processing with integration_full (all CONNECTORS.md batch "
            "connectors) and pre-compile integration_testing/*/rust/ connector test crates."
        )
    )
    parser.add_argument("--force", action="store_true", help="Force rebuild.")
    parser.add_argument(
        "--skip-prebuild",
        action="store_true",
        help="Build integration_full lib only; skip connector cargo test --no-run prebuild.",
    )
    args = parser.parse_args(argv)

    if args.force:
        os.environ["INTEG_FORCE_REBUILD"] = "1"
    if args.skip_prebuild:
        os.environ["INTEG_SKIP_PREBUILD"] = "1"

    setup_integration_build_env()
    prepare_integration_disk(force=args.force)
    log(f"Using CARGO_TARGET_DIR={os.environ['CARGO_TARGET_DIR']}")
    ensure_linux_native_deps()
    require_tool("cargo")

    with integration_cargo_build_lock():
        watch = integration_rust_watch_paths()
        if needs_rebuild(RUST_STAMP, watch):
            log(f"Building Rust workspace (--release --features {RUST_INTEGRATION_FEATURES})...")
            run(
                [
                    "cargo",
                    "build",
                    "--release",
                    "--locked",
                    "--features",
                    RUST_INTEGRATION_FEATURES,
                    "-p",
                    "rust-data-processing",
                ]
            )
            mark_built(RUST_STAMP)
            log("Rust lib build complete.")
        else:
            log("Rust lib up to date (skip rebuild). Use --force to rebuild.")

        # One-time compile per connector test crate; run_tests.py only executes tests.
        prebuild_integration_rust_tests()

    write_rust_env()
    log(f"Wrote {LIBS_DIR / 'rust' / 'env.sh'}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
