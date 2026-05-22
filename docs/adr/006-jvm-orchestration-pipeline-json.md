# ADR 006 — JVM orchestration: `rdp_run_pipeline_json`

## Status

Accepted — implements **`Planning/PHASE3_EPICS.md`** **P3-E1-S9** (orchestration contract) and frames **P3-E1-S10** (lake / JDBC sinks).

## Context

The JVM passes **control-plane JSON** to Rust; Polars and I/O stay in-process. We need a **single document** shape that can evolve, explicit **versioning**, operator-visible **limits** (timeouts, row budgets), and a **stable machine-readable error taxonomy** for CI and Java callers.

## Decision

1. **Symbol:** `rdp_run_pipeline_json` remains the orchestration entry (UTF‑8 JSON payload, JSON slice out).
2. **Version field:** `pipeline_spec_version` (integer, **≥ 1**). **`version`** is accepted as a serde **alias** for back-compat. Unsupported values (**> 2** today) fail with `ORCHESTRATION_VERSION_UNSUPPORTED` / stage `parse`.
3. **Orchestration block (optional):**
   - `timeout_ms` — wall-clock budget; **0** = disabled. Checked after parse, after ingest, after transform, and before each sink. Failure: `ORCHESTRATION_TIMEOUT` with stage `parse` | `ingest` | `transform` | `sink` | `sink_sql`.
   - `max_ingested_rows` — cap on rows from ordered-path ingest; **0** = disabled. Failure: `ORCHESTRATION_ROW_BUDGET` / stage `ingest`.
   - `idempotency_key` — opaque string **echoed** in the success envelope only; no idempotent commit or dedupe in Rust yet.
4. **Success envelope** (`ok: true`): `interchange` includes an **`orchestration`** summary (`pipeline_spec_version`, `idempotency_key`, `elapsed_ms`, requested `timeout_ms` / `max_ingested_rows`).
5. **Structured failures** (`ok: false`): `error` is an **object** `{ "code", "message", "stage" }` (not a plain string). Parity exports and other symbols keep the legacy string `error` where unchanged.
6. **Sink taxonomy (P3-E1-S10):**
   - **`postgresql`** — native **`COPY`** when `rdp-jvm-sys` is built with **`sink_postgres`**; otherwise `status: skipped`, `error_code: POSTGRES_SINK_NOT_BUILT`. Driver errors: `POSTGRES_SINK_FAILED`.
   - **`jdbc`** — **not linked**; always `status: unsupported`, `error_code: JDBC_PROTOCOL_NOT_LINKED` (callers must use **`postgresql://`** or **`parquet_file`** staging).
   - **`delta_lake` / `iceberg`** — `connector_pending` plus stable **`error_code`** (`DELTA_LAKE_CONNECTOR_PENDING`, `ICEBERG_CONNECTOR_PENDING`) until native catalog writers ship in-tree.
   - **`snowflake` / `databricks` / `spark` / `object_store` / `delta_lake`** — Rust executes when `rdp_jvm_sys` links **`cloud_connectors`**: object-store read/write, Parquet staging under Delta/Databricks paths, Spark **`handoff_uri`** writes. Snowflake **`COPY INTO`** is optional (stage write always in Rust; COPY when credentials + driver land).
7. **Object-store sources:** declare read URIs in **`sources.object_store_uris`**. Rust ingests via **`object_store`**; results appear in **`object_store_source_results`** with `status: ok`. Local **`sources.paths`** are optional if URIs are set. Cloud URIs in **`sources.paths`** still fail with **`OBJECT_STORE_SOURCE_NOT_SUPPORTED`**.
8. **DB sources:** declare ConnectorX URLs + SQL in **`sources.db_reads`** (`postgresql://`, `oracle://`, `mssql://`, `mysql://` — not `jdbc:`). Requires **`rdp_jvm_sys`** built with **`db_connectorx`**; results in **`db_source_results`**. Without that feature, ingest fails with **`DB_CONNECTORX_NOT_BUILT`**.

## Consequences

- Java tests that assert on **`error`** for **`rdp_run_pipeline_json`** must treat **`error`** as either `String` (legacy callers) or **`JSONObject`** (orchestration failures). New contract tests assert **`error.code`**.
- Bumping **`pipeline_spec_version`** is the primary evolution lever; breaking JSON field renames should coincide with a version bump and this ADR update.

## Links

- **`bindings/jvm-sys/src/pipeline_run.rs`**
- **`Planning/PHASE3_EPICS.md`** — P3-E1-S9, P3-E1-S10
