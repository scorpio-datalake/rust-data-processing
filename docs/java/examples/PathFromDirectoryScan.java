import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM equivalent of Python {@code paths_from_directory_scan} + {@code ingest_from_ordered_paths}
 * with watermark options. Schema, options, and response come from {@code tests/fixtures/watermark/}
 * JSON; Java only scans directories and supplies the {@code paths} array.
 *
 * <p>Cross-language tests: {@code tests/path_from_directory_scan_fixtures.rs}, {@code
 * python-wrapper/tests/test_path_from_directory_scan_fixtures.py}, {@code
 * tests/ordered_batch_ingestion.rs}, {@code DocsExampleNativeIntegrationTest}.
 */
public final class PathFromDirectoryScan {

  private static final String BUNDLE = "watermark";
  private static final String SCHEMA_EVENTS = "schemas/events.schema.json";
  private static final String PAYLOAD_BODY = "payloads/csv_watermark_ingest.body.json";
  private static final String PAYLOAD_TWO_CSV = "payloads/directory_scan_two_csv.payload.json";

  private PathFromDirectoryScan() {}

  public static Path watermarkBundle(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  public static List<Path> pathsFromDirectoryScan(Path root, String relativeGlob) throws Exception {
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("directory scan root must be an existing directory: " + root);
    }
    FileSystem fs = root.getFileSystem();
    PathMatcher matcher = fs.getPathMatcher("glob:" + relativeGlob);
    List<Path> out = new ArrayList<>();
    try (var stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile).forEach(p -> {
        Path rel = root.relativize(p);
        if (matcher.matches(rel)) {
          out.add(p);
        }
      });
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  /** {@code tests/fixtures/watermark/schemas/events.schema.json}. */
  public static JSONObject exampleEventSchema(Path fixturesDir) throws Exception {
    return PipelineJsonFixtures.loadSchemaObject(watermarkBundle(fixturesDir), SCHEMA_EVENTS);
  }

  /**
   * Build {@code rdp_ingest_ordered_paths_json} payload: expand {@link #PAYLOAD_BODY} (schema_ref +
   * options + response), then attach scanned {@code paths}.
   */
  public static String resolveWatermarkIngestPayload(Path fixturesDir, JSONArray absolutePaths)
      throws Exception {
    JSONObject body =
        new JSONObject(
            PipelineJsonFixtures.resolvePayloadJson(
                watermarkBundle(fixturesDir), PAYLOAD_BODY, Map.of()));
    body.put("paths", absolutePaths);
    return body.toString();
  }

  /**
   * Fixed two-path template ({@link #PAYLOAD_TWO_CSV}) when the demo creates exactly {@code a.csv}
   * and {@code nested/b.csv}.
   */
  public static String resolveTwoCsvWatermarkPayload(
      Path fixturesDir, Path pathA, Path pathB) throws Exception {
    return PipelineJsonFixtures.resolvePayloadJson(
        watermarkBundle(fixturesDir),
        PAYLOAD_TWO_CSV,
        Map.of(
            "PATH_A", pathA.toAbsolutePath().normalize().toString(),
            "PATH_B", pathB.toAbsolutePath().normalize().toString()));
  }

  public static Long maxInt64InColumn(JSONObject dataset, String column) throws Exception {
    JSONArray fieldDefs = dataset.getJSONObject("schema").getJSONArray("fields");
    int col = -1;
    for (int i = 0; i < fieldDefs.length(); i++) {
      if (column.equals(fieldDefs.getJSONObject(i).getString("name"))) {
        col = i;
        break;
      }
    }
    if (col < 0) {
      throw new IllegalArgumentException("column not in schema: " + column);
    }
    JSONArray rows = dataset.getJSONArray("rows");
    long max = Long.MIN_VALUE;
    for (int r = 0; r < rows.length(); r++) {
      JSONArray row = rows.getJSONArray(r);
      JSONObject cell = row.getJSONObject(col);
      if (cell.has("Int64")) {
        max = Math.max(max, cell.getLong("Int64"));
      }
    }
    return max == Long.MIN_VALUE ? null : max;
  }

  public static JSONObject ingestScannedCsvWithWatermark(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir, JSONArray absolutePaths)
      throws Throwable {
    String payload = resolveWatermarkIngestPayload(fixturesDir, absolutePaths);
    JSONObject root =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
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

      Path incoming = Files.createTempDirectory("rdp_path_from_directory_scan_");
      try {
        Path nested = incoming.resolve("nested");
        Files.createDirectories(nested);
        Path a = incoming.resolve("a.csv");
        Path b = nested.resolve("b.csv");
        Files.writeString(
            a,
            "id,ts\n1,50\n2,99\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            b,
            "id,ts\n3,150\n4,200\n",
            StandardCharsets.UTF_8);

        List<Path> paths = pathsFromDirectoryScan(incoming, "**/*.csv");
        JSONArray pathJson = new JSONArray();
        for (Path p : paths) {
          pathJson.put(p.toAbsolutePath().normalize().toString());
        }

        JSONObject root = ingestScannedCsvWithWatermark(linker, lookup, arena, fixtures, pathJson);
        JSONObject interchange = root.getJSONObject("interchange");
        JSONObject batch = interchange.getJSONObject("ordered_batch");

        System.out.println("Directory scan + watermark ingest (JSON schema + payload): ok");
        System.out.println("  schema: " + watermarkBundle(fixtures).resolve(SCHEMA_EVENTS));
        System.out.println("  payload body: " + watermarkBundle(fixtures).resolve(PAYLOAD_BODY));
        System.out.println("  last_path: " + batch.opt("last_path"));
        JSONObject dataset = interchange.getJSONObject("dataset");
        System.out.println("max_watermark_value (from returned rows): " + maxInt64InColumn(dataset, "ts"));
        System.out.println("returned_row_count: " + interchange.getInt("returned_row_count"));
      } finally {
        try (var walk = Files.walk(incoming)) {
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
