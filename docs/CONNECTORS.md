# Connectors — same URLs in Rust, Python, and Java

Rust (`rust-data-processing` / `rdp_jvm_sys`) performs I/O. **Python** and **Java** are thin wrappers: connection strings and pipeline JSON cross the boundary; wrappers do not reimplement drivers.

**Cloud auth:** URIs go in JSON; **tokens and keys do not** — Rust reads **system/OS environment variables** on the process that loads the native library (not Java-specific). Use shell `export`, Docker `--env-file`, or Kubernetes `env` / Secrets. Per-platform guides: **[AMAZON_S3.md](AMAZON_S3.md)** · **[AZURE_ADLS.md](AZURE_ADLS.md)** · **[SNOWFLAKE.md](SNOWFLAKE.md)** · overview **[CLOUD_AUTH.md](CLOUD_AUTH.md)**.

**Fake credentials below are placeholders only** — do not use in production.

| Connector | Shared connection / URI | Primary auth (examples) |
| --- | --- | --- |
| **PostgreSQL** | `postgresql://etl_user:FAKE_PG_PASS@db01.example.com:5432/analytics?sslmode=require` | User + password in URL |
| **Oracle** | `oracle://etl_user:FAKE_ORA_PASS@db01.example.com:1521/ORCLPDB1` | User + password in URL (ConnectorX) |
| **SQL Server** | `mssql://etl_user:FAKE_SQL_PASS@db01.example.com:1433/warehouse?encrypt=true` | User + password in URL |
| **Snowflake** | Account `https://xy12345.us-east-1.snowflakecomputing.com` · stage `s3://demo-bucket-us-east-1/snowflake-stage/rdp/` | OS env: stage `AWS_*` + optional `SNOWFLAKE_*` — **[SNOWFLAKE.md](SNOWFLAKE.md)** |
| **Databricks** | Workspace `https://dbc-a1b2c3d4-e5f6.cloud.databricks.com` · warehouse `abfss://datalake@storacc01.dfs.core.windows.net/unity/` | **Storage:** OS env `AZURE_*` or `AWS_*` for `warehouse` URI ([CLOUD_AUTH.md](CLOUD_AUTH.md)); PAT is outside in-tree sink I/O |
| **Spark** | Master `spark://spark-master.example.com:7077` · handoff `s3://demo-bucket-us-east-1/spark-handoff/out.parquet` | Cluster auth is in **your** Spark submit config (Kerberos, token, etc.) — not on the Rust FFI boundary |
| **Amazon S3** | `s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet` | OS env: `AWS_*` (or IAM role on host/pod) — **[AMAZON_S3.md](AMAZON_S3.md)** |
| **Google Cloud Storage** | `gs://demo-gcs-project/rdp/incoming/part-00000.parquet` | OS env: `GOOGLE_APPLICATION_CREDENTIALS` — [CLOUD_AUTH.md](CLOUD_AUTH.md) |
| **Azure Blob / ADLS** | `abfss://container@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet` | OS env: `AZURE_*` — **[AZURE_ADLS.md](AZURE_ADLS.md)** |
| **SFTP** | `sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet` | User + password or `SFTP_PRIVATE_KEY_PATH` env — see [CLOUD_AUTH.md](CLOUD_AUTH.md#sftp) |
| **FTP** | `ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet` | User + password (`FTP_PASSWORD` env) — see [CLOUD_AUTH.md](CLOUD_AUTH.md#ftp--ftps) |
| **Kafka (streaming)** | `localhost:9092` + topic `events` (bootstrap servers) | SASL/SSL via librdkafka config — **[KAFKA_ELT.md](KAFKA_ELT.md)** (ELT, not batch ETL) |

**Warehouse SQL** (same text in all languages where applicable):

```sql
SELECT id, name, amount FROM demo.fact_scores WHERE amount > 0 LIMIT 100000;
```

**Build features:** Validated by `scripts/check_jvm_full_features.py` (JVM, Rust, Python). **Rust integration:** `integration_full` (`db_connectorx` + `cloud_connectors` + `excel`). **Python integration:** `integration_full` (`db` + `cloud`). **JVM / CI:** `rdp_jvm_sys --features full` — all batch connectors below (`db_connectorx`, `sink_postgres`, `sink_oracle`, `cloud_connectors`, `sql`, `excel`). **Kafka streaming ELT:** add `--features kafka` — see **[KAFKA_ELT.md](KAFKA_ELT.md)** (poll windows, not file batches).

---

## Integration validation (Docker)

Tri-language tests under [`integration_testing/`](../integration_testing/) run **Java, Python, and Rust** against Docker emulators. Rust performs all protocol I/O; Java and Python call `rdp_run_pipeline_json` or Kafka FFI (`librdp_jvm_sys`).

| Suite | Run command | Protocols / pattern |
| --- | --- | --- |
| **CloudConnectors** | `python3 integration_testing/CloudConnectors/run_cloud_tests.py --no-rancher` | **S3** (MinIO), **GCS** (fake-gcs), **Azure** (Azurite): CSV → transform → `kind: object_store` export → read-back via `object_store_uris`. **SFTP / FTP**: import seeded CSV via `file_transfer_uris`. |
| **Kafka** | `python3 integration_testing/Kafka/run_kafka_tests.py --no-rancher` | One Uber CSV row per message → `rdp_kafka_export_dataset_json` → `rdp_kafka_poll_window_loaded_json` (Redpanda). |
| **Snowflake / Databricks / Spark** | `run_snowflake_tests.py`, `run_databricks_tests.py`, `run_spark_tests.py` | Platform `kind:` sinks → MinIO / Spark verify. |
| **SQL Server / Oracle / PostgreSQL** | `run_mssql_tests.py`, `run_oracle_tests.py`, `PostgreSQL/run_tests.py` | DB sink or ConnectorX import. |

**Prerequisites:** `python3 integration_testing/scripts/build_libs/build_all_libs.py`, Uber sample CSV (`download_uber_data.py --sample`). See [`integration_testing/README.md`](../integration_testing/README.md) and [`integration_testing/integration_testing_details.md`](../integration_testing/integration_testing_details.md).

### Object-store roundtrip (S3 / GCS / Azure)

Reference: [`integration_testing/scripts/cloud_pipeline.py`](../integration_testing/scripts/cloud_pipeline.py) — used by all three languages in CloudConnectors.

**Export** (local CSV → transform → cloud Parquet):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": ["/path/to/uber_nyc_pickups_sample.csv"],
    "schema": { "fields": [ … raw CSV columns … ] },
    "options": { "format": "csv", "max_rows": 500 }
  },
  "transform": { "sql": "SELECT \"Date/Time\" AS pickup_time, Lat AS lat, Lon AS lon, Base AS base_code FROM df" },
  "sinks": [{ "kind": "object_store", "uri": "s3://rdp-cloud-s3/out.parquet", "format": "parquet" }],
  "orchestration": { "max_ingested_rows": 500 }
}
```

Integration URIs (local emulators — see `integration_testing/CloudConnectors/.env.example`):

| Protocol | Export / read URI | Auth (process env) |
| --- | --- | --- |
| S3 | `s3://rdp-cloud-s3/out.parquet` | `AWS_*` → MinIO `:9000` |
| GCS | `gs://rdp-cloud-gcs/out.parquet` | `GOOGLE_APPLICATION_CREDENTIALS`, `STORAGE_EMULATOR_HOST` / `gcs_base_url` → fake-gcs `:4443` |
| Azure | `azure://rdp-cloud-azure/out.parquet` | `AZURE_STORAGE_USE_EMULATOR=true`, `AZURE_ENDPOINT` → Azurite `:10000` |

