"""GHCN SQL edge cases; full doc parity lives in ``test_ghcn_json_xml_parquet_pipeline_fixtures.py``."""

from __future__ import annotations

import rust_data_processing as rdp

from tests.pipeline_fixture_support import load_schema_fields, pipeline_transform_sql


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
