#!/usr/bin/env python3
"""Rust workspace: optional clean, fmt --check, clippy, build (default + ci_expanded).

Recommended order mirrors CI rationale: format first (cheap), then clippy (lint + compile),
then plain build. Optional ``cargo clean`` runs first only when requested.
"""

from __future__ import annotations

import argparse
import sys

from common import (
    REPO_ROOT,
    banner,
    cargo_jobs_args,
    ensure_min_disk_space,
    report_disk_usage,
    require_tool,
    run,
    setup_rust_toolchain_env,
    should_clean_between_rust_features,
)

# Criterion benches (`deep_tests`) are exercised via `cargo test --features ci_expanded` /
# `cargo bench`; linking them during build_all routinely OOMs on small VMs.
RUST_CLIPPY_TARGETS = ["--lib", "--bins", "--tests", "--examples"]
RUST_BUILD_TARGETS = ["--lib", "--bins", "--tests", "--examples"]


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
    args = ["cargo", "clippy", "--locked", *cargo_jobs_args(), *RUST_CLIPPY_TARGETS]
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
    args = ["cargo", "build", "--locked", *cargo_jobs_args(), *RUST_BUILD_TARGETS]
    if expanded:
        args.extend(["--features", "ci_expanded"])
        banner("Rust build (--features ci_expanded, lib/bins/tests/examples)")
    else:
        banner("Rust build (default features, lib/bins/tests/examples)")
    if offline:
        args.append("--offline")
    run(args, cwd=REPO_ROOT)


def maybe_clean_between_feature_sets(*, after: str) -> None:
    if not should_clean_between_rust_features():
        return
    banner(f"Rust: cargo clean (free disk after {after}; ci_expanded is next)")
    report_disk_usage(f"before clean after {after}", [REPO_ROOT / "target"])
    clean()


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
    ensure_min_disk_space(context="Rust build")
    if args.clean:
        clean()
    if not args.skip_fmt:
        fmt_check()
    if not args.expanded_only:
        if not args.skip_clippy:
            clippy(expanded=False, offline=args.offline)
        build(expanded=False, offline=args.offline)
        maybe_clean_between_feature_sets(after="default features")
    if not args.skip_clippy:
        clippy(expanded=True, offline=args.offline)
    build(expanded=True, offline=args.offline)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
