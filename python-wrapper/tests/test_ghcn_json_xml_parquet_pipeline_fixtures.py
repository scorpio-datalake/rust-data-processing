"""Parity with ``docs/java/examples/GhcnJsonXmlParquetPipeline.java`` and ``tests/fixtures/ghcn/``."""

from __future__ import annotations

import json

import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import (
    load_schema_fields,
    pipeline_transform_sql,
    resolve_pipeline_json,
)

EXPECTED_ROW_COUNT = 5
EXPECTED_FIRST_STATION_ID = "ACW00011604"


def test_ghcn_json_to_xml_pipeline_template_resolves() -> None:
    payload = json.loads(
        resolve_pipeline_json(
            "ghcn",
            "pipelines/json_to_xml.pipeline.json",
            {
                "SOURCE_PATH": str(
                    fixture_path("ghcn", "ghcn_stations_sample.json").resolve()
                ),
                "SINK_PATH": "/tmp/out.xml",
            },
        )
    )
    assert payload["sinks"][0]["kind"] == "xml_file"
    assert "schema" in payload["sources"]
    assert "stationCode" in payload["transform"]["sql"]


def test_ghcn_xml_to_parquet_pipeline_template_resolves() -> None:
    payload = json.loads(
        resolve_pipeline_json(
            "ghcn",
            "pipelines/xml_to_parquet.pipeline.json",
            {
                "SOURCE_PATH": "/tmp/in.xml",
                "SINK_PATH": "/tmp/out.parquet",
            },
        )
    )
    assert payload["sinks"][0]["kind"] == "parquet_file"
    assert "station_id" in payload["transform"]["sql"]


def test_ghcn_json_to_intermediate_sql_matches_pipeline_fixture() -> None:
    schema = load_schema_fields("schemas", "json_source.schema.json", bundle="ghcn")
    ds = rdp.ingest_from_path(
        fixture_path("ghcn", "ghcn_stations_sample.json"), schema, {"format": "json"}
    )
    sql = pipeline_transform_sql("ghcn", "pipelines/json_to_xml.pipeline.json")
    out = rdp.sql_query_dataset(ds, sql)
    assert out.row_count() == EXPECTED_ROW_COUNT
    row0 = out.to_rows()[0]
    assert row0[0] == EXPECTED_FIRST_STATION_ID
    assert abs(float(row0[1]) - 17.1167) < 0.0001


def test_ghcn_committed_intermediate_xml_ingest() -> None:
    """``verifyXmlWithSchema`` uses the intermediate schema on pipeline-produced XML."""
    schema = load_schema_fields("schemas", "xml_intermediate.schema.json", bundle="ghcn")
    ds = rdp.ingest_from_path(
        fixture_path("ghcn", "ghcn_stations_intermediate.xml"),
        schema,
        {"format": "xml"},
    )
    assert ds.row_count() == EXPECTED_ROW_COUNT
    assert ds.to_rows()[0][0] == EXPECTED_FIRST_STATION_ID


def test_ghcn_xml_to_lake_sql_on_committed_intermediate_xml() -> None:
    """``verifyParquetWithSchema`` SQL stage on committed intermediate XML (JVM runs full pipeline)."""
    schema = load_schema_fields("schemas", "xml_intermediate.schema.json", bundle="ghcn")
    ds = rdp.ingest_from_path(
        fixture_path("ghcn", "ghcn_stations_intermediate.xml"),
        schema,
        {"format": "xml"},
    )
    sql = pipeline_transform_sql("ghcn", "pipelines/xml_to_parquet.pipeline.json")
    out = rdp.sql_query_dataset(ds, sql)
    assert out.row_count() == EXPECTED_ROW_COUNT
    assert out.schema()[0]["name"] == "station_id"
    assert out.to_rows()[0][0] == EXPECTED_FIRST_STATION_ID
