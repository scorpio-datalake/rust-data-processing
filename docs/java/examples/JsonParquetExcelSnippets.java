import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * RDP (Phase 3) ETL from Java using Panama + JSON parity exports — aligned with Python:
 *
 * <pre>
 *   ds_json = rdp.ingest_from_path(..., schema_json, {"format": "json"})
 *   ds_parquet = rdp.ingest_from_path(..., schema_flat, {"format": "parquet"})
 *   ds_excel = rdp.ingest_from_path(..., schema_flat, {"format": "excel"})
 * </pre>
 *
 * <p>There is no single {@code ingest_from_path} Java API yet. This class covers <strong>CSV</strong>
 * ({@value #RDP_PARITY_INGESTION}) and <strong>JSON ingest metrics</strong> ({@value #RDP_PARITY_WATERMARK_MIRROR}).
 * For <strong>Excel path + sheet</strong> see {@code ExcelSnippets}; for <strong>temp Parquet handoff</strong>
 * see {@code ParquetSnippets} and {@code RdpNativeJson.invokeExportParquetTemp}.
 */
public final class JsonParquetExcelSnippets {

  private static final String RDP_PARITY_INGESTION = "rdp_parity_ingestion";
  private static final String RDP_PARITY_WATERMARK_MIRROR = "rdp_parity_watermark_mirror";

  private JsonParquetExcelSnippets() {}

  /** Python {@code {"format": "json"}} style options (extend with sheet_name for Excel, etc.). */
  public static JSONObject ingestionOptions(String format) {
    return new JSONObject().put("format", format);
  }

  /** Same shape as Python {@code schema_json} for {@code tests/fixtures/people.json}. */
  public static JSONArray schemaJsonPeopleFixture() {
    return new JSONArray()
        .put(new JSONObject().put("name", "id").put("data_type", "int64"))
        .put(new JSONObject().put("name", "user.name").put("data_type", "utf8"))
        .put(new JSONObject().put("name", "score").put("data_type", "float64"))
        .put(new JSONObject().put("name", "active").put("data_type", "bool"));
  }

  /** Same shape as Python {@code schema_flat} for Parquet / Excel. */
  public static JSONArray schemaFlatTabular() {
    return new JSONArray()
        .put(new JSONObject().put("name", "id").put("data_type", "int64"))
        .put(new JSONObject().put("name", "name").put("data_type", "utf8"))
        .put(new JSONObject().put("name", "score").put("data_type", "float64"))
        .put(new JSONObject().put("name", "active").put("data_type", "bool"));
  }

  /**
   * ETL via RDP: CSV reader parity — returns {@code interchange.dataset} (schema + rows), same contract
   * as {@code FfiExportedSymbolsContractTest} for {@value #RDP_PARITY_INGESTION}.
   */
  public static JSONObject etlCsvDatasetViaRdp(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, RDP_PARITY_INGESTION);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"ingestion_csv_reader_polars".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange.getJSONObject("dataset");
  }

  /**
   * ETL via RDP: JSON file ingest is run inside Rust for {@value #RDP_PARITY_WATERMARK_MIRROR}; Java
   * receives {@code interchange} with {@code json_row_count} (and CSV watermark fields). Use this to
   * validate JSON ingest wiring; for arbitrary paths like {@code people.json}, Python {@code rdp}
   * remains the supported path until a parameterized FFI exists.
   */
  public static JSONObject etlJsonIngestMetricsViaRdp(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, RDP_PARITY_WATERMARK_MIRROR);
    PytestMirrorAssertions.validateMirrorExport(RDP_PARITY_WATERMARK_MIRROR, root);
    return root.getJSONObject("interchange");
  }

  /** Convenience: {@link #etlCsvDatasetViaRdp} with {@code SymbolLookup.libraryLookup}. */
  public static JSONObject etlCsvDatasetViaRdp(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      return etlCsvDatasetViaRdp(linker, lookup, arena);
    }
  }

  /** Convenience: {@link #etlJsonIngestMetricsViaRdp} with {@code SymbolLookup.libraryLookup}. */
  public static JSONObject etlJsonIngestMetricsViaRdp(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      return etlJsonIngestMetricsViaRdp(linker, lookup, arena);
    }
  }

  /*
   * Parquet / Excel from Java: see ParquetSnippets.java (rdp_export_parquet_temp) and
   * ExcelSnippets.java (rdp_excel_ingest_path_sheet). Arbitrary Parquet path ingest from JVM is still
   * via Python rdp or Rust crate until a dedicated FFI exists.
   */
}
