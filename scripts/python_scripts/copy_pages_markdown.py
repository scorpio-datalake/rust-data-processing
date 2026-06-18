"""Copy shared Markdown docs onto the GitHub Pages site tree."""

from __future__ import annotations

import argparse
import shutil
import sys
from pathlib import Path

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

DEPLOYED_REPO_PATHS = frozenset(PAGES_MARKDOWN.keys())


def copy_pages_markdown(site_dir: Path) -> None:
    for repo_rel, site_rel in PAGES_MARKDOWN.items():
        src = REPO_ROOT / repo_rel
        dst = site_dir / site_rel
        if not src.is_file():
            raise SystemExit(f"Missing doc source for Pages: {src}")
        dst.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(src, dst)


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument(
        "site_dir",
        nargs="?",
        type=Path,
        default=REPO_ROOT / "site",
        help="Pages site root (default: site/; local builds use _site/)",
    )
    args = parser.parse_args(argv)
    copy_pages_markdown(args.site_dir.resolve())
    print(f"Copied {len(PAGES_MARKDOWN)} markdown files into {args.site_dir}", flush=True)
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except Exception as exc:
        print(exc, file=sys.stderr)
        raise
