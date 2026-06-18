"""Copy shared Markdown docs onto the GitHub Pages site tree (optional Pandoc HTML)."""

from __future__ import annotations

import argparse
import shutil
import subprocess
import sys
from pathlib import Path, PurePosixPath

from common import REPO_ROOT

# Repo path → path under site/ (must match ../… links from java/ and python/ examples HTML).
PAGES_MARKDOWN: dict[str, str] = {
    "docs/CONNECTORS.md": "CONNECTORS.md",
    "docs/CLOUD_AUTH.md": "CLOUD_AUTH.md",
    "docs/KAFKA_ELT.md": "KAFKA_ELT.md",
    "docs/LAKE_TABLE_READ.md": "LAKE_TABLE_READ.md",
    "docs/REDUCE_AGG_SEMANTICS.md": "REDUCE_AGG_SEMANTICS.md",
    "docs/RELEASE_CHECKLIST.md": "RELEASE_CHECKLIST.md",
    "docs/SFT_DATA_FORMATS.md": "SFT_DATA_FORMATS.md",
    "docs/rust/README.md": "rust/README.md",
    "docs/python/PHASE2_EXAMPLES.md": "python/PHASE2_EXAMPLES.md",
}

# docs/rust/README.md is rendered as site/rust/examples.html by docs.yml / docs_rust_readme.py.
PAGES_HTML_SKIP = frozenset({"docs/rust/README.md"})

DEPLOYED_REPO_PATHS = frozenset(PAGES_MARKDOWN.keys())

DEFAULT_PANDOC_HEADER = REPO_ROOT / "docs" / "landing" / "java-examples-pandoc-header.html"


def site_html_rel(site_md_rel: str) -> str:
    if not site_md_rel.endswith(".md"):
        raise ValueError(f"expected .md site path, got {site_md_rel!r}")
    return f"{site_md_rel[:-3]}.html"


def title_for_markdown(src: Path) -> str:
    for line in src.read_text(encoding="utf-8").splitlines():
        if line.startswith("# "):
            return line[2:].strip()
    return src.stem.replace("_", " ")


def copy_pages_markdown(site_dir: Path) -> None:
    for repo_rel, site_rel in PAGES_MARKDOWN.items():
        src = REPO_ROOT / repo_rel
        dst = site_dir / site_rel
        if not src.is_file():
            raise SystemExit(f"Missing doc source for Pages: {src}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def render_pages_markdown_html(
    site_dir: Path,
    *,
    pandoc: str,
    header: Path = DEFAULT_PANDOC_HEADER,
) -> list[Path]:
    if not header.is_file():
        raise SystemExit(f"Missing Pandoc header: {header}")

    rendered: list[Path] = []
    for repo_rel, site_rel in PAGES_MARKDOWN.items():
        if repo_rel in PAGES_HTML_SKIP:
            continue
        src = REPO_ROOT / repo_rel
        if not src.is_file():
            raise SystemExit(f"Missing doc source for Pages HTML: {src}")
        out_html = site_dir / site_html_rel(site_rel)
        out_html.parent.mkdir(parents=True, exist_ok=True)
        subprocess.run(
            [
                pandoc,
                str(src),
                "-f",
                "markdown+smart",
                "-o",
                str(out_html),
                "--standalone",
                "--metadata",
                f"title={title_for_markdown(src)}",
                "-V",
                "lang=en",
                "-H",
                str(header),
            ],
            check=True,
        )
        rendered.append(out_html)
    return rendered


def rewrite_shared_pages_html(site_dir: Path) -> None:
    from docs_pages_links import rewrite_html

    for repo_rel, site_rel in PAGES_MARKDOWN.items():
        if repo_rel in PAGES_HTML_SKIP:
            continue
        html_path = site_dir / site_html_rel(site_rel)
        if not html_path.is_file():
            continue
        markdown_base = PurePosixPath(str(Path(repo_rel).parent))
        original = html_path.read_text(encoding="utf-8")
        updated = rewrite_html(original, markdown_base)
        if updated != original:
            html_path.write_text(updated, encoding="utf-8")


def publish_pages_markdown(
    site_dir: Path,
    *,
    pandoc: str | None = None,
    header: Path = DEFAULT_PANDOC_HEADER,
    html: bool = False,
) -> None:
    copy_pages_markdown(site_dir)
    if not html:
        return
    pandoc_bin = pandoc or shutil.which("pandoc")
    if not pandoc_bin:
        raise SystemExit("pandoc not found (required for --html)")
    render_pages_markdown_html(site_dir, pandoc=pandoc_bin, header=header)
    rewrite_shared_pages_html(site_dir)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "site_dir",
        nargs="?",
        type=Path,
        default=REPO_ROOT / "site",
        help="Pages site root (default: site/; local builds use _site/)",
    )
    parser.add_argument(
        "--html",
        action="store_true",
        help="Also render Pandoc HTML for shared docs (CONNECTORS.html, …)",
    )
    parser.add_argument("--pandoc", default=None, help="pandoc executable (default: PATH)")
    parser.add_argument(
        "--header",
        type=Path,
        default=DEFAULT_PANDOC_HEADER,
        help="Pandoc header HTML (shared stylesheet)",
    )
    args = parser.parse_args(argv)
    site_dir = args.site_dir.resolve()
    publish_pages_markdown(
        site_dir,
        pandoc=args.pandoc,
        header=args.header.resolve(),
        html=args.html,
    )
    if args.html:
        count = len(PAGES_MARKDOWN) - len(PAGES_HTML_SKIP)
        print(
            f"Copied {len(PAGES_MARKDOWN)} markdown files and rendered {count} HTML pages into {site_dir}",
            flush=True,
        )
    else:
        print(f"Copied {len(PAGES_MARKDOWN)} markdown files into {site_dir}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
