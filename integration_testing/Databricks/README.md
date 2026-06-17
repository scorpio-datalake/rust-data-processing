# Databricks integration tests

1. **RDP pipeline** — CSV → `kind: databricks` → Parquet under **MinIO** `s3://` warehouse path  
2. **Spark SQL** (standalone cluster Docker) — `CREATE TABLE … USING PARQUET LOCATION 's3a://…'`  
3. **Verify** — `SELECT COUNT(*)`

Databricks cluster Docker is not required; warehouse I/O matches `docs/CONNECTORS.md` (object_store to `warehouse` URI). SQL verification uses the shared **Spark** compose stack.

## Run

```bash
python3 integration_testing/Databricks/run_databricks_tests.py
```

Requires Spark + MinIO (started automatically by the runner).
