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
 * <p>There is no {@code ingest_from_path} on the JVM yet. Use {@link RdpNativeJson#invokeParityExport}
 * for {@code rdp_parity_*} symbols (see {@code ffi_manifest.json}). CSV tabular JSON comes from
 * {@value #RDP_PARITY_INGESTION}; JSON <em>file</em> ingest is exercised inside
 * {@value #RDP_PARITY_WATERMARK_MIRROR} (fixture JSON, metrics in {@code interchange}). Parquet and
 * Excel path ingest are not in a parity export today — keep using Python {@code rdp} for those until
 * FFI catches up.
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
   * Python Parquet / Excel (not mirrored on JVM yet):
   *
   *   rdp.ingest_from_path("path/to/file.parquet", schema_flat, {"format": "parquet"})
   *   rdp.ingest_from_path("path/to/file.xlsx", schema_flat, {"format": "excel"})
   *
   * Build options with ingestionOptions("parquet") or ingestionOptions("excel"); wire schema_flat
   * via schemaFlatTabular() when calling Python from Java (subprocess, HTTP, etc.).
   */
}
