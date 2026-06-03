"""Shared PostgreSQL helpers for integration tests (library module — not a CLI entrypoint)."""

from __future__ import annotations

import os
import subprocess
import sys
import time
from pathlib import Path
from urllib.parse import urlparse, urlunparse

PG_DIR = Path(__file__).resolve().parent
INTEG_ROOT = PG_DIR.parent
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import DATA_DIR, count_lines, die, docker_command, log  # noqa: E402

PG_CONTAINER = "rdp-postgres-test"


def load_postgresql_env() -> None:
    for env_file in (PG_DIR / ".env", PG_DIR / ".env.example"):
        if env_file.is_file():
            for line in env_file.read_text(encoding="utf-8").splitlines():
                line = line.strip()
                if not line or line.startswith("#") or "=" not in line:
                    continue
                key, _, val = line.partition("=")
                os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))
            break
    if not os.environ.get("POSTGRES_CONNECT_URL"):
        die("Set POSTGRES_CONNECT_URL in PostgreSQL/.env")


def strip_url_query_for_libpq(url: str) -> str:
    """Strip ConnectorX-only query params (e.g. ``?cxprotocol=binary``) from URLs."""
    parsed = urlparse(url)
    if parsed.scheme not in ("postgresql", "postgres"):
        return url
    return urlunparse(parsed._replace(query="", fragment=""))


def isolate_docker_for_postgres() -> None:
    """Stop other containers and prune unused Docker resources before starting PostgreSQL."""
    log("Isolating Docker for PostgreSQL (stop other containers, prune unused resources)...")

    oracle_compose = INTEG_ROOT / "Oracle" / "docker-compose.yml"
    if oracle_compose.is_file():
        subprocess.run(
            docker_command(["compose", "-f", str(oracle_compose), "down"]),
            cwd=INTEG_ROOT / "Oracle",
            capture_output=True,
        )

    subprocess.run(
        docker_command(["compose", "-f", str(PG_DIR / "docker-compose.yml"), "down"]),
        cwd=PG_DIR,
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
            if name == PG_CONTAINER:
                continue
            log(f"  stopping container {name or cid}")
            subprocess.run(docker_command(["stop", cid]), capture_output=True)

    subprocess.run(docker_command(["container", "prune", "-f"]), capture_output=True)
    subprocess.run(docker_command(["network", "prune", "-f"]), capture_output=True)
    subprocess.run(docker_command(["image", "prune", "-f"]), capture_output=True)


def wait_for_postgres(attempts: int = 60) -> None:
    log("Waiting for PostgreSQL container health...")
    compose = PG_DIR / "docker-compose.yml"
    for _ in range(attempts):
        ps = subprocess.run(
            docker_command(["compose", "-f", str(compose), "ps", "--status", "running"]),
            capture_output=True,
            text=True,
        )
        if ps.returncode == 0 and "postgres" in ps.stdout:
            health = subprocess.run(
                docker_command(
                    [
                        "compose",
                        "-f",
                        str(compose),
                        "exec",
                        "-T",
                        "postgres",
                        "pg_isready",
                        "-U",
                        os.environ.get("POSTGRES_APP_USER", "etl_user"),
                        "-d",
                        os.environ.get("POSTGRES_DB", "rdp_test"),
                    ]
                ),
                capture_output=True,
            )
            if health.returncode == 0:
                log("PostgreSQL ready.")
                return
        time.sleep(3)
    die(f"PostgreSQL container not healthy after {attempts} attempts")


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
