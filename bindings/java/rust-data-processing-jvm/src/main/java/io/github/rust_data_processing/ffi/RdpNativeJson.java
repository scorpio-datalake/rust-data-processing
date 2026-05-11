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

  /** Full JSON envelope: {@code ok}, {@code interchange}, {@code notes}. */
  public static JSONObject invokeParityExport(
      Linker linker, SymbolLookup lookup, Arena arena, String exportedSymbol) throws Throwable {
    MemorySegment out = arena.allocate(RDP_JSON_SLICE_LAYOUT);
    MethodHandle fn =
        linker.downcallHandle(
            lookup.find(exportedSymbol).orElseThrow(),
            FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
    fn.invokeExact(out);

    long ptrRaw = out.get(ValueLayout.ADDRESS, 0);
    long len = out.get(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS.byteSize());
    if (ptrRaw == 0L) {
      throw new IllegalStateException(exportedSymbol + ": null JSON ptr");
    }
    MemorySegment utf8 =
        MemorySegment.ofAddress(ptrRaw).reinterpret(len, arena, null);
    byte[] rawJson = utf8.toArray(ValueLayout.JAVA_BYTE);
    String json = new String(rawJson, StandardCharsets.UTF_8);

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

    long ptrRaw = out.get(ValueLayout.ADDRESS, 0);
    long len = out.get(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS.byteSize());
    if (ptrRaw == 0L) {
      throw new IllegalStateException("rdp_excel_ingest_path_sheet: null JSON ptr");
    }
    MemorySegment utf8 =
        MemorySegment.ofAddress(ptrRaw).reinterpret(len, arena, null);
    byte[] rawJson = utf8.toArray(ValueLayout.JAVA_BYTE);
    String json = new String(rawJson, StandardCharsets.UTF_8);

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

    long ptrRaw = out.get(ValueLayout.ADDRESS, 0);
    long len = out.get(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS.byteSize());
    if (ptrRaw == 0L) {
      throw new IllegalStateException("rdp_ingest_ordered_paths_json: null JSON ptr");
    }
    MemorySegment utf8 =
        MemorySegment.ofAddress(ptrRaw).reinterpret(len, arena, null);
    byte[] rawJson = utf8.toArray(ValueLayout.JAVA_BYTE);
    String json = new String(rawJson, StandardCharsets.UTF_8);

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

    long ptrRaw = out.get(ValueLayout.ADDRESS, 0);
    long len = out.get(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS.byteSize());
    if (ptrRaw == 0L) {
      throw new IllegalStateException(symbol + ": null JSON ptr");
    }
    MemorySegment utf8 =
        MemorySegment.ofAddress(ptrRaw).reinterpret(len, arena, null);
    byte[] rawJson = utf8.toArray(ValueLayout.JAVA_BYTE);
    String json = new String(rawJson, StandardCharsets.UTF_8);

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
