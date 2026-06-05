"""Shared Snowflake integration helpers (S3 stage on MinIO Docker)."""

from __future__ import annotations

import os
import sys
from pathlib import Path

SNOWFLAKE_DIR = Path(__file__).resolve().parent
INTEG_ROOT = SNOWFLAKE_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

import socket
import time

from common import DATA_DIR, count_lines, die, log  # noqa: E402
from minio_common import load_minio_env, snowflake_stage_uri, start_minio_stack  # noqa: E402


def wait_for_snowflake_emulator(attempts: int = 60) -> None:
    log("Waiting for Snowflake emulator...")
    host = os.environ.get("SNOWFLAKE_HOST", "127.0.0.1")
    port = int(os.environ.get("SNOWFLAKE_PORT", "8080"))
    for _ in range(attempts):
        try:
            with socket.create_connection((host, port), timeout=2):
                log("Snowflake emulator port open.")
                return
        except OSError:
            pass
        time.sleep(2)
    die("Snowflake emulator did not become ready")


def load_snowflake_env() -> None:
    for env_file in (SNOWFLAKE_DIR / ".env", SNOWFLAKE_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
    load_minio_env()


def prepare_staging() -> None:
    log(f"Snowflake S3 stage: {snowflake_stage_uri()}")


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
    "load_snowflake_env",
    "pick_uber_csv",
    "prepare_staging",
    "start_minio_stack",
]
