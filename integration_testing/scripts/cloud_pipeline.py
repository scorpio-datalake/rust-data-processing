"""RDP pipeline helpers for cloud storage integration (S3, GCS, Azure, SFTP, FTP)."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from schema_util import (
    curated_dataset_schema,
    load_dataset_schema_json,
    load_table_spec,
    transform_sql,
)

from rdp_pipeline import run_pipeline_json

OBJECT_STORE_PROTOCOLS = ("s3", "gcs", "azure")
FILE_TRANSFER_PROTOCOLS = ("sftp", "ftp")


def _require_protocol(protocol: str) -> None:
    if protocol in OBJECT_STORE_PROTOCOLS or protocol in FILE_TRANSFER_PROTOCOLS:
        return
    raise ValueError(f"unknown cloud protocol: {protocol}")


def object_store_export_uri(protocol: str) -> str:
    _require_protocol(protocol)
    key = f"CLOUD_{protocol.upper()}_EXPORT_URI"
    default = {
        "s3": "s3://rdp-cloud-s3/out.parquet",
        "gcs": "gs://rdp-cloud-gcs/out.parquet",
        "azure": "azure://rdp-cloud-azure/out.parquet",
    }[protocol]
    return os.environ.get(key, default)


def file_transfer_source_uri(protocol: str) -> str:
    _require_protocol(protocol)
    key = f"CLOUD_{protocol.upper()}_SOURCE_URI"
    default = {
        "sftp": "sftp://rdp:rdp_sftp_secret@127.0.0.1:2222/upload/incoming.csv",
        "ftp": "ftp://rdp:rdp_ftp_secret@127.0.0.1:21/incoming.csv",
    }[protocol]
    return os.environ.get(key, default)


def export_csv_to_object_store(
    *,
    protocol: str,
    csv_path: str | Path,
    max_rows: int | None = None,
) -> dict[str, Any]:
    """CSV → transform → object_store sink (Rust I/O via rdp_run_pipeline_json)."""
    _require_protocol(protocol)
    if protocol not in OBJECT_STORE_PROTOCOLS:
        raise ValueError(f"{protocol} is not an object_store protocol")
    max_rows = max_rows or int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    schema = json.loads(load_dataset_schema_json())
    spec = load_table_spec()
    uri = object_store_export_uri(protocol)
    payload: dict[str, Any] = {
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [str(Path(csv_path).resolve())],
            "schema": schema,
            "options": {"format": "csv", "max_rows": max_rows},
        },
        "transform": {"sql": transform_sql(spec)},
        "sinks": [
            {
                "kind": "object_store",
                "uri": uri,
                "format": "parquet",
            }
        ],
        "orchestration": {"max_ingested_rows": max_rows},
    }
    inter = run_pipeline_json(payload)["interchange"]
    expected = int(inter["ingested_row_count"])
    sink = next(s for s in inter["sink_results"] if s["kind"] == "object_store")
    if sink.get("status") != "ok":
        raise RuntimeError(f"object_store sink failed: {sink}")
    if int(sink.get("row_count", 0)) != expected:
        raise RuntimeError(f"row_count mismatch: expected {expected}, got {sink}")
    return inter


def import_from_object_store(*, protocol: str, uri: str | None = None) -> dict[str, Any]:
    """Read Parquet from object_store URI and return interchange."""
    _require_protocol(protocol)
    if protocol not in OBJECT_STORE_PROTOCOLS:
        raise ValueError(f"{protocol} is not an object_store protocol")
    uri = uri or object_store_export_uri(protocol)
    schema = curated_dataset_schema()
    payload: dict[str, Any] = {
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [],
            "schema": schema,
            "options": {"format": "parquet"},
            "object_store_uris": [uri],
        },
        "sinks": [
            {
                "kind": "parquet_file",
                "path": f"/tmp/rdp-cloud-{protocol}-readback.parquet",
            }
        ],
    }
    return run_pipeline_json(payload)["interchange"]


def import_from_file_transfer(*, protocol: str, uri: str | None = None) -> dict[str, Any]:
    """Read CSV from SFTP/FTP URI (Rust file_transfer source)."""
    _require_protocol(protocol)
    if protocol not in FILE_TRANSFER_PROTOCOLS:
        raise ValueError(f"{protocol} is not a file_transfer protocol")
    uri = uri or file_transfer_source_uri(protocol)
    schema = json.loads(load_dataset_schema_json())
    max_rows = int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    payload: dict[str, Any] = {
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [],
            "schema": schema,
            "options": {"format": "csv", "max_rows": max_rows},
            "file_transfer_uris": [uri],
        },
        "sinks": [
            {
                "kind": "parquet_file",
                "path": f"/tmp/rdp-cloud-{protocol}-import.parquet",
            }
        ],
    }
    return run_pipeline_json(payload)["interchange"]


def verify_object_store_roundtrip(
    *,
    protocol: str,
    csv_path: str | Path,
    max_rows: int | None = None,
) -> int:
    """Export CSV to cloud storage, read back, assert row counts match."""
    inter = export_csv_to_object_store(protocol=protocol, csv_path=csv_path, max_rows=max_rows)
    expected = int(inter["ingested_row_count"])
    read_back = import_from_object_store(protocol=protocol)
    got = int(read_back["ingested_row_count"])
    if got != expected:
        raise RuntimeError(f"{protocol} roundtrip: expected {expected} rows, read {got}")
    return expected


def verify_file_transfer_import(*, protocol: str) -> int:
    """Import seeded CSV from SFTP/FTP and return row count."""
    inter = import_from_file_transfer(protocol=protocol)
    count = int(inter["ingested_row_count"])
    if count <= 0:
        raise RuntimeError(f"{protocol} import returned no rows")
    return count
