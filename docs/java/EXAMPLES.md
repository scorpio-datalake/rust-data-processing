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

From Java, call **`RdpNativeJson.invokeExportParquetTemp`**, then read the path with **Spark in `local[*]` mode** (or any Parquet reader), then delete the file. Helpers: **`io.github.rust_data_processing.integration.RdpParquetTemp`**. Runnable: **`ParquetTempExportExample`**.

```java
import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.integration.RdpParquetTemp;
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
- **Artifact:** `io.github.rust_data_processing:rust-data-processing-jvm` (same version as [`bindings/java/VERSION`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/VERSION)).
- **Native library:** build `rdp_jvm_sys` from [`bindings/jvm-sys/`](https://github.com/rust-data-processing/rust-data-processing/tree/main/bindings/jvm-sys) (CI uses `cargo build --release -p rdp-jvm-sys` with JVM features). Point Java at the absolute path:
  - Environment variable **`RDP_JVM_SYS`**, or
  - System property **`-Drdp.jvm.sys.library=…`**
- **JVM flag:** `--enable-native-access=ALL-UNNAMED` (or a tighter module policy if you wire one).

## FFI manifest and ABI

The JAR bundles **`ffi_manifest.json`** (same content as [`bindings/jvm-sys/ffi_manifest.json`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/jvm-sys/ffi_manifest.json)). Read it from the classpath to list **`exported_symbols`** and compare **`abi_version_constant`** with a live **`rdp_ffi_abi_version`** downcall.

```java
import io.github.rust_data_processing.ffi.RdpNativeJson;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

try (InputStream in = RdpNativeJson.class.getResourceAsStream(RdpNativeJson.FFI_MANIFEST_RESOURCE)) {
  JSONObject manifest = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
  int abiFromJar = manifest.getInt("abi_version_constant");
  // … compare with RdpNativeJson.invokeAbiVersion(linker, lookup) when native lib is loaded
}
```

Runnable: **`io.github.rust_data_processing.examples.LoadFfiManifestExample`**.

## Calling `rdp_parity_*` from Java

Parity exports take no Java arguments: Rust builds the scenario (fixtures, options), runs the engine, and writes a JSON envelope into an **`RdpJsonSlice`**. Use **`RdpNativeJson.invokeParityExport`** so the slice is freed correctly.

```java
import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
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

Runnable: **`io.github.rust_data_processing.examples.RunPytestMirrorExample`** — pass the export name as the only CLI argument.

For **how large `dataset` JSON fits into a production architecture**, see [Rust-first ETL vs JVM consumption](#rust-first-etl-vs-jvm-consumption).

## File ETL (ingestion and tabular JSON)

Python uses **`ingest_from_path`** and **`DataSet`** directly. On the JVM, see:

- **`rdp_parity_ingestion`** — CSV / ingestion path exercised in Rust; `interchange` includes tabular **`dataset`** (`schema` + `rows`) for Java-side projection.
- **`rdp_parity_types_dataset`** — tabular JSON shape for typed datasets.

Validate the envelope and `kind` the same way as **`FfiExportedSymbolsContractTest`** in `rust-data-processing-jvm`.

Illustrative Java snippets (copy into a module that depends on `rust-data-processing-jvm`): [`JsonParquetExcelSnippets.java`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/JsonParquetExcelSnippets.java) (CSV / JSON metrics), [`ExcelSnippets.java`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/ExcelSnippets.java) (`rdp_excel_ingest_path_sheet`), [`ParquetSnippets.java`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/ParquetSnippets.java) (`rdp_export_parquet_temp`).

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

**`io.github.rust_data_processing.examples.ParityScenariosWalkthrough`** runs a curated list of exports (including types, bindings, mapping, transform, processing, SQL, validation, benchmark smoke) and prints short summaries — useful to **see** several `interchange` shapes in one JVM process.

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "rust-data-processing-jvm-examples-…jar:rust-data-processing-jvm-…jar" \
  io.github.rust_data_processing.examples.ParityScenariosWalkthrough
```

(Exact `-cp` lines: [`bindings/java/rust-data-processing-jvm-examples/README.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/bindings/java/rust-data-processing-jvm-examples/README.md).)

## See also

- [`FFI_MANIFEST_JAVA_USAGE.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/FFI_MANIFEST_JAVA_USAGE.md) — Maven, `java -cp`, manifest resource path.
- [`README.md` (JVM doc index)](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/README.md) — Phase 3 links.
- [Python examples (`../python/examples.html`)](../python/examples.html) — full API tour on the same docs site.
- [`ARROW_FFI_JVM.md`](https://github.com/rust-data-processing/rust-data-processing/blob/main/docs/java/ARROW_FFI_JVM.md) — future Arrow IPC direction.
