"""Shared Spark integration helpers (S3 handoff on MinIO + Spark standalone Docker)."""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

SPARK_DIR = Path(__file__).resolve().parent
INTEG_ROOT = SPARK_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402
from minio_common import load_minio_env, spark_handoff_uri, start_minio_stack  # noqa: E402

SPARK_MASTER_CONTAINER = "rdp-spark-master"


def load_spark_env() -> None:
    for env_file in (SPARK_DIR / ".env", SPARK_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
    load_minio_env()
    os.environ.setdefault("SPARK_MASTER_URL", "spark://127.0.0.1:7077")


def prepare_staging() -> None:
    log(f"Spark S3 handoff: {spark_handoff_uri()}")
    log(f"Spark master: {os.environ.get('SPARK_MASTER_URL')}")


def wait_for_spark_master(attempts: int = 60) -> None:
    log("Waiting for Spark standalone master (7077)...")
    port = int(os.environ.get("SPARK_MASTER_PORT", "7077"))
    host = os.environ.get("SPARK_MASTER_HOST", "127.0.0.1")
    ui_port = int(os.environ.get("SPARK_UI_PORT", "8080"))
    for _ in range(attempts):
        try:
            with socket.create_connection((host, port), timeout=2):
                ui = subprocess.run(
                    ["curl", "-sf", f"http://{host}:{ui_port}/"],
                    capture_output=True,
                )
                if ui.returncode == 0:
                    log("Spark master UI reachable.")
                    return
        except OSError:
            pass
        time.sleep(2)
    die("Spark master did not become ready — check docker compose logs in integration_testing/Spark")


def start_spark_stack(*, isolate: bool = True) -> None:
    from minio_common import isolate_docker_for_platform

    if isolate:
        isolate_docker_for_platform("Spark")
    log("Starting Spark + MinIO (docker compose)...")
    subprocess.run(docker_command(["compose", "up", "-d"]), cwd=SPARK_DIR, check=True)
    from minio_common import wait_for_minio

    wait_for_minio()
    wait_for_spark_master()


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
    "load_spark_env",
    "pick_uber_csv",
    "prepare_staging",
    "start_spark_stack",
    "wait_for_spark_master",
]
