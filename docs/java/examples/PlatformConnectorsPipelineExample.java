import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM pipeline JSON with Snowflake, Databricks, Spark, Delta, and object-store URLs — all executed in
 * Rust ({@code rdp_run_pipeline_json}).
 *
 * <p>Loads {@code tests/fixtures/cloud_connectors/pipelines/platform_connectors.pipeline.json}.
 * Ingest uses {@code sources.object_store_uris} only ({@code file://} in tests; {@code s3://} /
 * {@code gs://} / {@code abfss://} in production). Sinks write Parquet via Rust {@code object_store}
 * (and stage paths for Snowflake). Java never opens warehouse drivers, Spark, or local files for ETL.
 */
public final class PlatformConnectorsPipelineExample {

  private static final String BUNDLE = "cloud_connectors";
  private static final String PIPELINE = "pipelines/platform_connectors.pipeline.json";

  private PlatformConnectorsPipelineExample() {}

  public static Path bundleRoot(Path fixturesDir) {
    return PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
        .orElseThrow(
            () -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
  }

  public static String resolvePipelineJson(
      Path fixturesDir, Path fileBase, Path stageBase, Path deltaWh) throws Exception {
    return PipelineJsonFixtures.resolvePipelineJson(
        bundleRoot(fixturesDir),
        PIPELINE,
        new HashMap<>(
            Map.of(
                "FILE_BASE", fileBase.toAbsolutePath().normalize().toString(),
                "STAGE_BASE", stageBase.toAbsolutePath().normalize().toString(),
                "DELTA_WH", deltaWh.toAbsolutePath().normalize().toString())));
  }

  public static JSONObject runPipeline(
      Linker linker, SymbolLookup lookup, Arena arena, String pipelineJson) throws Throwable {
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipelineJson);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root;
  }

  public static void assertRustConnectorsOk(JSONObject interchange) {
    JSONArray os = interchange.getJSONArray("object_store_source_results");
    if (os.length() < 1) {
      throw new IllegalStateException("expected object_store_source_results");
    }
    for (int i = 0; i < os.length(); i++) {
      if (!"ok".equals(os.getJSONObject(i).getString("status"))) {
        throw new IllegalStateException("object store source not ok: " + os.getJSONObject(i));
      }
    }
    JSONArray sinks = interchange.getJSONArray("sink_results");
    for (int i = 0; i < sinks.length(); i++) {
      JSONObject s = sinks.getJSONObject(i);
      if (!"ok".equals(s.getString("status"))) {
        throw new IllegalStateException("expected ok sink, got " + s);
      }
    }
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

      Path fileBase = bundleRoot(fixtures);
      Path stageBase = Files.createTempDirectory("rdp_java_stage_");
      Path deltaWh = Files.createTempDirectory("rdp_java_delta_");
      try {
        String pipeline = resolvePipelineJson(fixtures, fileBase, stageBase, deltaWh);
        System.out.println("=== Pipeline JSON (Rust executes all URIs) ===");
        System.out.println(pipeline);
        System.out.println();

        JSONObject root = runPipeline(linker, lookup, arena, pipeline);
        JSONObject inter = root.getJSONObject("interchange");
        assertRustConnectorsOk(inter);
        System.out.println(root.toString(2));
      } finally {
        deleteRecursively(stageBase);
        deleteRecursively(deltaWh);
      }
    }
  }

  private static void deleteRecursively(Path root) throws java.io.IOException {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var walk = Files.walk(root)) {
      walk.sorted(java.util.Comparator.reverseOrder()).forEach(p -> {
        try {
          Files.deleteIfExists(p);
        } catch (java.io.IOException ignored) {
          // best-effort cleanup
        }
      });
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println(
          "Set RDP_JVM_SYS or -Drdp.jvm.sys.library to a built rdp_jvm_sys with cloud_connectors.");
      System.exit(2);
    }
    demonstrate(lib);
  }
}
