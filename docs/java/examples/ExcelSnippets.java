import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Excel ETL from Java via RDP: {@code rdp_excel_ingest_path_sheet} (Panama downcall in {@link
 * RdpNativeJson#excelIngestPathSheet}). Rust infers schema from the workbook for the named sheet,
 * then ingests — same idea as Python:
 *
 * <pre>
 *   ds = rdp.ingest_from_path(
 *       "workbook.xlsx",
 *       schema,
 *       {"format": "excel", "sheet_name": "Sheet1"},
 *   )
 * </pre>
 *
 * <p>The JVM passes <strong>UTF-8 path + sheet name</strong> as C strings; the response is the usual
 * JSON envelope with {@code interchange.kind} = {@code excel_ingest_sheet} and tabular {@code
 * interchange.dataset} ({@code schema} + {@code rows}).
 *
 * <p>Requires built {@code rdp_jvm_sys} with Excel support (CI uses {@code --features full}). Provide a
 * real {@code .xlsx} path (e.g. repo fixture when present). When a fixture is absent, skip or supply
 * your own file.
 */
public final class ExcelSnippets {

  private ExcelSnippets() {}

  /** Same column shape as Python {@code schema_flat} for people-style workbooks (reference only). */
  public static JSONArray schemaFlatTabular() {
    return new JSONArray()
        .put(new JSONObject().put("name", "id").put("data_type", "int64"))
        .put(new JSONObject().put("name", "name").put("data_type", "utf8"))
        .put(new JSONObject().put("name", "score").put("data_type", "float64"))
        .put(new JSONObject().put("name", "active").put("data_type", "bool"));
  }

  /**
   * Ingest one Excel tab: {@code rdp_excel_ingest_path_sheet}. Returns full envelope; use {@code
   * root.getJSONObject("interchange").getJSONObject("dataset")} for tabular JSON.
   */
  public static JSONObject excelIngestSheet(
      Linker linker, SymbolLookup lookup, Arena arena, Path workbook, String sheetName)
      throws Throwable {
    JSONObject root =
        RdpNativeJson.excelIngestPathSheet(
            linker, lookup, arena, workbook.toAbsolutePath().toString(), sheetName);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"excel_ingest_sheet".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return root;
  }

  /** Convenience: {@link #excelIngestSheet} after {@code SymbolLookup.libraryLookup(nativeLibrary)}. */
  public static JSONObject excelIngestSheet(Path nativeLibrary, Path workbook, String sheetName)
      throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      return excelIngestSheet(linker, lookup, arena, workbook, sheetName);
    }
  }
}