**Read-back** uses `sources.object_store_uris` with the **curated** schema (transformed column names) and a local `parquet_file` sink for verification.

### File transfer import (SFTP / FTP)

Reference: `cloud_pipeline.import_from_file_transfer` — `sources.file_transfer_uris` + `file_transfer` Rust download.

```json
{
  "sources": {
    "paths": [],
    "file_transfer_uris": ["sftp://rdp:rdp_sftp_secret@127.0.0.1:2222/upload/incoming.csv"],
    "schema": { "fields": [ … ] },
    "options": { "format": "csv", "max_rows": 500 }
  },
  "sinks": [{ "kind": "parquet_file", "path": "/tmp/rdp-cloud-sftp-import.parquet" }]
}
```

### Kafka streaming (one row per message)

Reference: [`integration_testing/scripts/kafka_stream.py`](../integration_testing/scripts/kafka_stream.py). Dataset JSON uses tagged Rust `Value` cells: `{"Utf8": "…"}`, `{"Float64": 40.7}`.

```python
# Python integration helper (FFI to rdp_kafka_export_dataset_json / rdp_kafka_poll_window_loaded_json)
from kafka_stream import verify_uber_kafka_stream
count = verify_uber_kafka_stream("integration_testing/data/uber_nyc_pickups_sample.csv", max_rows=500)
```

