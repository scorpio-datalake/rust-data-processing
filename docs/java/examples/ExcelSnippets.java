import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Excel ETL from Java via RDP using shared fixture JSON (same bundle as {@code people.csv}).
 *
 * <p><strong>Recommended — JSON schema + payload</strong> ({@link #excelIngestViaPayload}): loads
 * {@code tests/fixtures/people/schemas/people_flat.schema.json} and {@code
 * payloads/excel_sheet_dataset.payload.json}, then calls {@code rdp_ingest_ordered_paths_json}.
 * This mirrors Python:
 *
 * <pre>
 *   ds = rdp.ingest_from_path(
 *       "people.xlsx", schema_flat, {"format": "excel", "sheet_name": "Sheet1"})
 * </pre>
 *
 * <p><strong>Alternate — dedicated FFI</strong> ({@link #excelIngestPathSheet}): {@code
 * rdp_excel_ingest_path_sheet} infers schema in Rust (no schema JSON on the wire). Manifest contract
 * tests use this symbol; see {@code FfiExportedSymbolsContractTest}.
 *
 * <p>Fixture: {@code tests/fixtures/people.xlsx} (sheet {@code Sheet1}). Generate if missing:
 * {@code cargo run --features excel_test_writer --bin generate_people_xlsx_fixture}.
 *
 * <p><strong>Tests</strong> — Rust {@code tests/excel_snippets_fixtures.rs} ({@code --features excel}),
 * {@code jvm-sys} {@code people_excel_sheet_dataset_payload_ordered_ingest}, Python {@code
 * test_excel_snippets_fixtures.py}, JUnit {@code
 * DocsExampleNativeIntegrationTest#excelSnippetsPeopleMatchesDocsExampleWhenFixturePresent}.
 */
public final class ExcelSnippets {

  private static final String PEOPLE_XLSX = "people.xlsx";
  private static final String DEFAULT_SHEET = "Sheet1";
  private static final String SCHEMA = "schemas/people_flat.schema.json";
  private static final String PAYLOAD = "payloads/excel_sheet_dataset.payload.json";

  private ExcelSnippets() {}

  public static Path peopleBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, "people")
        .orElseThrow(() -> new IllegalStateException("tests/fixtures/people not found"));
  }

  /** {@code tests/fixtures/people/schemas/people_flat.schema.json}. */
  public static JSONObject schemaFlatTabular(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(peopleBundle(fixturesDir), SCHEMA);
  }

  public static JSONArray schemaFlatFields(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaFieldsArray(peopleBundle(fixturesDir), SCHEMA);
  }

  /**
   * Resolved {@code rdp_ingest_ordered_paths_json} body: schema_ref expanded, paths/options bound.
   */
  public static String excelIngestPayloadJson(Path fixturesDir, Path workbook, String sheetName)
      throws Exception {
    return PipelineJsonFixtures.resolvePayloadJson(
        peopleBundle(fixturesDir),
        PAYLOAD,
        Map.of(
            "SOURCE_PATH", workbook.toAbsolutePath().normalize().toString(),
            "SHEET_NAME", sheetName));
  }

  /**
   * Ingest one Excel sheet with committed schema JSON + payload template (Python-aligned).
   */
  public static JSONObject excelIngestViaPayload(
      Linker linker, SymbolLookup lookup, Arena arena, Path workbook, String sheetName)
      throws Throwable {
    Path fixtures =
        PipelineJsonFixtures.resolveTestsFixturesDir()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tests/fixtures not found — run from repository checkout"));
    String payload = excelIngestPayloadJson(fixtures, workbook, sheetName);
    JSONObject root =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"ingest_ordered_paths_dataset".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return root;
  }

  /** Dedicated {@code rdp_excel_ingest_path_sheet} (schema inferred in Rust). */
  public static JSONObject excelIngestPathSheet(
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

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Path fixtures =
        PipelineJsonFixtures.resolveTestsFixturesDir()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tests/fixtures not found — run from repository checkout"));
    Path workbook = fixtures.resolve(PEOPLE_XLSX);
    if (!Files.isRegularFile(workbook)) {
      throw new IllegalStateException(
          "Missing "
              + workbook
              + " — cargo run --features excel_test_writer --bin generate_people_xlsx_fixture");
    }

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      JSONObject payloadRoot =
          excelIngestViaPayload(linker, lookup, arena, workbook, DEFAULT_SHEET);
      int rows =
          payloadRoot
              .getJSONObject("interchange")
              .getJSONObject("dataset")
              .getJSONArray("rows")
              .length();
      if (rows != 2) {
        throw new AssertionError("expected 2 rows from people.xlsx Sheet1, got " + rows);
      }
      System.out.println("Excel ingest (JSON schema + payload → rdp_ingest_ordered_paths_json): ok");
      System.out.println("  schema: " + peopleBundle(fixtures).resolve(SCHEMA));
      System.out.println("  payload: " + peopleBundle(fixtures).resolve(PAYLOAD));
      System.out.println("  row_count=" + rows);
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println(
          "Set RDP_JVM_SYS or -Drdp.jvm.sys.library to an existing file path of a built rdp_jvm_sys library.");
      System.exit(2);
    }
    try {
      demonstrate(lib);
    } catch (Throwable t) {
      for (Throwable c = t; c != null; c = c.getCause()) {
        String m = String.valueOf(c.getMessage());
        if (m.contains("native access") || m.contains("Restricted method")) {
          System.err.println(
              "JVM blocked Panama native access; rerun with VM flag: --enable-native-access=ALL-UNNAMED");
          System.exit(2);
          return;
        }
      }
      throw t;
    }
  }
}
