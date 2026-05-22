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
| **SFTP** | `sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet` | User + password (or SSH key) — **not implemented** |
| **FTP** | `ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet` | User + password — **not implemented** |

**Warehouse SQL** (same text in all languages where applicable):

```sql
SELECT id, name, amount FROM demo.fact_scores WHERE amount > 0 LIMIT 100000;
```

**Build features:** Rust/Python cloud I/O: `cloud_connectors` (object store + Delta staging). SQL read: `db_connectorx` / Python `db`. JVM: `rdp_jvm_sys` with `link-main` (includes `cloud_connectors`).

---

## PostgreSQL

| Layer | Connector-only usage |
| --- | --- |
| **Rust** | `ingest_from_db` / sink `postgresql://` |
| **Python** | `ingest_from_db(conn, query, schema)` — feature `db` |
| **Java** | `rdp_run_pipeline_json` with `kind: postgresql` sink (libpq URL, not `jdbc:`) |

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
| **Rust / Python** | ConnectorX `oracle://` (not `jdbc:oracle:thin:`) |
| **Java** | Same ConnectorX URL + SQL in pipeline **`sources.db_reads`** (requires `rdp_jvm_sys` built with **`--features db_connectorx`** or **`full`**) |

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

Rust runs the warehouse `SELECT` via ConnectorX — use **`oracle://`**, not **`jdbc:oracle:thin:`**. Build the native library with DB read enabled:

```bash
cargo build -p rdp-jvm-sys --features link-main,db_connectorx
```

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

```java
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipelineJson);
// root.optJSONArray("db_source_results") — one entry per db_reads[] with status ok
```

Without **`db_connectorx`**, `db_reads` returns **`DB_CONNECTORX_NOT_BUILT`**. JDBC staging → **`sources.paths`** is only a fallback when you cannot rebuild the native library.

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

Same as Oracle: **`sources.db_reads`** with ConnectorX **`mssql://`** (not **`jdbc:sqlserver:`**):

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

**URI:** `abfss://container@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet`

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

## SFTP (not implemented)

**Auth / status:** [CLOUD_AUTH.md — SFTP](CLOUD_AUTH.md#sftp-not-implemented) — not wired in Rust yet; use object-store or local workaround.

**Planned URL:** `sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet`

| Auth (future) | Example |
| --- | --- |
| Password | `etl_user` / env or URL placeholder |
| SSH private key | Path via env (e.g. `SFTP_PRIVATE_KEY_PATH`) — not in JSON |

Today: sync to S3/ADLS/GCS/local, then use `object_store_uris` or `sources.paths` with [S3](AMAZON_S3.md) / [Azure](AZURE_ADLS.md) OS env on the process.

---

## FTP (not implemented)

**Auth / status:** [CLOUD_AUTH.md — FTP](CLOUD_AUTH.md#ftp-not-implemented).

**Planned URL:** `ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet`

| Mode | Notes |
| --- | --- |
| Explicit TLS (FTPS) | Often `ftps://` on port 990 — same gap as FTP |
| Anonymous | `ftp://ftp.example.com/...` (discouraged) |

Same workaround as [SFTP](CLOUD_AUTH.md#sftp-not-implemented) until an FTP client is linked in Rust.

---

## Runnable Java tour

[`docs/java/EXAMPLES.md`](java/EXAMPLES.md) · [`PlatformConnectorsPipelineExample.java`](java/examples/PlatformConnectorsPipelineExample.java) · fixture bundle `tests/fixtures/cloud_connectors/`.

## Related

- **[CLOUD_AUTH.md](CLOUD_AUTH.md)** — where OS env vars must be set (not Java JSON); Docker / K8s overview
- **[AMAZON_S3.md](AMAZON_S3.md)** — Amazon S3 (`AWS_*`, IAM role, Docker, K8s)
- **[AZURE_ADLS.md](AZURE_ADLS.md)** — Azure ADLS / Blob (`AZURE_*`, Docker, K8s)
- **[SNOWFLAKE.md](SNOWFLAKE.md)** — Snowflake stage + optional `COPY INTO`
- [`docs/java/EXAMPLES.md`](java/EXAMPLES.md) — connector cookbook with warehouse vs Polars SQL
- [`python-wrapper/API.md`](../python-wrapper/API.md) — Python ingestion API
- [`docs/adr/006-jvm-orchestration-pipeline-json.md`](adr/006-jvm-orchestration-pipeline-json.md) — pipeline sink taxonomy
