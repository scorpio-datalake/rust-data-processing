"""Parity with ``docs/java/examples/SQLQueries.java`` and ``tests/sql.rs`` fixture SQL."""

from __future__ import annotations

import json

import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import bundle_root, load_schema_fields, pipeline_transform_sql


def test_sql_query_dataset_pipeline_sql_matches_jvm_contract_fixture() -> None:
    """Single-table: ``jvm_contract`` three_rows + ``sql_query_dataset.pipeline.json`` SQL."""
    schema = load_schema_fields("schemas", "three_rows.schema.json", bundle="jvm_contract")
    ds = rdp.ingest_from_path(
        fixture_path("jvm_contract", "data", "three_rows.json"),
        schema,
        {"format": "json"},
    )
    assert ds.row_count() == 3
    sql = pipeline_transform_sql("jvm_contract", "pipelines/sql_query_dataset.pipeline.json")
    out = rdp.sql_query_dataset(ds, sql)
    assert out.column_names() == ["id", "score"]
    assert out.row_count() == 2
    assert out.to_rows()[0] == [2, 20.0]
    assert out.to_rows()[1] == [1, 10.0]


def test_sql_parity_join_matches_committed_fixtures() -> None:
    """JOIN: ``sql_parity`` schemas, data, and ``join_people_scores.sql.json`` (``SQLQueries.java``)."""
    left_schema = load_schema_fields("schemas", "join_left.schema.json", bundle="sql_parity")
    right_schema = load_schema_fields("schemas", "join_right.schema.json", bundle="sql_parity")
    left = rdp.ingest_from_path(
        fixture_path("sql_parity", "data", "join_left.json"),
        left_schema,
        {"format": "json"},
    )
    right = rdp.ingest_from_path(
        fixture_path("sql_parity", "data", "join_right.json"),
        right_schema,
        {"format": "json"},
    )
    sql = json.loads(
        (bundle_root("sql_parity") / "queries" / "join_people_scores.sql.json").read_text(
            encoding="utf-8"
        )
    )["sql"]
    ctx = rdp.SqlContext()
    ctx.register("people", rdp.DataFrame.from_dataset(left))
    ctx.register("scores", rdp.DataFrame.from_dataset(right))
    out = ctx.execute(sql).collect()
    assert out.column_names() == ["id", "name", "score"]
    assert out.row_count() == 2
    assert out.to_rows()[0] == [1, "Ada", 98.5]
    assert out.to_rows()[1] == [3, "Linus", 77.0]
