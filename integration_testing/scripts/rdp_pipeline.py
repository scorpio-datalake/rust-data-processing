"""Call ``rdp_run_pipeline_json`` from the built ``librdp_jvm_sys`` shared library.

Requires ``build_java_lib.py`` / ``build_all_libs.py`` with ``--features full`` so
``kind: postgresql`` and ``kind: oracle`` sinks are available. Python verify uses the
separate PyO3 extension built with ``integration_full`` (``ingest_from_db``).
"""

from __future__ import annotations

import ctypes
import json
import os
from pathlib import Path
from typing import Any

from schema_util import connector_table, load_dataset_schema_json, load_table_spec, transform_sql

INTEG_ROOT = Path(__file__).resolve().parent.parent


class RdpJsonSlice(ctypes.Structure):
    _fields_ = [
        ("ptr", ctypes.POINTER(ctypes.c_uint8)),
        ("len", ctypes.c_size_t),
        ("cap", ctypes.c_size_t),
    ]


def _lib_path() -> Path:
    env = os.environ.get("RDP_JVM_SYS")
    if env:
        return Path(env)
    default = INTEG_ROOT / "libs" / "java" / "librdp_jvm_sys.so"
    if default.is_file():
        return default
    raise RuntimeError(
        "RDP_JVM_SYS not set and libs/java/librdp_jvm_sys.so missing — "
        "run build_all_libs.py (Java --features full)"
    )


def _load_lib() -> ctypes.CDLL:
    lib = ctypes.CDLL(str(_lib_path()))
    lib.rdp_json_slice_free.argtypes = [RdpJsonSlice]
    lib.rdp_json_slice_free.restype = None
    lib.rdp_run_pipeline_json.argtypes = [
        ctypes.POINTER(RdpJsonSlice),
        ctypes.c_char_p,
    ]
    lib.rdp_run_pipeline_json.restype = None
    return lib


def run_pipeline_json(payload: dict[str, Any] | str) -> dict[str, Any]:
    """Execute ``rdp_run_pipeline_json``; return parsed root envelope."""
    lib = _load_lib()
    text = payload if isinstance(payload, str) else json.dumps(payload)
    out = RdpJsonSlice()
    lib.rdp_run_pipeline_json(ctypes.byref(out), text.encode("utf-8"))
    try:
        raw = ctypes.string_at(out.ptr, out.len)
        root = json.loads(raw.decode("utf-8"))
    finally:
        lib.rdp_json_slice_free(out)
    if not root.get("ok", False):
        err = root.get("error", root)
        raise RuntimeError(f"rdp_run_pipeline_json failed: {err}")
    return root


def import_csv_pipeline(
    *,
    connector: str,
    csv_path: str | Path,
    connect_url: str = "",
    max_rows: int | None = None,
) -> dict[str, Any]:
    """CSV ingest → column transform → connector sink (RDP pipeline only)."""
    if connector in ("snowflake", "databricks", "spark"):
        from platform_pipeline import import_csv_platform_pipeline

        return import_csv_platform_pipeline(
            connector=connector,
            csv_path=csv_path,
            max_rows=max_rows,
        )
    if connector == "postgresql":
        from postgresql_common import strip_url_query_for_libpq

        connect_url = strip_url_query_for_libpq(connect_url)
    spec = load_table_spec()
    table = connector_table(connector, spec)
    schema = json.loads(load_dataset_schema_json())
    sink: dict[str, Any] = {
        "kind": connector,
        "table": table,
        "create_table_if_missing": True,
        "truncate_before_load": True,
    }
    if connector in ("postgresql", "oracle", "mssql"):
        sink["url"] = connect_url
    payload: dict[str, Any] = {
        "pipeline_spec_version": 1,
        "sources": {
            "paths": [str(Path(csv_path).resolve())],
            "schema": schema,
            "options": {"format": "csv"},
        },
        "transform": {"sql": transform_sql(spec)},
        "sinks": [sink],
    }
    if max_rows and max_rows > 0:
        payload["sources"]["options"]["max_rows"] = max_rows
        payload["orchestration"] = {"max_ingested_rows": max_rows}
    root = run_pipeline_json(payload)
    inter = root["interchange"]
    if inter.get("kind") != "run_pipeline_json":
        raise RuntimeError(f"unexpected interchange kind: {inter.get('kind')}")
    sink_results = inter.get("sink_results", [])
    sink = next((s for s in sink_results if s.get("kind") == connector), None)
    if sink is None or sink.get("status") != "ok":
        raise RuntimeError(f"{connector} sink failed: {sink_results}")
    return inter
