#!/usr/bin/env python3
"""Ensure bindings/java/VERSION, Gradle properties, and Maven pom agree."""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
VERSION_FILE = REPO / "bindings/java/VERSION"
GRADLE_PROPS = REPO / "bindings/java/rust-data-processing-jvm/gradle.properties"
JVM_POM = REPO / "bindings/java/rust-data-processing-jvm/pom.xml"
JVM_DEPENDENT_POMS = (
    REPO / "bindings/java/rust-data-processing-jvm-examples/pom.xml",
    REPO / "bindings/java/rust-data-processing-jvm-spark/pom.xml",
)
RDP_JVM_SYS_POM = REPO / "bindings/java/rdp-jvm-sys/pom.xml"
POM_VERSION_RE = re.compile(r"<version>([^<]+)</version>")
JVM_DEP_RE = re.compile(
    r"<artifactId>rust-data-processing-jvm</artifactId>\s*<version>([^<]+)</version>",
    re.DOTALL,
)


def jvm_dependency_version(pom_path: Path) -> str:
    m = JVM_DEP_RE.search(pom_path.read_text(encoding="utf-8"))
    return m.group(1).strip() if m else ""


def main() -> int:
    ver = VERSION_FILE.read_text(encoding="utf-8").strip()
    gradle_line = next(
        (ln for ln in GRADLE_PROPS.read_text(encoding="utf-8").splitlines() if ln.startswith("version=")),
        "",
    )
    gradle_ver = gradle_line.split("=", 1)[1].strip() if gradle_line else ""
    pom_ver = jvm_dependency_version(JVM_POM)
    rdp_sys_pom = POM_VERSION_RE.search(RDP_JVM_SYS_POM.read_text(encoding="utf-8"))
    rdp_sys_ver = rdp_sys_pom.group(1).strip() if rdp_sys_pom else ""

    bad = []
    if gradle_ver != ver:
        bad.append(f"gradle.properties version ({gradle_ver!r}) != VERSION ({ver!r})")
    if pom_ver != ver:
        bad.append(f"rust-data-processing-jvm/pom.xml <version> ({pom_ver!r}) != VERSION ({ver!r})")
    if rdp_sys_ver != ver:
        bad.append(f"rdp-jvm-sys/pom.xml <version> ({rdp_sys_ver!r}) != VERSION ({ver!r})")
    for pom in JVM_DEPENDENT_POMS:
        dep_ver = jvm_dependency_version(pom)
        if dep_ver != ver:
            bad.append(f"{pom.relative_to(REPO)} rust-data-processing-jvm dep ({dep_ver!r}) != VERSION ({ver!r})")

    if bad:
        for line in bad:
            print(f"error: {line}", file=sys.stderr)
        return 1
    print("check_java_version_consistency: OK")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
