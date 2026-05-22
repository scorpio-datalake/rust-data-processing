# Cloud authentication — Rust, Python, and Java

This document explains **where credentials live** when `rust-data-processing` or `rdp_jvm_sys` reads and writes `s3://`, `gs://`, `abfss://`, and related URIs. For per-connector URLs and copy-paste examples, see [CONNECTORS.md](CONNECTORS.md).

**Fake values below are placeholders only.**

> **Open this guide as the file** [`docs/CLOUD_AUTH.md`](CLOUD_AUTH.md) — it is a single markdown file, not a folder. If the editor says *“is a directory”*, you clicked a broken `#fragment` link. Use dedicated files: **[AMAZON_S3.md](AMAZON_S3.md)** · **[AZURE_ADLS.md](AZURE_ADLS.md)** · **[SNOWFLAKE.md](SNOWFLAKE.md)**.

## Core rule

Rust performs cloud I/O. **Python and Java are thin wrappers:** they pass **URIs and pipeline JSON** across FFI; they do **not** pass access tokens, Azure AD secrets, or AWS keys in that JSON.

Credentials are resolved by the **[`object_store`](https://docs.rs/object_store/latest/object_store/)** crate inside the **process that loaded the native library** (`rdp_jvm_sys` `.so` / `.dylib`, Python extension, or Rust binary). Those credentials come from the **operating-system environment** of that process — not from Java APIs, not from `System.getenv` configuration inside your application code unless your launcher actually exported vars into the process first.

```text
Java / Python                    Rust (same process)
─────────────                    ─────────────────
pipeline JSON  ──FFI──►  parse URI → object_store::parse_url_opts
  (location only)              │
                               ▼
                         credential chain from env / MSI / IAM / keys
                               │
                               ▼
                         GET/PUT to s3:// / abfss:// / gs://
```

Implementation entry points:

- [`src/ingestion/object_store.rs`](../src/ingestion/object_store.rs) — `ingest_from_object_store_uri`, `export_dataset_to_object_store_uri`
- [`src/ingestion/delta_lake.rs`](../src/ingestion/delta_lake.rs) — `delta_table_uri`, `write_dataset_to_delta_table` (Parquet under the table path)
- [`bindings/jvm-sys/src/pipeline_run.rs`](../bindings/jvm-sys/src/pipeline_run.rs) — `sources.object_store_uris`, sinks `databricks`, `object_store`, `snowflake` stage URIs

`parse_url_opts` is called with **no** credential map from callers — only the URL string.

## System environment variables (not Java-specific)

Names like `AWS_ACCESS_KEY_ID`, `AZURE_CLIENT_SECRET`, and `GOOGLE_APPLICATION_CREDENTIALS` are **standard OS / process environment variables**. They are **not** a special “Java environment” or JVM system property namespace.

| What people sometimes assume | What actually happens |
| --- | --- |
| Set vars in Java code with `System.setProperty` | **Does not work** for `object_store` — Rust reads the **process** env block your OS provides at startup |
| Configure only in IDE “Environment” for a Java main | Works **only if** that IDE/runner exports those vars **into the process** before `rdp_jvm_sys` loads (same as any native library) |
| Put secrets in `application.properties` | **Ignored** by Rust I/O unless **your** launcher copies them into real env vars before calling FFI |

**Who must see the variables:** the **single OS process** that loads `rdp_jvm_sys` (e.g. `java …`, `python …`, or a Rust binary). When Java calls native code, Rust runs **in the same process** as the JVM — so Docker/K8s env injection for the **container** (or pod) is what matters.

### Local shell (development)

```bash
export AWS_ACCESS_KEY_ID="..."
export AWS_SECRET_ACCESS_KEY="..."
java -jar your-etl.jar   # JVM inherits the shell’s environment
```

### Docker

Inject at **container** level — not inside Java source:

```dockerfile
# Prefer runtime injection (secrets manager, --env-file) over baking secrets into the image
ENV AWS_REGION=us-east-1
# Do NOT commit real keys in Dockerfile layers
```

```bash
docker run --env-file /secure/rdp.env your-image:tag
# or
docker run \
  -e AWS_ACCESS_KEY_ID=... \
  -e AWS_SECRET_ACCESS_KEY=... \
  -e AZURE_TENANT_ID=... \
  -e AZURE_CLIENT_ID=... \
  -e AZURE_CLIENT_SECRET=... \
  your-image:tag
```

Use a **`.env` file** only on the host or in CI to populate `docker run --env-file`; keep `.env` out of git (`.gitignore`). For production, prefer a secret store that mounts or injects env at deploy time.

### Kubernetes

Map secrets to **pod environment variables** (or use workload identity so fewer static keys are needed):

```yaml
apiVersion: v1
kind: Pod
spec:
  containers:
    - name: etl
      image: your-registry/rdp-etl:latest
      envFrom:
        - secretRef:
            name: rdp-cloud-credentials   # keys: AWS_ACCESS_KEY_ID, AWS_SECRET_ACCESS_KEY, …
      # Optional single vars:
      env:
        - name: AZURE_STORAGE_ACCOUNT_NAME
          value: "storacc01"
        - name: GOOGLE_APPLICATION_CREDENTIALS
          value: "/var/secrets/gcp/sa.json"
      volumeMounts:
        - name: gcp-sa
          mountPath: /var/secrets/gcp
          readOnly: true
  volumes:
    - name: gcp-sa
      secret:
        secretName: gcp-service-account
```

**Azure / AWS on K8s:** often you omit static keys and bind a **ServiceAccount** to IAM (EKS) or use **Workload Identity** (AKS) so the pod gets credentials without `AWS_*` / `AZURE_CLIENT_SECRET` in a Secret — still **platform env/metadata**, not Java config.

### Python and Rust binaries

Same rule: set env on the **process** (`export` in shell, `docker run -e`, K8s `env`, systemd `Environment=`, etc.). `maturin run` / `cargo run` inherit the parent shell unless you inject vars in the job definition.

### Quick reference (implemented today)

| Store / protocol | URI examples | OS / process env (see sections below) | In pipeline JSON? |
| --- | --- | --- | --- |
| [Amazon S3](AMAZON_S3.md) | `s3://bucket/key` | `AWS_*` or IAM role | Location only |
| [Google Cloud Storage](#google-cloud-storage) | `gs://` / `gcs://` | `GOOGLE_APPLICATION_CREDENTIALS` or GCE/GKE identity | Location only |
| [Azure ADLS](AZURE_ADLS.md) | `abfss://`, `azure://` | `AZURE_*` / MSI / account key | Location only |
| [Snowflake](SNOWFLAKE.md) | Often `s3://…` for stage I/O | Stage: [AMAZON_S3.md](AMAZON_S3.md); `COPY` optional: `SNOWFLAKE_*` | Account URL in sink JSON; not storage secrets |
| [Databricks warehouse](#databricks-pipeline-sink-kind-databricks) | `abfss://` or `s3://` under `warehouse` | Same as Azure or S3 for that URI | `warehouse` path only; PAT not used in-tree |
| [SFTP](#sftp-not-implemented) | `sftp://…` | N/A — not implemented | N/A |
| [FTP](#ftp-not-implemented) | `ftp://` / `ftps://` | N/A — not implemented | N/A |

---

## Two different “auth” stories (do not mix them up)

| Layer | What it protects | Used by this repo for `abfss://` I/O? | Where you configure it |
| --- | --- | --- | --- |
| **Cloud storage** (ADLS Gen2, S3, GCS) | Read/write blobs at `abfss://…`, `s3://…`, `gs://…` | **Yes** — all real bytes go through `object_store` | OS env / MSI / IAM on the **container or host process** |
| **Databricks workspace** (REST, notebooks, cluster OAuth, PAT `dapi…`) | Databricks APIs, SQL warehouses, cluster UI | **No for in-tree sinks today** | Databricks / Spark **outside** this FFI path |

---

## Amazon S3

Full guide (AWS env vars, IAM role, Docker, K8s, Java/Rust/Python): **[AMAZON_S3.md](AMAZON_S3.md)**.

---

## Google Cloud Storage

| Method | Environment / host setup |
| --- | --- |
| Service account JSON | `GOOGLE_APPLICATION_CREDENTIALS=/path/to/service-account.json` |
| GCE / GKE workload identity | Metadata server on the VM or pod — no path in JSON |
| User ADC (local dev) | `gcloud auth application-default login` on the machine running Rust |

**URI:** `gs://demo-gcs-project/rdp/incoming/part-00000.parquet` (validation also accepts `gcs://`).

### Rust / Python / Java

- **Rust:** export `GOOGLE_APPLICATION_CREDENTIALS` in the shell, then call `ingest_from_object_store_uri` / `export_dataset_to_object_store_uri`.
- **Python:** same env on the notebook or `maturin` process.
- **Java:** only the URI in `object_store_uris` or sink JSON; set `GOOGLE_APPLICATION_CREDENTIALS` on the **pod/container/process** (Docker `--env-file`, K8s `env`, etc.) — not via Java-only config files alone.

```json
"object_store_uris": ["gs://demo-gcs-project/rdp/incoming/part-00000.parquet"]
```

---

## Azure ADLS Gen2

Full guide (env vars, Java/Rust/Python, Databricks `abfss://` warehouse): **[AZURE_ADLS.md](AZURE_ADLS.md)**.

---

## Databricks pipeline sink (`kind: databricks`)

Java (and Rust/Python via the same layout) often include:

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

| Field | Role in-tree today |
| --- | --- |
| `warehouse` | **Required** — `abfss://` or `s3://` root; Rust builds `…/namespace/table/part-rdp-000.parquet` and writes via `object_store` |
| `namespace`, `table` | Path layout (`main.curated` → `main/curated/` under the warehouse) |
| `workspace_url`, `catalog_uri` | **Metadata only** — echoed in `sink_results`; **no** HTTP call or PAT/OAuth use in Rust |

**Auth you must configure:** for `abfss://` warehouse → [AZURE_ADLS.md](AZURE_ADLS.md); for `s3://` warehouse → [AMAZON_S3.md](AMAZON_S3.md).

A Databricks **PAT** (`dapi…`) or **workspace OAuth app** does **not** authenticate the in-tree write. Those are for Databricks REST, SQL warehouses, and Spark drivers you run separately.

Full Delta transaction logs (ACID, time travel) are not committed yet; see [`delta_lake.rs`](../src/ingestion/delta_lake.rs).

---

## Snowflake

Full guide (stage `AWS_*`, optional `SNOWFLAKE_*`, Docker/K8s, Java/Rust/Python): **[SNOWFLAKE.md](SNOWFLAKE.md)**.

---

## Apache Spark handoff

Rust writes Parquet to `handoff_uri` (`s3://`, `abfss://`, or `file://`).

| Concern | Where auth lives |
| --- | --- |
| Rust write to `handoff_uri` | [AMAZON_S3.md](AMAZON_S3.md) or [AZURE_ADLS.md](AZURE_ADLS.md) env on the OS process |
| Spark read in your cluster | **Your** `spark-submit` / Databricks cluster (Kerberos, PAT, OAuth) — not Rust FFI |

See [CONNECTORS.md — Apache Spark](CONNECTORS.md#apache-spark).

---

## SFTP (not implemented)

**Status:** No `sftp://` handler in `rdp_jvm_sys` or `rust-data-processing` today.

**Planned URL shape:** `sftp://etl_user:FAKE_SFTP_PASS@sftp.example.com:22/rdp/incoming/data.parquet`

| Auth (future) | Notes |
| --- | --- |
| Password | User in URL or env — **do not commit real passwords** to git |
| SSH private key | Path via env (e.g. `SFTP_PRIVATE_KEY_PATH`) — not in pipeline JSON |
| Host key trust | Known-hosts file or env — TBD when implemented |

**Workaround today:** sync files to S3, ADLS, GCS, or local disk with your own SFTP client (`scp`, `sftp`, Airflow operator, etc.), then:

```json
"object_store_uris": ["s3://demo-bucket-us-east-1/rdp/incoming/from-sftp.parquet"]
```

or `sources.paths` for a local copy.

When SFTP is added, expect the same rule: **Rust performs I/O; Java/Python pass URIs; credentials on the native process** (not in JSON).

---

## FTP (not implemented)

**Status:** No `ftp://` or `ftps://` handler in-tree.

**Planned URL:** `ftp://etl_user:FAKE_FTP_PASS@ftp.example.com:21/rdp/incoming/data.parquet`

| Auth (future) | Notes |
| --- | --- |
| User / password | Env or URL placeholder — prefer env for production |
| Explicit TLS (FTPS) | Often port 990 — same implementation gap as FTP |

**Workaround:** same as [SFTP](#sftp-not-implemented) — land files on object storage or local paths, then use `object_store_uris` or `sources.paths`.

---

## What is never in pipeline JSON

| Do not put in JSON | Why |
| --- | --- |
| `application.properties` / Spring `aws.*` alone | Rust does not read Java config files — map to **OS env** at deploy time |
| `System.setProperty("AWS…")` without exporting to env | Native code uses the process **environment block**, not JVM system properties |
| `AWS_ACCESS_KEY_ID`, `AWS_SECRET_ACCESS_KEY` | AWS chain reads OS env on the process |
| `AZURE_CLIENT_SECRET`, `AZURE_TENANT_ID`, bearer tokens | Azure client reads env / MSI on the Rust process |
| `GOOGLE_APPLICATION_CREDENTIALS` path | GCS client reads env on the Rust process |
| `dapi…` Databricks PAT | Not used by in-tree `databricks` sink (storage path only) |
| SFTP/FTP passwords for production | Not implemented; use object-store workaround |
| `jdbc:…` for DB read | Use ConnectorX `oracle://` / `mssql://` in `sources.db_reads` — see [CONNECTORS.md](CONNECTORS.md) |

---

## Mental model (all clouds)

| | S3 | Azure ADLS (`abfss://`) | GCS |
| --- | --- | --- | --- |
| In JSON | `s3://bucket/key` | `abfss://container@account.dfs…/path` | `gs://bucket/path` |
| Credentials | `AWS_*` or IAM role | `AZURE_*` / MSI / account key | `GOOGLE_APPLICATION_CREDENTIALS` or workload identity |
| Java’s job | Pass URI + call FFI | Pass URI + call FFI | Pass URI + call FFI |
| Rust’s job | `object_store` + AWS chain | `object_store` + Azure builder | `object_store` + GCP |

**Bottom line:** Rust obtains storage tokens without Java or Python handing them over, as long as the **native library’s process** is configured correctly. Databricks workspace OAuth/PAT is a separate concern until REST/catalog integration is added.

---

## Build features

| Component | Feature |
| --- | --- |
| Rust crate | `cloud_connectors` (includes `object_store`, Delta staging) |
| Python | `cloud` on `python-wrapper` |
| JVM | `rdp_jvm_sys` `link-main` (pulls `cloud_connectors`) |

DB read (`sources.db_reads`) is separate: `db_connectorx` on JVM — see [CONNECTORS.md](CONNECTORS.md).

---

## Related docs

- [AMAZON_S3.md](AMAZON_S3.md) — Amazon S3 auth (dedicated file)
- [AZURE_ADLS.md](AZURE_ADLS.md) — Azure ADLS / Blob auth (dedicated file)
- [SNOWFLAKE.md](SNOWFLAKE.md) — Snowflake stage + optional COPY (dedicated file)
- [CONNECTORS.md](CONNECTORS.md) — shared URLs and language snippets
- [java/EXAMPLES.md](java/EXAMPLES.md) — JVM pipeline examples
- [adr/006-jvm-orchestration-pipeline-json.md](adr/006-jvm-orchestration-pipeline-json.md) — pipeline envelope and source kinds
