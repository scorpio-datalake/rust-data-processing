import io.github.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.rust_data_processing.ffi.RdpNativeJson;
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
 * JSON, CSV, and Parquet ingest from Java using committed fixture schemas, payloads, and pipelines
 * under {@code tests/fixtures/people/} — aligned with Python {@code ingest_from_path}:
 *
 * <pre>
 *   ds_json = rdp.ingest_from_path("people.json", schema_json, {"format": "json"})
 *   ds_csv  = rdp.ingest_from_path("people.csv", schema_csv, {"format": "csv"})
 *   ds_pq   = rdp.ingest_from_path("out.parquet", schema_flat, {"format": "parquet"})
 * </pre>
 *
 * <p><strong>JSON / CSV</strong> — {@link #ingestJsonViaPayload} / {@link #ingestCsvViaPayload} use
 * {@code payloads/*_path_dataset.payload.json} → {@code rdp_ingest_ordered_paths_json}. Alternate
 * path FFI: {@link #ingestJsonViaPath} / {@link #ingestCsvViaPath} with {@code schemas/*.schema.json}
 * ; path verify uses {@link PipelineJsonFixtures#defaultPathIngestOptionsJson()} when options are empty.
 *
 * <p><strong>Parquet</strong> — {@link #csvToParquetViaPipeline} runs {@code
 * pipelines/csv_to_parquet.pipeline.json}, then {@link #ingestParquetViaPath} verifies with {@code
 * people_flat.schema.json}.
 *
 * <p>Excel: {@code ExcelSnippets}. Temp Parquet export: {@code ParquetSnippets}. Optional parity
 * smoke: {@link #parityCsvDataset}.
 */
public final class JsonParquetExcelSnippets {

  private static final String BUNDLE = "people";
  private static final String PEOPLE_JSON = "people.json";
  private static final String PEOPLE_CSV = "people.csv";

  private static final String SCHEMA_JSON = "schemas/people_json.schema.json";
  private static final String SCHEMA_CSV = "schemas/people_csv.schema.json";
  private static final String SCHEMA_FLAT = "schemas/people_flat.schema.json";

  private static final String PAYLOAD_JSON_DATASET = "payloads/json_path_dataset.payload.json";
  private static final String PAYLOAD_CSV_DATASET = "payloads/csv_path_dataset.payload.json";
  private static final String OPTIONS_JSON = "payloads/json_path_ingest.options.json";
  private static final String OPTIONS_CSV = "payloads/csv_path_ingest.options.json";
  private static final String PIPELINE_CSV_TO_PARQUET = "pipelines/csv_to_parquet.pipeline.json";

  private static final String RDP_PARITY_INGESTION = "rdp_parity_ingestion";

  private JsonParquetExcelSnippets() {}

  public static Path peopleBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(() -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  public static JSONObject schemaJson(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(peopleBundle(fixturesDir), SCHEMA_JSON);
  }

  public static JSONObject schemaCsv(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(peopleBundle(fixturesDir), SCHEMA_CSV);
  }

  public static JSONObject schemaFlat(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(peopleBundle(fixturesDir), SCHEMA_FLAT);
  }

  public static JSONArray schemaJsonFields(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaFieldsArray(peopleBundle(fixturesDir), SCHEMA_JSON);
  }

  public static JSONArray schemaFlatFields(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaFieldsArray(peopleBundle(fixturesDir), SCHEMA_FLAT);
  }

  /** {@code payloads/json_path_ingest.options.json}. */
  public static String jsonIngestOptionsJson(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.readUtf8(peopleBundle(fixturesDir), OPTIONS_JSON);
  }

  /** {@code payloads/csv_path_ingest.options.json}. */
  public static String csvIngestOptionsJson(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.readUtf8(peopleBundle(fixturesDir), OPTIONS_CSV);
  }

  public static String pathIngestOptionsJson(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.readUtf8(peopleBundle(fixturesDir), OPTIONS_PATH);
  }

  public static String resolveJsonDatasetPayload(Path fixturesDir, Path jsonPath) throws Exception {
    return PipelineJsonFixtures.resolvePayloadJson(
        peopleBundle(fixturesDir),
        PAYLOAD_JSON_DATASET,
        Map.of("SOURCE_PATH", jsonPath.toAbsolutePath().normalize().toString()));
  }

  public static String resolveCsvDatasetPayload(Path fixturesDir, Path csvPath) throws Exception {
    return PipelineJsonFixtures.resolvePayloadJson(
        peopleBundle(fixturesDir),
        PAYLOAD_CSV_DATASET,
        Map.of("SOURCE_PATH", csvPath.toAbsolutePath().normalize().toString()));
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

  public static JSONObject ingestJsonViaPayload(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path jsonPath)
      throws Throwable {
    String payload = resolveJsonDatasetPayload(fixturesDir, jsonPath);
    JSONObject root =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertDatasetRows(root, 2);
    return root.getJSONObject("interchange").getJSONObject("dataset");
  }

  public static JSONObject ingestCsvViaPayload(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path csvPath)
      throws Throwable {
    String payload = resolveCsvDatasetPayload(fixturesDir, csvPath);
    JSONObject root =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertDatasetRows(root, 2);
    return root.getJSONObject("interchange").getJSONObject("dataset");
  }

  public static JSONObject ingestJsonViaPath(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path jsonPath)
      throws Throwable {
    String schema = PipelineJsonFixtures.loadSchemaJson(peopleBundle(fixturesDir), SCHEMA_JSON);
    JSONObject root =
        RdpNativeJson.invokeIngestJsonPath(
            linker,
            lookup,
            arena,
            jsonPath.toString(),
            schema,
            jsonIngestOptionsJson(fixturesDir));
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertDatasetRows(root, 2);
    return root.getJSONObject("interchange").getJSONObject("dataset");
  }

  public static JSONObject ingestCsvViaPath(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path csvPath)
      throws Throwable {
    String schema = PipelineJsonFixtures.loadSchemaJson(peopleBundle(fixturesDir), SCHEMA_CSV);
    JSONObject root =
        RdpNativeJson.invokeIngestCsvPath(
            linker,
            lookup,
            arena,
            csvPath.toString(),
            schema,
            csvIngestOptionsJson(fixturesDir));
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertDatasetRows(root, 2);
    return root.getJSONObject("interchange").getJSONObject("dataset");
  }

  public static Path csvToParquetViaPipeline(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, Path csvPath, Path parquetSink)
      throws Throwable {
    String pipeline = resolveCsvToParquetPipeline(fixturesDir, csvPath, parquetSink);
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject sink = root.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
    if (!"ok".equals(sink.getString("status"))) {
      throw new AssertionError("parquet sink not ok: " + sink);
    }
    if (sink.getInt("row_count") != 2) {
      throw new AssertionError("expected 2 rows in parquet sink, got " + sink.getInt("row_count"));
    }
    if (!Files.isRegularFile(parquetSink)) {
      throw new IllegalStateException("parquet sink missing: " + parquetSink);
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
    assertDatasetRows(root, 2);
    return root.getJSONObject("interchange").getJSONObject("dataset");
  }

  /** Parity export smoke ({@code rdp_parity_ingestion}) — not path-parameterized. */
  public static JSONObject parityCsvDataset(Linker linker, SymbolLookup lookup, Arena arena)
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

  private static void assertDatasetRows(JSONObject root, int expected) {
    int rows =
        root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length();
    if (rows != expected) {
      throw new AssertionError("expected " + expected + " rows, got " + rows);
    }
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Path fixtures =
        PipelineJsonFixtures.resolveTestsFixturesDir()
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "tests/fixtures not found — run from repository checkout"));
    Path jsonPath = fixtures.resolve(PEOPLE_JSON);
    Path csvPath = fixtures.resolve(PEOPLE_CSV);
    if (!Files.isRegularFile(jsonPath) || !Files.isRegularFile(csvPath)) {
      throw new IllegalStateException("Missing people.json or people.csv under tests/fixtures/");
    }

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      ingestJsonViaPayload(linker, lookup, arena, fixtures, jsonPath);
      ingestCsvViaPayload(linker, lookup, arena, fixtures, csvPath);

      Path work = Files.createTempDirectory("rdp_json_parquet_demo_");
      try {
        Path parquetPath = work.resolve("people.parquet");
        csvToParquetViaPipeline(linker, lookup, arena, fixtures, csvPath, parquetPath);
        ingestParquetViaPath(linker, lookup, arena, fixtures, parquetPath);
        System.out.println("JSON / CSV / Parquet (fixture schemas + payloads + pipeline): ok");
        System.out.println("  json payload: " + peopleBundle(fixtures).resolve(PAYLOAD_JSON_DATASET));
        System.out.println("  csv payload: " + peopleBundle(fixtures).resolve(PAYLOAD_CSV_DATASET));
        System.out.println("  pipeline: " + peopleBundle(fixtures).resolve(PIPELINE_CSV_TO_PARQUET));
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
