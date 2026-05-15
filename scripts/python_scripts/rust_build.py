#!/usr/bin/env python3
"""Compile the Rust workspace (default + ci_expanded features)."""

from __future__ import annotations

import argparse
import sys

from common import REPO_ROOT, banner, require_tool, run, setup_rust_toolchain_env


def build(*, expanded: bool) -> None:
    require_tool("cargo")
    args = ["cargo", "build", "--locked", "--all-targets"]
    if expanded:
        args.extend(["--features", "ci_expanded"])
        banner("Rust build (--features ci_expanded, all targets)")
    else:
        banner("Rust build (default features, all targets)")
    run(args, cwd=REPO_ROOT)


def clean() -> None:
    banner("Rust: cargo clean")
    require_tool("cargo")
    run(["cargo", "clean"], cwd=REPO_ROOT)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--expanded-only",
        action="store_true",
        help="Only build with ci_expanded (skip default-feature build).",
    )
    parser.add_argument("--clean", action="store_true", help="Run cargo clean first.")
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)

    setup_rust_toolchain_env(offline=args.offline)
    if args.clean:
        clean()
    if not args.expanded_only:
        build(expanded=False)
    build(expanded=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
