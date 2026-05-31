"""Oracle integration: RDP CSV ingest → load into Oracle XE → verify via ingest_from_db."""

from __future__ import annotations

import json
import os
from pathlib import Path

import pytest

import rust_data_processing as rdp

from oracle_load import load_dataset, reset_table

ORACLE_DIR = Path(__file__).resolve().parent.parent
INTEG_ROOT = ORACLE_DIR.parent
SCHEMA_PATH = ORACLE_DIR / "schema" / "uber_pickups.schema.json"


def _require_integration() -> None:
    if os.environ.get("RUN_ORACLE_INTEGRATION") != "1":
        pytest.skip("set RUN_ORACLE_INTEGRATION=1 (via Oracle/run_tests.py)")
    if not hasattr(rdp, "ingest_from_db"):
        pytest.skip("Python extension not built with --features db")


def _uber_csv() -> Path:
    sample = INTEGR_ROOT / "data" / "uber_nyc_pickups_sample.csv"
    full = INTEGR_ROOT / "data" / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    pytest.skip("Uber CSV missing — run download_uber_data.py")


def _load_schema() -> list[dict[str, str]]:
    raw = json.loads(SCHEMA_PATH.read_text(encoding="utf-8"))
    return [
        {"name": f["name"], "data_type": f["data_type"].lower()}
        for f in raw["fields"]
    ]


def _max_rows() -> int:
    return int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))


@pytest.mark.integration
def test_python_oracle_import_roundtrip() -> None:
    _require_integration()
    url = os.environ["ORACLE_CONNECT_URL"]
    csv_path = _uber_csv()
    schema = _load_schema()

    reset_table(url)

    ds = rdp.ingest_from_path(str(csv_path), schema, {"format": "csv"})
    max_rows = _max_rows()
    if ds.row_count() > max_rows:
        # Truncate in Python for faster integration runs (full file on Rust leg).
        rows = ds.to_rows()[:max_rows]
        ds = rdp.DataSet(schema, rows)

    expected = ds.row_count()
    assert expected > 0

    loaded = load_dataset(url, ds)
    assert loaded == expected

    verify_schema = [{"name": "cnt", "data_type": "int64"}]
    counted = rdp.ingest_from_db(url, "SELECT COUNT(*) AS cnt FROM UBER_PICKUPS", verify_schema)
    assert counted.row_count() == 1
    assert counted.to_rows()[0][0] == expected
