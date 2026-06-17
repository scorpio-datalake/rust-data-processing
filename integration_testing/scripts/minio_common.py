"""MinIO / S3 helpers for Snowflake, Databricks, and Spark platform integration tests."""

from __future__ import annotations

import os
import socket
import subprocess
import sys
import time
from pathlib import Path

INTEG_ROOT = Path(__file__).resolve().parent.parent
MINIO_DIR = INTEG_ROOT / "MinIO"
_SCRIPTS = INTEG_ROOT / "scripts"

if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from common import die, docker_command, log  # noqa: E402

MINIO_CONTAINER = "rdp-minio-test"
MINIO_COMPOSE = MINIO_DIR / "docker-compose.yml"

PLATFORM_COMPOSE_DIRS = ("MinIO", "Snowflake", "Databricks", "Spark")


def _load_env_file(path: Path) -> None:
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        os.environ.setdefault(key.strip(), val.strip().strip('"').strip("'"))


def load_minio_env() -> None:
    """Load MinIO/.env (or .env.example) and set connector S3 URIs + AWS_* for object_store."""
    for env_file in (MINIO_DIR / ".env", MINIO_DIR / ".env.example"):
        _load_env_file(env_file)

    user = os.environ.get("MINIO_ROOT_USER", "rdp_minio")
    password = os.environ.get("MINIO_ROOT_PASSWORD", "rdp_minio_secret")
    endpoint = os.environ.get("AWS_ENDPOINT", "http://127.0.0.1:9000").rstrip("/")
    # Assign (not setdefault) so empty cloud-shell exports cannot block MinIO static creds.
    os.environ["AWS_ACCESS_KEY_ID"] = os.environ.get("AWS_ACCESS_KEY_ID") or user
    os.environ["AWS_SECRET_ACCESS_KEY"] = os.environ.get("AWS_SECRET_ACCESS_KEY") or password
    os.environ["AWS_ENDPOINT"] = endpoint
    os.environ["AWS_ENDPOINT_URL"] = endpoint
    os.environ["AWS_ALLOW_HTTP"] = "true"
    os.environ["AWS_DEFAULT_REGION"] = os.environ.get("AWS_DEFAULT_REGION", "us-east-1")
    # Path-style requests for MinIO (object_store AWS builder).
    os.environ["AWS_VIRTUAL_HOSTED_STYLE_REQUEST"] = "false"

    os.environ.setdefault("SNOWFLAKE_STAGE_URI", "s3://rdp-snowflake-stage/rdp/")
    os.environ.setdefault("DATABRICKS_WAREHOUSE_URI", "s3://rdp-databricks-warehouse/unity/")
    os.environ.setdefault("SPARK_HANDOFF_URI", "s3://rdp-spark-handoff/out.parquet")
    # Hostname reachable from Snowflake emulator / Spark containers on rdp-platform-net.
    os.environ.setdefault("MINIO_HTTP_HOST", "minio")

    for key in ("SNOWFLAKE_STAGE_URI", "DATABRICKS_WAREHOUSE_URI", "SPARK_HANDOFF_URI"):
        if not os.environ.get(key, "").startswith("s3://"):
            die(f"{key} must be an s3:// URI when using MinIO Docker")


def snowflake_stage_uri() -> str:
    uri = os.environ.get("SNOWFLAKE_STAGE_URI", "")
    if not uri.startswith("s3://"):
        die("SNOWFLAKE_STAGE_URI not set — start MinIO via connector run_*_tests.py")
    return uri if uri.endswith("/") else f"{uri}/"


def snowflake_stage_parquet_uri() -> str:
    stage = snowflake_stage_uri()
    return f"{stage}load.parquet"


def databricks_warehouse_uri() -> str:
    uri = os.environ.get("DATABRICKS_WAREHOUSE_URI", "")
    if not uri.startswith("s3://"):
        die("DATABRICKS_WAREHOUSE_URI not set — start MinIO via connector run_*_tests.py")
    return uri if uri.endswith("/") else f"{uri}/"


def delta_table_uri(warehouse: str, namespace: str | None, table: str) -> str:
    base = warehouse.rstrip("/")
    table_path = table.lstrip("/")
    if namespace:
        ns_path = namespace.replace(".", "/")
        return f"{base}/{ns_path}/{table_path}/"
    return f"{base}/{table_path}/"


def databricks_output_parquet_uri(namespace: str, table: str) -> str:
    wh = databricks_warehouse_uri()
    table_uri = delta_table_uri(wh, namespace, table)
    return f"{table_uri}part-rdp-000.parquet"


def spark_handoff_uri() -> str:
    uri = os.environ.get("SPARK_HANDOFF_URI", "")
    if not uri.startswith("s3://"):
        die("SPARK_HANDOFF_URI not set — start MinIO via connector run_*_tests.py")
    return uri


def isolate_docker_for_platform(active: str) -> None:
    """Stop other platform stacks and DB connector compose before starting."""
    log(f"Isolating Docker for platform connector ({active})...")
    for name in PLATFORM_COMPOSE_DIRS:
        if name == active:
            continue
        compose = INTEG_ROOT / name / "docker-compose.yml"
        if compose.is_file():
            subprocess.run(
                docker_command(["compose", "-f", str(compose), "down"]),
                cwd=INTEG_ROOT / name,
                capture_output=True,
            )
    for other in ("Oracle", "PostgreSQL", "SQLServer"):
        compose = INTEG_ROOT / other / "docker-compose.yml"
        if compose.is_file():
            subprocess.run(
                docker_command(["compose", "-f", str(compose), "down"]),
                cwd=INTEG_ROOT / other,
                capture_output=True,
            )


def wait_for_minio(attempts: int = 60) -> None:
    log("Waiting for MinIO (S3 API on host port)...")
    port = int(os.environ.get("MINIO_PORT", "9000"))
    host = os.environ.get("MINIO_HOST", "127.0.0.1")
    health_url = f"http://{host}:{port}/minio/health/live"
    for _ in range(attempts):
        try:
            with socket.create_connection((host, port), timeout=2):
                curl = subprocess.run(
                    ["curl", "-sf", health_url],
                    capture_output=True,
                )
                if curl.returncode != 0:
                    time.sleep(2)
                    continue
                init = subprocess.run(
                    docker_command(
                        [
                            "inspect",
                            "-f",
                            "{{.State.Status}}",
                            "rdp-minio-init",
                        ]
                    ),
                    capture_output=True,
                    text=True,
                )
                status = (init.stdout or "").strip()
                if init.returncode == 0 and status in ("exited", "running"):
                    log("MinIO healthy and buckets initialized.")
                    return
        except OSError:
            pass
        time.sleep(2)
    die("MinIO did not become ready — check docker compose logs in integration_testing/MinIO")


def start_minio_stack(*, isolate: bool = True, compose_dir: Path | None = None) -> None:
    """Bring up MinIO (and included services) from ``compose_dir`` or ``MinIO/``."""
    cwd = compose_dir or MINIO_DIR
    compose_file = cwd / "docker-compose.yml"
    if not compose_file.is_file():
        die(f"missing docker-compose.yml under {cwd}")
    if isolate:
        isolate_docker_for_platform(cwd.name)
    log(f"Starting MinIO stack (docker compose in {cwd})...")
    subprocess.run(
        docker_command(["compose", "-f", str(compose_file), "up", "-d"]),
        cwd=cwd,
        check=True,
    )
    wait_for_minio()
    log(f"Snowflake stage: {snowflake_stage_uri()}")
    log(f"Databricks warehouse: {databricks_warehouse_uri()}")
    log(f"Spark handoff: {spark_handoff_uri()}")
