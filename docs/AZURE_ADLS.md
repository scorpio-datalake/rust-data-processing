# Azure ADLS Gen2 and Blob Storage (ABFSS)

Authentication for `abfss://`, `abfs://`, `azure://`, and `az://` URIs in `rust-data-processing` / `rdp_jvm_sys`.

**Related:** [CLOUD_AUTH.md](CLOUD_AUTH.md) (all clouds, Docker, Kubernetes) · [CONNECTORS.md](CONNECTORS.md) (example URIs per language)

**Fake values below are placeholders only.**

Credentials are **system/OS environment variables** on the process that loads the native library — not Java properties or entries in pipeline JSON. See [CLOUD_AUTH.md — System environment variables](CLOUD_AUTH.md#system-environment-variables-not-java-specific) for Docker `.env`, `docker run --env-file`, and Kubernetes `env` / Secrets.

There is **no** Azure AD block in Java pipeline JSON. Configure the **container or host** that runs your JVM, Python interpreter, or Rust binary.

Blob Storage (`azure://` / `az://`) and ADLS Gen2 (`abfss://` / `abfs://`) use the same Azure credential resolution in [`object_store`](https://docs.rs/object_store/latest/object_store/azure/index.html).

---

## Option A — Service principal (Azure AD app → storage)

Typical for CI and unattended ETL. Grant **Storage Blob Data Contributor** (or equivalent) on account `storacc01` and container `datalake`.

```bash
export AZURE_TENANT_ID="11111111-1111-1111-1111-111111111111"
export AZURE_CLIENT_ID="22222222-2222-2222-2222-222222222222"
export AZURE_CLIENT_SECRET="FAKE_CLIENT_SECRET"
export AZURE_STORAGE_ACCOUNT_NAME="storacc01"
```

`object_store` also accepts aliases such as `azure_storage_client_id`, `azure_client_id`, `azure_storage_tenant_id`, `azure_storage_client_secret`, etc.

---

## Option B — Storage account key (shared key, not AAD)

```bash
export AZURE_STORAGE_ACCOUNT_NAME="storacc01"
export AZURE_STORAGE_ACCOUNT_KEY="FAKE_AZURE_KEY_BASE64=="
```

---

## Option C — Managed identity

Run on Azure VM, AKS pod, or Azure-hosted agent with a user-assigned or system-assigned identity that can access the storage account. No secret in Java; identity is on the **host** running `rdp_jvm_sys`.

On Kubernetes, prefer workload identity over static secrets when possible.

---

## Option D — Local development

Some environments use Azure CLI login (`az login`) via object_store’s Azure CLI credential path — still on the **Rust process**, not passed from Java.

---

## URI shape

`abfss://container@storacc01.dfs.core.windows.net/path/to/object.parquet`

- **Container** and **account** come from the URI hostname.
- **Tokens and tenant IDs do not** go in the URI string.

---

## Java

Only the URI appears in pipeline JSON; inject `AZURE_*` (or use MSI) on the **pod/container/process**:

```json
"object_store_uris": [
  "abfss://datalake@storacc01.dfs.core.windows.net/rdp/incoming/part-00000.parquet"
]
```

```json
{
  "kind": "databricks",
  "warehouse": "abfss://datalake@storacc01.dfs.core.windows.net/unity/",
  "namespace": "main.curated",
  "table": "fact_scores"
}
```

```bash
# Shell — use Docker --env-file or K8s Secret → env in production
export AZURE_TENANT_ID="..."
export AZURE_CLIENT_ID="..."
export AZURE_CLIENT_SECRET="..."
export AZURE_STORAGE_ACCOUNT_NAME="storacc01"
java -cp … com.example.YourPipelineMain
```

---

## Rust

```rust
use rust_data_processing::ingestion::{delta_table_uri, write_dataset_to_delta_table};

const WH: &str = "abfss://datalake@storacc01.dfs.core.windows.net/unity/";
let table_uri = delta_table_uri(WH, Some("curated"), "fact_scores");
// write_dataset_to_delta_table(&table_uri, &ds)?;
```

Set `AZURE_*` in the shell (or container env) before `cargo run`.

---

## Python

```python
WH = "abfss://datalake@storacc01.dfs.core.windows.net/unity/"
TABLE_URI = f"{WH.rstrip('/')}/curated/fact_scores/"
# export_dataset_to_object_store_uri(..., ds)  # --features cloud
```

Set `AZURE_*` on the **Python process** (notebook kernel, `maturin` shell, etc.).

---

## Databricks `warehouse` on ADLS

In-tree `kind: databricks` writes Parquet under `warehouse` using **storage** auth above — not a Databricks PAT. `workspace_url` and `catalog_uri` in JSON are metadata only. Details: [CLOUD_AUTH.md — Databricks sink](CLOUD_AUTH.md#databricks-pipeline-sink-kind-databricks).
