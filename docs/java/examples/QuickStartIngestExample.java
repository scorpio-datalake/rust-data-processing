import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Minimal “hello ingest” — one local CSV path and an explicit schema (Python quick start).
 *
 * <p>Python analogue: {@code ingest_from_path("people.csv", schema, {"format": "csv"})}. JVM uses
 * {@code rdp_ingest_csv_path} with schema JSON from {@code tests/fixtures/people/schemas/people_csv.schema.json}.
 */
public final class QuickStartIngestExample {

  private static final String BUNDLE = "people";
  private static final String PEOPLE_CSV = "people.csv";
  private static final String SCHEMA_CSV = "schemas/people_csv.schema.json";
  private static final String OPTIONS = "payloads/csv_path_ingest.options.json";

  private QuickStartIngestExample() {}

  public static JSONObject ingestPeopleCsv(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir) throws Exception {
    Path csv =
        fixturesDir
            .resolve(PEOPLE_CSV)
            .toAbsolutePath()
            .normalize();
    Path bundle =
        PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
            .orElseThrow(() -> new IllegalStateException("tests/fixtures/" + BUNDLE + " missing"));
    String schema = PipelineJsonFixtures.loadSchemaJson(bundle, SCHEMA_CSV);
    String options = PipelineJsonFixtures.readUtf8(bundle, OPTIONS);
    JSONObject root =
        RdpNativeJson.invokeIngestCsvPath(linker, lookup, arena, csv.toString(), schema, options);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root.getJSONObject("interchange");
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      Path fixtures =
          PipelineJsonFixtures.resolveTestsFixturesDir()
              .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
      JSONObject inter = ingestPeopleCsv(linker, lookup, arena, fixtures);
      int rows = inter.getJSONObject("dataset").getJSONArray("rows").length();
      System.out.println("Quick start ingest (people.csv): " + rows + " rows");
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library");
      System.exit(2);
    }
    demonstrate(lib);
  }
}
