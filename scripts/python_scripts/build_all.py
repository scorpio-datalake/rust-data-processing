#!/usr/bin/env python3
"""Orchestrate Rust / Python / JVM build, test, and doc generation.

Thin coordinator over sibling modules in this directory. Run from repo root:

    pwsh -File scripts/build_all.ps1   # cargo clean + python clean, then this module
    python scripts/python_scripts/build_all.py

Individual steps (same directory):

    rust_build.py, rust_test.py, python_clean.py, python_build.py, python_test.py,
    java_clean.py, java_build.py, java_test.py, docs_rust.py, docs_python.py, docs_java.py
"""

from __future__ import annotations

import argparse
import os
import subprocess
import sys
from pathlib import Path

_SCRIPT_DIR = Path(__file__).resolve().parent
if str(_SCRIPT_DIR) not in sys.path:
    sys.path.insert(0, str(_SCRIPT_DIR))

from common import (  # noqa: E402
    DEFAULT_RUST_BUILD_TEST_WAIT_SECONDS,
    DEFAULT_WAIT_SECONDS,
    REPO_ROOT,
    banner,
    pause,
    setup_rust_toolchain_env,
)
from docs_java import main as docs_java_main  # noqa: E402
from docs_python import main as docs_python_main  # noqa: E402
from docs_rust import main as docs_rust_main  # noqa: E402
from java_build import main as java_build_main  # noqa: E402
from java_test import main as java_test_main  # noqa: E402
from python_build import main as python_build_main  # noqa: E402
from python_test import main as python_test_main  # noqa: E402
from rust_build import main as rust_build_main  # noqa: E402
from rust_test import main as rust_test_main  # noqa: E402


def _run_module(main_fn, argv: list[str]) -> None:
    code = main_fn(argv)
    if code != 0:
        raise SystemExit(code)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--clean",
        action="store_true",
        help="Rust cargo clean, Python wrapper artifact clean, Gradle clean.",
    )
    parser.add_argument("--skip-fmt", action="store_true", help="Skip Rust/Python/Java format checks.")
    parser.add_argument("--skip-rust", action="store_true")
    parser.add_argument("--skip-python", action="store_true")
    parser.add_argument("--skip-java", action="store_true")
    parser.add_argument("--skip-docs", action="store_true")
    parser.add_argument(
        "--rust-expanded-only",
        action="store_true",
        help="Rust build/test: ci_expanded only (skip default features).",
    )
    parser.add_argument("--docs-only", action="store_true", help="Generate Rust, Python, and Java docs.")
    parser.add_argument("--docs-rust-only", action="store_true", help="Generate Rust API docs (cargo doc) only.")
    parser.add_argument(
        "--docs-python-only",
        action="store_true",
        help="Generate Python API docs (pdoc) under _site/python/ only.",
    )
    parser.add_argument(
        "--docs-java-only",
        action="store_true",
        help="Generate Java examples HTML (pandoc) under _site/java/ only.",
    )
    parser.add_argument(
        "--wait-seconds",
        type=float,
        default=DEFAULT_WAIT_SECONDS,
        help="Pause between major steps (default: %(default)s).",
    )
    parser.add_argument(
        "--rust-build-test-wait-seconds",
        type=float,
        default=DEFAULT_RUST_BUILD_TEST_WAIT_SECONDS,
        help="Extra pause after Rust build before Rust tests (default: %(default)s).",
    )
    parser.add_argument("--offline", action="store_true")
    args = parser.parse_args(argv)

    os.chdir(REPO_ROOT)
    setup_rust_toolchain_env(offline=args.offline)

    wait = args.wait_seconds
    rust_bt_wait = args.rust_build_test_wait_seconds
    offline_flag = ["--offline"] if args.offline else []

    want_docs_rust = args.docs_only or args.docs_rust_only
    want_docs_python = args.docs_only or args.docs_python_only
    want_docs_java = args.docs_only or args.docs_java_only
    if want_docs_rust or want_docs_python or want_docs_java:
        if want_docs_rust:
            _run_module(docs_rust_main, offline_flag)
        if want_docs_python:
            if want_docs_rust:
                pause(wait, "before Python docs")
            _run_module(docs_python_main, [])
        if want_docs_java:
            if want_docs_rust or want_docs_python:
                pause(wait, "before Java docs")
            _run_module(docs_java_main, ["--skip-if-no-pandoc"])
        banner("Done (docs)")
        return 0

    rust_flags: list[str] = []
    if args.rust_expanded_only:
        rust_flags.append("--expanded-only")
    rust_flags.extend(offline_flag)

    python_args: list[str] = []
    java_args: list[str] = []
    if args.clean:
        rust_flags.append("--clean")
        python_args.append("--clean")
        java_args.append("--clean")
    if args.skip_fmt:
        rust_flags.append("--skip-fmt")
        python_args.append("--skip-fmt")
        java_args.append("--skip-fmt")

    if not args.skip_rust:
        _run_module(rust_build_main, rust_flags)
        pause(
            rust_bt_wait,
            "after Rust compile — release disk/memory before Rust tests",
        )
        _run_module(rust_test_main, rust_flags)

    if not args.skip_python:
        pause(wait, "before Python build")
        _run_module(python_build_main, python_args)
        pause(wait, "before Python tests")
        _run_module(python_test_main, [])

    if not args.skip_java:
        pause(wait, "before JVM build")
        _run_module(java_build_main, java_args)
        pause(wait, "before JVM tests")
        _run_module(java_test_main, java_args)

    if not args.skip_docs:
        pause(wait, "before Rust docs")
        _run_module(docs_rust_main, offline_flag)
        pause(wait, "before Python docs")
        _run_module(docs_python_main, [])
        pause(wait, "before Java docs")
        _run_module(docs_java_main, ["--skip-if-no-pandoc"])

    banner("All requested steps completed successfully")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as exc:
        print(f"\nCommand failed with exit code {exc.returncode}", file=sys.stderr)
        raise SystemExit(exc.returncode) from exc