Java: [`KafkaEltStreamExample.java`](java/examples/KafkaEltStreamExample.java) · Rust: [`integration_testing/Kafka/rust/src/rdp_kafka.rs`](../integration_testing/Kafka/rust/src/rdp_kafka.rs).

---

## PostgreSQL

| Layer | Connector-only usage |
| --- | --- |
| **Rust** | `ingest_from_db` / sink `postgresql://` |
| **Python** | `ingest_from_db(conn, query, schema)` — feature `db` |
| **Java** | `rdp_run_pipeline_json` with `kind: postgresql` sink (`postgresql://` libpq URL) |

### Rust

```rust
use rust_data_processing::ingestion::{ingest_from_db_infer, IngestionOptions};
// --features db_connectorx

const URL: &str = "postgresql://etl_user:FAKE_PG_PASS@db01.example.com:5432/analytics?cxprotocol=binary";
const SQL: &str = "SELECT id, name, amount FROM public.fact_scores WHERE amount > 0 LIMIT 100000";

let ds = ingest_from_db_infer(URL, SQL, &IngestionOptions::default())?;
println!("rows={}", ds.row_count());
```

### Python

```python
import rust_data_processing as rdp

URL = "postgresql://etl_user:FAKE_PG_PASS@db01.example.com:5432/analytics?cxprotocol=binary"
SQL = "SELECT id, name, amount FROM public.fact_scores WHERE amount > 0 LIMIT 100000"

ds = rdp.ingest_from_db_infer(URL, SQL)  # extension built with: maturin build --features db
print("rows", ds.row_count())
```

### Java (pipeline JSON — sink URL; ingest from local path or object_store_uris)

```json
{
  "pipeline_spec_version": 1,
  "sources": { "paths": ["{{LOCAL_OR_CLOUD_PATH}}"], "schema": { "fields": [] }, "options": { "format": "parquet" } },
  "sinks": [
    {
      "kind": "postgresql",
      "url": "postgresql://etl_user:FAKE_PG_PASS@db01.example.com:5432/analytics?sslmode=require",
      "table": "public.fact_scores_curated"
    }
  ]
}
```

```java
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipelineJson);
```

---

## Oracle

| Layer | Notes |
| --- | --- |
| **Rust / Python** | ConnectorX `oracle://` (`ingest_from_db` / `--features db`) |
| **Java** | **`sources.db_reads`** (read) and **`kind: oracle`** sink (write) — build **`rdp_jvm_sys`** with **`--features full`** (includes `db_connectorx` + `sink_oracle`) |

### Rust

