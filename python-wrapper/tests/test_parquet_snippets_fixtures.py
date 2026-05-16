"""Parity with ``docs/java/examples/ParquetSnippets.java`` and ``tests/fixtures/people/``."""

from __future__ import annotations

import json
import tempfile
from pathlib import Path

import pytest
import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import load_schema_fields, resolve_pipeline_json


def test_people_csv_to_parquet_pipeline_template_resolves() -> None:
    out = Path(tempfile.gettempdir()) / "rdp_parquet_snippets_resolve.parquet"
    payload = json.loads(
        resolve_pipeline_json(
            "people",
            "pipelines/csv_to_parquet.pipeline.json",
            {
                "SOURCE_PATH": str(fixture_path("people.csv").resolve()),
                "SINK_PATH": str(out.resolve()),
            },
        )
    )
    assert payload["sinks"][0]["kind"] == "parquet_file"
    assert "schema" in payload["sources"]


def test_people_csv_ingest_with_flat_schema() -> None:
    """``people_flat.schema.json`` matches CSV layout (verify step in ParquetSnippets)."""
    flat = load_schema_fields("schemas", "people_flat.schema.json", bundle="people")
    csv = load_schema_fields("schemas", "people_csv.schema.json", bundle="people")
    assert flat == csv
    ds = rdp.ingest_from_path(str(fixture_path("people.csv")), flat, {"format": "csv"})
    assert ds.row_count() == 2


def test_people_parquet_round_trip_via_pyarrow() -> None:
    """CSV → Parquet → ingest (Rust export; JVM uses ``rdp_run_pipeline_json`` for the write)."""
    pa = pytest.importorskip("pyarrow")
    pq = pytest.importorskip("pyarrow.parquet")

    schema = load_schema_fields("schemas", "people_flat.schema.json", bundle="people")
    ds = rdp.ingest_from_path(str(fixture_path("people.csv")), schema, {"format": "csv"})
    assert ds.row_count() == 2

    with tempfile.TemporaryDirectory() as tmp:
        path = Path(tmp) / "people.parquet"
        table = pa.Table.from_pylist(
            [{"id": r[0], "name": r[1], "score": r[2], "active": r[3]} for r in ds.to_rows()]
        )
        pq.write_table(table, path)
        back = rdp.ingest_from_path(str(path), schema, {"format": "parquet"})
        assert back.row_count() == 2
        assert [r[0] for r in back.to_rows()] == [1, 2]
