# Spark handoff integration tests

1. **RDP pipeline** — CSV → `kind: spark` → Parquet at **MinIO** `s3://` handoff URI  
2. **Spark SQL** — `CREATE TABLE … USING PARQUET` on `s3a://` path  
3. **Verify** — `SELECT COUNT(*)`

Also starts a **standalone Spark master** (`spark://127.0.0.1:7077`) so pipeline `master` matches a live cluster.

## Docker

```bash
cd integration_testing/Spark
docker compose up -d   # MinIO + spark-master + spark-worker
```

## Run

```bash
python3 integration_testing/Spark/run_spark_tests.py
```
