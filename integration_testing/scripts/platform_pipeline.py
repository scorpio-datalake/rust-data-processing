"""RDP pipeline helpers for Snowflake / Databricks / Spark integration sinks (S3 via MinIO Docker)."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

from minio_common import (
    databricks_output_parquet_uri,
    databricks_warehouse_uri,
    snowflake_stage_parquet_uri,
    snowflake_stage_uri,
    spark_handoff_uri,
)
from schema_util import connector_table, load_dataset_schema_json, load_table_spec, transform_sql

from rdp_pipeline import run_pipeline_json


def build_platform_sink(connector: str, spec: dict[str, Any] | None = None) -> dict[str, Any]:
    spec = spec or load_table_spec()
    cfg = spec["connectors"][connector]
    if connector == "snowflake":
        return {
            "kind": "snowflake",
            "account_url": cfg["account_url"],
            "database": cfg["database"],
            "schema": cfg["schema"],
            "table": cfg["table"],
            "stage_uri": snowflake_stage_uri(),
        }
    if connector == "databricks":
        return {
            "kind": "databricks",
            "workspace_url": cfg["workspace_url"],
            "warehouse": databricks_warehouse_uri(),
            "namespace": cfg["namespace"],
            "table": cfg["table"],
        }
    if connector == "spark":
        master = os.environ.get("SPARK_MASTER_URL", cfg["master"])
        return {
            "kind": "spark",
            "master": master,
            "app_name": cfg["app_name"],
            "handoff_uri": spark_handoff_uri(),
        }
    raise ValueError(f"not a platform connector: {connector}")


def platform_output_uri(connector: str, spec: dict[str, Any] | None = None) -> str:
    """S3 URI of the Parquet object written by the platform sink."""
    spec = spec or load_table_spec()
    if connector == "snowflake":
        return snowflake_stage_parquet_uri()
    if connector == "databricks":
        db = spec["connectors"]["databricks"]
        return databricks_output_parquet_uri(db["namespace"], db["table"])
    if connector == "spark":
        return spark_handoff_uri()
    raise ValueError(f"not a platform connector: {connector}")


def import_csv_platform_pipeline(
    *,
    connector: str,
    csv_path: str | Path,
    max_rows: int | None = None,
) -> dict[str, Any]:
    schema = json.loads(load_dataset_schema_json())
    spec = load_table_spec()
    payload: dict[str, Any] = {
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [str(Path(csv_path).resolve())],
            "schema": schema,
            "options": {"format": "csv"},
        },
        "transform": {"sql": transform_sql(spec)},
        "sinks": [build_platform_sink(connector, spec)],
    }
    if max_rows and max_rows > 0:
        payload["sources"]["options"]["max_rows"] = max_rows
        payload["orchestration"] = {"max_ingested_rows": max_rows}
    root = run_pipeline_json(payload)
    inter = root["interchange"]
    if inter.get("kind") != "run_pipeline_json":
        raise RuntimeError(f"unexpected interchange kind: {inter.get('kind')}")
    sink = next((s for s in inter.get("sink_results", []) if s.get("kind") == connector), None)
    if sink is None or sink.get("status") != "ok":
        raise RuntimeError(f"{connector} sink failed: {inter.get('sink_results')}")
    return inter


def verify_object_store_row_count(uri: str, expected: int) -> int:
    """Read Parquet back via s3:// (or other object_store URI) — proves storage connection."""
    import rust_data_processing as rdp

    from schema_util import load_dataset_schema

    if not uri.startswith(("s3://", "gs://", "abfss://", "abfs://", "azure://", "az://", "https://")):
        raise AssertionError(f"platform verify expects object-store URI, got: {uri}")
    schema = load_dataset_schema()
    ds = rdp.ingest_from_object_store_uri(uri, schema, {"format": "parquet"})
    count = ds.row_count()
    if count != expected:
        raise AssertionError(f"expected {expected} rows at {uri}, got {count}")
    return count


def verify_platform_output(connector: str, expected: int, spec: dict[str, Any] | None = None) -> int:
    return verify_object_store_row_count(platform_output_uri(connector, spec), expected)


def verify_platform_sql(connector: str, expected: int) -> int:
    """CREATE TABLE + load staged data + SELECT COUNT(*) on the platform engine."""
    from platform_sql import verify_connector

    return verify_connector(connector, expected)
