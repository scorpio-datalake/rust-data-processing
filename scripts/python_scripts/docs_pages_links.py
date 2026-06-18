"""Rewrite relative doc links in GitHub Pages HTML (Java Pandoc, Python pdoc)."""

from __future__ import annotations

import re
from pathlib import PurePosixPath

GITHUB_BLOB = "https://github.com/scorpio-datalake/rust-data-processing/blob/main"
HREF_RE = re.compile(r'href="([^"]*)"')

try:
    from copy_pages_markdown import DEPLOYED_REPO_PATHS
except ImportError:
    DEPLOYED_REPO_PATHS = frozenset(
        {
            "docs/CONNECTORS.md",
            "docs/CLOUD_AUTH.md",
            "docs/KAFKA_ELT.md",
            "docs/LAKE_TABLE_READ.md",
            "docs/REDUCE_AGG_SEMANTICS.md",
            "docs/RELEASE_CHECKLIST.md",
            "docs/SFT_DATA_FORMATS.md",
            "docs/rust/README.md",
            "docs/python/PHASE2_EXAMPLES.md",
        }
    )


def resolve_repo_path(href_path: str, markdown_base: PurePosixPath) -> str:
    parts: list[str] = []
    for part in (markdown_base / href_path).parts:
        if part == "..":
            if parts:
                parts.pop()
        elif part != ".":
            parts.append(part)
    return "/".join(parts)


def deployed_pages_href(path: str, resolved: str) -> str:
    """Map a deployed Markdown href to its GitHub Pages HTML target."""
    if resolved == "docs/rust/README.md":
        return path.replace("README.md", "examples.html")
    if resolved in DEPLOYED_REPO_PATHS and path.endswith(".md"):
        return f"{path[:-3]}.html"
    return path


def rewrite_href(href: str, markdown_base: PurePosixPath) -> str:
    if not href or href.startswith(("#", "http://", "https://", "mailto:")):
        return href

    path, sep, fragment = href.partition("#")
    if not path:
        return href

    resolved = resolve_repo_path(path, markdown_base)

    if resolved in DEPLOYED_REPO_PATHS or resolved == "docs/rust/README.md":
        new_path = deployed_pages_href(path, resolved)
    elif markdown_base == PurePosixPath("docs/java"):
        if resolved.startswith("docs/java/examples/") and resolved.endswith(".java"):
            new_path = f"examples/{PurePosixPath(resolved).name}"
        elif path in ("examples", "examples/"):
            new_path = "examples/"
        elif resolved == "docs/python/README.md" or path.startswith("../python/examples.html"):
            new_path = "../python/examples.html" if resolved == "docs/python/README.md" else path
        elif resolved.endswith((".md", ".java", ".py")):
            new_path = f"{GITHUB_BLOB}/{resolved}"
        else:
            new_path = path
    elif markdown_base == PurePosixPath("docs/python"):
        if resolved.startswith("docs/images/"):
            new_path = path
        elif path.startswith("../rust_data_processing.html") or path == "rust_data_processing.html":
            new_path = path
        elif resolved == "docs/java/EXAMPLES.md" or path.startswith("../java/examples.html"):
            new_path = "../java/examples.html" if resolved == "docs/java/EXAMPLES.md" else path
        elif resolved == "docs/python/README.md" or path.endswith("/python/examples.html"):
            new_path = path
        elif resolved.endswith((".md", ".java", ".py", ".ndjson")):
            new_path = f"{GITHUB_BLOB}/{resolved}"
        else:
            new_path = path
    elif markdown_base == PurePosixPath("docs/rust"):
        if resolved.startswith("docs/images/"):
            new_path = path
        elif resolved == "docs/python/README.md":
            new_path = "../python/examples.html"
        elif path.startswith("../python/examples.html"):
            new_path = path
        elif resolved == "docs/java/EXAMPLES.md":
            new_path = "../java/examples.html"
        elif path.startswith("../java/examples.html"):
            new_path = path
        elif resolved.endswith((".md", ".java", ".py", ".rs")):
            new_path = f"{GITHUB_BLOB}/{resolved}"
        else:
            new_path = path
    elif str(markdown_base) == "docs" or str(markdown_base).startswith("docs/"):
        if resolved == "docs/java/EXAMPLES.md":
            new_path = "../java/examples.html" if path.startswith("..") else "java/examples.html"
        elif resolved == "docs/python/README.md":
            new_path = "../python/examples.html" if path.startswith("..") else "python/examples.html"
        elif resolved.endswith(".md"):
            new_path = f"{GITHUB_BLOB}/{resolved}"
        else:
            new_path = path
    else:
        new_path = path

    return f"{new_path}{sep}{fragment}" if sep else new_path


def rewrite_html(html: str, markdown_base: PurePosixPath) -> str:
    def repl(match: re.Match[str]) -> str:
        old = match.group(1)
        new = rewrite_href(old, markdown_base)
        return f'href="{new}"' if new != old else match.group(0)

    return HREF_RE.sub(repl, html)
