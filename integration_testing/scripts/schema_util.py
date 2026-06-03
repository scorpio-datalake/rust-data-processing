"""Shared integration schema helpers (``integration_testing/schema/``)."""

from __future__ import annotations

import json
import os
from pathlib import Path
from typing import Any

INTEG_ROOT = Path(__file__).resolve().parent.parent
SCHEMA_DIR = INTEG_ROOT / "schema"


def dataset_schema_path() -> Path:
    override = os.environ.get("RDP_INTEGRATION_DATASET_SCHEMA")
    if override:
        return Path(override)
    return SCHEMA_DIR / "uber_pickups.schema.json"


def table_spec_path() -> Path:
    override = os.environ.get("RDP_INTEGRATION_TABLE_SPEC")
    if override:
        return Path(override)
    return SCHEMA_DIR / "uber_pickups.table.json"


def load_dataset_schema_json() -> str:
    return dataset_schema_path().read_text(encoding="utf-8")


def load_dataset_schema() -> list[dict[str, str]]:
    raw = json.loads(load_dataset_schema_json())
    return [
        {"name": field["name"], "data_type": field["data_type"].lower()}
        for field in raw["fields"]
    ]


def load_table_spec() -> dict[str, Any]:
    return json.loads(table_spec_path().read_text(encoding="utf-8"))


def connector_table(connector: str, spec: dict[str, Any] | None = None) -> str:
    spec = spec or load_table_spec()
    return str(spec["connectors"][connector]["table"])


def transform_sql(spec: dict[str, Any] | None = None) -> str:
    """Polars SQL renaming CSV/RDP fields to warehouse columns (registered as ``df``)."""
    spec = spec or load_table_spec()
    parts: list[str] = []
    for col in spec["columns"]:
        src = col["source_field"]
        name = col["name"]
        if any(ch in src for ch in (' ', '/', '-', '.')):
            parts.append(f'"{src}" AS {name}')
        else:
            parts.append(f"{src} AS {name}")
    return f"SELECT {', '.join(parts)} FROM df"


def count_verify_query(connector: str, table: str) -> str:
    if connector == "oracle":
        return f"SELECT CAST(COUNT(*) AS NUMBER(19)) AS CNT FROM {table}"
    return f"SELECT COUNT(*)::bigint AS cnt FROM {table}"


def count_verify_schema(connector: str) -> dict[str, Any]:
    if connector == "oracle":
        return {"fields": [{"name": "CNT", "data_type": "Int64"}]}
    return {"fields": [{"name": "cnt", "data_type": "Int64"}]}
