#!/usr/bin/env python3
"""Rewrite relative links in Pandoc Rust examples HTML for GitHub Pages."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path, PurePosixPath

from docs_pages_links import rewrite_html as rewrite_pages_html

REPO_ROOT = Path(__file__).resolve().parents[2]
MARKDOWN_BASE = PurePosixPath("docs/rust")


def rewrite_file(html_path: Path) -> None:
    original = html_path.read_text(encoding="utf-8")
    updated = rewrite_pages_html(original, MARKDOWN_BASE)
    if updated != original:
        html_path.write_text(updated, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "html_path",
        nargs="?",
        type=Path,
        default=None,
        help="examples.html to rewrite (default: _site/rust/examples.html or site/rust/examples.html)",
    )
    args = parser.parse_args(argv)

    html_path = args.html_path
    if html_path is None:
        for candidate in (
            REPO_ROOT / "_site" / "rust" / "examples.html",
            REPO_ROOT / "site" / "rust" / "examples.html",
        ):
            if candidate.is_file():
                html_path = candidate
                break
        if html_path is None:
            html_path = REPO_ROOT / "_site" / "rust" / "examples.html"

    if not html_path.is_file():
        raise SystemExit(
            f"{html_path} not found. Generate it first:\n"
            "  python3 scripts/python_scripts/docs_rust_readme.py"
        )

    rewrite_file(html_path)
    print(f"Rewrote links in {html_path}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
