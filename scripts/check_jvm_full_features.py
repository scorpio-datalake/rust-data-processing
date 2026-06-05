#!/usr/bin/env python3
"""Verify CONNECTORS.md batch connectors are enabled in JVM, Rust, and Python builds.

- ``rdp_jvm_sys`` feature ``full`` — JVM bindings CI + integration Java builds
- ``rust-data-processing`` feature ``integration_full`` — integration Rust builds
- ``rust_data_processing_py`` feature ``integration_full`` — integration Python builds

Kafka streaming ELT is validated separately via ``--features full,kafka`` on Linux CI.
"""
from __future__ import annotations

import re
import sys
from pathlib import Path

REPO = Path(__file__).resolve().parents[1]
JVM_CARGO = REPO / "bindings/jvm-sys/Cargo.toml"
RUST_CARGO = REPO / "Cargo.toml"
PYTHON_CARGO = REPO / "python-wrapper/Cargo.toml"
CONNECTORS_DOC = REPO / "docs/CONNECTORS.md"

# Each CONNECTORS.md row → required tokens in the expanded JVM ``full`` feature list.
JVM_REQUIRED_IN_FULL: dict[str, str] = {
    "link-main": "base Polars pipeline FFI (local paths, transforms, sinks)",
    "db_connectorx": "sources.db_reads — PostgreSQL, Oracle, SQL Server (ConnectorX)",
    "sink_postgres": "kind: postgresql sink (libpq COPY)",
    "sink_oracle": "kind: oracle sink (OCI row load)",
    "sink_mssql": "kind: mssql sink (TDS row load)",
    "rust-data-processing/cloud_connectors": "S3, GCS, Azure ADLS, SFTP/FTP, Snowflake stage, Databricks warehouse, Spark handoff",
    "rust-data-processing/sql": "Polars SQL transforms in pipeline JSON",
    "rust-data-processing/excel": "Excel ingest FFI paths",
}

# Main crate ``integration_full`` — DB reads + cloud I/O + Excel (no JVM-only sinks).
RUST_REQUIRED_IN_INTEGRATION_FULL: dict[str, str] = {
    "db_connectorx": "ingest_from_db — PostgreSQL, Oracle, SQL Server, MySQL (ConnectorX)",
    "cloud_connectors": "S3, GCS, Azure ADLS, SFTP/FTP, Snowflake stage, Databricks warehouse, Spark handoff",
    "excel": "Excel ingest paths",
}

# Python wrapper ``integration_full`` → parent crate features via ``db`` + ``cloud``.
PYTHON_REQUIRED_IN_INTEGRATION_FULL: dict[str, str] = {
    "db": "ingest_from_db / ingest_from_db_infer (ConnectorX)",
    "cloud": "ingest_from_object_store_uri, file_transfer, cloud pipeline sinks",
    "rust-data-processing/db_connectorx": "expanded from ``db``",
    "rust-data-processing/cloud_connectors": "expanded from ``cloud``",
}


def _parse_features(cargo_text: str) -> dict[str, list[str]]:
    in_features = False
    features: dict[str, list[str]] = {}
    current: str | None = None
    for line in cargo_text.splitlines():
        if line.strip() == "[features]":
            in_features = True
            continue
        if in_features and line.startswith("["):
            break
        if not in_features:
            continue
        m = re.match(r"^(\w+)\s*=\s*\[(.*)$", line)
        if m:
            current = m.group(1)
            rest = m.group(2)
            items = re.findall(r'"([^"]+)"', rest)
            features[current] = items
            if rest.rstrip().endswith("]"):
                current = None
            continue
        if current is not None:
            features[current].extend(re.findall(r'"([^"]+)"', line))
            if line.rstrip().endswith("]"):
                current = None
    return features


def _expand(features: dict[str, list[str]], name: str, seen: set[str] | None = None) -> set[str]:
    if seen is None:
        seen = set()
    if name in seen:
        return seen
    seen.add(name)
    for dep in features.get(name, []):
        _expand(features, dep, seen)
    return seen


def _check_feature_set(
    *,
    label: str,
    cargo_path: Path,
    feature_name: str,
    required: dict[str, str],
) -> list[tuple[str, str]]:
    if not cargo_path.is_file():
        print(f"error: missing {cargo_path.relative_to(REPO)}", file=sys.stderr)
        raise SystemExit(1)
    features = _parse_features(cargo_path.read_text(encoding="utf-8"))
    if feature_name not in features:
        print(
            f"error: [features] {feature_name!r} not defined in {cargo_path.relative_to(REPO)}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    expanded = _expand(features, feature_name)
    missing = [(token, purpose) for token, purpose in required.items() if token not in expanded]
    if missing:
        print(
            f"error: `{feature_name}` in {cargo_path.relative_to(REPO)} must include every "
            f"batch connector from docs/CONNECTORS.md.\n"
            f"Missing from expanded `{feature_name}` feature set:",
            file=sys.stderr,
        )
        for token, purpose in missing:
            print(f"  - {token!r} ({purpose})", file=sys.stderr)
        print(
            f"\nCurrent `{feature_name}` = {features[feature_name]!r}\n"
            f"Expanded           = {sorted(expanded)!r}",
            file=sys.stderr,
        )
        raise SystemExit(1)
    print(f"check_connector_features: OK — {label} `{feature_name}` covers CONNECTORS.md batch connectors")
    print(f"  file: {cargo_path.relative_to(REPO)}")
    print(f"  expanded ({len(expanded)}): {', '.join(sorted(expanded))}")
    return []


def main() -> int:
    if not CONNECTORS_DOC.is_file():
        print(f"error: missing {CONNECTORS_DOC.relative_to(REPO)}", file=sys.stderr)
        return 1

    _check_feature_set(
        label="rdp_jvm_sys",
        cargo_path=JVM_CARGO,
        feature_name="full",
        required=JVM_REQUIRED_IN_FULL,
    )
    _check_feature_set(
        label="rust-data-processing",
        cargo_path=RUST_CARGO,
        feature_name="integration_full",
        required=RUST_REQUIRED_IN_INTEGRATION_FULL,
    )
    _check_feature_set(
        label="rust_data_processing_py",
        cargo_path=PYTHON_CARGO,
        feature_name="integration_full",
        required=PYTHON_REQUIRED_IN_INTEGRATION_FULL,
    )
    print("  kafka: validated separately in CI via --features full,kafka (Linux)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
