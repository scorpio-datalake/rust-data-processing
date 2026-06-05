"""Kafka streaming integration: stream Uber CSV one row per message, poll verify."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

_SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from kafka_stream import verify_uber_kafka_stream  # noqa: E402

INTEG_ROOT = Path(__file__).resolve().parent.parent.parent


def _require_integration() -> None:
    if os.environ.get("RUN_KAFKA_INTEGRATION") != "1":
        pytest.skip("set RUN_KAFKA_INTEGRATION=1 (via Kafka/run_kafka_tests.py)")


def _uber_csv() -> Path:
    sample = INTEG_ROOT / "data" / "uber_nyc_pickups_sample.csv"
    full = INTEG_ROOT / "data" / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    pytest.skip("Uber CSV missing")


@pytest.mark.integration
def test_python_kafka_uber_stream() -> None:
    _require_integration()
    max_rows = int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    count = verify_uber_kafka_stream(_uber_csv(), max_rows=max_rows)
    assert count > 0
