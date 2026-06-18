"""Rewrite relative links in Pandoc Java examples HTML for GitHub Pages."""

from __future__ import annotations

import argparse
import sys
from pathlib import Path, PurePosixPath

from docs_pages_links import rewrite_html as rewrite_pages_html

REPO_ROOT = Path(__file__).resolve().parents[2]
MARKDOWN_BASE = PurePosixPath("docs/java")


def default_html_path() -> Path:
    for candidate in (REPO_ROOT / "_site" / "java" / "examples.html", REPO_ROOT / "site" / "java" / "examples.html"):
        if candidate.is_file():
            return candidate
    return REPO_ROOT / "_site" / "java" / "examples.html"


def resolve_repo_path(href_path: str) -> str:
    """Normalize an href path relative to docs/java/ to a repo-root posix path."""
    from docs_pages_links import resolve_repo_path as _resolve

    return _resolve(href_path, MARKDOWN_BASE)


def rewrite_href(href: str) -> str:
    from docs_pages_links import rewrite_href as _rewrite

    return _rewrite(href, MARKDOWN_BASE)


def write_examples_index(examples_dir: Path) -> None:
    """Directory listing for examples/ (GitHub Pages has no auto index)."""
    java_files = sorted(p.name for p in examples_dir.glob("*.java"))
    items = "\n".join(f'    <li><a href="{name}">{name}</a></li>' for name in java_files)
    html = f"""<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8">
  <title>Java example sources</title>
</head>
<body>
  <h1>Runnable Java examples</h1>
  <p>Copy-paste sources linked from <a href="../examples.html">java/examples.html</a>.</p>
  <ul>
{items}
  </ul>
</body>
</html>
"""
    (examples_dir / "index.html").write_text(html, encoding="utf-8")


def rewrite_file(html_path: Path) -> None:
    original = html_path.read_text(encoding="utf-8")
    updated = rewrite_pages_html(original, MARKDOWN_BASE)
    if updated != original:
        html_path.write_text(updated, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        epilog=(
            "With no html_path, uses _site/java/examples.html (or site/java/examples.html if present). "
            "Run docs_java.py first to generate the HTML."
        ),
    )
    parser.add_argument(
        "html_path",
        nargs="?",
        type=Path,
        default=None,
        help="examples.html to rewrite (default: _site/java/examples.html or site/java/examples.html)",
    )
    parser.add_argument(
        "--examples-dir",
        type=Path,
        default=None,
        help="site/java/examples for index.html (default: <html_path>/../examples if it exists)",
    )
    parser.add_argument(
        "--no-index",
        action="store_true",
        help="Skip writing examples/index.html",
    )
    args = parser.parse_args(argv)

    html_path = args.html_path or default_html_path()
    if not html_path.is_file():
        raise SystemExit(
            f"{html_path} not found. Generate it first:\n"
            "  python3 scripts/python_scripts/docs_java.py"
        )

    rewrite_file(html_path)
    print(f"Rewrote links in {html_path}", flush=True)

    if not args.no_index:
        examples_dir = args.examples_dir or (html_path.parent / "examples")
        if examples_dir.is_dir():
            write_examples_index(examples_dir)
            print(f"Wrote {examples_dir / 'index.html'}", flush=True)

    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
