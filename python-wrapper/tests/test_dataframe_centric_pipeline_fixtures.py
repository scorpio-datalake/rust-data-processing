"""Parity with ``docs/java/examples/DataFrameCentricPipeline.java`` and ``tests/fixtures/jvm_contract/``."""

from __future__ import annotations

import json

import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import (
    load_schema_fields,
    pipeline_transform_sql,
    resolve_pipeline_json,
)


def test_jvm_contract_dataframe_centric_pipeline_template_resolves() -> None:
    payload = json.loads(
        resolve_pipeline_json(
            "jvm_contract",
            "pipelines/dataframe_centric_sql.pipeline.json",
            {
                "SOURCE_PATH": str(fixture_path("jvm_contract_three_rows.json")),
                "SINK_PATH": "/tmp/out.parquet",
            },
        )
    )
    assert payload["sinks"][0]["kind"] == "parquet_file"
    assert "schema" in payload["sources"]
    assert "score * 2.0" in payload["transform"]["sql"]


def test_dataframe_centric_sql_filter_and_multiply_matches_doc_example() -> None:
    """Same SQL as ``DataFrameCentricPipeline`` / ``rdp_run_pipeline_json`` transform stage."""
    schema = load_schema_fields("schemas", "three_rows.schema.json", bundle="jvm_contract")
    ds = rdp.ingest_from_path(
        str(fixture_path("jvm_contract_three_rows.json")),
        schema,
        {"format": "json"},
    )
    assert ds.row_count() == 3
    sql = pipeline_transform_sql("jvm_contract", "pipelines/dataframe_centric_sql.pipeline.json")
    out = rdp.sql_query_dataset(ds, sql)
    assert out.row_count() == 2
    assert out.column_names() == ["id", "active", "score"]
    assert out.to_rows()[0] == [1, True, 20.0]
    assert out.to_rows()[1] == [2, True, 40.0]
