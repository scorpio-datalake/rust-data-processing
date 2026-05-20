import io.github.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.integration.RdpParquetTemp;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.json.JSONObject;

/**
 * Parquet on the JVM: write with a JSON pipeline, read back with a JSON schema (no huge row arrays over FFI).
 *
 * <p><strong>Pipeline path (recommended)</strong> — {@code tests/fixtures/people/pipelines/csv_to_parquet.pipeline.json}
 * ingests {@code people.csv} with {@code schema_ref} → {@code people_csv.schema.json}, sinks {@code parquet_file}.
 * Verify with {@link #ingestParquetViaPath} and {@code schemas/people_flat.schema.json} (same column
 * layout as the CSV; default path-ingest options via {@link PipelineJsonFixtures#defaultPathIngestOptionsJson()}).
 *
 * <p><strong>Temp export FFI</strong> — {@link #exportParquetTempEnvelope} ({@code rdp_export_parquet_temp}) writes a
 * small Rust-built sample to the OS temp dir for Spark {@code local[*]} handoff ({@link RdpParquetTemp}); no pipeline
 * JSON on that symbol.
 *
 * <p><strong>Tests</strong> — Rust {@code tests/parquet_snippets_fixtures.rs}, {@code jvm-sys}
 * {@code run_pipeline_people_csv_to_parquet_committed_fixture}, Python {@code test_parquet_snippets_fixtures.py}, JUnit
 * {@code DocsExampleNativeIntegrationTest#parquetSnippetsCsvToParquetRoundTripMatchesDocsExample}.
 */
public final class ParquetSnippets {

  private static final String BUNDLE = "people";
  private static final String PEOPLE_CSV = "people.csv";

  private static final String SCHEMA_CSV = "schemas/people_csv.schema.json";
  private static final String SCHEMA_FLAT = "schemas/people_flat.schema.json";
  private static final String PIPELINE_CSV_TO_PARQUET = "pipelines/csv_to_parquet.pipeline.json";
  private ParquetSnippets() {}

  public static Path peopleBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(() -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  public static JSONObject schemaCsv(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(peopleBundle(fixturesDir), SCHEMA_CSV);
  }

  public static JSONObject schemaFlat(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(peopleBundle(fixturesDir), SCHEMA_FLAT);
  }

  public static String resolveCsvToParquetPipeline(
      Path fixturesDir, Path csvPath, Path parquetSink) throws Exception {
    return PipelineJsonFixtures.resolvePipelineJson(
        peopleBundle(fixturesDir),
        PIPELINE_CSV_TO_PARQUET,
        Map.of(
            "SOURCE_PATH", csvPath.toAbsolutePath().normalize().toString(),
            "SINK_PATH", parquetSink.toAbsolutePath().normalize().toString()));
  }

  public static Path writeParquetViaPipeline(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path csvPath, Path parquetSink)
      throws Throwable {
    String pipeline = resolveCsvToParquetPipeline(fixturesDir, csvPath, parquetSink);
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject sink = root.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
    if (!"parquet_file".equals(sink.getString("kind"))) {
      throw new AssertionError("expected parquet_file sink, got " + sink.getString("kind"));
    }
    if (!"ok".equals(sink.getString("status"))) {
      throw new AssertionError("sink not ok: " + sink);
    }
    if (sink.getInt("row_count") != 2) {
      throw new AssertionError("expected 2 rows in parquet sink, got " + sink.getInt("row_count"));
    }
    if (!Files.isRegularFile(parquetSink)) {
      throw new IllegalStateException("parquet file missing: " + parquetSink);
    }
    return parquetSink;
  }

  public static JSONObject ingestParquetViaPath(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path parquetPath)
      throws Throwable {
    String schema = PipelineJsonFixtures.loadSchemaJson(peopleBundle(fixturesDir), SCHEMA_FLAT);
    JSONObject root =
        RdpNativeJson.invokeIngestParquetPath(
            linker,
            lookup,
            arena,
            parquetPath.toString(),
            schema,
            PipelineJsonFixtures.defaultPathIngestOptionsJson()));
    PytestMirrorAssertions.assertEnvelopeOk(root);
    if (!"ingest_path_parquet".equals(root.getJSONObject("interchange").getString("kind"))) {
      throw new IllegalStateException(
          "unexpected kind: " + root.getJSONObject("interchange").getString("kind"));
    }
    int rows =
        root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length();
    if (rows != 2) {
      throw new AssertionError("expected 2 parquet rows, got " + rows);
    }
    return root.getJSONObject("interchange").getJSONObject("dataset");
  }

  /** {@code rdp_export_parquet_temp} — compact path handoff (Rust sample, not the people pipeline). */
  public static JSONObject exportParquetTempEnvelope(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"parquet_export_temp".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return root;
  }

  public static String parquetPathFromEnvelope(JSONObject root) {
    return RdpParquetTemp.parquetPath(root);
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Path fixtures =
        PipelineJsonFixtures.resolveTestsFixturesDir()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tests/fixtures not found — run from repository checkout"));
    Path csvPath = fixtures.resolve(PEOPLE_CSV);
    if (!Files.isRegularFile(csvPath)) {
      throw new IllegalStateException("Missing tests/fixtures/" + PEOPLE_CSV);
    }

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      Path work = Files.createTempDirectory("rdp_parquet_snippets_demo_");
      try {
        Path parquetPath = work.resolve("people.parquet");
        writeParquetViaPipeline(linker, lookup, arena, fixtures, csvPath, parquetPath);
        ingestParquetViaPath(linker, lookup, arena, fixtures, parquetPath);

        JSONObject tempExport = exportParquetTempEnvelope(linker, lookup, arena);
        String tempPath = parquetPathFromEnvelope(tempExport);
        RdpParquetTemp.deleteQuietly(tempPath);

        System.out.println("Parquet pipeline + schema ingest: ok");
        System.out.println("  pipeline: " + peopleBundle(fixtures).resolve(PIPELINE_CSV_TO_PARQUET));
        System.out.println("  ingest schema: " + peopleBundle(fixtures).resolve(SCHEMA_FLAT));
        System.out.println("  written: " + parquetPath);
      } finally {
        try (var walk = Files.walk(work)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      }
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
