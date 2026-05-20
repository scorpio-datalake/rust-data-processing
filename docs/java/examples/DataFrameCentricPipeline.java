import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM analogue of Python {@code DataFrame.from_dataset(ds).filter_eq(...).multiply_f64(...).collect()}.
 *
 * <p>The Python {@code DataFrame} API is Polars-backed in Rust; on the JVM there is no lazy
 * wrapper — you express the same work as <strong>Polars SQL</strong> on registered table {@code df}
 * inside {@code rdp_run_pipeline_json} (ingest ordered paths → optional {@code transform.sql} →
 * sinks). Pipeline and schema JSON live under {@code tests/fixtures/jvm_contract/} (shared with Rust
 * and Python tests).
 *
 * <p>Prerequisites: a native {@code rdp_jvm_sys} built with {@code link-main} (or {@code jvm_ffi}
 * / {@code full}), {@link RdpNativeJson#resolveNativeLibraryFromEnvOrProperty()} must resolve to
 * that file, and the JVM needs {@code --enable-native-access=ALL-UNNAMED}.
 *
 * <p><strong>Tests</strong> — Rust {@code tests/dataframe_centric_pipeline_fixtures.rs}, {@code
 * jvm-sys} {@code run_pipeline_dataframe_centric_sql_committed_fixture}, Python {@code
 * test_dataframe_centric_pipeline_fixtures.py}, JUnit {@code
 * DocsExampleNativeIntegrationTest#runPipelineJsonPolarsSqlFilterAndMultiplyMatchesDocsExample}.
 */
public final class DataFrameCentricPipeline {

  private static final String BUNDLE = "jvm_contract";
  private static final String INPUT_JSON = "jvm_contract_three_rows.json";
  private static final String PIPELINE = "pipelines/dataframe_centric_sql.pipeline.json";
  private static final String SCHEMA = "schemas/three_rows.schema.json";

  private DataFrameCentricPipeline() {}

  public static Path jvmContractBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  /** {@code tests/fixtures/jvm_contract/schemas/three_rows.schema.json}. */
  public static JSONObject exampleSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(jvmContractBundle(fixturesDir), SCHEMA);
  }

  /** Committed input rows: {@code tests/fixtures/jvm_contract_three_rows.json}. */
  public static JSONArray exampleRowsJson(Path fixturesDir) throws Exception {
    return new JSONArray(
        Files.readString(fixturesDir.resolve(INPUT_JSON), StandardCharsets.UTF_8));
  }

  /** SQL from {@code tests/fixtures/jvm_contract/pipelines/dataframe_centric_sql.pipeline.json}. */
  public static String transformSql(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.pipelineTransformSql(jvmContractBundle(fixturesDir), PIPELINE);
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      Path fixtures =
          PipelineJsonFixtures.resolveTestsFixturesDir()
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "tests/fixtures not found — run from repository checkout"));
      Path bundle = jvmContractBundle(fixtures);
      Path jsonPath = fixtures.resolve(INPUT_JSON);
      Path work = Files.createTempDirectory("rdp_dataframe_centric_demo_");
      try {
        Path parquetPath = work.resolve("out.parquet");
        String payload =
            PipelineJsonFixtures.resolvePipelineJson(
                bundle,
                PIPELINE,
                Map.of(
                    "SOURCE_PATH", jsonPath.toAbsolutePath().normalize().toString(),
                    "SINK_PATH", parquetPath.toAbsolutePath().normalize().toString()));

        JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
        PytestMirrorAssertions.assertEnvelopeOk(root);
        JSONObject interchange = root.getJSONObject("interchange");
        if (!"run_pipeline_json".equals(interchange.getString("kind"))) {
          throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
        }
        JSONArray sinkResults = interchange.getJSONArray("sink_results");
        JSONObject parquetSink = sinkResults.getJSONObject(0);
        if (!"ok".equals(parquetSink.getString("status"))) {
          throw new AssertionError("sink not ok: " + parquetSink);
        }
        int rowCount = parquetSink.getInt("row_count");
        if (rowCount != 2) {
          throw new AssertionError(
              "expected row_count == 2 (Python assert out.row_count() == 2), got " + rowCount);
        }
        System.out.println("DataFrame-centric pipeline (Polars SQL via rdp_run_pipeline_json): ok");
        System.out.println("schema bundle: " + bundle.resolve(SCHEMA));
        System.out.println("transform.sql: " + transformSql(fixtures));
        System.out.println("sink parquet row_count=" + rowCount + " path=" + parquetSink.getString("path"));
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
