"""Parity with ``docs/java/examples/JsonParquetExcelSnippets.java`` and ``tests/fixtures/people/``."""

from __future__ import annotations

import json

import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import load_schema_fields, resolve_payload_json


def test_people_json_path_dataset_payload_resolves() -> None:
    payload = json.loads(
        resolve_payload_json(
            "people",
            "payloads/json_path_dataset.payload.json",
            {"SOURCE_PATH": str(fixture_path("people.json").resolve())},
        )
    )
    assert payload["options"]["format"] == "json"
    assert "schema" in payload
    assert payload["response"]["mode"] == "dataset"


def test_people_csv_path_dataset_payload_resolves() -> None:
    payload = json.loads(
        resolve_payload_json(
            "people",
            "payloads/csv_path_dataset.payload.json",
            {"SOURCE_PATH": str(fixture_path("people.csv").resolve())},
        )
    )
    assert payload["options"]["format"] == "csv"
    assert "schema" in payload


def test_people_json_ordered_ingest_via_resolved_payload() -> None:
    payload = json.loads(
        resolve_payload_json(
            "people",
            "payloads/json_path_dataset.payload.json",
            {"SOURCE_PATH": str(fixture_path("people.json").resolve())},
        )
    )
    schema = [
        {"name": f["name"], "data_type": f["data_type"].lower()}
        for f in payload["schema"]["fields"]
    ]
    ds, _meta = rdp.ingest_from_ordered_paths(
        payload["paths"], schema, payload["options"]
    )
    assert ds.row_count() == 2
    assert ds.to_rows()[0][1] == "Ada"


def test_people_csv_ordered_ingest_via_resolved_payload() -> None:
    payload = json.loads(
        resolve_payload_json(
            "people",
            "payloads/csv_path_dataset.payload.json",
            {"SOURCE_PATH": str(fixture_path("people.csv").resolve())},
        )
    )
    schema = [
        {"name": f["name"], "data_type": f["data_type"].lower()}
        for f in payload["schema"]["fields"]
    ]
    ds, _meta = rdp.ingest_from_ordered_paths(
        payload["paths"], schema, payload["options"]
    )
    assert ds.row_count() == 2
    assert ds.to_rows()[0] == [1, "Ada", 98.5, True]


def test_people_json_path_ingest_matches_doc_example() -> None:
    schema = load_schema_fields("schemas", "people_json.schema.json", bundle="people")
    ds = rdp.ingest_from_path(fixture_path("people.json"), schema, {"format": "json"})
    assert ds.row_count() == 2
    assert ds.to_rows()[0][0] == 1


def test_people_csv_path_ingest_matches_doc_example() -> None:
    schema = load_schema_fields("schemas", "people_csv.schema.json", bundle="people")
    ds = rdp.ingest_from_path(fixture_path("people.csv"), schema, {"format": "csv"})
    assert ds.row_count() == 2
    assert ds.to_rows()[0][1] == "Ada"
