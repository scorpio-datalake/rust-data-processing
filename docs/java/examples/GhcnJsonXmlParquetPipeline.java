import io.github.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.json.SerdeDatasetRows;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Multi-format ETL in Rust only: GHCN station JSON → XML (intermediate schema) → Parquet (lake schema).
 * All schemas and pipeline specs are committed JSON under {@code tests/fixtures/ghcn/} (three
 * distinct serde schemas; two {@code rdp_run_pipeline_json} templates with {@code schema_ref}).
 *
 * <p>CI: {@code XmlGhcnPipelineContractTest}, {@code
 * JvmNativeContractScenarios#runGhcnJsonXmlParquetPipelineContract}, {@code
 * DocsExampleNativeIntegrationTest#ghcnJsonXmlParquetPipelineMatchesDocsExample}.
 */
public final class GhcnJsonXmlParquetPipeline {

  private static final String BUNDLE = "ghcn";
  private static final String JSON_SAMPLE = "ghcn/ghcn_stations_sample.json";

  private static final String SCHEMA_JSON_SOURCE = "schemas/json_source.schema.json";
  private static final String SCHEMA_XML_INTERMEDIATE = "schemas/xml_intermediate.schema.json";
  private static final String SCHEMA_PARQUET_LAKE = "schemas/parquet_lake.schema.json";

  private static final String PIPELINE_JSON_TO_XML = "pipelines/json_to_xml.pipeline.json";
  private static final String PIPELINE_XML_TO_PARQUET = "pipelines/xml_to_parquet.pipeline.json";

  private static final int EXPECTED_ROW_COUNT = 5;
  private static final String EXPECTED_FIRST_STATION_ID = "ACW00011604";

  private GhcnJsonXmlParquetPipeline() {}

  public static Path ghcnBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  public static Path jsonSamplePath(Path fixturesDir) {
    Path p = fixturesDir.resolve(JSON_SAMPLE);
    if (!Files.isRegularFile(p)) {
      throw new IllegalStateException("Missing " + p);
    }
    return p;
  }

  public static JSONObject jsonSourceSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(ghcnBundle(fixturesDir), SCHEMA_JSON_SOURCE);
  }

  public static JSONObject xmlIntermediateSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(ghcnBundle(fixturesDir), SCHEMA_XML_INTERMEDIATE);
  }

  public static JSONObject parquetLakeSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(ghcnBundle(fixturesDir), SCHEMA_PARQUET_LAKE);
  }

  public static String jsonToXmlTransformSql(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.pipelineTransformSql(ghcnBundle(fixturesDir), PIPELINE_JSON_TO_XML);
  }

  public static String xmlToParquetTransformSql(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.pipelineTransformSql(ghcnBundle(fixturesDir), PIPELINE_XML_TO_PARQUET);
  }

  public static String resolveJsonToXmlPipeline(
      Path fixturesDir, Path sourceJson, Path xmlSink) throws Exception {
    return PipelineJsonFixtures.resolvePipelineJson(
        ghcnBundle(fixturesDir),
        PIPELINE_JSON_TO_XML,
        Map.of(
            "SOURCE_PATH", sourceJson.toAbsolutePath().normalize().toString(),
            "SINK_PATH", xmlSink.toAbsolutePath().normalize().toString()));
  }

  public static String resolveXmlToParquetPipeline(
      Path fixturesDir, Path xmlSource, Path parquetSink) throws Exception {
    return PipelineJsonFixtures.resolvePipelineJson(
        ghcnBundle(fixturesDir),
        PIPELINE_XML_TO_PARQUET,
        Map.of(
            "SOURCE_PATH", xmlSource.toAbsolutePath().normalize().toString(),
            "SINK_PATH", parquetSink.toAbsolutePath().normalize().toString()));
  }

  public static JSONObject runJsonToXmlStage(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path sourceJson, Path xmlSink)
      throws Throwable {
    String payload = resolveJsonToXmlPipeline(fixturesDir, sourceJson, xmlSink);
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertSinkOk(root, "xml_file", EXPECTED_ROW_COUNT);
    if (!Files.isRegularFile(xmlSink)) {
      throw new IllegalStateException("XML sink missing: " + xmlSink);
    }
    return root;
  }

  public static JSONObject verifyXmlWithSchema(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path xmlPath)
      throws Throwable {
    String schema = PipelineJsonFixtures.loadSchemaJson(ghcnBundle(fixturesDir), SCHEMA_XML_INTERMEDIATE);
    JSONObject root =
        RdpNativeJson.invokeIngestXmlPath(
            linker,
            lookup,
            arena,
            xmlPath.toString(),
            schema,
            PipelineJsonFixtures.defaultPathIngestOptionsJson()));
    PytestMirrorAssertions.assertEnvelopeOk(root);
    int rows =
        root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length();
    if (rows != EXPECTED_ROW_COUNT) {
      throw new AssertionError("expected " + EXPECTED_ROW_COUNT + " XML rows, got " + rows);
    }
    return root;
  }

  public static JSONObject runXmlToParquetStage(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path xmlSource, Path parquetSink)
      throws Throwable {
    String payload = resolveXmlToParquetPipeline(fixturesDir, xmlSource, parquetSink);
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertSinkOk(root, "parquet_file", EXPECTED_ROW_COUNT);
    return root;
  }

  public static JSONArray verifyParquetWithSchema(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path parquetPath)
      throws Throwable {
    String schema = PipelineJsonFixtures.loadSchemaJson(ghcnBundle(fixturesDir), SCHEMA_PARQUET_LAKE);
    JSONObject root =
        RdpNativeJson.invokeIngestParquetPath(
            linker,
            lookup,
            arena,
            parquetPath.toString(),
            schema,
            PipelineJsonFixtures.defaultPathIngestOptionsJson()));
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONArray rows =
        root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows");
    if (rows.length() != EXPECTED_ROW_COUNT) {
      throw new AssertionError("expected " + EXPECTED_ROW_COUNT + " parquet rows, got " + rows.length());
    }
    return rows;
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
      Path bundle = ghcnBundle(fixtures);
      Path jsonInput = jsonSamplePath(fixtures);

      Path work = Files.createTempDirectory("rdp_ghcn_json_xml_parquet_demo_");
      try {
        Path xmlPath = work.resolve("stations.xml");
        Path parquetPath = work.resolve("stations.parquet");

        runJsonToXmlStage(linker, lookup, arena, fixtures, jsonInput, xmlPath);
        verifyXmlWithSchema(linker, lookup, arena, fixtures, xmlPath);
        runXmlToParquetStage(linker, lookup, arena, fixtures, xmlPath, parquetPath);
        JSONArray rows = verifyParquetWithSchema(linker, lookup, arena, fixtures, parquetPath);

        JSONArray first = rows.getJSONArray(0);
        if (!EXPECTED_FIRST_STATION_ID.equals(SerdeDatasetRows.utf8(first, 0))) {
          throw new AssertionError(
              "unexpected first station_id: " + SerdeDatasetRows.utf8(first, 0));
        }

        System.out.println("GHCN JSON → XML → Parquet pipeline: ok");
        System.out.println("  bundle: " + bundle);
        System.out.println("  schemas: " + SCHEMA_JSON_SOURCE + ", " + SCHEMA_XML_INTERMEDIATE + ", " + SCHEMA_PARQUET_LAKE);
        System.out.println("  pipelines: " + PIPELINE_JSON_TO_XML + ", " + PIPELINE_XML_TO_PARQUET);
        System.out.println("  json_to_xml sql: " + jsonToXmlTransformSql(fixtures));
        System.out.println("  xml_to_parquet sql: " + xmlToParquetTransformSql(fixtures));
        System.out.println("  lake row[0] station_id=" + SerdeDatasetRows.utf8(first, 0));
      } finally {
        try (var walk = Files.walk(work)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      }
    }
  }

  private static void assertSinkOk(JSONObject pipelineRoot, String expectedKind, int expectedRows) {
    JSONObject sink =
        pipelineRoot
            .getJSONObject("interchange")
            .getJSONArray("sink_results")
            .getJSONObject(0);
    if (!expectedKind.equals(sink.getString("kind"))) {
      throw new AssertionError("expected sink kind " + expectedKind + ", got " + sink.getString("kind"));
    }
    if (!"ok".equals(sink.getString("status"))) {
      throw new AssertionError("sink not ok: " + sink);
    }
    if (sink.getInt("row_count") != expectedRows) {
      throw new AssertionError(
          "expected row_count " + expectedRows + ", got " + sink.getInt("row_count"));
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
