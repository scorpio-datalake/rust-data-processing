import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * S3-, GCS-, and Azure-style URIs in {@code sources.object_store_uris} — Rust reads; Java only passes JSON.
 *
 * <p>CI uses {@code file://} paths under {@code tests/fixtures/cloud_connectors/cloud/}. Production uses
 * {@code s3://}, {@code gs://}, or {@code azure://} in the same JSON fields. Optional local {@code parquet_file}
 * sink path is a temp file path substituted into the pipeline (Rust writes it).
 */
public final class ObjectStoreUrlsExample {

  private static final String BUNDLE = "cloud_connectors";
  private static final String PIPELINE = "pipelines/object_store_sources_only.pipeline.json";

  private ObjectStoreUrlsExample() {}

  public static JSONObject runPipeline(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      Path fixturesDir,
      Path sinkParquet)
      throws Throwable {
    Path fileBase = PlatformConnectorsPipelineExample.bundleRoot(fixturesDir);
    String pipeline =
        PipelineJsonFixtures.resolvePipelineJson(
            fileBase,
            PIPELINE,
            Map.of(
                "FILE_BASE", fileBase.toAbsolutePath().normalize().toString(),
                "SINK_PATH", sinkParquet.toAbsolutePath().normalize().toString()));
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
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

      Path sink = Files.createTempFile("rdp_object_store_demo_", ".parquet");
      try {
        JSONObject root = runPipeline(linker, lookup, arena, fixtures, sink);
        JSONObject inter = root.getJSONObject("interchange");
        JSONArray os = inter.getJSONArray("object_store_source_results");
        System.out.println("Rust ingested via object_store_uris: " + os.toString(2));
        System.out.println("ingested_row_count: " + inter.getInt("ingested_row_count"));
        if (!Files.isRegularFile(sink)) {
          throw new IllegalStateException("Rust did not write parquet_file sink: " + sink);
        }
        System.out.println("parquet_file sink written by Rust: " + sink);
      } finally {
        Files.deleteIfExists(sink);
      }
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
