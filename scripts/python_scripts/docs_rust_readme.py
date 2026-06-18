#!/usr/bin/env python3
"""Generate Rust examples HTML (Pandoc) under _site/rust."""

from __future__ import annotations

import argparse
import shutil
import sys

from common import REPO_ROOT, banner, run
from copy_pages_markdown import copy_pages_markdown
from docs_java import find_pandoc
from rust_examples_pages_links import rewrite_file


def generate(*, skip_if_no_pandoc: bool = False) -> None:
    rust_site = REPO_ROOT / "_site" / "rust"
    rust_site.mkdir(parents=True, exist_ok=True)

    pandoc = find_pandoc()
    if pandoc is None:
        if skip_if_no_pandoc:
            print("pandoc not on PATH; skipping Rust examples HTML", flush=True)
            return
        raise SystemExit(
            "pandoc not found. Install system-wide (sudo apt install pandoc) or fetch a portable build:\n"
            "  python3 scripts/python_scripts/docs_java.py  # prints fetch instructions"
        )

    readme_md = REPO_ROOT / "docs" / "rust" / "README.md"
    header = REPO_ROOT / "docs" / "landing" / "java-examples-pandoc-header.html"
    out_html = rust_site / "examples.html"

    banner("Rust docs: pandoc README.md → _site/rust/examples.html")
    run(
        [
            pandoc,
            str(readme_md),
            "-f",
            "markdown+smart",
            "-o",
            str(out_html),
            "--standalone",
            "--metadata",
            "title=Rust examples - rust-data-processing",
            "-V",
            "lang=en",
            "-H",
            str(header),
        ],
        cwd=REPO_ROOT,
    )
    rewrite_file(out_html)
    copy_pages_markdown(REPO_ROOT / "_site")
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
