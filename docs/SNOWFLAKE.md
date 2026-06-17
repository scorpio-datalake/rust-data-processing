# Snowflake authentication

Snowflake integration in `rust-data-processing` / `rdp_jvm_sys` splits into **two auth layers**. Both use **system/OS environment variables** on the process that loads the native library — not Java properties or secrets in pipeline JSON.

**Related:** [AMAZON_S3.md](AMAZON_S3.md) (stage on S3) · [CLOUD_AUTH.md](CLOUD_AUTH.md) (Docker, Kubernetes) · [CONNECTORS.md](CONNECTORS.md) (example URIs per language)

**Fake values below are placeholders only.**

---

## Two layers (do not mix them up)

| Step | What runs | Auth |
| --- | --- | --- |
| **Stage write** | Rust writes Parquet to `stage_uri` (usually `s3://…`) via `object_store` | [AMAZON_S3.md](AMAZON_S3.md) — `AWS_*` or IAM role on the **OS process** |
| **`COPY INTO` (optional)** | Snowflake driver path when wired (`snowflake` feature) | `SNOWFLAKE_USER` / `SNOWFLAKE_PASSWORD` (or key pair / SSO in your tooling) on the **same process** |

Pipeline JSON may include `account_url`, `warehouse`, `database`, `schema`, `table`, `role` for orchestration metadata. **Passwords and AWS keys do not belong in JSON.**

---

## Stage URI (object store)

Rust always lands Parquet on the external stage path using the scheme of `stage_uri`:

```json
"stage_uri": "s3://demo-bucket-us-east-1/snowflake-stage/rdp/"
```

| `stage_uri` scheme | Configure on container/host |
| --- | --- |
| `s3://` | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, optional `AWS_SESSION_TOKEN`, or IAM role — see [AMAZON_S3.md](AMAZON_S3.md) |
| `abfss://` | `AZURE_*` or managed identity — see [AZURE_ADLS.md](AZURE_ADLS.md) |
| `gs://` | `GOOGLE_APPLICATION_CREDENTIALS` — see [CLOUD_AUTH.md](CLOUD_AUTH.md) |

Java does not pass cloud keys in JSON. Inject them via shell `export`, Docker `--env-file`, or Kubernetes `env` / Secrets on the **pod running `rdp_jvm_sys`**.

---

## Snowflake account (optional `COPY INTO`)

When using Rust helpers `copy_into_table_from_stage` (feature `snowflake` on the crate), set on the **same OS process**:

```bash
export SNOWFLAKE_USER="etl_user"
export SNOWFLAKE_PASSWORD="FAKE_SF_PASS"
```

| Method | Notes |
| --- | --- |
| Password | `SNOWFLAKE_USER` + `SNOWFLAKE_PASSWORD` |
| Key pair | `SNOWFLAKE_USER` + private key file (not fully automated in-tree yet) |
| OAuth / SSO | IdP token in your Snowflake client (outside this repo) |

`COPY INTO` is optional in-tree; **stage write** via `write_dataset_to_snowflake_stage` / pipeline `kind: snowflake` works without Snowflake password if you only need Parquet on the stage.

---

## Docker / Kubernetes

```bash
# Example .env for docker run --env-file (stage on S3 + optional COPY)
AWS_ACCESS_KEY_ID=AKIAFAKEEXAMPLE
AWS_SECRET_ACCESS_KEY=FAKE_SECRET_KEY_40_chars_long_demo
SNOWFLAKE_USER=etl_user
SNOWFLAKE_PASSWORD=FAKE_SF_PASS
```

```yaml
envFrom:
  - secretRef:
      name: rdp-snowflake-and-aws   # AWS_* for stage + SNOWFLAKE_* for COPY
```

---

## Rust

```rust
use rust_data_processing::ingestion::{write_dataset_to_snowflake_stage, copy_into_table_from_stage};
// --features cloud_connectors

const ACCOUNT: &str = "https://xy12345.us-east-1.snowflakecomputing.com";
const STAGE: &str = "s3://demo-bucket-us-east-1/snowflake-stage/rdp/load.parquet";
// write_dataset_to_snowflake_stage(STAGE, &ds)?;
// copy_into_table_from_stage(
//     ACCOUNT, Some("COMPUTE_WH"), Some("DEMO_DB"), Some("CURATED"),
//     "FACT_SCORES", STAGE, Some("ETL_ROLE"),
// )?;
```

Set `AWS_*` before stage write; set `SNOWFLAKE_*` before optional `COPY INTO`.

---

## Python

```python
# Stage + optional COPY: use JVM pipeline JSON or Rust until PyO3 exposes snowflake helpers.
STAGE = "s3://demo-bucket-us-east-1/snowflake-stage/rdp/load.parquet"
ACCOUNT = "https://xy12345.us-east-1.snowflakecomputing.com"
```

Set `AWS_*` and optional `SNOWFLAKE_*` on the **Python process**.

---

## Java

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

```bash
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
export SNOWFLAKE_USER="etl_user"
export SNOWFLAKE_PASSWORD="FAKE_SF_PASS"
java -cp … com.example.PlatformConnectorsPipelineExample
```

Runnable example: [`PlatformConnectorsPipelineExample.java`](java/examples/PlatformConnectorsPipelineExample.java).

---

## Warehouse SQL (outside FFI)

`COPY INTO @stage …`, merges, and warehouse DDL run in **Snowflake** or your ETL tool — not inside `rdp_jvm_sys`. After data is on the stage (S3/ADLS/GCS), you can also **ingest** from the object URI with `sources.object_store_uris` and the matching cloud env vars.
