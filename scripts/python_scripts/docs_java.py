#!/usr/bin/env python3
"""Generate Java examples HTML (Pandoc) under _site/java."""

from __future__ import annotations

import argparse
import shutil
import sys

from common import REPO_ROOT, banner, require_tool, run


def generate(*, skip_if_no_pandoc: bool = False) -> None:
    java_site = REPO_ROOT / "_site" / "java"
    java_site.mkdir(parents=True, exist_ok=True)

    pandoc = shutil.which("pandoc")
    if pandoc is None:
        if skip_if_no_pandoc:
            print("pandoc not on PATH; skipping Java HTML docs", flush=True)
            return
        raise SystemExit("pandoc not on PATH (https://pandoc.org)")

    examples_md = REPO_ROOT / "docs" / "java" / "EXAMPLES.md"
    header = REPO_ROOT / "docs" / "landing" / "java-examples-pandoc-header.html"
    out_html = java_site / "examples.html"

    banner("Java docs: pandoc EXAMPLES.md → _site/java/examples.html")
    require_tool("pandoc")
    run(
        [
            pandoc,
            str(examples_md),
            "-f",
            "markdown+smart",
            "-o",
            str(out_html),
            "--standalone",
            "--metadata",
            "title=Java examples - JVM bindings",
            "-V",
            "lang=en",
            "-H",
            str(header),
        ],
        cwd=REPO_ROOT,
    )
    print(f"Open: {out_html}", flush=True)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "--skip-if-no-pandoc",
        action="store_true",
        help="Exit 0 when pandoc is missing (orchestrator uses this).",
    )
    args = parser.parse_args(argv)
    generate(skip_if_no_pandoc=args.skip_if_no_pandoc)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
