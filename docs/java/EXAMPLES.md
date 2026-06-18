---
title: "Java examples — JVM bindings (Panama + JSON parity)"
---

# Java quick start and examples

This page is the **JVM counterpart** to the Python tour published as **`python/examples.html`** on the docs site (Markdown source: [`docs/python/README.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/python/README.md)). The Python package calls Rust through **PyO3** with a rich in-process API. On the JVM, Phase 3 exposes a **narrower surface**: a native **`rdp_jvm_sys`** library, an **`ffi_manifest.json`** list of `extern "C"` symbols, and **JSON parity exports** (`rdp_parity_*`) you call with **Panama (FFM)** from Java.

**Canonical API detail** for symbols and calling convention: [`FFI_MANIFEST_JAVA_USAGE.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/java/FFI_MANIFEST_JAVA_USAGE.md), [`FFI_API_SLICE.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/java/FFI_API_SLICE.md), and [`Planning/PHASE3_EPICS.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/Planning/PHASE3_EPICS.md).

**Runnable code** in the repository: [`bindings/java/rust-data-processing-jvm-examples/README.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm-examples/README.md) (`LoadFfiManifestExample`, `RunPytestMirrorExample`, `ParityScenariosWalkthrough`, …).

## What this page covers

Use this as a **tour of how Java integrates today** (parity JSON over FFI), not a line-for-line duplicate of every Python method. For full signatures and options on the Python side, see [`python-wrapper/API.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/python-wrapper/API.md).

| Topic | Where below |
| --- | --- |
| **Maven / native library / JVM flags** | [Prerequisites](#prerequisites) |
| **Discover symbols, ABI** | [FFI manifest and ABI](#ffi-manifest-and-abi) |
| **Invoke any parity export** | [Calling `rdp_parity_*` from Java](#calling-rdp_parity_-from-java) |
| **File ETL** (ingestion, tabular JSON) | [`rdp_parity_ingestion`](#file-etl-ingestion-and-tabular-json), [`rdp_parity_types_dataset`](#file-etl-ingestion-and-tabular-json) |
| **Ordered paths, directory scans, watermarks, Hive-style discovery** | [Ordered paths and directory scans (incremental batches)](#ordered-paths-and-directory-scans-incremental-batches) |
| **SQL & lazy plans** | [`rdp_parity_pipeline_sql`](#sql-and-dataframe-parity), [`rdp_parity_sql_suite_mirror`](#sql-and-dataframe-parity) |
| **Declarative transforms** | [`rdp_parity_transform`](#transform-and-mapping-spec) |
| **Mapping spec** | [`rdp_parity_mapping_spec_mirror`](#transform-and-mapping-spec) |
| **Row-wise processing** | [`rdp_parity_processing`](#processing-and-execution) |
| **Benchmark-style smoke** | [`rdp_parity_benchmark_smoke_mirror`](#processing-and-execution) |
| **Observability** | [`rdp_parity_observability_mirror`](#observability) |
| **Multi-scenario walkthrough** | [Runnable walkthrough class](#runnable-walkthrough-class) |
| **Large results / production layout** | [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption) |
| **Temp Parquet → local Spark `DataFrame`** | [`rdp_export_parquet_temp`](#temp-parquet-handoff-rdp_export_parquet_temp) |
| **All doc examples** (`docs/java/examples/`) | [Example catalog](#example-catalog), [Why these examples](#why-these-examples), [Shared fixtures](#shared-json-fixtures-testsfixtures) |
| **Phase 2 (Python `PHASE2_EXAMPLES.md`)** | [Phase 2 examples](#phase-2-examples) |
| **Database / data lake (no warehouse driver on FFI)** | [Database and data lake on the JVM](#database-and-data-lake-jvm) |
| **All connectors (Rust / Python / Java, same URLs)** | [`docs/CONNECTORS.md`](../CONNECTORS.md) |

## Why rust-data-processing on the JVM {#why-rust-data-processing-on-the-jvm}

The JVM module is deliberately **thin**: Java does not re-implement CSV parsing, Polars SQL, Excel readers, or pipeline orchestration. You load **`rdp_jvm_sys`** once, pass **UTF-8 JSON** (schemas, pipeline specs, ingest payloads) over **Panama downcalls**, and read back a small **JSON envelope** (`ok`, `interchange`, optional `error`). That design buys you:

- **One engine, three languages** — the same `tests/fixtures/<bundle>/` trees are shared by Rust (`PipelineBundle`), Python (`tests.pipeline_fixture_support`), and Java (`PipelineJsonFixtures` in these examples), so schemas and pipelines stay aligned across bindings.
- **Production-shaped control plane** — orchestration (paths, watermarks, idempotency keys) can live in Java/Kotlin while **heavy transforms stay in Rust**, with sinks writing **Parquet / XML / CSV** to disk instead of shipping millions of rows as JSON (see [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption)).
- **Typed contracts** — serde `Schema` JSON (`data_type`: `Int64`, `Utf8`, …) is validated at ingest time in Rust; examples decode `interchange.dataset` with `PytestMirrorAssertions` and `SerdeDatasetRows` where needed.
- **Inspectable failures** — pipeline failures return structured `error.code` / `error.stage` (ADR 006) in the same JSON envelope shape the examples parse.

**When to reach for which FFI:**

| Goal | Prefer |
| --- | --- |
| Declarative multi-step ETL (sources → SQL on `df` → sinks) | `rdp_run_pipeline_json` + bundle `pipelines/*.pipeline.json` |
| Single-shot multi-file ingest (dataset / temp Parquet / Arrow IPC) | `rdp_ingest_ordered_paths_json` + bundle `payloads/*.payload.json` |
| One file + explicit schema (CSV / JSON / Parquet / XML / Excel sheet) | `rdp_ingest_*_path` or `rdp_excel_ingest_path_sheet` |
| Pytest-shaped regression without crafting payloads | `rdp_parity_*` via `RdpNativeJson.invokeParityExport` |

## Shared JSON fixtures (`tests/fixtures/`) {#shared-json-fixtures-testsfixtures}

Every runnable class under [`docs/java/examples/`](examples/) loads committed JSON from the repo — **not** strings embedded in Java source. Layout (see also [`tests/fixtures/README.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/tests/fixtures/README.md)):

| Bundle | Schemas | Pipelines / payloads | Data files |
| --- | --- | --- | --- |
| `jvm_contract/` | `schemas/three_rows.schema.json`, `id_name.schema.json` | `pipelines/dataframe_centric_sql.pipeline.json`, `sql_query_dataset.pipeline.json`, `ordered_json_to_parquet.pipeline.json`, `payloads/ordered_paths_*.payload.json` | `data/three_rows.json`; also `../jvm_contract_three_rows.json` at fixtures root |
| `ghcn/` | `json_source`, `xml_intermediate`, `parquet_lake` under `schemas/` | `pipelines/json_to_xml.pipeline.json`, `xml_to_parquet.pipeline.json` | `ghcn_stations_sample.json` (5 NOAA stations, committed sample) |
| `people/` | `people_csv`, `people_json`, `people_flat`, … | `pipelines/csv_to_parquet.pipeline.json`, `payloads/*_path_*.json` | `../people.csv`, `../people.json`, `../people.xlsx` |
| `student_etl/` | `student_source`, `lake_grade_stats`, `postgres_courses` | `legacy_student_etl*.pipeline.json`, `payloads/ordered_ingest_*.payload.json` | `data/part-0000*.json` |
| `cloud_connectors/` | `id_name` | `platform_connectors.pipeline.json`, `object_store_sources_only.pipeline.json`, `oracle_db_read.pipeline.json` | `data/two_rows.json` |
| `file_transfer/` | `id_name` | `ftp_sources_only.pipeline.json` | `data/two_rows.json` (FTP demo) |
| `watermark/` | `schemas/events.schema.json` | `payloads/csv_watermark_ingest.body.json`, `directory_scan_two_csv.payload.json` | `../watermark_events.csv` / `.json` |
| `sql_parity/` | `join_left`, `join_right` | `queries/join_people_scores.sql.json` | `data/join_left.json`, `join_right.json` |

**Schema shape** (shared with Rust/Python):

```json
{
  "fields": [
    { "name": "id", "data_type": "Int64" },
    { "name": "active", "data_type": "Bool" },
    { "name": "score", "data_type": "Float64" }
  ]
}
```

**Pipeline shape** — placeholders `{{SOURCE_PATH}}`, `{{SINK_PATH}}`, … are substituted in Java before calling Rust (`tests/fixtures/jvm_contract/pipelines/dataframe_centric_sql.pipeline.json`):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": ["{{SOURCE_PATH}}"],
    "schema_ref": "schemas/three_rows.schema.json",
    "options": { "format": "json" }
  },
  "transform": {
    "sql": "SELECT id, active, (score * 2.0) AS score FROM df WHERE active = TRUE ORDER BY id"
  },
  "sinks": [{ "kind": "parquet_file", "path": "{{SINK_PATH}}" }]
}
```

**Sample input data** (`tests/fixtures/jvm_contract_three_rows.json` — three rows, one inactive):

```json
[
  {"id": 1, "active": true, "score": 10.0},
  {"id": 2, "active": true, "score": 20.0},
  {"id": 3, "active": false, "score": 30.0}
]
```

**Ordered-ingest payload** (`people/payloads/excel_sheet_dataset.payload.json` — `schema_ref` expanded in Rust/Java):

```json
{
  "paths": ["{{SOURCE_PATH}}"],
  "schema_ref": "schemas/people_flat.schema.json",
  "options": { "format": "excel", "sheet_name": "{{SHEET_NAME}}" },
  "response": { "mode": "dataset", "max_rows": 1000 }
}
```

**Path ingest with no extra options** — pass `PipelineJsonFixtures.defaultPathIngestOptionsJson()` (`"{}"`) to `rdp_ingest_*_path`; do not add empty per-bundle `*.options.json` files.

### Loading fixtures from Java

[`PipelineJsonFixtures`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/main/java/io/github/scorpio_datalake/rust_data_processing/fixture/PipelineJsonFixtures.java) resolves `tests/fixtures` by walking up from the working directory until `people.csv` exists (or uses `GITHUB_WORKSPACE` in CI):

```java
Path fixtures = PipelineJsonFixtures.resolveTestsFixturesDir().orElseThrow();
Path bundle = PipelineJsonFixtures.resolveBundleRoot(fixtures, "jvm_contract").orElseThrow();
String pipeline =
    PipelineJsonFixtures.resolvePipelineJson(
        bundle,
        "pipelines/dataframe_centric_sql.pipeline.json",
        Map.of(
            "SOURCE_PATH", inputJson.toAbsolutePath().normalize().toString(),
            "SINK_PATH", parquetOut.toAbsolutePath().normalize().toString()));
JSONObject root =
    RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
```

`schema_ref` inside pipelines and payloads is expanded to an inline `schema` object before the native call. **`SerdeDatasetRows`** decodes `interchange.dataset.rows` cells (serde `Value` tags like `{"Utf8":"Ada"}`).

## Why these examples {#why-these-examples}

The files under [`docs/java/examples/`](examples/) are **copy-paste tutorials** for JVM integrators. They are **not** compiled into the `rust-data-processing-jvm` JAR; you copy a class into your app module (which depends on that JAR plus a native classifier, or a locally built `rdp_jvm_sys`).

Each example shows how to:

- Load the native library (**`rdp-jvm-sys`** classifier on the classpath by default, or **`RDP_JVM_SYS`** / **`-Drdp.jvm.sys.library`** to override) and enable **`--enable-native-access=ALL-UNNAMED`**
- Resolve **`tests/fixtures/<bundle>/`** JSON (schemas, pipelines, payloads) instead of hard-coding SQL or schemas in Java
- Call the right FFI entry point (`rdp_run_pipeline_json`, `rdp_ingest_*_path`, `rdp_parity_*`, …)
- Map the flow to the **Python** tour on the same docs site ([`docs/python/README.md`](../python/README.md), [`PHASE2_EXAMPLES.md`](../python/PHASE2_EXAMPLES.md))

Class-level Javadoc in each `.java` file explains **why** that sketch exists, **what** it calls in Rust, and the Python analogue.

> **JUnit:** Most runnable examples have a matching integration test in [`DocsExampleNativeIntegrationTest`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/test/java/io/github/scorpio_datalake/rust_data_processing/docexamples/DocsExampleNativeIntegrationTest.java) (when `RDP_JVM_SYS` is set in CI). Cloud connector examples use committed **`file://`** URIs in CI (no live S3/Snowflake). **Template-only in CI** (no network): [`DbReadPipelineExample.java`](examples/DbReadPipelineExample.java), [`SftpFtpConnectorsExample.java`](examples/SftpFtpConnectorsExample.java) — run `main` with real URLs locally. Documentation-only: [`ExportFilterRowsMaxUtf8Chars.java`](examples/ExportFilterRowsMaxUtf8Chars.java), [`MedianReduceAndDataFrame.java`](examples/MedianReduceAndDataFrame.java), [`ExecutionEngineNoteExample.java`](examples/ExecutionEngineNoteExample.java), [`DeltaLakeHandoff.java`](examples/DeltaLakeHandoff.java) (prerequisites only). See [Prerequisites](#prerequisites).

## Example catalog {#example-catalog}

All **37** classes in [`docs/java/examples/`](examples/):

### Pipelines, SQL, and multi-format ETL

| Example | What it demonstrates | Native entry point(s) | Fixtures |
| --- | --- | --- | --- |
| [`QuickStartIngestExample.java`](examples/QuickStartIngestExample.java) | Minimal CSV path ingest with explicit schema | `rdp_ingest_csv_path` | `people` |
| [`DataFrameCentricPipeline.java`](examples/DataFrameCentricPipeline.java) | Polars SQL on `df` inside a pipeline (filter active rows, double `score`) → Parquet sink | `rdp_run_pipeline_json` | `jvm_contract` |
| [`SQLQueries.java`](examples/SQLQueries.java) | Single-table SQL via pipeline JSON; JOIN via `rdp_parity_sql_suite_mirror` | `rdp_run_pipeline_json`, `rdp_parity_sql_suite_mirror` | `jvm_contract`, `sql_parity` |
| [`SqlJoinPipelineExample.java`](examples/SqlJoinPipelineExample.java) | JOIN-only tour via `rdp_parity_sql_suite_mirror` | `rdp_parity_sql_suite_mirror` | `sql_parity` (parity data) |
| [`GroupByAggregatesExample.java`](examples/GroupByAggregatesExample.java) | `GROUP BY` / `HAVING` via SQL suite parity | `rdp_parity_sql_suite_mirror` | parity |
| [`CookbookTransformsExample.java`](examples/CookbookTransformsExample.java) | Rename / cast / fill_null mapping spec | `rdp_parity_mapping_spec_mirror` | parity |
| [`ProcessingReduceExample.java`](examples/ProcessingReduceExample.java) | Filter / map / reduce row-wise API | `rdp_parity_processing` | parity |
| [`ProfilingExample.java`](examples/ProfilingExample.java) | Dataset profiling JSON report | `rdp_parity_profiling` | parity |
| [`OutlierDetectionExample.java`](examples/OutlierDetectionExample.java) | IQR outlier detection summary | `rdp_parity_outliers` | parity |
| [`ValidationUtf8Length.java`](examples/ValidationUtf8Length.java) | Validation summary over FFI | `rdp_parity_validation` | parity |
| [`CdcBoundaryExample.java`](examples/CdcBoundaryExample.java) | CDC event type shapes (no live connector) | `rdp_parity_cdc` | parity |
| [`ExecutionEngineNoteExample.java`](examples/ExecutionEngineNoteExample.java) | Documents Python-only `ExecutionEngine` | (none) | — |
| [`GhcnJsonXmlParquetPipeline.java`](examples/GhcnJsonXmlParquetPipeline.java) | JSON → XML → Parquet with three schemas on a NOAA station sample | `rdp_run_pipeline_json`, `rdp_ingest_xml_path`, `rdp_ingest_parquet_path` | `ghcn` |
| [`RDPOnlyETLExample.java`](examples/RDPOnlyETLExample.java) | `postgresql_url` + `lake_sink` URLs in pipeline JSON; local JSON sources | `rdp_run_pipeline_json`, `rdp_ingest_ordered_paths_json` | `student_etl` |
| [`PlatformConnectorsPipelineExample.java`](examples/PlatformConnectorsPipelineExample.java) | Full Snowflake / Databricks / Spark / S3·GCS·ABFS URLs; `object_store_uris` + Rust sinks | `rdp_run_pipeline_json` | `cloud_connectors` |
| [`ObjectStoreUrlsExample.java`](examples/ObjectStoreUrlsExample.java) | S3, GCS, Azure Blob read URIs + working local `parquet_file` sink | `rdp_run_pipeline_json` | `cloud_connectors` |
| [`SftpFtpConnectorsExample.java`](examples/SftpFtpConnectorsExample.java) | SFTP / FTP / FTPS in `file_transfer_uris` | `rdp_run_pipeline_json` | `file_transfer` |
| [`KafkaEltStreamExample.java`](examples/KafkaEltStreamExample.java) | Kafka **Extract → Load** on a broker (one row per message in integration) | `rdp_kafka_export_dataset_json`, `rdp_kafka_poll_window_loaded_json` | (broker) — build **`full,kafka`** |
| [`KafkaEltLoadExample.java`](examples/KafkaEltLoadExample.java) | Kafka **Load** from fixture JSON (no broker) | `rdp_kafka_elt_load_records_json` | `tests/fixtures/kafka/` |
| [`DbReadPipelineExample.java`](examples/DbReadPipelineExample.java) | ConnectorX `sources.db_reads` pipeline sketch (run locally) | `rdp_run_pipeline_json` | `cloud_connectors` |
| [`WarehouseExportHandoffExample.java`](examples/WarehouseExportHandoffExample.java) | Export Parquet locally → `rdp_ingest_parquet_path` | `rdp_run_pipeline_json`, `rdp_ingest_parquet_path` | `people` |
| [`SparkParquetHandoffExample.java`](examples/SparkParquetHandoffExample.java) | Working `rdp_export_parquet_temp`; documents pending `kind: spark` sink | `rdp_export_parquet_temp`, (pipeline JSON in sibling example) | `jvm_contract` (export sample) |

### People fixtures (CSV, JSON, Parquet, Excel)

| Example | What it demonstrates | Native entry point(s) | Fixtures |
| --- | --- | --- | --- |
| [`JsonParquetExcelSnippets.java`](examples/JsonParquetExcelSnippets.java) | Payload and path ingest for JSON/CSV; CSV→Parquet pipeline round-trip | `rdp_ingest_ordered_paths_json`, `rdp_ingest_*_path`, `rdp_run_pipeline_json` | `people` |
| [`InferredSchemaIngestExample.java`](examples/InferredSchemaIngestExample.java) | Excel infer-then-ingest inside Rust (`rdp_excel_ingest_path_sheet`) | `rdp_excel_ingest_path_sheet` | `people.xlsx` |
| [`ExcelSnippets.java`](examples/ExcelSnippets.java) | Excel sheet ingest via payload and `rdp_excel_ingest_path_sheet` | `rdp_ingest_ordered_paths_json`, `rdp_excel_ingest_path_sheet` | `people` (+ `people.xlsx`) |
| [`ParquetSnippets.java`](examples/ParquetSnippets.java) | CSV→Parquet pipeline, path verify, temp Parquet export handoff | `rdp_run_pipeline_json`, `rdp_ingest_parquet_path`, `rdp_export_parquet_temp` | `people` |

### Incremental batches (watermarks, directory scan)

| Example | What it demonstrates | Native entry point(s) | Fixtures |
| --- | --- | --- | --- |
| [`OrderedPaths.java`](examples/OrderedPaths.java) | Java glob → `paths` array → ingest with watermark options | `rdp_ingest_ordered_paths_json` | `watermark` |
| [`PathFromDirectoryScan.java`](examples/PathFromDirectoryScan.java) | Back-compat alias; delegates to `OrderedPaths` | (same as `OrderedPaths`) | `watermark` |
| [`PartitionDiscoveryExample.java`](examples/PartitionDiscoveryExample.java) | Hive partition discovery, globs, explicit lists | `rdp_parity_partition_discovery_mirror` | `hive_partitioned` |
| [`IngestObservabilityExample.java`](examples/IngestObservabilityExample.java) | Missing-file ingest errors / observability hooks | `rdp_parity_observability_mirror` | parity |

## Database and data lake on the JVM {#database-and-data-lake-jvm}

This is **not** the same model as opening a warehouse connection in Java and streaming rows across the FFI boundary. On the JVM you pass **UTF-8 JSON** (pipeline specs, ingest payloads, schemas) and **local filesystem paths** for sources. Connection strings and lake catalog URIs appear **inside that JSON**; **Rust** (`rdp_jvm_sys`) interprets them and uses the crate’s own drivers (file readers, optional PostgreSQL **sink** via libpq, optional **`sources.db_reads`** via ConnectorX when built with **`db_connectorx`**, etc.). Java does **not** pass a live database session, Spark session, or Delta catalog handle across FFI.

| What Java developers often expect | What this library does on the JVM |
| --- | --- |
| `jdbc:…` URL or `DataSource` → direct ingest FFI | **Not supported** — use **`sources.db_reads`** (`oracle://`, `mssql://`, …) or **local file** handoff |
| SQL URL in a **pipeline JSON** field → Rust runs the connector | **Supported** for **sinks** and **declared** lake metadata (see below) |
| `s3://` / `gs://` / `abfss://` in **`sources.paths`** | **Rejected** — use **`sources.object_store_uris`** (pending) + **local** `paths` for ingest |
| Read `your_lake` table via catalog API in Java | **Out of process** — export Parquet/CSV locally, then ingest (see [Your data lake](#your-data-lake-your_lake)) |

The runnable examples teach the mechanics: [`JsonParquetExcelSnippets`](examples/JsonParquetExcelSnippets.java) and [`ParquetSnippets`](examples/ParquetSnippets.java) for **local** Parquet/CSV; [`RDPOnlyETLExample`](examples/RDPOnlyETLExample.java) for **pipeline JSON** that carries **`postgresql_url`**, **`catalog_uri`**, and **`warehouse`** placeholders; [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java) and [`ObjectStoreUrlsExample`](examples/ObjectStoreUrlsExample.java) for **full JVM URLs** (Snowflake, Databricks, Spark, S3, GCS, Azure); [`SparkParquetHandoffExample`](examples/SparkParquetHandoffExample.java) for working Spark handoff FFI; [`DeltaLakeHandoff`](examples/DeltaLakeHandoff.java) for the lake → file → ingest story. Per-platform notes: [Connector cookbook](#connector-cookbook).

### Your database (`your_database`) {#your-database-your_database}

**Three patterns** (pick one; they are not interchangeable):

**1. File handoff (most common on the JVM today)** — Your app (or any ETL tool) runs warehouse SQL with **your own** database client, writes a **bounded** CSV or Parquet file to disk, then passes that **local path** into ingest FFI—the same as `people.csv` in the people fixtures:

```java
// Step A — outside rdp_jvm_sys: your app's DB client (Hikari, Spring, sqlcmd, etc.)
// ResultSet → write /tmp/your_database/nightly_accounts.parquet (or .csv)

// Step B — Rust ingest: absolute LOCAL path only
String payload =
    PipelineJsonFixtures.resolvePayloadJson(
        peopleBundle,
        "payloads/csv_path_dataset.payload.json",
        Map.of(
            "SOURCE_PATH",
            Path.of("/tmp/your_database/nightly_accounts.csv").toAbsolutePath().toString()));
JSONObject root = RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
```

See [`JsonParquetExcelSnippets`](examples/JsonParquetExcelSnippets.java), [`IngestValidateJsonlEndToEnd`](examples/IngestValidateJsonlEndToEnd.java).

**2. SQL URL in pipeline JSON (Rust sink)** — You embed a **`postgresql://`** string in pipeline metadata; Java sends the document to `rdp_run_pipeline_json`. Rust ingests **local JSON sources**, transforms in Polars, then may **write** to Postgres using a **Rust** libpq sink (when `rdp_jvm_sys` is built with `sink_postgres`).

Committed fixture (replace host, database, and table with **your_database** values):

```json
{
  "relational_sink": {
    "postgresql_url": "postgresql://app:CHANGE_ME@db.your_database.example:5432/analytics?sslmode=require",
    "courses_teachers_table": "public.your_table"
  }
}
```

Full legacy control-plane shape (lake + DB URLs + **local** JSON source paths after substitution):

[`tests/fixtures/student_etl/pipelines/legacy_student_etl_three_paths.pipeline.json`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/tests/fixtures/student_etl/pipelines/legacy_student_etl_three_paths.pipeline.json)

```java
// RDPOnlyETLExample: bind PATH_A..C to tests/fixtures/student_etl/data/part-*.json (local files)
String pipeline =
    RDPOnlyETLExample.resolveLiveLegacyPipelineJson(fixturesDir, pathA, pathB, pathC);
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
// interchange.sink_results[] describes parquet/postgresql/delta_lake branches
```

See [`RDPOnlyETLExample.java`](examples/RDPOnlyETLExample.java) — loads `schema_postgres_courses.schema.json`, `lake_grade_stats`, and prints the conceptual `s3://` sketch from [`legacy_student_etl.pipeline.json`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/tests/fixtures/student_etl/pipelines/legacy_student_etl.pipeline.json) (production metadata only; live demo uses local parts).

**3. Built-in SQL → tabular read (Rust ConnectorX, Python today)** — `ingest_from_db("postgresql://…", "SELECT …", schema)` uses **ConnectorX inside the native extension** (`db_connectorx` feature). That is the close analogue to “give the library a SQL URL,” but it is exposed on **Python**, not as a JVM FFI symbol in [`ffi_manifest.json`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/jvm-sys/ffi_manifest.json). See [`python-wrapper/API.md`](../../python-wrapper/API.md) § Ingestion and [`README_DEV.md`](../../python-wrapper/README_DEV.md).

### Your data lake (`your_lake`) {#your-data-lake-your_lake}

**There is no JVM call** that says “open `your_lake` catalog and read table X.” Lake **catalog URIs** and **warehouse** paths in JSON describe **where Rust should write** (or future connectors), not a live read API from Java.

**Lake URLs in pipeline JSON (declare sink target)** — same legacy fixture family as the database:

```json
{
  "lake_sink": {
    "format": "delta_or_iceberg_tbd",
    "catalog_uri": "thrift://iceberg-catalog.your_lake.example:9083",
    "warehouse": "s3://your-lake-warehouse/",
    "namespace": "curated",
    "table_student_grades": "your_table"
  }
}
```

On typical `rdp_jvm_sys` builds, `delta_lake` / `iceberg` sink kinds return **`connector_pending`** in `sink_results` (metadata accepted; native lake write not linked yet). Use a **`parquet_file`** sink to a **local path** for a working end-to-end demo—see [`ParquetSnippets`](examples/ParquetSnippets.java) and GHCN’s `parquet_file` step in [`GhcnJsonXmlParquetPipeline`](examples/GhcnJsonXmlParquetPipeline.java).

**Lake read handoff (what works today)** — Use **your_lake**’s normal client (Spark, Databricks, `deltalake`, Trino, etc.) to materialize a slice, copy or write to a **local** Parquet directory, then ingest:

```java
// your_lake export (outside this library), then local path into Rust:
String parquetFromYourLake =
    "/data/your_lake/curated/your_table/dt=2026-05-20/part-00000.parquet";
JSONObject root =
    RdpNativeJson.invokeIngestParquetPath(
        linker, lookup, arena,
        parquetFromYourLake,
        schemaJson,
        PipelineJsonFixtures.defaultPathIngestOptionsJson());
```

**Do not** put `s3://your-bucket/...` in **`sources.paths`** — use **`sources.object_store_uris`** for cloud ingest (validated in [`CloudImportIntegrationTest`](../../integration_testing/CloudConnectors/java/src/test/java/io/github/scorpio_datalake/rust_data_processing/integration/CloudImportIntegrationTest.java) and [`cloud_pipeline.py`](../../integration_testing/scripts/cloud_pipeline.py)). For a local staging pattern, sync objects to disk first → substitute **local** absolutes in `paths`, as [`RDPOnlyETLExample`](examples/RDPOnlyETLExample.java) does for `student_etl/data/part-0000*.json`.

**Partitioned / Hive-style layouts** — Java discovers files (glob), builds a sorted `paths` array of **local** absolutes, and calls `rdp_ingest_ordered_paths_json` with watermark options—[`OrderedPaths`](examples/OrderedPaths.java). That mirrors scanning `s3://your_lake/events/dt=*/part-*.parquet` **after** you list or sync keys to local paths.

More background: [`docs/LAKE_TABLE_READ.md`](../LAKE_TABLE_READ.md), [`DeltaLakeHandoff.java`](examples/DeltaLakeHandoff.java), Python § 8 in [`PHASE2_EXAMPLES.md`](../python/PHASE2_EXAMPLES.md).

### Connector cookbook: PostgreSQL, Oracle, SQL Server, Snowflake, Databricks, Spark, object stores {#connector-cookbook}

Cross-language reference with **the same fake URLs** in Rust, Python, and Java: [`docs/CONNECTORS.md`](../CONNECTORS.md) (SFTP/FTP via `sources.file_transfer_uris` — see [`SftpFtpConnectorsExample.java`](examples/SftpFtpConnectorsExample.java)). **Docker-validated flows:** [`integration_testing/integration_testing_details.md`](../../integration_testing/integration_testing_details.md).

**Runnable JVM examples (no external CI):** [`PlatformConnectorsPipelineExample.java`](examples/PlatformConnectorsPipelineExample.java), [`ObjectStoreUrlsExample.java`](examples/ObjectStoreUrlsExample.java), [`SparkParquetHandoffExample.java`](examples/SparkParquetHandoffExample.java), [`KafkaEltStreamExample.java`](examples/KafkaEltStreamExample.java). Fixtures: `tests/fixtures/cloud_connectors/`. Build `rdp_jvm_sys` with **`cloud_connectors`** (enabled on `link-main`); add **`kafka`** for streaming. CI uses `file://`; production and **integration tests** use `s3://` / `gs://` / `azure://` in the same JSON fields — Java never opens cloud clients; Rust does I/O.

| Platform | JVM `rdp_run_pipeline_json` | JVM ingest today | Example class / fixture |
| --- | --- | --- | --- |
| **PostgreSQL** | `postgresql` sink (`postgresql://…`, libpq) or legacy `relational_sink` | Local paths; optional COPY sink | [`RDPOnlyETLExample`](examples/RDPOnlyETLExample.java) |
| **Oracle / SQL Server** | **`sources.db_reads`** (`oracle://`, `mssql://`) when **`db_connectorx`** | Else: export → local file → **`sources.paths`** | Cookbook snippets below |
| **Snowflake** | `kind: snowflake` — Rust writes stage Parquet (`stage_uri`); optional `COPY INTO` | `sources.object_store_uris` | [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java) |
| **Databricks** | `kind: databricks` — Rust writes Parquet under `warehouse` path | same | Same |
| **Apache Spark** | `kind: spark` — Rust writes `handoff_uri` Parquet (driver reads outside FFI) | same | [`SparkParquetHandoffExample`](examples/SparkParquetHandoffExample.java) |
| **S3 / GCS / Azure** | `sources.object_store_uris` + `kind: object_store` sink (Rust `object_store` crate) | URIs in JSON only | [`ObjectStoreUrlsExample`](examples/ObjectStoreUrlsExample.java) · integration: [`CloudImportIntegrationTest`](../../integration_testing/CloudConnectors/java/src/test/java/io/github/scorpio_datalake/rust_data_processing/integration/CloudImportIntegrationTest.java) |
| **SFTP / FTP** | `sources.file_transfer_uris` | URIs + env passwords | [`SftpFtpConnectorsExample`](examples/SftpFtpConnectorsExample.java) · integration: same CloudConnectors suite |
| **Kafka (streaming)** | `rdp_kafka_export_dataset_json` / `rdp_kafka_poll_window_loaded_json` | Broker config JSON | [`KafkaEltStreamExample`](examples/KafkaEltStreamExample.java) · integration: [`KafkaStreamIntegrationTest`](../../integration_testing/Kafka/java/src/test/java/io/github/scorpio_datalake/rust_data_processing/integration/KafkaStreamIntegrationTest.java) |
| **Delta / Iceberg** | `delta_lake` — Rust stages Parquet at `warehouse/.../table/`; Iceberg still pending | URIs in JSON | [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java) |

#### Two kinds of SQL on the JVM

| Kind | Where you put it | What runs it | Example |
| --- | --- | --- | --- |
| **Source / warehouse SQL** | Java `String` in your app, or Python `ingest_from_db(..., query, ...)` | **Your** DB client, **`sources.db_reads`**, or Python ConnectorX | `SELECT … FROM hr.employees` on Oracle |
| **Pipeline SQL (Polars)** | `transform.sql` and/or per-sink `"sql"` in **pipeline JSON** | **Rust** (`rdp_run_pipeline_json`) on the ingested frame `df` | `SELECT id, score FROM df WHERE active = TRUE` |

Pipeline SQL is **not** sent to Oracle or PostgreSQL as a remote query — it filters and projects rows **after** Rust has loaded a file or object-store slice. See [`SQLQueries.java`](examples/SQLQueries.java) and `tests/fixtures/jvm_contract/pipelines/sql_query_dataset.pipeline.json`.

**Source SQL in pipeline JSON (documentation):** the contract has no `source_sql` field yet; keep the warehouse `SELECT` in Java constants or in your orchestrator docs, and pass only **local paths** or **`object_store_uris`** in `sources`.

#### PostgreSQL

**Python — built-in read** (build with `db` feature; use ConnectorX URL form):

```python
import rust_data_processing as rdp

ds = rdp.ingest_from_db_infer(
    "postgresql://etl:CHANGE_ME@db.your_database.example:5432/analytics?cxprotocol=binary",
    "SELECT id, score FROM public.fact_scores WHERE dt = CURRENT_DATE",
)
```

**JVM — pipeline sink** (ingest stays **local** JSON/Parquet paths; URL is for **load** only):

```json
{
  "relational_sink": {
    "postgresql_url": "postgresql://app:CHANGE_ME@db.your_database.example:5432/analytics?sslmode=require",
    "courses_teachers_table": "public.fact_scores"
  }
}
```

```java
// Same substitution pattern as RDPOnlyETLExample — sources are local files, not the URL
String pipeline = RDPOnlyETLExample.resolveLiveLegacyPipelineJson(fixturesDir, pathA, pathB, pathC);
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
```

**Source SQL (warehouse)** — run in your PostgreSQL client; Rust does not execute this string on the JVM today:

```sql
SELECT id, score, posted_at
FROM public.fact_scores
WHERE dt = CURRENT_DATE;
```

**In Java** — run the query in your app, export rows to disk, then call Rust on the file path:

```java
String postgresSelect =
    """
    SELECT id, score, posted_at
    FROM public.fact_scores
    WHERE dt = CURRENT_DATE
    """;
// Your DB client → write /var/rdp/staging/pg_fact_scores.parquet

Path staging = Path.of("/var/rdp/staging/pg_fact_scores.parquet");
String payload =
    PipelineJsonFixtures.resolvePayloadJson(
        peopleBundle,
        "payloads/csv_path_dataset.payload.json",
        Map.of("SOURCE_PATH", staging.toAbsolutePath().toString()));
JSONObject root = RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
```

**Pipeline SQL in JSON** — Polars on `df` after ingest (same pattern as [`SQLQueries.java`](examples/SQLQueries.java)):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": ["{{STAGING_PARQUET}}"],
    "schema_ref": "schemas/people_flat.schema.json",
    "options": { "format": "parquet" }
  },
  "transform": {
    "sql": "SELECT id, score FROM df WHERE score > 0 ORDER BY id"
  },
  "sinks": [
    {
      "kind": "postgresql",
      "url": "postgresql://app:CHANGE_ME@db.your_database.example:5432/analytics?sslmode=require",
      "table": "public.fact_scores_curated",
      "sql": "SELECT id, score FROM df"
    }
  ]
}
```

Per-sink `"sql"` is optional; it projects the frame again before that sink writes.

#### Oracle

**Python — built-in read** (requires `--features db_connectorx` / Python `db`; use ConnectorX `oracle://` form):

```python
ds = rdp.ingest_from_db_infer(
    "oracle://etl:CHANGE_ME@db.your_database.example:1521/ORCLPDB1",
    "SELECT employee_id, department_id FROM hr.employees WHERE ROWNUM <= 100000",
)
```

**Source SQL (warehouse)** — Oracle dialect; pass the same text in pipeline **`sources.db_reads[].query`** (Rust ConnectorX):

```sql
SELECT employee_id, department_id, hire_date
FROM hr.employees
WHERE ROWNUM <= 100000;
```

**In Java (preferred)** — ConnectorX URL + query in pipeline JSON; build **`rdp_jvm_sys`** with **`--features db_connectorx`** (or **`full`**). Use **`oracle://`** (see [`docs/CONNECTORS.md`](../CONNECTORS.md)):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": [],
    "db_reads": [
      {
        "url": "oracle://etl:CHANGE_ME@db.your_database.example:1521/ORCLPDB1",
        "query": "SELECT employee_id, department_id, hire_date FROM hr.employees WHERE ROWNUM <= 100000"
      }
    ],
    "schema_ref": "schemas/your_oracle_hr.schema.json",
    "options": {}
  },
  "transform": {
    "sql": "SELECT employee_id, department_id FROM df WHERE department_id IS NOT NULL ORDER BY employee_id"
  },
  "sinks": [
    {
      "kind": "parquet_file",
      "path": "{{CURATED_PARQUET}}",
      "sql": "SELECT employee_id, department_id FROM df"
    }
  ]
}
```

```java
Path bundleRoot =
    PipelineJsonFixtures.resolveBundleRoot(fixturesDir, "your_bundle").orElseThrow();
String pipeline =
    PipelineJsonFixtures.resolvePipelineJson(
        bundleRoot,
        "pipelines/your_oracle_hr_curate.pipeline.json",
        Map.of("CURATED_PARQUET", curatedOut.toAbsolutePath().toString()));
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
```

**Fallback (no `db_connectorx` build)** — run the same SQL in your environment, write Parquet locally, then set **`sources.paths`** to that file. Expect **`DB_CONNECTORX_NOT_BUILT`** if you use **`db_reads`** without the feature. Do **not** use `jdbc:` URLs in **`db_reads`** — they are rejected at parse.

#### Microsoft SQL Server

**Python — built-in read:**

```python
ds = rdp.ingest_from_db_infer(
    "mssql://etl:CHANGE_ME@db.your_database.example:1433/warehouse?encrypt=true",
    "SELECT TOP (100000) id, amount FROM dbo.ledger WHERE posted_at >= '2026-05-01'",
)
```

**Source SQL (warehouse)** — T-SQL; run in your DB client or `sqlcmd`:

```sql
SELECT TOP (100000) id, amount, posted_at
FROM dbo.ledger
WHERE posted_at >= '2026-05-01';
```

**In Java (preferred)** — **`sources.db_reads`** with **`mssql://`** (requires **`db_connectorx`** on **`rdp_jvm_sys`**):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": [],
    "db_reads": [
      {
        "url": "mssql://etl:CHANGE_ME@db.your_database.example:1433/warehouse?encrypt=true",
        "query": "SELECT TOP (100000) id, amount, posted_at FROM dbo.ledger WHERE posted_at >= '2026-05-01'"
      }
    ],
    "schema_ref": "schemas/ledger.schema.json",
    "options": {}
  },
  "transform": {
    "sql": "SELECT id, amount FROM df WHERE amount > 0 ORDER BY posted_at"
  },
  "sinks": [
    { "kind": "parquet_file", "path": "{{SINK_PATH}}" }
  ]
}
```

```java
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipelineJson);
```

**Fallback** — export to local Parquet → **`sources.paths`** when the native library is not built with **`db_connectorx`**.

#### Snowflake

**JVM pipeline (full URLs, Rust executes):** run [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java) — `account_url`, `warehouse`, `stage_uri`; expect `sink_results[].status: ok` (stage write in Rust).

Stage **read** uses the same **S3 auth** as any `s3://` URI (not in the JSON string). Set credentials on the **process** that runs `rdp_jvm_sys`:

| Auth | How Rust / `object_store` gets it |
| --- | --- |
| Access key | `AWS_ACCESS_KEY_ID` + `AWS_SECRET_ACCESS_KEY` (see [`docs/CONNECTORS.md`](../CONNECTORS.md)) |
| Session token | `AWS_SESSION_TOKEN` (temporary creds) |
| IAM role | Instance profile / IRSA on the host (no keys in Java) |

**JVM ingest from the stage object in S3** (preferred — Rust reads `s3://`; Java only passes JSON):

```json
{
  "pipeline_spec_version": 1,
  "sources": {
    "paths": [],
    "object_store_uris": [
      "s3://demo-bucket-us-east-1/snowflake-stage/rdp/ledger/dt=2026-05-20/part-00000.parquet"
    ],
    "schema_ref": "schemas/your_ledger.schema.json",
    "options": { "format": "parquet" }
  },
  "sinks": [{ "kind": "parquet_file", "path": "{{LOCAL_SINK}}" }]
}
```

```java
String pipeline =
    PipelineJsonFixtures.resolvePipelineJson(bundleRoot, "pipelines/your_snowflake_ingest.pipeline.json", bindings);
JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
```

**Legacy pattern (only if you sync/mount S3 to disk yourself):** local path + `invokeIngestParquetPath` — Java/your sync tool must copy the object first; credentials are still required for that S3 copy, just not passed into `rdp_ingest_parquet_path`:

```java
// After aws s3 sync / Snowflake COPY INTO external stage backed by S3:
String localSlice =
    "/data/snowflake_sync/demo-bucket-us-east-1/snowflake-stage/rdp/ledger/dt=2026-05-20/part-00000.parquet";
RdpNativeJson.invokeIngestParquetPath(linker, lookup, arena, localSlice, schemaJson, optionsJson);
```

Snowflake **warehouse** SQL (`COPY INTO @stage …`) runs in Snowflake or your ETL tool, not inside `rdp_jvm_sys`. See [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java) for `kind: snowflake` **sink** (Rust writes to `stage_uri`).

Pipeline JSON can still **declare** lake layout metadata (same as other warehouses):

```json
{
  "lake_sink": {
    "format": "delta_or_iceberg_tbd",
    "catalog_uri": "https://your_account.snowflakecomputing.com",
    "warehouse": "s3://your-snowflake-external-volume/",
    "namespace": "your_schema",
    "table_student_grades": "ledger_curated"
  }
}
```

#### Databricks (Unity Catalog / Delta)

**JVM pipeline:** [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java) — `workspace_url`, Unity `catalog_uri`, `warehouse` (`abfss://…` or `file://` in tests); Rust writes Parquet under the warehouse path.

**Read path:** use Databricks SQL, notebook, or Spark on the cluster to write **local** Parquet (or sync cloud storage to a mounted path), then ingest — see [Your data lake](#your-data-lake-your_lake).

**PySpark export (runs on Databricks or a cluster; not in `rdp_jvm_sys`):**

```python
# spark is your Databricks / cluster session
df = spark.table("your_catalog.your_schema.your_table")
df.where("dt = '2026-05-20'").write.mode("overwrite").parquet("/local/mnt/rdp/your_table/dt=2026-05-20")
```

**JVM ingest** that Parquet tree (single file or [`OrderedPaths`](examples/OrderedPaths.java) for many parts):

```java
String path =
    "/local/mnt/rdp/your_table/dt=2026-05-20/part-00000-*.parquet"; // glob in your app, absolutes in JSON
```

**Pipeline metadata** for a future native Delta writer (today often `connector_pending`):

```json
{
  "lake_sink": {
    "format": "delta",
    "catalog_uri": "https://your-workspace.cloud.databricks.com/api/2.1/unity-catalog/iceberg",
    "warehouse": "s3://your-unity-catalog-warehouse/",
    "namespace": "your_catalog.your_schema",
    "table_student_grades": "your_table"
  }
}
```

See [`DeltaLakeHandoff.java`](examples/DeltaLakeHandoff.java) for the end-to-end story in comments.

#### Apache Spark (connector / consumer)

Spark is the usual **consumer** after Rust processing, or the **producer** that writes files Rust ingests. There is **no** `SparkSession` parameter on the FFI boundary.

**Runnable:** [`SparkParquetHandoffExample`](examples/SparkParquetHandoffExample.java) runs the platform pipeline and prints the `spark` sink (`handoff_uri` written by Rust).

**Rust → Spark (small/medium handoff)** — [`rdp_export_parquet_temp`](#temp-parquet-handoff-rdp_export_parquet_temp), module `rust-data-processing-jvm-spark` for Arrow/Parquet helpers:

```java
JSONObject envelope = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena, interchangeJson);
String path = envelope.getString("path");
SparkSession spark =
    SparkSession.builder().master("local[*]").appName("rdp-handoff").getOrCreate();
Dataset<Row> df = spark.read().parquet(path);
// delete temp file when done (see ParquetTempExportExample)
```

**Spark / Databricks → Rust (large tables)** — cluster writes Parquet; Java passes **local** absolutes into `rdp_ingest_parquet_path` or `rdp_ingest_ordered_paths_json` (same as [lake read handoff](#your-data-lake-your_lake)).

**Spark Snowflake connector** (example producer only):

```scala
// Runs in your Spark app — not shipped inside rdp_jvm_sys
val sfOptions = Map(
  "sfURL" -> "your_account.snowflakecomputing.com",
  "sfDatabase" -> "YOUR_DB",
  "sfSchema" -> "YOUR_SCHEMA",
  "sfWarehouse" -> "YOUR_WH"
)
spark.read.format("net.snowflake.spark.snowflake").options(sfOptions)
  .option("query", "select id, amount from ledger limit 1000000")
  .load()
  .write.parquet("/var/rdp/staging/snowflake_ledger.parquet")
```

Then ingest `/var/rdp/staging/snowflake_ledger.parquet` from Java as in the Snowflake subsection above.

### Summary for Java integrators

- **Credentials and URLs** belong in **JSON documents** (and **local path** lists) you send to `rdp_run_pipeline_json`, `rdp_ingest_*_path`, or `rdp_ingest_ordered_paths_json`—not in live database session objects on the FFI boundary.
- **Rust** performs reads/writes using library drivers; you avoid reimplementing Polars, Parquet, or SQL execution in Java.
- **Examples to copy:** file ingest → people/GHCN fixtures; pipeline URLs → [`RDPOnlyETLExample`](examples/RDPOnlyETLExample.java) + `tests/fixtures/student_etl/`; cloud platforms + object stores → [`PlatformConnectorsPipelineExample`](examples/PlatformConnectorsPipelineExample.java), [`ObjectStoreUrlsExample`](examples/ObjectStoreUrlsExample.java), [`SparkParquetHandoffExample`](examples/SparkParquetHandoffExample.java) + `tests/fixtures/cloud_connectors/`; [Connector cookbook](#connector-cookbook).

## Phase 2 examples {#phase-2-examples}

Counterpart to [`docs/python/PHASE2_EXAMPLES.md`](../python/PHASE2_EXAMPLES.md). Python often builds `rdp.DataSet(...)` in-process; JVM examples call **`rdp_parity_*`** (or file ingest) and read fields from `interchange`. Where FFI is missing, the class documents the Python API and the intended Rust function.

| § | Python topic | Example | How it runs on the JVM |
| --- | --- | --- | --- |
| 1 | JSONL + train/test indices | [`ExportJsonlTrainTest.java`](examples/ExportJsonlTrainTest.java) | `rdp_parity_export_privacy_reports` → `jsonl_preview_lines`, `train_test_indices_demo` |
| 2 | UTF-8 length row filter | [`ExportFilterRowsMaxUtf8Chars.java`](examples/ExportFilterRowsMaxUtf8Chars.java) | **Doc only** — `export_filter_rows_max_utf8_chars` not on FFI yet |
| 3 | Privacy diff after transform | [`PrivacyDiffReports.java`](examples/PrivacyDiffReports.java) | Same parity export → `privacy_report_json` |
| 4 | Truncate large UTF-8 text | [`ReportsTruncateUtf8.java`](examples/ReportsTruncateUtf8.java) | Same parity export → `reports_truncated_sample` |
| 5 | Utf8 masking `TransformSpec` | [`TransformUtf8Masking.java`](examples/TransformUtf8Masking.java) | `rdp_parity_transform` (rename/cast demo; masking steps in Python/Rust) |
| 6 | UTF-8 length validation | [`ValidationUtf8Length.java`](examples/ValidationUtf8Length.java) | `rdp_parity_validation` (summary envelope; `utf8_len_chars_between` in Python) |
| 7 | Median reduce / grouped median | [`MedianReduceAndDataFrame.java`](examples/MedianReduceAndDataFrame.java) | **Doc only** — see [`SQLQueries.java`](examples/SQLQueries.java) for Polars SQL on JVM |
| 8 | Delta / Iceberg handoff | [`DeltaLakeHandoff.java`](examples/DeltaLakeHandoff.java) | **Doc only** — lake read out-of-process; then Parquet ingest like `JsonParquetExcelSnippets` |
| 9 | Ingest → validate → JSONL | [`IngestValidateJsonlEndToEnd.java`](examples/IngestValidateJsonlEndToEnd.java) | `people.csv` ingest + validation + JSONL preview parity |
| 10 | Watermark / ordered paths | [`OrderedPaths.java`](examples/OrderedPaths.java) | (see [Incremental batches](#incremental-batches-watermarks-directory-scan) above) |

### DataFrame-centric Polars SQL (`DataFrameCentricPipeline`)

Python’s lazy `DataFrame.filter_eq(…).multiply_f64(…)` maps to **Polars SQL** on table `df` inside a pipeline. Fixture SQL filters `active = TRUE` and doubles `score`; the Parquet sink should have **2 rows**.

- **Schema:** `jvm_contract/schemas/three_rows.schema.json`
- **Pipeline:** `jvm_contract/pipelines/dataframe_centric_sql.pipeline.json`
- **Input:** `tests/fixtures/jvm_contract_three_rows.json`

### SQL queries (`SQLQueries`)

- **Single-table:** `sql_query_dataset.pipeline.json` with the same `three_rows` schema; SQL selects active rows ordered by `id DESC`.
- **JOIN:** SQL text in `sql_parity/queries/join_people_scores.sql.json`; side tables `sql_parity/data/join_left.json` and `join_right.json`. Multi-table `rdp_run_pipeline_json` is not on the JVM yet — the doc example runs the join via **`rdp_parity_sql_suite_mirror`** (same workload as Python `SqlContext` tests).

### GHCN JSON → XML → Parquet (`GhcnJsonXmlParquetPipeline`)

Demonstrates **three distinct schemas** on one small NOAA station sample (`ghcn/ghcn_stations_sample.json`):

1. **`json_to_xml.pipeline.json`** — renames columns (`id` → `stationCode`, …) and writes `xml_file`.
2. **`rdp_ingest_xml_path`** — verifies intermediate XML against `xml_intermediate.schema.json`.
3. **`xml_to_parquet.pipeline.json`** — maps to lake column names (`station_id`, `geo_lat`, …) and writes `parquet_file`.
4. **`rdp_ingest_parquet_path`** — verifies lake Parquet (5 rows; first `station_id` = `ACW00011604`).

No runtime download: the sample is committed under `tests/fixtures/ghcn/`.

### People CSV / JSON / Parquet / Excel (`JsonParquetExcelSnippets`, `ExcelSnippets`, `ParquetSnippets`)

- **Payload ingest:** `people/payloads/json_path_dataset.payload.json`, `csv_path_dataset.payload.json` → `rdp_ingest_ordered_paths_json`.
- **Path ingest:** `rdp_ingest_json_path` / `rdp_ingest_csv_path` with `people/schemas/*.schema.json` and `defaultPathIngestOptionsJson()`.
- **CSV → Parquet pipeline:** `people/pipelines/csv_to_parquet.pipeline.json` then verify with `people_flat.schema.json`.
- **Excel:** `excel_sheet_dataset.payload.json` with `{{SOURCE_PATH}}` → `tests/fixtures/people.xlsx` (generate via `python scripts/write_people_xlsx_stdlib.py` if missing).

### Incremental batches (watermarks, directory scan) {#incremental-batches-watermarks-directory-scan}

[`OrderedPaths.java`](examples/OrderedPaths.java) — Java lists files (glob) like Python `paths_from_directory_scan`; Rust ingests with watermark options from `watermark/payloads/csv_watermark_ingest.body.json`. [`PathFromDirectoryScan.java`](examples/PathFromDirectoryScan.java) delegates to the same logic for back-compat.

```json
{
  "schema_ref": "schemas/events.schema.json",
  "options": {
    "format": "csv",
    "watermark_column": "ts",
    "watermark_exclusive_above": 100
  },
  "response": { "mode": "dataset", "max_rows": 10000 }
}
```

Attach the scanned absolute paths as the `paths` array in the payload JSON before calling `rdp_ingest_ordered_paths_json`. Semantics are also covered by **`rdp_parity_watermark_mirror`**.

### Rust-only student ETL (`RDPOnlyETLExample`)

Shows how **database and lake connection strings live in pipeline JSON** (`postgresql_url`, `catalog_uri`, `warehouse`) while **ingest sources stay local files** — see [Your database](#your-database-your_database) and [Your data lake](#your-data-lake-your_lake). Loads [`legacy_student_etl_three_paths.pipeline.json`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/tests/fixtures/student_etl/pipelines/legacy_student_etl_three_paths.pipeline.json) with `PATH_A..C` → `student_etl/data/part-0000*.json`, plus ordered ingest payloads. Execution stays in Rust; Java substitutes paths and calls `rdp_run_pipeline_json` / `rdp_ingest_ordered_paths_json`.

## Rust-first ETL vs JVM consumption {#rust-first-etl-vs-jvm-consumption}

**Default recommendation:** do as much work as possible **inside the Rust engine** (or via the Python extension calling Rust), and **persist** results where downstream systems already read efficiently — **Parquet**, **CSV**, **database** tables, object storage, etc. Avoid pulling large **`DataSet`** / Polars materializations back over the JVM FFI when you do not need them on the JVM.

**Why:** Parity exports that return tabular data today expose **`interchange.dataset`** as **JSON** (`schema` + `rows`). That is appropriate for **contracts, tests, demos, and small control-plane payloads**. It is **not** the right default for multi‑gigabyte pipelines: memory, GC, and parse cost grow with row count on both sides.

**This guidance applies to every export that materializes a full table in `interchange`**, including (non‑exhaustive):

- **`rdp_parity_types_dataset`**, **`rdp_parity_ingestion`**, **`rdp_parity_pipeline_sql`**, **`rdp_parity_transform`** — tabular **`dataset`**.
- **`rdp_parity_benchmark_smoke_mirror`** and similar smoke paths that include large-ish synthetic **`dataset`** payloads in JSON.

For **mirrors** that mostly return **metrics, counts, flags, or small JSON** (bindings, mapping spec, SQL suite, partition discovery, watermark, validation summaries, observability, etc.), JVM JSON is usually fine.

**When you do need data on the JVM** (local Spark `DataFrame`, unit tests, small extracts):

- Use **`interchange.dataset`** JSON only for **bounded** row counts you are willing to hold in the heap.
- Prefer **file-based handoff** when you add or use dedicated FFI: e.g. Rust writes **Parquet or CSV** to a path (temp or durable), returns a **small envelope** (`path`, `row_count`, `schema`, checksum), and Java or **Spark in `local[*]` mode** reads from that path — then delete the temp file if you created one.
- Longer term, **Arrow IPC / C Data Interface** (see [`ARROW_FFI_JVM.md`](ARROW_FFI_JVM.md)) is the direction for columnar, lower-copy bridges; until then, **Rust writes files → JVM reads files** is the scalable pattern outside a data lake too.

**Summary:** treat JVM parity calls as **orchestration, validation, and small-surface results**; treat **Rust (or Python + Rust) as the place that runs ETL and lands data** to Parquet, CSV, or a database unless you have a deliberate, size-bounded reason to materialize rows in the JVM.

### Temp Parquet handoff (`rdp_export_parquet_temp`) {#temp-parquet-handoff-rdp_export_parquet_temp}

Rust writes a **small sample** `DataSet` (two rows: id/name) to a file under the OS temp directory (`…/rdp_jvm_parquet/rdp_export_<nanos>.parquet`) and returns a **small JSON envelope** (`interchange.kind` = `parquet_export_temp`, `path`, `row_count`, `schema`). No giant `rows` array crosses the boundary.

From Java, call **`RdpNativeJson.invokeExportParquetTemp`**, then read the path with **Spark in `local[*]` mode** (or any Parquet reader), then delete the file. Helpers: **`io.github.scorpio_datalake.rust_data_processing.integration.RdpParquetTemp`**. Runnable: **`ParquetTempExportExample`**.

```java
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.integration.RdpParquetTemp;
import org.apache.spark.sql.SparkSession;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.json.JSONObject;

JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
String path = RdpParquetTemp.parquetPath(root);
SparkSession spark = SparkSession.builder().master("local[*]").appName("rdp").getOrCreate();
Dataset<Row> df = spark.read().parquet(path);
// … use df …
RdpParquetTemp.deleteQuietly(path);
```

The crate also exposes **`rust_data_processing::ingestion::export_dataset_to_parquet`** for Rust callers who write their own paths.

## Prerequisites

- **JDK 21+** (Panama FFM). Some builds use preview features on newer JDKs; see the module `pom.xml`.
- **JVM flag:** `--enable-native-access=ALL-UNNAMED` (or a tighter module policy if you wire one).

### Maven (recommended — no Rust install)

Add **two** dependencies at the **same version** ([`bindings/java/VERSION`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/VERSION)): the Java API JAR and **one** native classifier for your OS/CPU. See **[`NATIVE_ARTIFACT_PACKAGING.md`](NATIVE_ARTIFACT_PACKAGING.md)** for the full classifier table.

| Your machine | `rdp-jvm-sys` classifier |
| --- | --- |
| Linux x86_64 | `linux-x86_64` |
| Linux ARM64 (aarch64) | `linux-aarch64` |
| macOS Apple Silicon | `osx-aarch64` |
| macOS Intel | `osx-x86_64` |
| Windows x86_64 | `windows-x86_64` |

```xml
<dependency>
  <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
  <artifactId>rust-data-processing-jvm</artifactId>
  <version>0.3.4</version>
</dependency>
<dependency>
  <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
  <artifactId>rdp-jvm-sys</artifactId>
  <version>0.3.4</version>
  <classifier>linux-x86_64</classifier><!-- pick row from table above -->
</dependency>
```

`RdpNativeJson` loads the native library from **`META-INF/native/`** on the classpath automatically. You do **not** need `RDP_JVM_SYS` unless overriding the path.

### Build from source (contributors / custom features)

- **Rust + Cargo** required.
- Build `rdp_jvm_sys` from [`bindings/jvm-sys/`](https://github.com/scorpio-datalake/rust-data-processing/tree/main/bindings/jvm-sys) (`cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full`).
- Point Java at the absolute path: **`RDP_JVM_SYS`** or **`-Drdp.jvm.sys.library=…`**

**Running an example `main` from a checkout:**

```bash
# Option A — Maven classifiers (no Rust)
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
# … depend on rust-data-processing-jvm + rdp-jvm-sys:${classifier} …

# Option B — local cargo build
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full
export RDP_JVM_SYS=$PWD/bindings/jvm-sys/target/release/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
```

**JUnit (maintainers):** [`DocsExampleNativeIntegrationTest`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/test/java/io/github/scorpio_datalake/rust_data_processing/docexamples/DocsExampleNativeIntegrationTest.java) exercises most catalog examples when `RDP_JVM_SYS` is set; [`FfiExportedSymbolsContractTest`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/test/java/io/github/scorpio_datalake/rust_data_processing/FfiExportedSymbolsContractTest.java) covers manifest-wide symbol smoke separately.

## FFI manifest and ABI

The JAR bundles **`ffi_manifest.json`** (same content as [`bindings/jvm-sys/ffi_manifest.json`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/jvm-sys/ffi_manifest.json)). Read it from the classpath to list **`exported_symbols`** and compare **`abi_version_constant`** with a live **`rdp_ffi_abi_version`** downcall.

```java
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

try (InputStream in = RdpNativeJson.class.getResourceAsStream(RdpNativeJson.FFI_MANIFEST_RESOURCE)) {
  JSONObject manifest = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
  int abiFromJar = manifest.getInt("abi_version_constant");
  // … compare with RdpNativeJson.invokeAbiVersion(linker, lookup) when native lib is loaded
}
```

Runnable: **`io.github.scorpio_datalake.rust_data_processing.examples.LoadFfiManifestExample`**.

## Calling `rdp_parity_*` from Java

Parity exports take no Java arguments: Rust builds the scenario (fixtures, options), runs the engine, and writes a JSON envelope into an **`RdpJsonSlice`**. Use **`RdpNativeJson.invokeParityExport`** so the slice is freed correctly.

```java
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty(); // classpath classifier, or RDP_JVM_SYS / -Drdp.jvm.sys.library
Linker linker = Linker.nativeLinker();
try (Arena arena = Arena.ofConfined()) {
  SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
  JSONObject root =
      RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_bindings_mirror");
  PytestMirrorAssertions.validateMirrorExport("rdp_parity_bindings_mirror", root);
  JSONObject interchange = root.getJSONObject("interchange");
  // read fields …
}
```

Runnable: **`io.github.scorpio_datalake.rust_data_processing.examples.RunPytestMirrorExample`** — pass the export name as the only CLI argument.

For **how large `dataset` JSON fits into a production architecture**, see [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption).

## File ETL (ingestion and tabular JSON)

Python uses **`ingest_from_path`** and **`DataSet`** directly. On the JVM, see:

- **`rdp_parity_ingestion`** — CSV / ingestion path exercised in Rust; `interchange` includes tabular **`dataset`** (`schema` + `rows`) for Java-side projection.
- **`rdp_parity_types_dataset`** — tabular JSON shape for typed datasets.

Validate the envelope and `kind` the same way as in the runnable examples (see class Javadoc on [`JsonParquetExcelSnippets.java`](examples/JsonParquetExcelSnippets.java) and parity helpers in [`FFI_MANIFEST_JAVA_USAGE.md`](FFI_MANIFEST_JAVA_USAGE.md)).

For copy-pasteable classes with committed fixtures, see the [example catalog](#example-catalog). Parity-only sketches: [`rdp_parity_ingestion`](#file-etl-ingestion-and-tabular-json), [`rdp_parity_types_dataset`](#file-etl-ingestion-and-tabular-json).

<h2 id="ordered-paths-and-directory-scans-incremental-batches">Ordered paths and directory scans (incremental batches)</h2>

In Python, incremental batch patterns use **`paths_from_directory_scan`**, **`ingest_from_ordered_paths`**, watermark options, and Hive-style layout helpers — see [the same heading in the Python examples](../python/examples.html#ordered-paths-and-directory-scans-incremental-batches) on this site and [`docs/python/PHASE2_EXAMPLES.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/python/PHASE2_EXAMPLES.md) § 10.

**On the JVM today** there are no Java methods with those names; the same **Rust capabilities** are covered by parity exports you call over FFI:

### Hive-style partition discovery

- **Export:** `rdp_parity_partition_discovery_mirror`
- **Role:** Mirrors pytest coverage for **`discover_hive_partitioned_files`**, globs, explicit path lists, and **`parse_partition_segment`**. The JSON `interchange` includes fields such as `discover_all_len`, `discover_events_glob_len`, `reject_non_directory_ok`, `parse_dt`, `parse_nodash_is_null`, etc. (see **`PytestMirrorAssertions.assertPartitionDiscoveryMirror`**).

```java
JSONObject root =
    RdpNativeJson.invokeParityExport(
        linker, lookup, arena, "rdp_parity_partition_discovery_mirror");
PytestMirrorAssertions.validateMirrorExport(
    "rdp_parity_partition_discovery_mirror", root);
```

### Watermark and ordered ingestion semantics

- **Export:** `rdp_parity_watermark_mirror`
- **Role:** Exercises watermark options on fixture CSV/JSON (e.g. `watermark_column`, `watermark_exclusive_above`), row counts, and rejection of incomplete watermark options — aligned with Python incremental / watermark tests.

```java
JSONObject root =
    RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_watermark_mirror");
PytestMirrorAssertions.validateMirrorExport("rdp_parity_watermark_mirror", root);
```

**Practical integration pattern:** implement directory listing, ordered file batches, and checkpoint storage **in Java** (or your orchestrator), and keep **heavy ingestion / transforms** in **Rust** (or Python calling Rust), **writing outputs to files or a database** rather than streaming huge tables as JSON into the JVM. Use parity exports to **prove behavior** and for **small extracts**; see [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption).

## SQL and DataFrame parity

- **`rdp_parity_pipeline_sql`** — lazy SQL / Polars pipeline; `interchange` includes **`dataset`**.
- **`rdp_parity_sql_suite_mirror`** — multi-query SQL mirror (joins, errors); use **`validateMirrorExport`**.

For production-sized Polars outputs, prefer **Rust-side** materialization to **Parquet / CSV / DB** and only use JVM JSON **`dataset`** when the result set is intentionally small; see [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption).

## Transform and mapping spec

- **`rdp_parity_transform`** — `TransformSpec` / Polars transform parity; includes **`dataset`** in `interchange`.
- **`rdp_parity_mapping_spec_mirror`** — mapping DSL mirror; **`validateMirrorExport`**.

## Processing and execution

- **`rdp_parity_processing`** — filter / map / reduce style parity (`filtered_row_count`, `mapped_row_count`, …).
- **`rdp_parity_benchmark_smoke_mirror`** — wide dataset, processing, DataFrame group-by, parallel filter (same workload spirit as `python-wrapper/tests/test_benchmarks.py`), returned as JSON stats.

## Observability

- **`rdp_parity_observability_mirror`** — ingestion failure / missing path behavior surfaced as JSON.

## Runnable walkthrough class

**`io.github.scorpio_datalake.rust_data_processing.examples.ParityScenariosWalkthrough`** runs a curated list of exports (including types, bindings, mapping, transform, processing, SQL, validation, benchmark smoke) and prints short summaries — useful to **see** several `interchange` shapes in one JVM process.

**Maven Central (classifier on classpath — no `RDP_JVM_SYS`):**

```bash
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "rust-data-processing-jvm-examples-…jar:rust-data-processing-jvm-…jar:rdp-jvm-sys-…-linux-x86_64.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.ParityScenariosWalkthrough
```

**From source (checkout build):**

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "rust-data-processing-jvm-examples-…jar:rust-data-processing-jvm-…jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.ParityScenariosWalkthrough
```

(Exact `-cp` lines: [`bindings/java/rust-data-processing-jvm-examples/README.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm-examples/README.md).)

## See also

- [`FFI_MANIFEST_JAVA_USAGE.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/java/FFI_MANIFEST_JAVA_USAGE.md) — Maven, `java -cp`, manifest resource path.
- [`README.md` (JVM doc index)](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/java/README.md) — Phase 3 links.
- [Python examples (`../python/examples.html`)](../python/examples.html) — full API tour on the same docs site.
- [`ARROW_FFI_JVM.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/java/ARROW_FFI_JVM.md) — future Arrow IPC direction.
