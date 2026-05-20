#!/usr/bin/env python3
"""Generate Rust API docs (cargo doc --no-deps)."""

from __future__ import annotations

import argparse
import sys

from common import REPO_ROOT, banner, require_tool, run, setup_rust_toolchain_env


def generate(*, offline: bool = False) -> None:
    require_tool("cargo")
    setup_rust_toolchain_env(offline=offline)
    banner("Rustdoc: cargo doc --no-deps")
    args = ["cargo", "doc", "--no-deps", "--locked"]
    if offline:
        args.append("--offline")
    run(args, cwd=REPO_ROOT)
    print(f"Open: {REPO_ROOT / 'target/doc/rust_data_processing/index.html'}", flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)
    generate(offline=args.offline)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
