import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phase 2 §9 — End-to-end tabular QA: ingest → validate → JSONL (Python tour).
 *
 * <p><strong>Why this example exists.</strong> The most common production flow is: read a file with a
 * known schema, run validation rules, export JSONL for downstream systems. Python does that in one
 * process; JVM orchestrators often chain <em>separate</em> FFI calls (ingest payload, validation parity,
 * export parity) until a single parameterized pipeline JSON exists.
 *
 * <p><strong>What it demonstrates.</strong>
 *
 * <ol>
 *   <li>Ingest {@code tests/fixtures/people.csv} via {@code people/payloads/csv_path_dataset.payload.json}
 *       → {@code rdp_ingest_ordered_paths_json} (real file, two rows).
 *   <li>Call {@code rdp_parity_validation} for validation summary shape (built-in table, not the ingested
 *       CSV — documented gap).
 *   <li>Call {@code rdp_parity_export_privacy_reports} for {@code jsonl_preview_lines} shape.
 * </ol>
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §9):
 *
 * <pre>{@code
 * ds = rdp.ingest_from_path(path, schema)
 * rep = rdp.validate_dataset(ds, {"checks": [...]})
 * jl = rdp.export_dataset_jsonl(ds, cols)
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#ingestValidateJsonlEndToEndMatchesDocsExample}):
 * asserts ingested row count 2, validation summary present, and at least one JSONL preview line — proving
 * the three-step tour works when fixtures and native lib are available.
 */
public final class IngestValidateJsonlEndToEnd {

  private static final String PEOPLE_CSV = "people.csv";
  private static final String VALIDATION = "rdp_parity_validation";
  private static final String EXPORT = "rdp_parity_export_privacy_reports";

  private IngestValidateJsonlEndToEnd() {}

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Path fixtures =
        PipelineJsonFixtures.resolveTestsFixturesDir()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tests/fixtures not found — run from repository checkout"));
    Path csvPath = fixtures.resolve(PEOPLE_CSV);
    if (!Files.isRegularFile(csvPath)) {
      throw new IllegalStateException("Missing " + PEOPLE_CSV + " under tests/fixtures/");
    }

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      JSONObject ingestRoot =
          JsonParquetExcelSnippets.ingestCsvViaPayload(linker, lookup, arena, fixtures, csvPath);
      int ingestedRows =
          ingestRoot.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length();

      JSONObject validationRoot =
          RdpNativeJson.invokeParityExport(linker, lookup, arena, VALIDATION);
      PytestMirrorAssertions.assertEnvelopeOk(validationRoot);
      JSONObject validationSummary =
          validationRoot.getJSONObject("interchange").getJSONObject("summary");

      JSONObject exportRoot = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
      PytestMirrorAssertions.assertEnvelopeOk(exportRoot);
      JSONArray jsonlLines =
          exportRoot.getJSONObject("interchange").getJSONArray("jsonl_preview_lines");

      System.out.println("Ingest → validate → JSONL tour: ok");
      System.out.println("  ingest: people.csv via JsonParquetExcelSnippets.ingestCsvViaPayload");
      System.out.println("  ingested row_count=" + ingestedRows);
      System.out.println(
          "  validate (rdp_parity_validation): total_checks="
              + validationSummary.getInt("total_checks")
              + " failed_checks="
              + validationSummary.getInt("failed_checks"));
      System.out.println(
          "  jsonl preview (rdp_parity_export_privacy_reports): "
              + jsonlLines.length()
              + " line(s)");
      System.out.println("  Python §9 also uses profile_dataset + export_dataset_jsonl on the same ds.");
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
