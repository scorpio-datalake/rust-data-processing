#!/usr/bin/env python3
"""Ensure bindings/java/VERSION, Gradle properties, and Maven pom agree."""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VERSION_FILE = REPO / "bindings/java/VERSION"
GRADLE_PROPS = REPO / "bindings/java/rust-data-processing-jvm/gradle.properties"
POM = REPO / "bindings/java/rust-data-processing-jvm/pom.xml"


def main() -> int:
    ver = VERSION_FILE.read_text(encoding="utf-8").strip()
    gradle_line = next(
        (ln for ln in GRADLE_PROPS.read_text(encoding="utf-8").splitlines() if ln.startswith("version=")),
        "",
    )
    gradle_ver = gradle_line.split("=", 1)[1].strip() if gradle_line else ""
    pom_text = POM.read_text(encoding="utf-8")
    m = re.search(
        r"<artifactId>rust-data-processing-jvm</artifactId>\s*<version>([^<]+)</version>",
        pom_text,
        re.DOTALL,
    )
    pom_ver = m.group(1).strip() if m else ""

    bad = []
    if gradle_ver != ver:
        bad.append(f"gradle.properties version ({gradle_ver!r}) != VERSION ({ver!r})")
    if pom_ver != ver:
        bad.append(f"pom.xml <version> ({pom_ver!r}) != VERSION ({ver!r})")

    if bad:
        for line in bad:
            print(f"error: {line}", file=sys.stderr)
        return 1
    print("check_java_version_consistency: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
