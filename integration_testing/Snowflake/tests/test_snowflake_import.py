"""Snowflake integration: RDP s3 stage → SQL CREATE TABLE + COPY/LOAD → COUNT verify."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

_SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from platform_pipeline import import_csv_platform_pipeline, verify_platform_sql  # noqa: E402

INTEG_ROOT = Path(__file__).resolve().parent.parent.parent


def _require_integration() -> None:
    if os.environ.get("RUN_SNOWFLAKE_INTEGRATION") != "1":
        pytest.skip("set RUN_SNOWFLAKE_INTEGRATION=1 (via Snowflake/run_snowflake_tests.py)")


def _uber_csv() -> Path:
    sample = INTEG_ROOT / "data" / "uber_nyc_pickups_sample.csv"
    full = INTEG_ROOT / "data" / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    pytest.skip("Uber CSV missing")


@pytest.mark.integration
def test_python_snowflake_sql_roundtrip() -> None:
    _require_integration()
    max_rows = int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    inter = import_csv_platform_pipeline(
        connector="snowflake", csv_path=_uber_csv(), max_rows=max_rows
    )
    expected = int(inter["ingested_row_count"])
    assert expected > 0
    sink = next(s for s in inter["sink_results"] if s["kind"] == "snowflake")
    assert sink["status"] == "ok"
    assert sink["row_count"] == expected
    assert str(sink.get("stage_uri", "")).startswith("s3://")
    verify_platform_sql("snowflake", expected)
