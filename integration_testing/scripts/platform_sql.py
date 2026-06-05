#!/usr/bin/env python3
"""SQL load + COUNT verify for Snowflake / Databricks / Spark platform connector tests.

After RDP writes Parquet to MinIO (s3://), this module runs warehouse SQL:
  - Snowflake: REST API v2 against local snowflake-emulator (CREATE TABLE, READ_PARQUET, COUNT)
  - Databricks / Spark: spark-sql CREATE TABLE + SELECT COUNT(*) via Docker
"""

from __future__ import annotations

import argparse
import json
import os
import subprocess
import sys
import urllib.error
import urllib.request
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

_SCRIPTS = Path(__file__).resolve().parent
_INTEG = _SCRIPTS.parent
if str(_SCRIPTS) not in sys.path:
    sys.path.insert(0, str(_SCRIPTS))

from minio_common import (  # noqa: E402
    databricks_output_parquet_uri,
    databricks_warehouse_uri,
    load_minio_env,
    snowflake_stage_parquet_uri,
)
from schema_util import load_table_spec  # noqa: E402

_EMULATOR_OK_CODES = frozenset({"090001", "00000"})


def _die(msg: str, code: int = 1) -> None:
    print(f"[platform_sql] ERROR: {msg}", file=sys.stderr, flush=True)
    raise SystemExit(code)


def _log(msg: str) -> None:
    print(f"[platform_sql] {msg}", flush=True)


def _emulator_stage_file(s3_uri: str) -> str:
    """Path inside snowflake-emulator container (STAGE_DIR) mirroring the object key."""
    parsed = urlparse(s3_uri)
    if parsed.scheme != "s3" or not parsed.netloc:
        _die(f"expected s3:// URI, got {s3_uri}")
    key = parsed.path.lstrip("/")
    # /data/stages is root-owned in the image; /tmp is writable for docker exec -i.
    stage_root = os.environ.get("SNOWFLAKE_EMULATOR_STAGE_DIR", "/tmp/rdp-stage")
    return f"{stage_root.rstrip('/')}/{key}"


def _copy_parquet_to_emulator_stage(s3_uri: str) -> str:
    """Copy staged Parquet from MinIO into the emulator container for READ_PARQUET."""
    parsed = urlparse(s3_uri)
    bucket = parsed.netloc
    key = parsed.path.lstrip("/")
    dest = _emulator_stage_file(s3_uri)
    minio_c = os.environ.get("MINIO_CONTAINER", "rdp-minio-test")
    sf_c = os.environ.get("SNOWFLAKE_EMULATOR_CONTAINER", "rdp-snowflake-emulator")
    user = os.environ.get("MINIO_ROOT_USER", "rdp_minio")
    password = os.environ.get("MINIO_ROOT_PASSWORD", "rdp_minio_secret")
    remote = f"local/{bucket}/{key}"
    mkdir = f"mkdir -p $(dirname {dest})"
    copy_in = f"cat > {dest}"
    mc_cat = (
        f"mc alias set local http://localhost:9000 {user} {password} >/dev/null 2>&1; "
        f"mc cat {remote}"
    )
    _log(f"Copying {s3_uri} into {sf_c}:{dest}")
    proc = subprocess.run(
        [
            "docker",
            "exec",
            minio_c,
            "sh",
            "-c",
            mc_cat,
        ],
        capture_output=True,
    )
    if proc.returncode != 0:
        _die(
            f"mc cat from MinIO failed ({proc.returncode}): "
            f"{(proc.stderr or proc.stdout).decode(errors='replace')}"
        )
    write = subprocess.run(
        ["docker", "exec", "-i", sf_c, "sh", "-c", f"{mkdir} && {copy_in}"],
        input=proc.stdout,
    )
    if write.returncode != 0:
        _die(f"failed to write {dest} in {sf_c} (exit {write.returncode})")
    return dest


def _snowflake_emulator_base() -> str:
    host = os.environ.get("SNOWFLAKE_HOST", "127.0.0.1")
    port = os.environ.get("SNOWFLAKE_PORT", "8080")
    protocol = os.environ.get("SNOWFLAKE_PROTOCOL", "http")
    return f"{protocol}://{host}:{port}"