```rust
const URL: &str = "oracle://etl_user:FAKE_ORA_PASS@db01.example.com:1521/ORCLPDB1";
const SQL: &str = "SELECT id, name, amount FROM demo.fact_scores WHERE ROWNUM <= 100000";
// ingest_from_db_infer(URL, SQL, &IngestionOptions::default())?;
```

### Python

```python
URL = "oracle://etl_user:FAKE_ORA_PASS@db01.example.com:1521/ORCLPDB1"
SQL = "SELECT id, name, amount FROM demo.fact_scores WHERE ROWNUM <= 100000"
# ds = rdp.ingest_from_db_infer(URL, SQL)  # --features db
```

### Java

Rust runs warehouse SQL via ConnectorX and row loads via OCI — use **`oracle://`**. Build the native library with DB read + sink enabled (CI uses **`--features full`**):

```bash
cargo build -p rdp-jvm-sys --features full
```

**Read** (export to Parquet):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": [],
    "db_reads": [
      {
        "url": "oracle://etl_user:FAKE_ORA_PASS@db01.example.com:1521/ORCLPDB1",
        "query": "SELECT id, name, amount FROM demo.fact_scores WHERE ROWNUM <= 100000"
      }
    ],
    "schema_ref": "schemas/your_fact_scores.schema.json",
    "options": {}
  },
  "transform": { "sql": "SELECT id, name, amount FROM df" },
  "sinks": [{ "kind": "parquet_file", "path": "/var/rdp/curated/oracle_fact.parquet" }]
}
```

**Write** (CSV/Parquet → Oracle table — same pattern as PostgreSQL sink):

```json
{
  "pipeline_spec_version": 1,
  "sources": { "paths": ["{{LOCAL_OR_CLOUD_PATH}}"], "schema": { "fields": [] }, "options": { "format": "csv" } },
  "transform": { "sql": "SELECT id, name, amount FROM df" },
  "sinks": [
    {
      "kind": "oracle",
      "url": "oracle://etl_user:FAKE_ORA_PASS@db01.example.com:1521/ORCLPDB1",
      "table": "DEMO.FACT_SCORES_CURATED",
      "create_table_if_missing": true,
      "truncate_before_load": true
    }
  ]
}
```

```java
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipelineJson);
// db_reads: root.optJSONArray("db_source_results")
// oracle sink: root.getJSONObject("interchange").optJSONArray("sink_results")
```

Without **`db_connectorx`**, `db_reads` returns **`DB_CONNECTORX_NOT_BUILT`**. Without **`sink_oracle`**, `kind: oracle` returns **`ORACLE_SINK_NOT_BUILT`**. Use **`--features full`** for both. Export query results to a local file and use **`sources.paths`** when you cannot rebuild.

---

## Microsoft SQL Server

### Rust

```rust
const URL: &str = "mssql://etl_user:FAKE_SQL_PASS@db01.example.com:1433/warehouse?encrypt=true";
const SQL: &str = "SELECT TOP (100000) id, name, amount FROM dbo.fact_scores WHERE amount > 0";
```

### Python

```python
URL = "mssql://etl_user:FAKE_SQL_PASS@db01.example.com:1433/warehouse?encrypt=true"
SQL = "SELECT TOP (100000) id, name, amount FROM dbo.fact_scores WHERE amount > 0"
# ds = rdp.ingest_from_db_infer(URL, SQL)
```

### Java

Same as Oracle: **`sources.db_reads`** with ConnectorX **`mssql://`**:

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": [],
    "db_reads": [
      {
        "url": "mssql://etl_user:FAKE_SQL_PASS@db01.example.com:1433/warehouse?encrypt=true",
        "query": "SELECT TOP (100000) id, name, amount FROM dbo.fact_scores WHERE amount > 0"
      }
    ],
    "schema_ref": "schemas/your_fact_scores.schema.json",
    "options": {}
  },
  "sinks": [{ "kind": "parquet_file", "path": "/var/rdp/curated/mssql_fact.parquet" }]
}
```

```java
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipelineJson);
```

---

## Snowflake

**Auth:** Stage I/O uses the **object-store scheme** of `stage_uri` (usually S3 → `AWS_*`). Optional `COPY INTO` uses `SNOWFLAKE_USER` / `SNOWFLAKE_PASSWORD` on the same OS process — not in pipeline JSON. Full guide: **[SNOWFLAKE.md](SNOWFLAKE.md)**. Cross-cloud deployment: [CLOUD_AUTH.md](CLOUD_AUTH.md).

**Shared stage URI (Rust writes Parquet here):** `s3://demo-bucket-us-east-1/snowflake-stage/rdp/load.parquet`

