"""Shared Databricks integration helpers (S3 warehouse on MinIO Docker)."""

from __future__ import annotations

import os
import sys
from pathlib import Path

DATABRICKS_DIR = Path(__file__).resolve().parent
INTEG_ROOT = DATABRICKS_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, log  # noqa: E402
from minio_common import databricks_warehouse_uri, load_minio_env, start_minio_stack  # noqa: E402


def load_databricks_env() -> None:
    for env_file in (DATABRICKS_DIR / ".env", DATABRICKS_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
    load_minio_env()


def prepare_staging() -> None:
    log(f"Databricks S3 warehouse: {databricks_warehouse_uri()}")


def pick_uber_csv() -> Path:
    sample = DATA_DIR / "uber_nyc_pickups_sample.csv"
    full = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    die("Uber CSV missing — run download_uber_data.py")


def expected_csv_rows(csv: Path) -> int:
    return count_lines(csv) - 1


__all__ = [
    "expected_csv_rows",
    "load_databricks_env",
    "pick_uber_csv",
    "prepare_staging",
    "start_minio_stack",
]
