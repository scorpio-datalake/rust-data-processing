# Integration testing — step-by-step flows

Personal reference for what each **`run_*_tests.py`** orchestrator does in **Java, Python, and Rust**. Rust always performs protocol I/O; Java and Python call **`rdp_run_pipeline_json`** or Kafka FFI via **`librdp_jvm_sys.so`**.

**Setup (once):**

```bash
cd /home/ubuntu/rust-data-processing
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
```

**Re-run with Docker already up:** add **`--no-rancher`**. **Leave containers running:** add **`--keep-cloud`**, **`--keep-kafka`**, **`--keep-minio`**, etc.

---

## 8 — Cloud storage (S3, GCS, Azure, SFTP, FTP)

```bash
nohup python3 integration_testing/CloudConnectors/run_cloud_tests.py --no-rancher \
  > ./integration_testing/logs/run_cloud_tests.log 2>&1 &
tail -f ./integration_testing/logs/run_cloud_tests.log
```

**Docker stack:** MinIO (S3) + fake-gcs (GCS) + Azurite (Azure) + SFTP + FTP — see `CloudConnectors/docker-compose.yml`.

**Shared helpers:** `integration_testing/scripts/cloud_pipeline.py` · schema: `integration_testing/schema/uber_pickups.*.json`

### S3 / GCS / Azure — object-store roundtrip (all three languages)

Each language runs the same logical steps:

1. **Start emulators** — `cloud_common.start_cloud_stack()` (`docker compose up`, seed GCS bucket + Azure container, SFTP/FTP CSV, S3 bucket `rdp-cloud-s3`).
2. **Export** — Local Uber CSV → Polars SQL transform → **`kind: object_store`** sink (Rust writes Parquet):
   - S3: `s3://rdp-cloud-s3/out.parquet` + `AWS_*` → MinIO `:9000`
   - GCS: `gs://rdp-cloud-gcs/out.parquet` + `STORAGE_EMULATOR_HOST` / `gcs_base_url` → fake-gcs `:4443`
   - Azure: `azure://rdp-cloud-azure/out.parquet` + `AZURE_STORAGE_USE_EMULATOR` → Azurite `:10000`
3. **Read-back** — Pipeline with **`sources.object_store_uris`** (curated schema after transform) → local **`parquet_file`** sink.
4. **Assert** — `ingested_row_count` on read-back equals export row count.

| Language | Test entry | Rust I/O path |
| --- | --- | --- |
| **Java** | `CloudImportIntegrationTest.java*` (`javaS3ObjectStoreRoundtrip`, …) | `rdp_run_pipeline_json` → `object_store` crate |
| **Python** | `tests/test_cloud_import.py` → `verify_object_store_roundtrip` | ctypes → `rdp_run_pipeline_json` |
| **Rust** | `rust/tests/import_test.rs` → `verify_object_store_roundtrip` | ctypes → `rdp_run_pipeline_json` |

\* Java/Python/Rust do **not** delete cloud objects — roundtrip verification only.

### SFTP / FTP — file transfer import

1. **Seed** — `cloud-seed` copies head of Uber CSV to SFTP `/upload/incoming.csv` and FTP `/incoming.csv`.
2. **Import** — Pipeline with **`sources.file_transfer_uris`** (CSV format) → local **`parquet_file`** sink.
3. **Assert** — row count &gt; 0.

| Language | URI (defaults) |
| --- | --- |
| SFTP | `sftp://rdp:rdp_sftp_secret@127.0.0.1:2222/upload/incoming.csv` |
| FTP | `ftp://rdp:rdp_ftp_secret@127.0.0.1:21/incoming.csv` |

---

## 9 — Kafka streaming (one row per message)

```bash
nohup python3 integration_testing/Kafka/run_kafka_tests.py --no-rancher \
  > ./integration_testing/logs/run_kafka_tests.log 2>&1 &
tail -f ./integration_testing/logs/run_kafka_tests.log
```

**Docker:** Redpanda on **`127.0.0.1:19092`**, topic **`rdp-uber-pickups`**.

**Helper:** `integration_testing/scripts/kafka_stream.py`

### Java testing

1. Resolve `RDP_JVM_SYS` (built with **`full,kafka`** on first run).
2. For each Uber CSV row (up to `INTEG_MAX_IMPORT_ROWS`):
   - Build one-row `DataSet` JSON with tagged cells: `{"Utf8": pickup_time}`, `{"Float64": lat}`, …
   - Call **`rdp_kafka_export_dataset_json`** (Rust producer → topic).
3. Call **`rdp_kafka_poll_window_loaded_json`** with fresh consumer group → landing schema includes `_kafka_offset`, `_kafka_partition`.
4. Assert landed row count == produced row count.

**Class:** `KafkaStreamIntegrationTest.java`

### Python testing

Same flow via **`verify_uber_kafka_stream(csv)`** in `kafka_stream.py` (ctypes to the same FFI symbols).

**Test:** `Kafka/tests/test_kafka_stream.py`

### Rust testing

Same flow via **`verify_uber_kafka_stream`** in `Kafka/rust/src/rdp_kafka.rs`.

**Test:** `Kafka/rust/tests/import_test.rs`

---

## Platform connectors (Snowflake, Databricks, Spark)

See `to_do_notes.md` § Platform connectors.

| Suite | Pattern |
| --- | --- |
| **Snowflake** | CSV → **`kind: snowflake`** sink → Parquet on MinIO stage → emulator verify |
| **Databricks** | CSV → **`kind: databricks`** sink → MinIO warehouse path → verify |
| **Spark** | CSV → **`kind: spark`** handoff Parquet on MinIO → `spark-sql` count verify |

---

## Database connectors (SQL Server, Oracle, PostgreSQL)

| Suite | Pattern |
| --- | --- |
| **SQL Server** | Uber CSV → **`kind: mssql`** sink → row count verify |
| **Oracle** | Uber CSV → Oracle import via ConnectorX / pipeline |
| **PostgreSQL** | Same RDP tri-language import pattern |

---

## Log cheat sheet

| Look for | Meaning |
| --- | --- |
| `PASSED: Java integration test` | Maven leg OK |
| `PASSED: Python integration test` | pytest OK |
| `PASSED: Rust integration test` | cargo test OK |
| `--- Test summary ---` | Per-leg recap |
| `All … integration tests passed.` | Full success |

**Docs mirror:** [`docs/CONNECTORS.md`](../docs/CONNECTORS.md) · [`docs/KAFKA_ELT.md`](../docs/KAFKA_ELT.md) · [`docs/java/EXAMPLES.md`](../docs/java/EXAMPLES.md) · [`docs/python/README.md`](../docs/python/README.md)