def _emulator_http(
    method: str,
    path: str,
    *,
    body: dict[str, Any] | None = None,
    timeout: int | None = None,
) -> Any:
    url = f"{_snowflake_emulator_base()}{path}"
    data = None
    headers = {"Accept": "application/json"}
    if body is not None:
        data = json.dumps(body).encode("utf-8")
        headers["Content-Type"] = "application/json"
    req = urllib.request.Request(url, data=data, headers=headers, method=method)
    timeout = timeout or int(os.environ.get("SNOWFLAKE_SQL_TIMEOUT", "180"))
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read()
            return json.loads(raw) if raw else {}
    except urllib.error.HTTPError as exc:
        detail = exc.read().decode("utf-8", errors="replace")
        _die(f"Snowflake emulator HTTP {exc.code} {method} {path}: {detail}")


def _emulator_list_names(path: str, key: str = "name") -> set[str]:
    payload = _emulator_http("GET", path)
    if isinstance(payload, list):
        return {str(item.get(key, item.get("database_name", ""))) for item in payload}
    if isinstance(payload, dict):
        items = payload.get("data") or payload.get("databases") or payload.get("schemas") or []
        return {str(item.get(key, "")) for item in items if isinstance(item, dict)}
    return set()


def _ensure_emulator_database(database: str) -> None:
    existing = _emulator_list_names("/api/v2/databases")
    if database in existing:
        return
    _emulator_http("POST", "/api/v2/databases", body={"name": database})
    _log(f"Snowflake emulator: created database {database}")


def _ensure_emulator_schema(database: str, schema: str) -> None:
    path = f"/api/v2/databases/{database}/schemas"
    existing = _emulator_list_names(path)
    if schema in existing:
        return
    _emulator_http("POST", path, body={"name": schema})
    _log(f"Snowflake emulator: created schema {database}.{schema}")


def _snowflake_emulator_sql(
    statement: str,
    database: str,
    schema: str,
    *,
    allow_error: bool = False,
) -> dict[str, Any] | None:
    """Submit SQL via snowflake-emulator REST API v2 (no snowflake-connector-python)."""
    result = _emulator_http(
        "POST",
        "/api/v2/statements",
        body={"statement": statement, "database": database, "schema": schema},
    )
    code = str(result.get("code", ""))
    if code not in _EMULATOR_OK_CODES:
        if allow_error:
            _log(f"Snowflake emulator SQL skipped ({code}): {result.get('message', result)}")
            return None
        _die(
            f"Snowflake emulator SQL failed ({code}): "
            f"{result.get('message', result)}"
        )
    return result


def _snowflake_emulator_scalar(statement: str, database: str, schema: str) -> int:
    result = _snowflake_emulator_sql(statement, database, schema)
    rows = result.get("data") or []
    if not rows:
        _die(f"Snowflake emulator returned no rows for: {statement}")
    row = rows[0]
    if isinstance(row, list):
        return int(row[0])
    if isinstance(row, dict):
        for val in row.values():
            return int(val)
    return int(row)


def snowflake_create_load_count(expected: int) -> int:
    """CREATE TABLE, load staged Parquet into Snowflake emulator, verify row count."""
    spec = load_table_spec()
    sf = spec["connectors"]["snowflake"]
    table = sf["table"]
    database = os.environ.get("SNOWFLAKE_DATABASE", sf["database"])
    schema = os.environ.get("SNOWFLAKE_SCHEMA", sf["schema"])
    stage_parquet = snowflake_stage_parquet_uri()
    qualified = f"{database}.{schema}.{table}"

    if database != "TEST_DB":
        _ensure_emulator_database(database)
    _ensure_emulator_schema(database, schema)

    stage_file = _copy_parquet_to_emulator_stage(stage_parquet)
    # REST v2 sets database/schema on the request; do not prefix TEST_DB in SQL text.
    _snowflake_emulator_sql(
        f"CREATE OR REPLACE TABLE {table} AS "
        f"SELECT * FROM READ_PARQUET('{stage_file}')",
        database,
        schema,
    )
    _log(f"Snowflake load via READ_PARQUET({stage_file})")

    count = _snowflake_emulator_scalar(f"SELECT COUNT(*) FROM {table}", database, schema)
    if count != expected:
        _die(f"Snowflake {qualified}: expected {expected} rows, got {count}")
    _log(f"Snowflake table {qualified}: {count} rows")
    return count


