#!/usr/bin/env python3
"""Rewrite relative links in pdoc Python examples HTML for GitHub Pages."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path, PurePosixPath

from docs_pages_links import rewrite_html

REPO_ROOT = Path(__file__).resolve().parents[2]
MARKDOWN_BASE = PurePosixPath("docs/python")


def rewrite_file(html_path: Path) -> None:
    original = html_path.read_text(encoding="utf-8")
    updated = rewrite_html(original, MARKDOWN_BASE)
    if updated != original:
        html_path.write_text(updated, encoding="utf-8")


def default_html_paths() -> list[Path]:
    found: list[Path] = []
    for root in (REPO_ROOT / "_site", REPO_ROOT / "site"):
        for rel in (
            "python/examples.html",
            "python/rust_data_processing/examples.html",
        ):
            candidate = root / rel
            if candidate.is_file():
                found.append(candidate)
    return found


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "html_paths",
        nargs="*",
        type=Path,
        help="Python examples HTML files (default: site/python/examples.html and module copy)",
    )
    args = parser.parse_args(argv)

    paths = args.html_paths or default_html_paths()
    if not paths:
        raise SystemExit(
            "No Python examples HTML found. Run pdoc / docs_python.py first."
        )

    for html_path in paths:
        if not html_path.is_file():
            raise SystemExit(f"{html_path} not found")
        rewrite_file(html_path)
        print(f"Rewrote links in {html_path}", flush=True)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
