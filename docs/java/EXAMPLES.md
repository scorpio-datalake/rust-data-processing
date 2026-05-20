---
title: "Java examples — JVM bindings (Panama + JSON parity)"
---

# Java quick start and examples

This page is the **JVM counterpart** to the Python tour published as **`python/examples.html`** on the docs site (Markdown source: [`docs/python/README.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/python/README.md)). The Python package calls Rust through **PyO3** with a rich in-process API. On the JVM, Phase 3 exposes a **narrower surface**: a native **`rdp_jvm_sys`** library, an **`ffi_manifest.json`** list of `extern "C"` symbols, and **JSON parity exports** (`rdp_parity_*`) you call with **Panama (FFM)** from Java.

**Canonical API detail** for symbols and calling convention: [`FFI_MANIFEST_JAVA_USAGE.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/FFI_MANIFEST_JAVA_USAGE.md), [`FFI_API_SLICE.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/FFI_API_SLICE.md), and [`Planning/PHASE3_EPICS.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/Planning/PHASE3_EPICS.md).

**Runnable code** in the repository: [`bindings/java/rust-data-processing-jvm-examples/README.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm-examples/README.md) (`LoadFfiManifestExample`, `RunPytestMirrorExample`, `ParityScenariosWalkthrough`, …).

## What this page covers

Use this as a **tour of how Java integrates today** (parity JSON over FFI), not a line-for-line duplicate of every Python method. For full signatures and options on the Python side, see [`python-wrapper/API.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/python-wrapper/API.md).

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
| **JSON-first doc examples** (`docs/java/examples/`) | [Why this library](#why-rust-data-processing-on-the-jvm), [Shared fixtures](#shared-json-fixtures-testsfixtures), [Doc examples tour](#json-first-doc-examples) |
| **CI for doc examples** | [JUnit alignment](#junit-tests-for-doc-examples) |

## Why rust-data-processing on the JVM {#why-rust-data-processing-on-the-jvm}

The JVM module is deliberately **thin**: Java does not re-implement CSV parsing, Polars SQL, Excel readers, or pipeline orchestration. You load **`rdp_jvm_sys`** once, pass **UTF-8 JSON** (schemas, pipeline specs, ingest payloads) over **Panama downcalls**, and read back a small **JSON envelope** (`ok`, `interchange`, optional `error`). That design buys you:

- **One engine, three languages** — the same `tests/fixtures/<bundle>/` trees are exercised by Rust (`PipelineBundle`), Python (`tests.pipeline_fixture_support`), and Java (`PipelineJsonFixtures`). Doc examples and CI prove cross-language parity without copy-pasted SQL or schemas in each binding.
- **Production-shaped control plane** — orchestration (paths, watermarks, idempotency keys) can live in Java/Kotlin while **heavy transforms stay in Rust**, with sinks writing **Parquet / XML / CSV** to disk instead of shipping millions of rows as JSON (see [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption)).
- **Typed contracts** — serde `Schema` JSON (`data_type`: `Int64`, `Utf8`, …) is validated at ingest time in Rust; JVM tests assert row counts and sample cells via `PytestMirrorAssertions` and `SerdeDatasetRows`.
- **Inspectable failures** — pipeline failures return structured `error.code` / `error.stage` (ADR 006) in the same envelope shape tests already assert.

**When to reach for which FFI:**

| Goal | Prefer |
| --- | --- |
| Declarative multi-step ETL (sources → SQL on `df` → sinks) | `rdp_run_pipeline_json` + bundle `pipelines/*.pipeline.json` |
| Single-shot multi-file ingest (dataset / temp Parquet / Arrow IPC) | `rdp_ingest_ordered_paths_json` + bundle `payloads/*.payload.json` |
| One file + explicit schema (CSV / JSON / Parquet / XML / Excel sheet) | `rdp_ingest_*_path` or `rdp_excel_ingest_path_sheet` |
| Pytest-shaped regression without crafting payloads | `rdp_parity_*` via `RdpNativeJson.invokeParityExport` |

## Shared JSON fixtures (`tests/fixtures/`) {#shared-json-fixtures-testsfixtures}

Every runnable class under [`docs/java/examples/`](examples/) loads committed JSON from the repo — **not** strings embedded in Java source. Layout (see also [`tests/fixtures/README.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/tests/fixtures/README.md)):

| Bundle | Schemas | Pipelines / payloads | Data files |
| --- | --- | --- | --- |
| `jvm_contract/` | `schemas/three_rows.schema.json`, `id_name.schema.json` | `pipelines/dataframe_centric_sql.pipeline.json`, `sql_query_dataset.pipeline.json`, `ordered_json_to_parquet.pipeline.json`, `payloads/ordered_paths_*.payload.json` | `data/three_rows.json`; also `../jvm_contract_three_rows.json` at fixtures root |
| `ghcn/` | `json_source`, `xml_intermediate`, `parquet_lake` under `schemas/` | `pipelines/json_to_xml.pipeline.json`, `xml_to_parquet.pipeline.json` | `ghcn_stations_sample.json` (5 NOAA stations, committed sample) |
| `people/` | `people_csv`, `people_json`, `people_flat`, … | `pipelines/csv_to_parquet.pipeline.json`, `payloads/*_path_*.json` | `../people.csv`, `../people.json`, `../people.xlsx` |
| `student_etl/` | `student_source`, `lake_grade_stats`, `postgres_courses` | `legacy_student_etl*.pipeline.json`, `payloads/ordered_ingest_*.payload.json` | `data/part-0000*.json` |
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

[`PipelineJsonFixtures`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/main/java/io/github/scorpio_datalake/rust_data_processing/fixture/PipelineJsonFixtures.java) resolves `tests/fixtures` by walking up from the working directory until `people.csv` exists (or uses `GITHUB_WORKSPACE` in CI):

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

## JSON-first doc examples {#json-first-doc-examples}

Source: [`docs/java/examples/*.java`](examples/). Each class has a `main` that expects `RDP_JVM_SYS` (or `-Drdp.jvm.sys.library`) and `--enable-native-access=ALL-UNNAMED`. Copy the class into a module that depends on `rust-data-processing-jvm`.

| Java class | Native entry point(s) | Primary bundle | JUnit (when native lib present) |
| --- | --- | --- | --- |
| [`DataFrameCentricPipeline.java`](examples/DataFrameCentricPipeline.java) | `rdp_run_pipeline_json` | `jvm_contract` | `runPipelineJsonPolarsSqlFilterAndMultiplyMatchesDocsExample`; Rust/Python `dataframe_centric_pipeline_fixtures` |
| [`SQLQueries.java`](examples/SQLQueries.java) | `rdp_run_pipeline_json` + `rdp_parity_sql_suite_mirror` | `jvm_contract`, `sql_parity` | `runPipelineJsonSingleTableSql…`, `rdpParitySqlSuiteMirrorJoin…` |
| [`GhcnJsonXmlParquetPipeline.java`](examples/GhcnJsonXmlParquetPipeline.java) | `rdp_run_pipeline_json`, `rdp_ingest_xml_path`, `rdp_ingest_parquet_path` | `ghcn` | `ghcnJsonXmlParquetPipelineMatchesDocsExample`, `XmlGhcnPipelineContractTest`; Rust/Python `ghcn_*_pipeline_fixtures` |
| [`ExcelSnippets.java`](examples/ExcelSnippets.java) | `rdp_ingest_ordered_paths_json`, `rdp_excel_ingest_path_sheet` | `people` | `excelSnippetsPeopleMatchesDocsExampleWhenFixturePresent`; Rust/Python `excel_snippets_fixtures` |
| [`JsonParquetExcelSnippets.java`](examples/JsonParquetExcelSnippets.java) | `rdp_ingest_ordered_paths_json`, `rdp_ingest_*_path`, `rdp_run_pipeline_json` | `people` | `jsonParquetExcelSnippetsPeopleMatchesDocsExample` |
| [`ParquetSnippets.java`](examples/ParquetSnippets.java) | `rdp_run_pipeline_json`, `rdp_export_parquet_temp`, `rdp_ingest_parquet_path` | `people` | `parquetSnippetsCsvToParquetRoundTripMatchesDocsExample`, `parquetSnippetsExportTempMatchesDocsExample` |
| [`PathFromDirectoryScan.java`](examples/PathFromDirectoryScan.java) | `rdp_ingest_ordered_paths_json` | `watermark` | `pathFromDirectoryScanWatermarkMatchesDocsExample` |
| [`RDPOnlyETLExample.java`](examples/RDPOnlyETLExample.java) | `rdp_ingest_ordered_paths_json`, legacy pipeline JSON | `student_etl` | `studentEtlLegacyThreePaths…`, `studentEtlOrderedIngestTwoParts…` |

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

### Watermark + directory scan (`PathFromDirectoryScan`)

Java lists files (glob) like Python `paths_from_directory_scan`; Rust ingests with watermark options from `watermark/payloads/csv_watermark_ingest.body.json`:

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

Loads **legacy control-plane** JSON (`student_etl/pipelines/legacy_student_etl.pipeline.json` — catalog URIs, sink metadata) and runs **ordered JSON ingest** over `data/part-0000*.json` via `ordered_ingest_dataset.payload.json`. Execution stays in Rust; Java supplies paths and schemas only.

## JUnit tests for doc examples {#junit-tests-for-doc-examples}

[`DocsExampleNativeIntegrationTest`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/test/java/io/github/scorpio_datalake/rust_data_processing/docexamples/DocsExampleNativeIntegrationTest.java) and [`XmlGhcnPipelineContractTest`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm/src/test/java/io/github/scorpio_datalake/rust_data_processing/XmlGhcnPipelineContractTest.java) call the same helpers as the doc classes (`JvmNativeContractScenarios`). They **assume** a built native library:

```bash
# From repository root (Linux/macOS; adjust extension on Windows)
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full
export RDP_JVM_SYS=$PWD/bindings/jvm-sys/target/release/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
cd bindings/java/rust-data-processing-jvm
./gradlew test --tests 'io.github.scorpio_datalake.rust_data_processing.docexamples.*' \
  --tests 'io.github.scorpio_datalake.rust_data_processing.XmlGhcnPipelineContractTest'
# or: mvn -q test -Dtest=DocsExampleNativeIntegrationTest,XmlGhcnPipelineContractTest
```

Manifest-wide symbol smoke remains in **`FfiExportedSymbolsContractTest`** (separate from the doc-example suite).

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
- **Artifact:** `io.github.scorpio-datalake.rust-data-processing:rust-data-processing-jvm` (same version as [`bindings/java/VERSION`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/VERSION)).
- **Native library:** build `rdp_jvm_sys` from [`bindings/jvm-sys/`](https://github.com/rust-data-processing/rust-data-processing/tree/main/bindings/jvm-sys) (CI: `cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full`). Point Java at the absolute path:
  - Environment variable **`RDP_JVM_SYS`**, or
  - System property **`-Drdp.jvm.sys.library=…`**
- **JVM flag:** `--enable-native-access=ALL-UNNAMED` (or a tighter module policy if you wire one).

## FFI manifest and ABI

The JAR bundles **`ffi_manifest.json`** (same content as [`bindings/jvm-sys/ffi_manifest.json`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/jvm-sys/ffi_manifest.json)). Read it from the classpath to list **`exported_symbols`** and compare **`abi_version_constant`** with a live **`rdp_ffi_abi_version`** downcall.

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

Path lib = Path.of(System.getenv("RDP_JVM_SYS")); // or System.getProperty("rdp.jvm.sys.library")
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

Validate the envelope and `kind` the same way as **`FfiExportedSymbolsContractTest`** and **`DocsExampleNativeIntegrationTest`** in `rust-data-processing-jvm`.

For copy-pasteable classes with committed fixtures, see [JSON-first doc examples](#json-first-doc-examples). Parity-only sketches: [`rdp_parity_ingestion`](#file-etl-ingestion-and-tabular-json), [`rdp_parity_types_dataset`](#file-etl-ingestion-and-tabular-json).

<h2 id="ordered-paths-and-directory-scans-incremental-batches">Ordered paths and directory scans (incremental batches)</h2>

In Python, incremental batch patterns use **`paths_from_directory_scan`**, **`ingest_from_ordered_paths`**, watermark options, and Hive-style layout helpers — see [the same heading in the Python examples](../python/examples.html#ordered-paths-and-directory-scans-incremental-batches) on this site and [`docs/python/PHASE2_EXAMPLES.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/python/PHASE2_EXAMPLES.md) § 10.

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

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "rust-data-processing-jvm-examples-…jar:rust-data-processing-jvm-…jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.ParityScenariosWalkthrough
```

(Exact `-cp` lines: [`bindings/java/rust-data-processing-jvm-examples/README.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm-examples/README.md).)

## See also

- [`FFI_MANIFEST_JAVA_USAGE.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/FFI_MANIFEST_JAVA_USAGE.md) — Maven, `java -cp`, manifest resource path.
- [`README.md` (JVM doc index)](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/README.md) — Phase 3 links.
- [Python examples (`../python/examples.html`)](../python/examples.html) — full API tour on the same docs site.
- [`ARROW_FFI_JVM.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/ARROW_FFI_JVM.md) — future Arrow IPC direction.