def _s3a_path(s3_uri: str) -> str:
    return "s3a://" + urlparse(s3_uri).netloc + urlparse(s3_uri).path


def _render_spark_sql(connector: str, expected: int) -> str:
    spec = load_table_spec()
    if connector == "databricks":
        db = spec["connectors"]["databricks"]
        table = db["table"]
        parquet_uri = databricks_output_parquet_uri(db["namespace"], db["table"])
        # Directory containing part-rdp-000.parquet
        parent = urlparse(parquet_uri).path.rsplit("/", 1)[0]
        s3a = _s3a_path(f"s3://{urlparse(parquet_uri).netloc}{parent}")
        return f"""
DROP TABLE IF EXISTS {table};
CREATE TABLE {table}
USING PARQUET
LOCATION '{s3a}';
SELECT COUNT(*) AS cnt FROM {table};
""".strip()
    if connector == "spark":
        sp = spec["connectors"]["spark"]
        table = sp["table"]
        handoff = os.environ.get("SPARK_HANDOFF_URI", "s3://rdp-spark-handoff/out.parquet")
        s3a = _s3a_path(handoff)
        return f"""
DROP TABLE IF EXISTS {table};
CREATE TABLE {table}
USING PARQUET
OPTIONS (path '{s3a}');
SELECT COUNT(*) AS cnt FROM {table};
""".strip()
    _die(f"unknown spark connector: {connector}")


def spark_sql_create_load_count(connector: str, expected: int) -> int:
    """Run spark-sql in Docker to register Parquet as a table and COUNT rows."""
    compose_dir = _INTEG / "Spark"
    compose = compose_dir / "docker-compose.yml"
    if not compose.is_file():
        _die(f"missing {compose}")

    sql = _render_spark_sql(connector, expected)
    # spark-sql runs inside spark-master on the compose network. Host SPARK_MASTER_URL
    # (spark://127.0.0.1:7077) is for VM-side clients; the master binds to the container
    # hostname, so loopback from exec fails with "Connection refused".
    master = os.environ.get("SPARK_MASTER_URL_DOCKER", "spark://spark-master:7077")
    cmd = [
        "docker",
        "compose",
        "-f",
        str(compose),
        "exec",
        "-T",
        "spark-master",
        "/opt/bitnami/spark/bin/spark-sql",
        "--master",
        master,
        "-e",
        sql,
    ]
    _log(f"Running spark-sql for {connector}...")
    proc = subprocess.run(cmd, cwd=compose_dir, capture_output=True, text=True)
    if proc.returncode != 0:
        _die(f"spark-sql failed:\n{proc.stdout}\n{proc.stderr}")
    lines = [ln.strip() for ln in (proc.stdout or "").splitlines() if ln.strip()]
    # Last line with only digits is typically COUNT result
    count_line = lines[-1] if lines else ""
    try:
        count = int(count_line.split()[-1])
    except ValueError:
        # Parse "cnt\n123" style output
        for ln in reversed(lines):
            parts = ln.split()
            if len(parts) == 1 and parts[0].isdigit():
                count = int(parts[0])
                break
        else:
            _die(f"could not parse COUNT from spark-sql output:\n{proc.stdout}")
    if count != expected:
        _die(f"{connector} SQL verify: expected {expected} rows, got {count}")
    _log(f"{connector} table row count: {count}")
    return count


def verify_connector(connector: str, expected: int) -> int:
    load_minio_env()
    if connector == "snowflake":
        return snowflake_create_load_count(expected)
    if connector in ("databricks", "spark"):
        return spark_sql_create_load_count(connector, expected)
    _die(f"unknown connector: {connector}")


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(description="Platform connector SQL load + COUNT verify")
    parser.add_argument("connector", choices=("snowflake", "databricks", "spark"))
    parser.add_argument("--expected", type=int, required=True)
    args = parser.parse_args(argv)
    verify_connector(args.connector, args.expected)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
