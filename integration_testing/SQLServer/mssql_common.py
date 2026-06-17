"""Shared SQL Server helpers for integration tests (library module — not a CLI entrypoint)."""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path

MSSQL_DIR = Path(__file__).resolve().parent
INTEG_ROOT = MSSQL_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402

MSSQL_CONTAINER = "rdp-mssql-test"


def load_mssql_env() -> None:
    for env_file in (MSSQL_DIR / ".env", MSSQL_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
            break
    if not os.environ.get("MSSQL_CONNECT_URL"):
        die("Set MSSQL_CONNECT_URL in SQLServer/.env")


def strip_mssql_url(url: str) -> str:
    """Return URL unchanged — TDS sink and ConnectorX both need encrypt/trust query params."""
    return url


def isolate_docker_for_mssql() -> None:
    """Stop other containers and prune unused Docker resources before starting SQL Server."""
    log("Isolating Docker for SQL Server (stop other containers, prune unused resources)...")

    for other in ("Oracle", "PostgreSQL"):
        compose = INTEG_ROOT / other / "docker-compose.yml"
        if compose.is_file():
            subprocess.run(
                docker_command(["compose", "-f", str(compose), "down"]),
                cwd=INTEG_ROOT / other,
                capture_output=True,
            )

    subprocess.run(
        docker_command(["compose", "-f", str(MSSQL_DIR / "docker-compose.yml"), "down"]),
        cwd=MSSQL_DIR,
        capture_output=True,
    )

    ps = subprocess.run(
        docker_command(["ps", "-q"]),
        capture_output=True,
        text=True,
    )
    if ps.returncode == 0 and ps.stdout.strip():
        for cid in ps.stdout.split():
            cid = cid.strip()
            if not cid:
                continue
            inspect = subprocess.run(
                docker_command(["inspect", "-f", "{{.Name}}", cid]),
                capture_output=True,
                text=True,
            )
            name = (inspect.stdout or "").strip().lstrip("/")
            if name == MSSQL_CONTAINER:
                continue
            log(f"  stopping container {name or cid}")
            subprocess.run(docker_command(["stop", cid]), capture_output=True)

    subprocess.run(docker_command(["container", "prune", "-f"]), capture_output=True)
    subprocess.run(docker_command(["network", "prune", "-f"]), capture_output=True)
    subprocess.run(docker_command(["image", "prune", "-f"]), capture_output=True)


def wait_for_mssql(attempts: int = 60) -> None:
    log("Waiting for SQL Server container health...")
    compose = MSSQL_DIR / "docker-compose.yml"
    sa_password = os.environ.get("MSSQL_SA_PASSWORD", "Rdp_test_sa1!")
    for _ in range(attempts):
        health = subprocess.run(
            docker_command(
                [
                    "compose",
                    "-f",
                    str(compose),
                    "exec",
                    "-T",
                    "mssql",
                    "/opt/mssql-tools18/bin/sqlcmd",
                    "-S",
                    "localhost",
                    "-U",
                    "sa",
                    "-P",
                    sa_password,
                    "-C",
                    "-Q",
                    "SELECT 1",
                ]
            ),
            capture_output=True,
        )
        if health.returncode == 0:
            init = MSSQL_DIR / "init" / "01-create-db.sql"
            if init.is_file():
                subprocess.run(
                    docker_command(
                        [
                            "compose",
                            "-f",
                            str(compose),
                            "exec",
                            "-T",
                            "mssql",
                            "/opt/mssql-tools18/bin/sqlcmd",
                            "-S",
                            "localhost",
                            "-U",
                            "sa",
                            "-P",
                            sa_password,
                            "-C",
                            "-i",
                            "/docker-entrypoint-initdb.d/01-create-db.sql",
                        ]
                    ),
                    capture_output=True,
                )
            log("SQL Server ready.")
            return
        time.sleep(3)
    die(f"SQL Server container not healthy after {attempts} attempts")


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
