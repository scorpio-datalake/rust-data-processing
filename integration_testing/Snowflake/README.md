# Snowflake integration tests

Tri-language flow (mirrors PostgreSQL/Oracle):

1. **RDP pipeline** — Uber CSV → `kind: snowflake` sink → Parquet on **MinIO** `s3://` stage  
2. **Snowflake SQL** — `CREATE TABLE` + `COPY INTO` (or `READ_PARQUET` fallback) on **Snowflake emulator** Docker  
3. **Verify** — `SELECT COUNT(*)` on `UBER_PICKUPS`

## Docker

```bash
cd integration_testing/Snowflake
cp .env.example .env
docker compose up -d   # MinIO + ghcr.io/nnnkkk7/snowflake-emulator
```

## Prerequisites

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
cd python-wrapper && uv sync --group dev
```

SQL verify uses **snowflake-emulator REST API v2** (`platform_sql.py`, stdlib only). `run_snowflake_tests.py` calls `platform_deps.py` to ensure the venv exists — no `snowflake-connector-python` (its gzipped login breaks the local emulator).

## Run

```bash
python3 integration_testing/Snowflake/run_snowflake_tests.py
```
