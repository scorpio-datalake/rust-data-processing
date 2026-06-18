"""Rewrite relative links in Pandoc Java examples HTML for GitHub Pages."""

from __future__ import annotations

import argparse
import re
import sys
from pathlib import Path, PurePosixPath

GITHUB_BLOB = "https://github.com/scorpio-datalake/rust-data-processing/blob/main"
MARKDOWN_BASE = PurePosixPath("docs/java")
HREF_RE = re.compile(r'href="([^"]*)"')


def resolve_repo_path(href_path: str) -> str:
    """Normalize an href path relative to docs/java/ to a repo-root posix path."""
    parts: list[str] = []
    for part in (MARKDOWN_BASE / href_path).parts:
        if part == "..":
            if parts:
                parts.pop()
        elif part != ".":
            parts.append(part)
    return "/".join(parts)


def rewrite_href(href: str) -> str:
    if not href or href.startswith(("#", "http://", "https://", "mailto:")):
        return href

    path, sep, fragment = href.partition("#")
    if not path:
        return href

    resolved = resolve_repo_path(path)

    if resolved.startswith("docs/java/examples/") and resolved.endswith(".java"):
        new_path = f"examples/{PurePosixPath(resolved).name}"
    elif path in ("examples", "examples/"):
        new_path = "examples/"
    elif resolved == "docs/python/README.md":
        new_path = "../python/examples.html"
    elif path.startswith("../python/examples.html"):
        new_path = path
    elif resolved.endswith((".md", ".java", ".py")):
        new_path = f"{GITHUB_BLOB}/{resolved}"
    else:
        new_path = path

    return f"{new_path}{sep}{fragment}" if sep else new_path


def rewrite_html(html: str) -> str:
    def repl(match: re.Match[str]) -> str:
        old = match.group(1)
        new = rewrite_href(old)
        return f'href="{new}"' if new != old else match.group(0)

    return HREF_RE.sub(repl, html)


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
    updated = rewrite_html(original)
    if updated != original:
        html_path.write_text(updated, encoding="utf-8")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("html_path", type=Path, help="site/java/examples.html")
    parser.add_argument(
        "--examples-dir",
        type=Path,
        default=None,
        help="Optional site/java/examples for index.html generation",
    )
    args = parser.parse_args(argv)
    rewrite_file(args.html_path)
    if args.examples_dir is not None:
        write_examples_index(args.examples_dir)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
