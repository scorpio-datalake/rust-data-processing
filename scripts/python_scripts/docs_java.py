#!/usr/bin/env python3
"""Generate Java examples HTML (Pandoc) under _site/java."""

from __future__ import annotations

import argparse
import os
import shutil
import sys

from common import REPO_ROOT, banner, run
from java_examples_pages_links import rewrite_file, write_examples_index


def find_pandoc() -> str | None:
    pandoc = shutil.which("pandoc")
    if pandoc is not None:
        return pandoc
    tools = REPO_ROOT / ".tools"
    if not tools.is_dir():
        return None
    for candidate in sorted(tools.glob("pandoc-*/bin/pandoc"), reverse=True):
        if candidate.is_file() and os.access(candidate, os.X_OK):
            return str(candidate)
    return None


def generate(*, skip_if_no_pandoc: bool = False) -> None:
    java_site = REPO_ROOT / "_site" / "java"
    java_site.mkdir(parents=True, exist_ok=True)

    pandoc = find_pandoc()
    if pandoc is None:
        if skip_if_no_pandoc:
            print("pandoc not on PATH; skipping Java HTML docs", flush=True)
            return
        raise SystemExit(
            "pandoc not found. Install system-wide (sudo apt install pandoc) or fetch a portable build:\n"
            "  mkdir -p .tools && curl -fsSL -o .tools/pandoc.tgz "
            "https://github.com/jgm/pandoc/releases/download/3.7/pandoc-3.7-linux-amd64.tar.gz\n"
            "  tar xzf .tools/pandoc.tgz -C .tools"
        )

    examples_md = REPO_ROOT / "docs" / "java" / "EXAMPLES.md"
    header = REPO_ROOT / "docs" / "landing" / "java-examples-pandoc-header.html"
    out_html = java_site / "examples.html"

    banner("Java docs: pandoc EXAMPLES.md → _site/java/examples.html")
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
    examples_src = REPO_ROOT / "docs" / "java" / "examples"
    examples_dst = java_site / "examples"
    if examples_dst.exists():
        shutil.rmtree(examples_dst)
    shutil.copytree(examples_src, examples_dst)
    rewrite_file(out_html)
    write_examples_index(examples_dst)
    from copy_pages_markdown import publish_pages_markdown

    publish_pages_markdown(REPO_ROOT / "_site", pandoc=pandoc, html=True)
    print(f"Open: {out_html}", flush=True)
    print(f"Sources: {examples_dst}/", flush=True)


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
