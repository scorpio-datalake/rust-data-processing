"""Shared JSON schema fixtures load and match serde field names."""

from __future__ import annotations

import json

import pytest
import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import bundle_root, load_schema, load_schema_fields


@pytest.mark.parametrize(
    "bundle,schema_rel,expected_first",
    [
        ("people", "schemas/people_csv.schema.json", "id"),
        ("jvm_contract", "schemas/three_rows.schema.json", "id"),
        ("ghcn", "schemas/json_source.schema.json", "id"),
        ("ghcn", "schemas/parquet_lake.schema.json", "station_id"),
    ],
)
def test_schema_json_loads(bundle: str, schema_rel: str, expected_first: str) -> None:
    data = load_schema(bundle_root(bundle) / schema_rel)
    assert data["fields"][0]["name"] == expected_first


def test_jvm_contract_pipeline_templates_have_transform_sql() -> None:
    for name in ("dataframe_centric_sql", "sql_query_dataset", "ordered_json_to_parquet"):
        doc = json.loads(
            (bundle_root("jvm_contract") / "pipelines" / f"{name}.pipeline.json").read_text()
        )
        assert "sql" in doc["transform"]
        assert doc["pipeline_spec_version"] == 1


def test_ghcn_json_ingest_matches_fixture_schema() -> None:
    schema = load_schema_fields("schemas", "json_source.schema.json", bundle="ghcn")
    ds = rdp.ingest_from_path(
        str(fixture_path("ghcn", "ghcn_stations_sample.json")),
        schema,
        {"format": "json"},
    )
    assert ds.row_count() == 5
    assert ds.to_rows()[0][0] == "ACW00011604"
