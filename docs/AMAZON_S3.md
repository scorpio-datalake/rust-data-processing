# Amazon S3 authentication

Authentication for `s3://` URIs in `rust-data-processing` / `rdp_jvm_sys`.

**Related:** [CLOUD_AUTH.md](CLOUD_AUTH.md) (all clouds, Docker, Kubernetes) · [CONNECTORS.md](CONNECTORS.md) (example URIs and code per language)

**Fake values below are placeholders only.**

Credentials are **system/OS environment variables** on the process that loads the native library — not Java properties or entries in pipeline JSON. See [CLOUD_AUTH.md — System environment variables](CLOUD_AUTH.md#system-environment-variables-not-java-specific) for Docker `.env`, `docker run --env-file`, and Kubernetes `env` / Secrets.

**URI in JSON = location only.** No `AWS_ACCESS_KEY_ID` in pipeline JSON. Rust passes `AWS_*` from the **process environment** into [`object_store::parse_url_opts`](https://docs.rs/object_store/latest/object_store/fn.parse_url_opts.html) when opening `s3://` buckets (static keys, `AWS_ENDPOINT` for MinIO, or instance metadata if no keys are set).

---

## Credential methods

| Method | Environment / host setup |
| --- | --- |
| Static keys | `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY`, optional `AWS_SESSION_TOKEN` |
| IAM role | EC2 / EKS / Lambda instance profile on the **pod or VM** — no keys in Java or JSON |
| Named profile | `AWS_PROFILE` in the **process** environment |
| Region | `AWS_REGION` or `AWS_DEFAULT_REGION` (if needed for your bucket) |

If credentials are missing or wrong, ingest fails with an engine/I/O error from Rust — not a separate login step in Java.

---

## Local shell (development)

```bash
export AWS_ACCESS_KEY_ID="AKIAFAKEEXAMPLE"
export AWS_SECRET_ACCESS_KEY="FAKE_SECRET_KEY_40_chars_long_demo"
# export AWS_SESSION_TOKEN="FAKE_SESSION_TOKEN"   # optional temporary creds
export AWS_REGION="us-east-1"   # if required

java -jar your-etl.jar          # JVM inherits shell env
# python / cargo run — same process env rule
```

---

## Docker

```bash
docker run --env-file /secure/rdp-aws.env your-image:tag
```

```dockerfile
# Prefer runtime injection; do not bake secrets into image layers
ENV AWS_REGION=us-east-1
```

Use a **`.env` file** on the host only to feed `docker run --env-file`; keep it out of git.

---

## Kubernetes

```yaml
envFrom:
  - secretRef:
      name: rdp-aws-credentials   # keys: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, …
```

On EKS, prefer **IRSA** (IAM Roles for Service Accounts) so the pod assumes an IAM role without static keys in a Secret.

---

## Rust

```rust
use rust_data_processing::ingestion::{
    ingest_from_object_store_uri, export_dataset_to_object_store_uri, IngestionOptions,
};
// --features cloud_connectors

const URI: &str = "s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet";
// let ds = ingest_from_object_store_uri(URI, &schema, &opts)?;
// export_dataset_to_object_store_uri("s3://demo-bucket-us-east-1/rdp/out/result.parquet", &ds)?;
```

Set `AWS_*` (or run on a host with an instance role) before `cargo run`.

---

## Python

```python
import rust_data_processing as rdp

URI = "s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet"
schema = [{"name": "id", "data_type": "int64"}, {"name": "name", "data_type": "utf8"}]
ds = rdp.ingest_from_object_store_uri(URI, schema, {"format": "parquet"})  # --features cloud
```

Set the same `AWS_*` variables on the **Python interpreter process**.

---

## Java

Only URIs in pipeline JSON; inject `AWS_*` on the **container or OS process**:

```json
{
  "sources": {
    "paths": [],
    "object_store_uris": ["s3://demo-bucket-us-east-1/rdp/incoming/part-00000.parquet"],
    "schema": { "fields": [] },
    "options": { "format": "parquet" }
  },
  "sinks": [
    {
      "kind": "object_store",
      "uri": "s3://demo-bucket-us-east-1/rdp/out/",
      "format": "parquet"
    }
  ]
}
```

```bash
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
java -cp … com.example.YourPipelineMain
```

---

## Also used for

| Feature | URI field |
| --- | --- |
| Spark handoff | `handoff_uri` when `s3://…` |
| Snowflake stage write | `stage_uri` when `s3://…` — see [SNOWFLAKE.md](SNOWFLAKE.md) |
| Databricks warehouse | `warehouse` when `s3://…` — see [AZURE_ADLS.md](AZURE_ADLS.md) for `abfss://` |