### Rust (stage via object_store; optional COPY when env set)

```rust
use rust_data_processing::ingestion::{write_dataset_to_snowflake_stage, copy_into_table_from_stage};
// --features cloud_connectors

const ACCOUNT: &str = "https://xy12345.us-east-1.snowflakecomputing.com";
const STAGE: &str = "s3://demo-bucket-us-east-1/snowflake-stage/rdp/load.parquet";
// write_dataset_to_snowflake_stage(STAGE, &ds)?;
// copy_into_table_from_stage(ACCOUNT, Some("COMPUTE_WH"), Some("DEMO_DB"), Some("CURATED"), "FACT_SCORES", STAGE, Some("ETL_ROLE"))?;
```

### Python

```python
# Same ACCOUNT and STAGE; use JVM pipeline JSON or Rust until PyO3 exposes snowflake helpers.
STAGE = "s3://demo-bucket-us-east-1/snowflake-stage/rdp/load.parquet"
```

### Java

```json
{
  "kind": "snowflake",
  "account_url": "https://xy12345.us-east-1.snowflakecomputing.com",
  "warehouse": "COMPUTE_WH",
  "database": "DEMO_DB",
  "schema": "CURATED",
  "table": "FACT_SCORES",
  "stage_uri": "s3://demo-bucket-us-east-1/snowflake-stage/rdp/",
  "role": "ETL_ROLE"
}
```

See [`PlatformConnectorsPipelineExample.java`](java/examples/PlatformConnectorsPipelineExample.java).

---

## Databricks

