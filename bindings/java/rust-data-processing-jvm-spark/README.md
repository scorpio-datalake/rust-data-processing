# JVM ↔ Spark (`rust-data-processing-jvm-spark`)

One-call helpers that turn **RDP Panama JSON responses** into **`Dataset<Row>`** while hiding temp files.

| Class | Role |
| --- | --- |
| **`RdpSparkMaterializer`** | `fromRdpJsonRoot` / `fromParityExport` / `fromExportParquetTemp` / `fromExportArrowIpcTemp` / `fromExportPolarsParquetTemp` — reads **temp Parquet** (`parquet_export_temp`, `polars_parquet_export_temp`), **temp Arrow IPC** (`arrow_ipc_export_temp`, via Apache Arrow Java → `createDataFrame`), or writes tabular JSON to **temp CSV** when `interchange.dataset` exists; then **cache + count**, **delete** temp files, return `Dataset<Row>`. |

## Build

Requires `rust-data-processing-jvm` installed locally (`mvn install` in `rust-data-processing-jvm`).

```bash
cd bindings/java/rust-data-processing-jvm-spark
mvn -q -DskipTests package
```

Spark pulls a large dependency graph; use only when you need Spark on the JVM.

## Example

```java
SparkSession spark = SparkSession.builder().master("local[*]").appName("rdp").getOrCreate();
Linker linker = Linker.nativeLinker();
try (Arena arena = Arena.ofConfined()) {
  SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLib, arena);
  Dataset<Row> df =
      RdpSparkMaterializer.fromParityExport(
          linker, lookup, arena, "rdp_parity_ingestion", spark);
  df.show();
}
```

Run the demo (requires `RDP_JVM_SYS`, JDK 21, `--enable-native-access=ALL-UNNAMED`):

```bash
mvn -q -DskipTests package exec:java \
  -Dexec.jvmArgs="--enable-native-access=ALL-UNNAMED"
```
