"""Parity with ``docs/java/examples/RDPOnlyETLExample.java`` and ``tests/fixtures/student_etl/``."""

from __future__ import annotations

import json

import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import bundle_root, load_schema_fields, resolve_payload_json


def test_student_etl_schemas_load() -> None:
    student = load_schema_fields("schemas", "student_source.schema.json", bundle="student_etl")
    assert student[0]["name"] == "student_id"
    lake = load_schema_fields("schemas", "lake_grade_stats.schema.json", bundle="student_etl")
    assert len(lake) >= 1
    pg = load_schema_fields("schemas", "postgres_courses.schema.json", bundle="student_etl")
    assert len(pg) >= 1


def test_student_etl_s3_paths_sketch_loads() -> None:
    paths = json.loads(
        (bundle_root("student_etl") / "data" / "example_s3_json_source_paths.json").read_text(
            encoding="utf-8"
        )
    )
    assert len(paths) == 3
    assert paths[0].startswith("s3://")


def test_student_etl_ordered_ingest_two_committed_parts() -> None:
    """``ordered_ingest_dataset_2paths.payload.json`` + ``data/part-0000*.json`` (RDPOnlyETLExample)."""
    schema = load_schema_fields("schemas", "student_source.schema.json", bundle="student_etl")
    p0 = fixture_path("student_etl", "data", "part-00000.json")
    p1 = fixture_path("student_etl", "data", "part-00001.json")
    ds, meta = rdp.ingest_from_ordered_paths(
        [str(p0), str(p1)],
        schema,
        {"format": "json"},
    )
    assert ds.row_count() == 2
    assert len(meta.paths) == 2
    assert ds.to_rows()[0][0] == 1
    assert ds.to_rows()[1][0] == 2


def test_student_etl_ordered_payload_template_resolves() -> None:
    p0 = fixture_path("student_etl", "data", "part-00000.json")
    p1 = fixture_path("student_etl", "data", "part-00001.json")
    payload = json.loads(
        resolve_payload_json(
            "student_etl",
            "payloads/ordered_ingest_dataset_2paths.payload.json",
            {
                "PATH_A": str(p0.resolve()),
                "PATH_B": str(p1.resolve()),
            },
        )
    )
    assert len(payload["paths"]) == 2
    assert "schema" in payload
    assert payload["response"]["mode"] == "dataset"
