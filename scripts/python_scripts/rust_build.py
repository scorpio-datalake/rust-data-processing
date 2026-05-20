#!/usr/bin/env python3
"""Rust workspace: optional clean, fmt --check, clippy, build (default + ci_expanded).

Recommended order mirrors CI rationale: format first (cheap), then clippy (lint + compile),
then plain build. Optional ``cargo clean`` runs first only when requested.
"""

from __future__ import annotations

import argparse
import sys

from common import REPO_ROOT, banner, require_tool, run, setup_rust_toolchain_env


def clean() -> None:
    banner("Rust: cargo clean")
    require_tool("cargo")
    run(["cargo", "clean"], cwd=REPO_ROOT)


def fmt_check() -> None:
    banner("Rust: cargo fmt (--check)")
    require_tool("cargo")
    run(["cargo", "fmt", "--all", "--", "--check"], cwd=REPO_ROOT)


def clippy(*, expanded: bool, offline: bool) -> None:
    require_tool("cargo")
    args = ["cargo", "clippy", "--locked", "--all-targets"]
    if expanded:
        args.extend(["--features", "ci_expanded"])
        banner("Rust clippy (--features ci_expanded, all targets)")
    else:
        banner("Rust clippy (default features, all targets)")
    if offline:
        args.append("--offline")
    run(args, cwd=REPO_ROOT)


def build(*, expanded: bool, offline: bool) -> None:
    require_tool("cargo")
    args = ["cargo", "build", "--locked", "--all-targets"]
    if expanded:
        args.extend(["--features", "ci_expanded"])
        banner("Rust build (--features ci_expanded, all targets)")
    else:
        banner("Rust build (default features, all targets)")
    if offline:
        args.append("--offline")
    run(args, cwd=REPO_ROOT)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--expanded-only",
        action="store_true",
        help="Only clippy/build with ci_expanded (skip default-feature runs).",
    )
    parser.add_argument("--clean", action="store_true", help="Run cargo clean first.")
    parser.add_argument("--offline", action="store_true")
    parser.add_argument(
        "--skip-fmt",
        action="store_true",
        help="Skip cargo fmt --check.",
    )
    parser.add_argument(
        "--skip-clippy",
        action="store_true",
        help="Skip cargo clippy.",
    )
    args = parser.parse_args(argv)

    setup_rust_toolchain_env(offline=args.offline)
    if args.clean:
        clean()
    if not args.skip_fmt:
        fmt_check()
    if not args.skip_clippy:
        if not args.expanded_only:
            clippy(expanded=False, offline=args.offline)
        clippy(expanded=True, offline=args.offline)
    if not args.expanded_only:
        build(expanded=False, offline=args.offline)
    build(expanded=True, offline=args.offline)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
