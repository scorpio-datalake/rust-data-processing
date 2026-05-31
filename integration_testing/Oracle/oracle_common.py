"""Shared Oracle helpers for integration tests (library module — not a CLI entrypoint)."""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

ORACLE_DIR = Path(__file__).resolve().parent
INTEG_ROOT = ORACLE_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402


def load_oracle_env() -> None:
    for env_file in (ORACLE_DIR / ".env", ORACLE_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
            break
    if not os.environ.get("ORACLE_CONNECT_URL"):
        die("Set ORACLE_CONNECT_URL in Oracle/.env")


def wait_for_oracle(attempts: int = 60) -> None:
    log("Waiting for Oracle container health...")
    compose = ORACLE_DIR / "docker-compose.yml"
    for _ in range(attempts):
        ps = subprocess.run(
            docker_command(["compose", "-f", str(compose), "ps", "--status", "running"]),
            capture_output=True,
            text=True,
        )
        if ps.returncode == 0 and "oracle" in ps.stdout:
            health = subprocess.run(
                docker_command(
                    [
                        "compose",
                        "-f",
                        str(compose),
                        "exec",
                        "-T",
                        "oracle",
                        "healthcheck.sh",
                    ]
                ),
                capture_output=True,
            )
            if health.returncode == 0:
                log("Oracle ready.")
                return
        time.sleep(5)
    die(f"Oracle container not healthy after {attempts} attempts")


def pick_uber_csv() -> Path:
    sample = DATA_DIR / "uber_nyc_pickups_sample.csv"
    full = DATA_DIR / "uber_nyc_pickups_apr2014.csv"
    if sample.is_file():
        return sample
    if full.is_file():
        return full
    die("Uber CSV missing — run integration_testing/scripts/data_download/download_uber_data.py")


def expected_csv_rows(csv: Path) -> int:
    return count_lines(csv) - 1
