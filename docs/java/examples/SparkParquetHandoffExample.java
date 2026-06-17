import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Spark handoff: Rust writes Parquet to {@code handoff_uri} in a pipeline ({@code kind: spark} sink).
 *
 * <p>There is no {@code SparkSession} on the FFI boundary. This example runs the same pipeline as
 * {@link PlatformConnectorsPipelineExample} and prints the {@code spark} sink result — the Parquet path
 * your Spark driver should use is in {@code sink_results[].handoff_uri}.
 *
 * <p>For a minimal temp export without pipeline JSON, see {@code ParquetTempExportExample} in
 * {@code rust-data-processing-jvm-examples} ({@code rdp_export_parquet_temp}).
 */
public final class SparkParquetHandoffExample {

  private SparkParquetHandoffExample() {}

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

      Path stageBase = Files.createTempDirectory("rdp_spark_handoff_");
      Path deltaWh = Files.createTempDirectory("rdp_spark_delta_");
      try {
        String pipeline =
            PlatformConnectorsPipelineExample.resolvePipelineJson(
                fixtures,
                PlatformConnectorsPipelineExample.bundleRoot(fixtures),
                stageBase,
                deltaWh);
        JSONObject root = PlatformConnectorsPipelineExample.runPipeline(linker, lookup, arena, pipeline);
        JSONArray sinks = root.getJSONObject("interchange").getJSONArray("sink_results");
        JSONObject sparkSink = null;
        for (int i = 0; i < sinks.length(); i++) {
          if ("spark".equals(sinks.getJSONObject(i).getString("kind"))) {
            sparkSink = sinks.getJSONObject(i);
            break;
          }
        }
        if (sparkSink == null || !"ok".equals(sparkSink.getString("status"))) {
          throw new IllegalStateException("expected ok spark sink: " + sinks);
        }
        System.out.println("Rust wrote Spark handoff Parquet:");
        System.out.println(sparkSink.toString(2));
        System.out.println();
        System.out.println("In your Spark driver (separate app): spark.read().parquet(<handoff_uri>)");
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
          // best-effort
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
