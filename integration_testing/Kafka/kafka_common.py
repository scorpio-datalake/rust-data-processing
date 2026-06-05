"""Shared Kafka streaming integration helpers."""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

KAFKA_DIR = Path(__file__).resolve().parent
INTEG_ROOT = KAFKA_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402


def load_kafka_env() -> None:
    for env_file in (KAFKA_DIR / ".env", KAFKA_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
    os.environ.setdefault("KAFKA_BROKERS", "127.0.0.1:19092")
    os.environ.setdefault("KAFKA_TOPIC", "rdp-uber-pickups")
    os.environ.setdefault("KAFKA_GROUP_ID", "rdp-integration-test")


def wait_for_kafka() -> None:
    host, _, port_str = os.environ["KAFKA_BROKERS"].partition(":")
    port = int(port_str or "9092")
    log(f"Waiting for Kafka broker ({host}:{port})...")
    for _ in range(60):
        try:
            with socket.create_connection((host, port), timeout=2):
                log("Kafka broker reachable.")
                time.sleep(2)
                return
        except OSError:
            time.sleep(1)
    die("Kafka broker did not become ready")


def start_kafka_stack(*, isolate: bool = True) -> None:
    from minio_common import isolate_docker_for_platform

    if isolate:
        isolate_docker_for_platform("Kafka")
    log("Starting Kafka (Redpanda docker compose)...")
    subprocess.run(docker_command(["compose", "up", "-d"]), cwd=KAFKA_DIR, check=True)
    wait_for_kafka()


def pick_uber_csv() -> Path:
    sample = DATA_DIR / "uber_nyc_pickups_sample.csv"
    full = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    die("Uber CSV missing — run download_uber_data.py --sample")


def expected_csv_rows(csv: Path) -> int:
    return count_lines(csv) - 1


__all__ = [
    "expected_csv_rows",
    "load_kafka_env",
    "pick_uber_csv",
    "start_kafka_stack",
    "wait_for_kafka",
]
