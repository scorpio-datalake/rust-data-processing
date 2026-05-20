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
 * JVM SQL examples using committed JSON under {@code tests/fixtures/}.
 *
 * <p><strong>Single-table</strong> — {@code jvm_contract/pipelines/sql_query_dataset.pipeline.json}
 * with {@code schema_ref} → {@code three_rows.schema.json} and input {@code data/three_rows.json};
 * executed via {@code rdp_run_pipeline_json}.
 *
 * <p><strong>JOIN</strong> — SQL text and table schemas/data from {@code sql_parity/}; Rust runs the
 * suite via {@code rdp_parity_sql_suite_mirror} until multi-source pipeline JSON exists on the JVM.
 *
 * <p>Cross-language tests: {@code tests/sql.rs}, {@code python-wrapper/tests/test_sql_queries_fixtures.py},
 * {@code DocsExampleNativeIntegrationTest}.
 */
public final class SQLQueries {

  private static final String JVM_BUNDLE = "jvm_contract";
  private static final String SQL_BUNDLE = "sql_parity";

  private static final String INPUT_JSON = "data/three_rows.json";
  private static final String PIPELINE_SINGLE = "pipelines/sql_query_dataset.pipeline.json";
  private static final String SCHEMA_THREE_ROWS = "schemas/three_rows.schema.json";

  private static final String JOIN_SQL = "queries/join_people_scores.sql.json";
  private static final String SCHEMA_JOIN_LEFT = "schemas/join_left.schema.json";
  private static final String SCHEMA_JOIN_RIGHT = "schemas/join_right.schema.json";
  private static final String DATA_JOIN_LEFT = "data/join_left.json";
  private static final String DATA_JOIN_RIGHT = "data/join_right.json";

  private static final String PARITY_SQL_SUITE = "rdp_parity_sql_suite_mirror";

  private SQLQueries() {}

  public static Path jvmContractBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, JVM_BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + JVM_BUNDLE + " not found"));
  }

  public static Path sqlParityBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, SQL_BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + SQL_BUNDLE + " not found"));
  }

  public static JSONObject exampleSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(jvmContractBundle(fixturesDir), SCHEMA_THREE_ROWS);
  }

  public static JSONArray exampleRowsJson(Path fixturesDir) throws Exception {
    Path file = jvmContractBundle(fixturesDir).resolve(INPUT_JSON);
    return new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
  }

  public static String singleTableSqlOnDf(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.pipelineTransformSql(
        jvmContractBundle(fixturesDir), PIPELINE_SINGLE);
  }

  public static String resolveSingleTablePipeline(
      Path fixturesDir, Path sourceJson, Path parquetSink) throws Exception {
    return PipelineJsonFixtures.resolvePipelineJson(
        jvmContractBundle(fixturesDir),
        PIPELINE_SINGLE,
        Map.of(
            "SOURCE_PATH", sourceJson.toAbsolutePath().normalize().toString(),
            "SINK_PATH", parquetSink.toAbsolutePath().normalize().toString()));
  }

  public static JSONObject joinLeftSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(sqlParityBundle(fixturesDir), SCHEMA_JOIN_LEFT);
  }

  public static JSONObject joinRightSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(sqlParityBundle(fixturesDir), SCHEMA_JOIN_RIGHT);
  }

  public static String joinSql(Path fixturesDir) throws Exception {
    return new JSONObject(
            PipelineJsonFixtures.readUtf8(sqlParityBundle(fixturesDir), JOIN_SQL))
        .getString("sql");
  }

  public static JSONArray joinLeftRows(Path fixturesDir) throws Exception {
    return new JSONArray(
        Files.readString(
            sqlParityBundle(fixturesDir).resolve(DATA_JOIN_LEFT), StandardCharsets.UTF_8));
  }

  public static JSONArray joinRightRows(Path fixturesDir) throws Exception {
    return new JSONArray(
        Files.readString(
            sqlParityBundle(fixturesDir).resolve(DATA_JOIN_RIGHT), StandardCharsets.UTF_8));
  }

  public static JSONObject runSingleTableSqlPipeline(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir) throws Throwable {
    Path jsonPath = jvmContractBundle(fixturesDir).resolve(INPUT_JSON);
    if (!Files.isRegularFile(jsonPath)) {
      throw new IllegalStateException("Missing " + jsonPath);
    }
    Path work = Files.createTempDirectory("rdp_sql_queries_single_");
    try {
      Path parquetPath = work.resolve("out.parquet");
      String pipeline = resolveSingleTablePipeline(fixturesDir, jsonPath, parquetPath);
      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject sink = root.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
      if (!"ok".equals(sink.getString("status"))) {
        throw new AssertionError("sink not ok: " + sink);
      }
      if (sink.getInt("row_count") != 2) {
        throw new AssertionError("expected 2 rows (active=TRUE), got " + sink.getInt("row_count"));
      }
      return root;
    } finally {
      try (var walk = Files.walk(work)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  /**
   * JOIN: fixture SQL + schemas + data from {@code sql_parity/}; execution via parity mirror (same
   * Rust {@code SqlContext} checks as Python {@code test_sql_parity.py}).
   */
  public static JSONObject runJoinSketchViaParity(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir) throws Throwable {
    String sql = joinSql(fixturesDir);
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, PARITY_SQL_SUITE);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertSqlSuiteMirror(root.getJSONObject("interchange"));
    System.out.println("JOIN SQL (from fixture): " + sql.trim().replace('\n', ' '));
    return root;
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

      runSingleTableSqlPipeline(linker, lookup, arena, fixtures);
      System.out.println("single-table SQL pipeline: ok");
      System.out.println("  pipeline: " + jvmContractBundle(fixtures).resolve(PIPELINE_SINGLE));
      System.out.println("  transform.sql: " + singleTableSqlOnDf(fixtures));

      runJoinSketchViaParity(linker, lookup, arena, fixtures);
      System.out.println("JOIN sketch: ok (parity executes; fixtures define SQL + schemas)");
      System.out.println("  join SQL file: " + sqlParityBundle(fixtures).resolve(JOIN_SQL));
      System.out.println(
          "  left schema: " + sqlParityBundle(fixtures).resolve(SCHEMA_JOIN_LEFT));
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
