"""Oracle integration: RDP pipeline CSV → Oracle sink → RDP verify (no python-oracledb)."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest
import rust_data_processing as rdp

_SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from rdp_pipeline import import_csv_pipeline  # noqa: E402
from schema_util import count_verify_query, count_verify_schema, connector_table, load_table_spec  # noqa: E402

INTEG_ROOT = Path(__file__).resolve().parent.parent.parent


def _require_integration() -> None:
    if os.environ.get("RUN_ORACLE_INTEGRATION") != "1":
        pytest.skip("set RUN_ORACLE_INTEGRATION=1 (via Oracle/run_oracle_tests.py)")


def _uber_csv() -> Path:
    sample = INTEG_ROOT / "data" / "uber_nyc_pickups_sample.csv"
    full = INTEG_ROOT / "data" / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    pytest.skip("Uber CSV missing — run download_uber_data.py")


def _max_rows() -> int:
    return int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))


def _verify_count_rdp(url: str, expected: int) -> int:
    table = connector_table("oracle")
    schema = [
        {"name": f["name"], "data_type": f["data_type"].lower()}
        for f in count_verify_schema("oracle")["fields"]
    ]
    ds = rdp.ingest_from_db(url, count_verify_query("oracle", table), schema, {})
    rows = ds.to_rows()
    assert rows, "COUNT(*) returned no rows"
    count = int(rows[0][0])
    assert count == expected, f"expected {expected} rows in {table}, got {count}"
    return count


@pytest.mark.integration
def test_python_oracle_import_roundtrip() -> None:
    _require_integration()
    url = os.environ["ORACLE_CONNECT_URL"]
    csv_path = _uber_csv()
    max_rows = _max_rows()

    inter = import_csv_pipeline(
        connector="oracle",
        csv_path=csv_path,
        connect_url=url,
        max_rows=max_rows,
    )
    expected = int(inter["ingested_row_count"])
    assert expected > 0

    sink = next(s for s in inter["sink_results"] if s["kind"] == "oracle")
    assert sink["row_count"] == expected

    _verify_count_rdp(url, expected)
