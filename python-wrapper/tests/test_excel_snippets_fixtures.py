"""Parity with ``docs/java/examples/ExcelSnippets.java`` and ``tests/fixtures/people/``."""

from __future__ import annotations

import json

import pytest
import rust_data_processing as rdp

from tests.conftest import fixture_path
from tests.pipeline_fixture_support import load_schema_fields, resolve_payload_json

DEFAULT_SHEET = "Sheet1"
PEOPLE_XLSX = fixture_path("people.xlsx")


def _require_people_xlsx() -> None:
    if not PEOPLE_XLSX.is_file():
        pytest.skip(
            "missing tests/fixtures/people.xlsx — python scripts/write_people_xlsx_stdlib.py"
        )


def test_people_excel_sheet_dataset_payload_resolves() -> None:
    _require_people_xlsx()
    payload = json.loads(
        resolve_payload_json(
            "people",
            "payloads/excel_sheet_dataset.payload.json",
            {
                "SOURCE_PATH": str(PEOPLE_XLSX.resolve()),
                "SHEET_NAME": DEFAULT_SHEET,
            },
        )
    )
    assert payload["options"]["format"] == "excel"
    assert payload["options"]["sheet_name"] == DEFAULT_SHEET
    assert "schema" in payload


def test_people_excel_ordered_ingest_via_resolved_payload() -> None:
    """``excelIngestViaPayload`` → ``rdp_ingest_ordered_paths_json``."""
    _require_people_xlsx()
    payload = json.loads(
        resolve_payload_json(
            "people",
            "payloads/excel_sheet_dataset.payload.json",
            {
                "SOURCE_PATH": str(PEOPLE_XLSX.resolve()),
                "SHEET_NAME": DEFAULT_SHEET,
            },
        )
    )
    schema = [
        {"name": f["name"], "data_type": f["data_type"].lower()}
        for f in payload["schema"]["fields"]
    ]
    ds, _meta = rdp.ingest_from_ordered_paths(payload["paths"], schema, payload["options"])
    assert ds.row_count() == 2
    assert ds.to_rows()[0] == [1, "Ada", 98.5, True]


def test_people_excel_path_ingest_sheet1_matches_doc_example() -> None:
    """Python sketch in ``ExcelSnippets`` javadoc."""
    _require_people_xlsx()
    schema = load_schema_fields("schemas", "people_flat.schema.json", bundle="people")
    ds = rdp.ingest_from_path(
        str(PEOPLE_XLSX),
        schema,
        {"format": "excel", "sheet_name": DEFAULT_SHEET},
    )
    assert ds.row_count() == 2
    assert ds.to_rows()[0][1] == "Ada"


def test_people_excel_inferred_schema_matches_path_sheet_ffi() -> None:
    """``excelIngestPathSheet`` / ``rdp_excel_ingest_path_sheet`` (schema inferred in Rust)."""
    _require_people_xlsx()
    ds, _schema = rdp.ingest_with_inferred_schema(
        str(PEOPLE_XLSX),
        {"format": "excel", "sheet_name": DEFAULT_SHEET},
    )
    assert ds.row_count() == 2
    assert ds.to_rows()[0][0] == 1
