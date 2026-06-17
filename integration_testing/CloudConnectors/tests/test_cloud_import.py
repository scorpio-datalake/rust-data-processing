"""Cloud storage integration: object_store roundtrip + file_transfer import."""

from __future__ import annotations

import os
import sys
from pathlib import Path

import pytest

_SCRIPTS = Path(__file__).resolve().parent.parent.parent / "scripts"
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from cloud_pipeline import (  # noqa: E402
    FILE_TRANSFER_PROTOCOLS,
    OBJECT_STORE_PROTOCOLS,
    verify_file_transfer_import,
    verify_object_store_roundtrip,
)

INTEG_ROOT = Path(__file__).resolve().parent.parent.parent


def _require_integration() -> None:
    if os.environ.get("RUN_CLOUD_INTEGRATION") != "1":
        pytest.skip("set RUN_CLOUD_INTEGRATION=1 (via CloudConnectors/run_cloud_tests.py)")


def _uber_csv() -> Path:
    sample = INTEG_ROOT / "data" / "uber_nyc_pickups_sample.csv"
    full = INTEG_ROOT / "data" / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    pytest.skip("Uber CSV missing")


@pytest.mark.integration
@pytest.mark.parametrize("protocol", OBJECT_STORE_PROTOCOLS)
def test_python_object_store_roundtrip(protocol: str) -> None:
    _require_integration()
    max_rows = int(os.environ.get("INTEG_MAX_IMPORT_ROWS", "500"))
    count = verify_object_store_roundtrip(
        protocol=protocol, csv_path=_uber_csv(), max_rows=max_rows
    )
    assert count > 0


@pytest.mark.integration
@pytest.mark.parametrize("protocol", FILE_TRANSFER_PROTOCOLS)
def test_python_file_transfer_import(protocol: str) -> None:
    _require_integration()
    count = verify_file_transfer_import(protocol=protocol)
    assert count > 0
