import io.github.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
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
 * Rust-only student ETL: JVM supplies <strong>JSON schemas</strong> and <strong>pipeline / payload</strong>
 * fixtures from {@code tests/fixtures/student_etl/}; execution and sinks stay in {@code rdp_jvm_sys}.
 *
 * <p>Legacy control plane: {@code pipelines/legacy_student_etl.pipeline.json} (conceptual {@code s3://}
 * path) and {@code pipelines/legacy_student_etl_three_paths.pipeline.json} (local demo). Ordered ingest:
 * {@code payloads/ordered_ingest_dataset*.payload.json} with {@code schema_ref}.
 *
 * <p>Cross-language tests: {@code tests/student_etl_fixtures.rs}, {@code
 * python-wrapper/tests/test_student_etl_fixtures.py}, {@code bindings/jvm-sys} {@code
 * pipeline_run} tests, {@code DocsExampleNativeIntegrationTest}.
 */
public final class RDPOnlyETLExample {

  private static final String BUNDLE = "student_etl";

  private static final String SCHEMA_STUDENT = "schemas/student_source.schema.json";
  private static final String SCHEMA_LAKE = "schemas/lake_grade_stats.schema.json";
  private static final String SCHEMA_POSTGRES = "schemas/postgres_courses.schema.json";

  private static final String PIPELINE_LEGACY_S3 = "pipelines/legacy_student_etl.pipeline.json";
  private static final String PIPELINE_LEGACY_LOCAL = "pipelines/legacy_student_etl_three_paths.pipeline.json";

  private static final String PAYLOAD_ORDERED_3 = "payloads/ordered_ingest_dataset.payload.json";
  private static final String PAYLOAD_ORDERED_2 = "payloads/ordered_ingest_dataset_2paths.payload.json";

  private static final String DATA_PART_0 = "data/part-00000.json";
  private static final String DATA_PART_1 = "data/part-00001.json";
  private static final String DATA_PART_2 = "data/part-00002.json";
  private static final String DATA_S3_PATHS = "data/example_s3_json_source_paths.json";

  private RDPOnlyETLExample() {}

  public static Path studentEtlBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  public static JSONObject schemaStudentJsonSource(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(studentEtlBundle(fixturesDir), SCHEMA_STUDENT);
  }

  public static JSONObject schemaLakeStudentGradeStats(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(studentEtlBundle(fixturesDir), SCHEMA_LAKE);
  }

  public static JSONObject schemaPostgresCoursesTeachers(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(studentEtlBundle(fixturesDir), SCHEMA_POSTGRES);
  }

  /** {@code data/example_s3_json_source_paths.json} — production sketch only. */
  public static JSONArray exampleJsonSourcePaths(Path fixturesDir) throws Exception {
    Path file = studentEtlBundle(fixturesDir).resolve(DATA_S3_PATHS);
    return new JSONArray(Files.readString(file, StandardCharsets.UTF_8));
  }

  /**
   * Conceptual legacy spec with a single {@code s3://} placeholder ({@link #PIPELINE_LEGACY_S3}).
   */
  public static JSONObject conceptualPipelineSpec(Path fixturesDir) throws Exception {
    JSONArray s3 = exampleJsonSourcePaths(fixturesDir);
    return new JSONObject(
        PipelineJsonFixtures.resolvePipelineJson(
            studentEtlBundle(fixturesDir),
            PIPELINE_LEGACY_S3,
            Map.of("SOURCE_PATH", s3.getString(0))));
  }

  /**
   * Live demo pipeline: three local JSON parts under {@code student_etl/data/} bound as {@code PATH_A..C}.
   */
  public static String resolveLiveLegacyPipelineJson(
      Path fixturesDir, Path pathA, Path pathB, Path pathC) throws Exception {
    return PipelineJsonFixtures.resolvePipelineJson(
        studentEtlBundle(fixturesDir),
        PIPELINE_LEGACY_LOCAL,
        Map.of(
            "PATH_A", pathA.toAbsolutePath().normalize().toString(),
            "PATH_B", pathB.toAbsolutePath().normalize().toString(),
            "PATH_C", pathC.toAbsolutePath().normalize().toString()));
  }

  public static Path committedStudentPart(Path fixturesDir, String dataRel) {
    Path p = studentEtlBundle(fixturesDir).resolve(dataRel);
    if (!Files.isRegularFile(p)) {
      throw new IllegalStateException("Missing student fixture: " + p);
    }
    return p;
  }

  public static String resolveOrderedIngestPayloadJson(Path fixturesDir, JSONArray absolutePaths)
      throws Exception {
    String payloadRel =
        absolutePaths.length() >= 3 ? PAYLOAD_ORDERED_3 : PAYLOAD_ORDERED_2;
    java.util.Map<String, String> bindings = new java.util.HashMap<>();
    for (int i = 0; i < absolutePaths.length(); i++) {
      bindings.put("PATH_" + (char) ('A' + i), absolutePaths.getString(i));
    }
    return PipelineJsonFixtures.resolvePayloadJson(
        studentEtlBundle(fixturesDir), payloadRel, bindings);
  }

  public static JSONObject runLiveLegacyPipeline(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir) throws Throwable {
    Path p0 = committedStudentPart(fixturesDir, DATA_PART_0);
    Path p1 = committedStudentPart(fixturesDir, DATA_PART_1);
    Path p2 = committedStudentPart(fixturesDir, DATA_PART_2);
    String pipeline = resolveLiveLegacyPipelineJson(fixturesDir, p0, p1, p2);
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root;
  }

  public static JSONObject runOrderedIngestDataset(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      Path fixturesDir,
      Path pathA,
      Path pathB)
      throws Throwable {
    JSONArray paths =
        new JSONArray()
            .put(pathA.toAbsolutePath().normalize().toString())
            .put(pathB.toAbsolutePath().normalize().toString());
    String payload = resolveOrderedIngestPayloadJson(fixturesDir, paths);
    JSONObject root =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root;
  }

  public static void demonstrateSchemas(Path nativeLibrary) throws Throwable {
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
      Path bundle = studentEtlBundle(fixtures);

      System.out.println("=== Schemas (tests/fixtures/student_etl/schemas/) ===");
      System.out.println(schemaStudentJsonSource(fixtures).toString(2));
      System.out.println(schemaLakeStudentGradeStats(fixtures).toString(2));
      System.out.println(schemaPostgresCoursesTeachers(fixtures).toString(2));

      System.out.println("=== Conceptual legacy pipeline (S3 sketch) ===");
      System.out.println(conceptualPipelineSpec(fixtures).toString(2));

      System.out.println("=== Live rdp_run_pipeline_json (three_paths pipeline + data/*.json) ===");
      JSONObject pipelineRoot = runLiveLegacyPipeline(linker, lookup, arena, fixtures);
      System.out.println(pipelineRoot.toString(2));
      JSONObject pInter = pipelineRoot.getJSONObject("interchange");
      System.out.println("Ingested rows: " + pInter.getInt("ingested_row_count"));
      System.out.println("Sink phases: " + pInter.getJSONArray("sink_results").toString(2));

      Path p0 = committedStudentPart(fixtures, DATA_PART_0);
      Path p1 = committedStudentPart(fixtures, DATA_PART_1);
      System.out.println("=== Ordered ingest payload (2 paths, schema_ref) ===");
      JSONObject ordered =
          runOrderedIngestDataset(linker, lookup, arena, fixtures, p0, p1);
      System.out.println(ordered.getJSONObject("interchange").toString(2));

      System.out.println("student_etl bundle: " + bundle);
      System.out.println("pipelines: " + PIPELINE_LEGACY_S3 + ", " + PIPELINE_LEGACY_LOCAL);
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
      demonstrateSchemas(lib);
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
