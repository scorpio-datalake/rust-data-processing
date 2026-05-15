#!/usr/bin/env python3
"""Run Rust library and integration tests; generate Excel fixture."""

from __future__ import annotations

import argparse
import sys

from common import REPO_ROOT, banner, require_tool, run, setup_rust_toolchain_env


def test(*, expanded: bool) -> None:
    require_tool("cargo")
    args = ["cargo", "test", "--locked"]
    if expanded:
        args.extend(["--features", "ci_expanded"])
        banner("Rust test (--features ci_expanded)")
    else:
        banner("Rust test (default features)")
    run(args, cwd=REPO_ROOT)


def generate_people_xlsx() -> None:
    banner("Rust: generate tests/fixtures/people.xlsx")
    run(
        [
            "cargo",
            "run",
            "--features",
            "excel_test_writer",
            "--bin",
            "generate_people_xlsx_fixture",
        ],
        cwd=REPO_ROOT,
    )


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--expanded-only",
        action="store_true",
        help="Only run ci_expanded tests (skip default-feature tests).",
    )
    parser.add_argument(
        "--skip-fixtures",
        action="store_true",
        help="Skip people.xlsx generation.",
    )
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)

    setup_rust_toolchain_env(offline=args.offline)
    if not args.expanded_only:
        test(expanded=False)
    test(expanded=True)
    if not args.skip_fixtures:
        generate_people_xlsx()
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
