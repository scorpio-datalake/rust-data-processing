"""GHCN JSON → SQL reshape parity with shared pipeline fixture SQL (Rust JVM runs full XML/Parquet)."""

from __future__ import annotations

import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import load_schema_fields, pipeline_transform_sql


def test_ghcn_json_to_intermediate_sql_matches_pipeline_fixture() -> None:
    schema = load_schema_fields("schemas", "json_source.schema.json", bundle="ghcn")
    ds = rdp.ingest_from_path(
        fixture_path("ghcn", "ghcn_stations_sample.json"), schema, {"format": "json"}
    )
    sql = pipeline_transform_sql("ghcn", "pipelines/json_to_xml.pipeline.json")
    out = rdp.sql_query_dataset(ds, sql)
    assert out.row_count() == 5
  # stationCode, lat, lon, elev_m, label, region
    row0 = out.to_rows()[0]
    assert row0[0] == "ACW00011604"
    assert abs(float(row0[1]) - 17.1167) < 0.0001


def test_ghcn_xml_to_parquet_sql_on_synthetic_intermediate_rows() -> None:
    schema = load_schema_fields("schemas", "xml_intermediate.schema.json", bundle="ghcn")
    rows = [
        ["ACW00011604", 17.1167, -61.7833, 10.1, "ST JOHNS COOLIDGE FLD", ""],
    ]
    ds = rdp.DataSet(schema, rows)
    sql = pipeline_transform_sql("ghcn", "pipelines/xml_to_parquet.pipeline.json")
    out = rdp.sql_query_dataset(ds, sql)
    assert out.to_rows()[0][0] == "ACW00011604"
    assert out.schema()[0]["name"] == "station_id"