In-tree writes go to **`warehouse`** (`abfss://` or `s3://`) via `object_store` — not via Databricks REST with a PAT. `workspace_url` and `catalog_uri` in pipeline JSON are metadata only. **Azure AD / storage credentials:** [CLOUD_AUTH.md — Databricks sink](CLOUD_AUTH.md#databricks-pipeline-sink-kind-databricks).

**Workspace auth (outside in-tree I/O)** — for notebooks, jobs, or Spark drivers you operate separately:

| Method | Example |
| --- | --- |
| Personal access token | `dapiFAKE_DATABRICKS_PAT_abcdef0123456789` (HTTP header / env in your tools) |
| OAuth (Azure AD) | App registration + client secret in Databricks workspace settings |

**Shared warehouse path:** `abfss://datalake@storacc01.dfs.core.windows.net/unity/curated/fact_scores/`

### Rust

```rust
use rust_data_processing::ingestion::{delta_table_uri, write_dataset_to_delta_table};
const WH: &str = "abfss://datalake@storacc01.dfs.core.windows.net/unity/";
let table_uri = delta_table_uri(WH, Some("curated"), "fact_scores");
// write_dataset_to_delta_table(&table_uri, &ds)?;
```

### Python

```python
WH = "abfss://datalake@storacc01.dfs.core.windows.net/unity/"
TABLE_URI = f"{WH.rstrip('/')}/curated/fact_scores/"  # same layout as Rust delta_table_uri
```

### Java

```json
{
  "kind": "databricks",
  "workspace_url": "https://dbc-a1b2c3d4-e5f6.cloud.databricks.com",
  "catalog_uri": "https://dbc-a1b2c3d4-e5f6.cloud.databricks.com/api/2.1/unity-catalog/iceberg",
  "warehouse": "abfss://datalake@storacc01.dfs.core.windows.net/unity/",
  "namespace": "main.curated",
  "table": "fact_scores"
}
```

---

## Apache Spark

Rust does **not** embed `SparkSession`. It writes Parquet to **`handoff_uri`**; your Spark driver reads it.

**Auth:** Rust write to `handoff_uri` follows [CLOUD_AUTH.md](CLOUD_AUTH.md) (S3 / Azure / `file://`). Spark cluster login is separate — [CLOUD_AUTH.md — Apache Spark handoff](CLOUD_AUTH.md#apache-spark-handoff).

**Auth (in your Spark app, not in Rust):**

| Deployment | Typical credentials |
| --- | --- |
| YARN / Kerberos | `principal` + keytab |
| Databricks | PAT or OAuth (cluster config) |
| `local[*]` demo | Often none |

**Shared:** `handoff_uri` = `s3://demo-bucket-us-east-1/spark-handoff/out.parquet` · `master` = `spark://spark-master.example.com:7077`

### Rust / Java (pipeline `kind: spark`)

```json
{
  "kind": "spark",
  "master": "spark://spark-master.example.com:7077",
  "app_name": "rdp-demo",
  "handoff_uri": "s3://demo-bucket-us-east-1/spark-handoff/out.parquet"
}
```

```java
// rdp_run_pipeline_json → sink_results[].handoff_uri written by Rust
// spark.read().parquet(handoffUri) in your separate Spark module
```

### Python

```python
HANDOFF = "s3://demo-bucket-us-east-1/spark-handoff/out.parquet"
MASTER = "spark://spark-master.example.com:7077"
# Same pipeline JSON as Java via shared fixtures, or PySpark after Rust write
```

---

## Amazon S3

**Yes — S3 requires authentication.** The `s3://bucket/key` string in pipeline JSON is only the **location**; it does **not** contain keys or tokens. Full auth guide (OS env, Docker, K8s, IAM): **[AMAZON_S3.md](AMAZON_S3.md)**. Cross-cloud notes: [CLOUD_AUTH.md](CLOUD_AUTH.md).

**URI (location only):** `s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet`

**Integration (MinIO):** `s3://rdp-cloud-s3/out.parquet` with `AWS_ENDPOINT=http://127.0.0.1:9000` — see [Integration validation](#integration-validation-docker).

### Rust

```rust
use rust_data_processing::ingestion::{ingest_from_object_store_uri, export_dataset_to_object_store_uri, IngestionOptions};
// --features cloud_connectors

const URI: &str = "s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet";
// let ds = ingest_from_object_store_uri(URI, &schema, &IngestionOptions { format: Some(IngestionFormat::Parquet), .. })?;
// export_dataset_to_object_store_uri("s3://demo-bucket-us-east-1/rdp/out/result.parquet", &ds)?;
```

### Python

```python
import rust_data_processing as rdp

URI = "s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet"
schema = [{"name": "id", "data_type": "int64"}, {"name": "name", "data_type": "utf8"}]
ds = rdp.ingest_from_object_store_uri(URI, schema, {"format": "parquet"})  # --features cloud
```

### Java

```json
{
  "sources": {
    "paths": [],
    "object_store_uris": ["s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet"],
    "schema": { "fields": [] },
    "options": { "format": "parquet" }
  },
  "sinks": [{ "kind": "object_store", "uri": "s3://demo-bucket-us-east-1/rdp/out/", "format": "parquet" }]
}
```

---

## Google Cloud Storage

**Auth:** `GOOGLE_APPLICATION_CREDENTIALS` or GCE/GKE workload identity on the **process running Rust** — not in pipeline JSON. See [CLOUD_AUTH.md — Google Cloud Storage](CLOUD_AUTH.md#google-cloud-storage).

| Method | Typical setup |
| --- | --- |
| Service account JSON | `GOOGLE_APPLICATION_CREDENTIALS=/path/to/fake-service-account.json` |
| Workload identity | Metadata on GCE/GKE — no path in JSON |

**URI:** `gs://demo-gcs-project/rdp/incoming/part-00000.parquet` (alias `gcs://` accepted in validation)

**Local emulator (integration tests):** set `STORAGE_EMULATOR_HOST=http://127.0.0.1:4443` and `gcs_base_url` (or `GOOGLE_APPLICATION_CREDENTIALS` pointing at a fake service account with `gcs_base_url`). Rust uses the GCS JSON API against [fake-gcs-server](https://github.com/fsouza/fake-gcs-server) when those vars are set. Validated: `integration_testing/CloudConnectors/` → `gs://rdp-cloud-gcs/out.parquet`.

### Rust

```rust
const URI: &str = "gs://demo-gcs-project/rdp/incoming/part-00000.parquet";
// ingest_from_object_store_uri(URI, &schema, &opts)?;
```

### Python

```python
URI = "gs://demo-gcs-project/rdp/incoming/part-00000.parquet"
# ds = rdp.ingest_from_object_store_uri(URI, schema, {"format": "parquet"})
```

### Java

```json
"object_store_uris": ["gs://demo-gcs-project/rdp/incoming/part-00000.parquet"]
```

---

## Azure Blob Storage / ADLS Gen2

Full guide: **[AZURE_ADLS.md](AZURE_ADLS.md)** (service principal env vars, managed identity, Docker/K8s, Java with no secrets in JSON). Cross-cloud notes: [CLOUD_AUTH.md](CLOUD_AUTH.md).

**Quick reference:**

| Method | Variables (on the process running `rdp_jvm_sys` / Rust / Python) |
| --- | --- |
| Service principal | `AZURE_TENANT_ID`, `AZURE_CLIENT_ID`, `AZURE_CLIENT_SECRET`, `AZURE_STORAGE_ACCOUNT_NAME` |
| Account key | `AZURE_STORAGE_ACCOUNT_NAME`, `AZURE_STORAGE_ACCOUNT_KEY` |
| Managed identity / CLI | Identity on host or `az login` — see CLOUD_AUTH.md |

**URI:** `abfss://container@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet` · **`azure://container/path`** also accepted (Azurite integration uses `azure://rdp-cloud-azure/out.parquet`)

**Local emulator (integration tests):** `AZURE_STORAGE_USE_EMULATOR=true`, `AZURE_ENDPOINT=http://127.0.0.1:10000`. Do not set a truncated `AZURE_STORAGE_ACCOUNT_KEY` — Rust uses the well-known Azurite key when emulator mode is on. Validated: `integration_testing/CloudConnectors/`.

### Rust

```rust
const URI: &str = "abfss://container@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet";
```

### Python

```python
URI = "abfss://container@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet"
```

### Java

```json
"object_store_uris": ["abfss://container@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet"]
```

---

## SFTP

**Auth:** [CLOUD_AUTH.md — SFTP](CLOUD_AUTH.md#sftp). **Pipeline field:** `sources.file_transfer_uris` (not `object_store_uris`).

**URL:** `sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet`

| Auth | Example |
| --- | --- |
| Password | URL userinfo or `SFTP_PASSWORD` env (overrides URL password) |
| SSH private key | `SFTP_PRIVATE_KEY_PATH` — not in JSON |

### Rust

```rust
use rust_data_processing::ingestion::{ingest_from_file_transfer_uri, IngestionOptions};
// cargo run --features cloud_connectors --example file_transfer_ingest -- 'sftp://...'

const URI: &str = "sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet";
let ds = ingest_from_file_transfer_uri(URI, &schema, &IngestionOptions::default())?;
```

### Python

```python
import rust_data_processing as rdp  # maturin build --features cloud

URI = "sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet"
ds = rdp.ingest_from_file_transfer_uri(URI, schema, {"format": "parquet"})
```

### Java

```json
"file_transfer_uris": ["sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet"]
```

See [`SftpFtpConnectorsExample.java`](java/examples/SftpFtpConnectorsExample.java) and fixture `tests/fixtures/file_transfer/`. **Docker-validated:** `integration_testing/CloudConnectors/` (seeded CSV on SFTP/FTP containers).

---

## FTP / FTPS

**Auth:** [CLOUD_AUTH.md — FTP / FTPS](CLOUD_AUTH.md#ftp--ftps). **Pipeline field:** `sources.file_transfer_uris`.

**URL:** `ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet` · FTPS: `ftps://…` (port 990 default)

| Mode | Notes |
| --- | --- |
| Plain FTP | `ftp://` |
| Explicit TLS (FTPS) | `ftps://` — requires `cloud_connectors` (rustls) |
| Anonymous | Omit user; set `FTP_USER` if needed (discouraged) |

### Rust

```rust
const URI: &str = "ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet";
let ds = ingest_from_file_transfer_uri(URI, &schema, &IngestionOptions::default())?;
```

### Python

```python
ds = rdp.ingest_from_file_transfer_uri(
    "ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet",
    schema,
    {"format": "parquet"},
)
```

### Java

```json
"file_transfer_uris": ["ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet"]
```

---

## Kafka (streaming ELT)

Not a batch file connector — poll **windows** from a topic, land rows, transform separately. Full guide: **[KAFKA_ELT.md](KAFKA_ELT.md)**.

| Language | Entry point | Integration reference |
| --- | --- | --- |
| Rust | `export_dataset_to_kafka`, `poll_kafka_window_loaded` | `integration_testing/Kafka/rust/src/rdp_kafka.rs` |
| Python | `rdp.export_dataset_to_kafka`, `rdp.poll_kafka_window_loaded` (feature **`kafka`**) | `integration_testing/scripts/kafka_stream.py` |
| Java | `rdp_kafka_export_dataset_json`, `rdp_kafka_poll_window_loaded_json` (build **`full,kafka`**) | `KafkaStreamIntegrationTest`, [`KafkaEltStreamExample.java`](java/examples/KafkaEltStreamExample.java) |

**Run:** `python3 integration_testing/Kafka/run_kafka_tests.py --no-rancher` (Redpanda on `127.0.0.1:19092`).

---

## Runnable Java tour

[`docs/java/EXAMPLES.md`](java/EXAMPLES.md) · [`PlatformConnectorsPipelineExample.java`](java/examples/PlatformConnectorsPipelineExample.java) · fixture bundle `tests/fixtures/cloud_connectors/`.

## Related

- **[KAFKA_ELT.md](KAFKA_ELT.md)** — streaming Extract → Load → Transform
- **[integration_testing/README.md](../integration_testing/README.md)** — Docker tri-language connector tests
- **[integration_testing/integration_testing_details.md](../integration_testing/integration_testing_details.md)** — step-by-step per connector
- **[CLOUD_AUTH.md](CLOUD_AUTH.md)** — where OS env vars must be set (not Java JSON); Docker / K8s overview
- **[AMAZON_S3.md](AMAZON_S3.md)** — Amazon S3 (`AWS_*`, IAM role, Docker, K8s)
- **[AZURE_ADLS.md](AZURE_ADLS.md)** — Azure ADLS / Blob (`AZURE_*`, Docker, K8s)
- **[SNOWFLAKE.md](SNOWFLAKE.md)** — Snowflake stage + optional `COPY INTO`
- [`docs/java/EXAMPLES.md`](java/EXAMPLES.md) — connector cookbook with warehouse vs Polars SQL
- [`python-wrapper/API.md`](../python-wrapper/API.md) — Python ingestion API
- [`docs/adr/006-jvm-orchestration-pipeline-json.md`](adr/006-jvm-orchestration-pipeline-json.md) — pipeline sink taxonomy
