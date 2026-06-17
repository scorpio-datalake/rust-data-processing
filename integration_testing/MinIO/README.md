# MinIO (S3-compatible) for platform connectors

Shared object storage for **Snowflake** (`stage_uri`), **Databricks** (`warehouse`), and **Spark** (`handoff_uri`) integration tests. Tests write and read via real `s3://` I/O (`object_store` + `AWS_*` env), not local `file://` staging.

## Start

```bash
cd integration_testing/MinIO
cp .env.example .env   # optional
docker compose up -d
```

Connector runners (`run_snowflake_tests.py`, etc.) include this compose file and export the same env vars.

## Buckets

| Bucket | Used by |
| --- | --- |
| `rdp-snowflake-stage` | Snowflake `stage_uri` → `s3://rdp-snowflake-stage/rdp/load.parquet` |
| `rdp-databricks-warehouse` | Databricks `warehouse` → Delta path under `unity/main/curated/uber_pickups/` |
| `rdp-spark-handoff` | Spark `handoff_uri` → `s3://rdp-spark-handoff/out.parquet` |
