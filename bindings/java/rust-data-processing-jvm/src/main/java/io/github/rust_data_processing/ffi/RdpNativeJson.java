package io.github.rust_data_processing.ffi;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Invokes {@code rdp_parity_*} and related JSON-slice exports that fill {@link MemorySegment}
 * structured as {@code RdpJsonSlice} — same layout used by JUnit tests and runnable examples.
 */
public final class RdpNativeJson {

  /**
   * Classpath location of the JSON manifest shipped in the {@code rust-data-processing-jvm} JAR
   * (must match {@code bindings/jvm-sys/ffi_manifest.json} in the repository).
   */
  public static final String FFI_MANIFEST_RESOURCE =
      "/io/github/rust_data_processing/ffi_manifest.json";

  private static final GroupLayout RDP_JSON_SLICE_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.ADDRESS.withName("ptr"),
          ValueLayout.JAVA_LONG.withName("len"),
          ValueLayout.JAVA_LONG.withName("cap"));

  private RdpNativeJson() {}

  /**
   * Resolves an existing native library path from {@code RDP_JVM_SYS} or system property {@code
   * rdp.jvm.sys.library} (first match wins). Returns {@code null} if unset, blank, or the path is
   * not a regular file (symlinks to files count as present).
   */
  public static Path resolveNativeLibraryFromEnvOrProperty() {
    String env = System.getenv("RDP_JVM_SYS");
    if (env != null && !env.isBlank()) {
      Path p = Path.of(env.strip()).toAbsolutePath();
      if (Files.isRegularFile(p)) {
        return p;
      }
    }
    String prop = System.getProperty("rdp.jvm.sys.library");
    if (prop != null && !prop.isBlank()) {
      Path p = Path.of(prop.strip()).toAbsolutePath();
      if (Files.isRegularFile(p)) {
        return p;
      }
    }
    return null;
  }

  /**
   * Reads UTF-8 JSON from native {@code RdpJsonSlice} after Rust filled {@code sliceStruct} ({@code
   * ptr}, {@code len}, {@code cap}).
   */
  private static String readJsonSliceUtf8(MemorySegment sliceStruct, Arena arena, String what) {
    MemorySegment ptrSeg = sliceStruct.get(ValueLayout.ADDRESS, 0);
    long len = sliceStruct.get(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS.byteSize());
    if (ptrSeg.address() == 0L) {
      throw new IllegalStateException(what + ": null JSON ptr");
    }
    MemorySegment utf8 = ptrSeg.reinterpret(len, arena, null);
    byte[] rawJson = utf8.toArray(ValueLayout.JAVA_BYTE);
    return new String(rawJson, StandardCharsets.UTF_8);
  }

  /** Full JSON envelope: {@code ok}, {@code interchange}, {@code notes}. */
  public static JSONObject invokeParityExport(
      Linker linker, SymbolLookup lookup, Arena arena, String exportedSymbol) throws Throwable {
    MemorySegment out = arena.allocate(RDP_JSON_SLICE_LAYOUT);
    MethodHandle fn =
        linker.downcallHandle(
            lookup.find(exportedSymbol).orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    fn.invokeExact(out);

    String json = readJsonSliceUtf8(out, arena, exportedSymbol);

    MethodHandle free =
        linker.downcallHandle(
            lookup.find("rdp_json_slice_free").orElseThrow(),
            FunctionDescriptor.ofVoid(RDP_JSON_SLICE_LAYOUT));
    free.invokeExact(out);

    return new JSONObject(json);
  }

  /**
   * Writes a sample {@code DataSet} to a temp Parquet file; returns JSON with {@code
   * interchange.kind} = {@code parquet_export_temp}, {@code path}, {@code row_count}, {@code
   * schema}. For Spark, prefer {@code rust-data-processing-jvm-spark} {@code RdpSparkMaterializer}
   * (materialize, then delete) instead of managing the path by hand.
   */
  public static JSONObject invokeExportParquetTemp(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    return invokeParityExport(linker, lookup, arena, "rdp_export_parquet_temp");
  }

  /**
   * Sample {@code DataSet} written to a temp Arrow IPC file; {@code interchange.kind} = {@code
   * arrow_ipc_export_temp}.
   */
  public static JSONObject invokeExportArrowIpcTemp(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    return invokeParityExport(linker, lookup, arena, "rdp_export_arrow_ipc_temp");
  }

  /**
   * Polars SQL on a small in-memory frame, result written to temp Parquet (no embedded {@code
   * dataset} JSON); {@code interchange.kind} = {@code polars_parquet_export_temp}.
   */
  public static JSONObject invokeExportPolarsParquetTemp(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    return invokeParityExport(linker, lookup, arena, "rdp_export_polars_parquet_temp");
  }

  /**
   * Excel ingest for a single sheet, backed by {@code rdp_excel_ingest_path_sheet}. The native
   * function infers a schema from the workbook (path + sheet), then ingests again with that schema
   * and returns a {@code dataset} under {@code interchange.dataset}.
   */
  public static JSONObject excelIngestPathSheet(
      Linker linker, SymbolLookup lookup, Arena arena, String path, String sheetName)
      throws Throwable {
    MemorySegment out = arena.allocate(RDP_JSON_SLICE_LAYOUT);
    MemorySegment pathUtf8 = arena.allocateUtf8String(path);
    MemorySegment sheetUtf8 = arena.allocateUtf8String(sheetName);

    MethodHandle fn =
        linker.downcallHandle(
            lookup.find("rdp_excel_ingest_path_sheet").orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS, // out
                ValueLayout.ADDRESS, // path ptr
                ValueLayout.ADDRESS // sheet ptr
                ));
    fn.invokeExact(out, pathUtf8, sheetUtf8);

    String json = readJsonSliceUtf8(out, arena, "rdp_excel_ingest_path_sheet");

    MethodHandle free =
        linker.downcallHandle(
            lookup.find("rdp_json_slice_free").orElseThrow(),
            FunctionDescriptor.ofVoid(RDP_JSON_SLICE_LAYOUT));
    free.invokeExact(out);

    return new JSONObject(json);
  }

  /**
   * Ingest one CSV file: {@code rdp_ingest_csv_path}. {@code schemaJson} is serde {@code Schema}
   * JSON; {@code optionsJson} is an object (use {@code "{}"} for defaults). Format is forced to
   * CSV regardless of extension.
   */
  public static JSONObject invokeIngestCsvPath(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String path,
      String schemaJson,
      String optionsJson)
      throws Throwable {
    return invokePathIngest(linker, lookup, arena, "rdp_ingest_csv_path", path, schemaJson, optionsJson);
  }

  /** Ingest one JSON file (array-of-objects or NDJSON): {@code rdp_ingest_json_path}. */
  public static JSONObject invokeIngestJsonPath(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String path,
      String schemaJson,
      String optionsJson)
      throws Throwable {
    return invokePathIngest(linker, lookup, arena, "rdp_ingest_json_path", path, schemaJson, optionsJson);
  }

  /** Ingest one Parquet file: {@code rdp_ingest_parquet_path}. */
  public static JSONObject invokeIngestParquetPath(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String path,
      String schemaJson,
      String optionsJson)
      throws Throwable {
    return invokePathIngest(linker, lookup, arena, "rdp_ingest_parquet_path", path, schemaJson, optionsJson);
  }

  /**
   * Ordered multi-path ingest: {@code rdp_ingest_ordered_paths_json}. {@code payloadJson} is UTF-8
   * JSON with {@code paths}, {@code schema}, {@code options}, and {@code response} ({@code mode} =
   * {@code dataset} | {@code parquet_temp} | {@code arrow_ipc_temp}).
   */
  public static JSONObject invokeIngestOrderedPathsJson(
      Linker linker, SymbolLookup lookup, Arena arena, String payloadJson) throws Throwable {
    MemorySegment out = arena.allocate(RDP_JSON_SLICE_LAYOUT);
    MemorySegment payloadUtf8 = arena.allocateUtf8String(payloadJson);
    MethodHandle fn =
        linker.downcallHandle(
            lookup.find("rdp_ingest_ordered_paths_json").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    fn.invokeExact(out, payloadUtf8);

    String json = readJsonSliceUtf8(out, arena, "rdp_ingest_ordered_paths_json");

    MethodHandle free =
        linker.downcallHandle(
            lookup.find("rdp_json_slice_free").orElseThrow(),
            FunctionDescriptor.ofVoid(RDP_JSON_SLICE_LAYOUT));
    free.invokeExact(out);

    return new JSONObject(json);
  }

  /**
   * Single-document pipeline: {@code rdp_run_pipeline_json}. {@code payloadJson} is UTF-8 JSON
   * with optional {@code pipeline_spec_version} (default 1; alias {@code version}), optional
   * {@code orchestration} ({@code timeout_ms}, {@code max_ingested_rows}, {@code idempotency_key}),
   * {@code sources} (paths, schema, options), optional {@code transform.sql} on {@code df}, and
   * {@code sinks}. On failure {@code ok} is false and {@code error} is an object with {@code code},
   * {@code message}, and {@code stage} (see ADR 006). The legacy student-ETL document shape is also
   * accepted.
   */
  public static JSONObject invokeRunPipelineJson(
      Linker linker, SymbolLookup lookup, Arena arena, String payloadJson) throws Throwable {
    MemorySegment out = arena.allocate(RDP_JSON_SLICE_LAYOUT);
    MemorySegment payloadUtf8 = arena.allocateUtf8String(payloadJson);
    MethodHandle fn =
        linker.downcallHandle(
            lookup.find("rdp_run_pipeline_json").orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS));
    fn.invokeExact(out, payloadUtf8);

    String json = readJsonSliceUtf8(out, arena, "rdp_run_pipeline_json");

    MethodHandle free =
        linker.downcallHandle(
            lookup.find("rdp_json_slice_free").orElseThrow(),
            FunctionDescriptor.ofVoid(RDP_JSON_SLICE_LAYOUT));
    free.invokeExact(out);

    return new JSONObject(json);
  }

  private static JSONObject invokePathIngest(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String symbol,
      String path,
      String schemaJson,
      String optionsJson)
      throws Throwable {
    MemorySegment out = arena.allocate(RDP_JSON_SLICE_LAYOUT);
    MemorySegment pathUtf8 = arena.allocateUtf8String(path);
    MemorySegment schemaUtf8 = arena.allocateUtf8String(schemaJson);
    MemorySegment optionsUtf8 = arena.allocateUtf8String(optionsJson);

    MethodHandle fn =
        linker.downcallHandle(
            lookup.find(symbol).orElseThrow(),
            FunctionDescriptor.ofVoid(
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS,
                ValueLayout.ADDRESS));
    fn.invokeExact(out, pathUtf8, schemaUtf8, optionsUtf8);

    String json = readJsonSliceUtf8(out, arena, symbol);

    MethodHandle free =
        linker.downcallHandle(
            lookup.find("rdp_json_slice_free").orElseThrow(),
            FunctionDescriptor.ofVoid(RDP_JSON_SLICE_LAYOUT));
    free.invokeExact(out);

    return new JSONObject(json);
  }

  public static int invokeAbiVersion(Linker linker, SymbolLookup lookup) throws Throwable {
    MethodHandle mh =
        linker.downcallHandle(
            lookup.find("rdp_ffi_abi_version").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
    return (int) mh.invokeExact();
  }
}
