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
 * SFTP / FTP / FTPS URIs in {@code sources.file_transfer_uris} — Rust downloads and ingests; Java
 * passes JSON only.
 *
 * <p>Committed fixture: {@code tests/fixtures/file_transfer/pipelines/ftp_sources_only.pipeline.json}.
 * CI substitutes a loopback {@code ftp://} URL (see {@code tests/file_transfer_ftp_integration.rs}).
 * Production: use real hosts; set {@code SFTP_PASSWORD}, {@code FTP_PASSWORD}, or {@code
 * SFTP_PRIVATE_KEY_PATH} on the process — not in pipeline JSON (see {@code docs/CLOUD_AUTH.md}).
 *
 * <p><strong>Integration-tested import</strong> (Docker SFTP + FTP with seeded Uber CSV): {@code
 * integration_testing/CloudConnectors/} — {@code file_transfer_uris} via {@code cloud_pipeline.py} and {@code
 * CloudImportIntegrationTest.javaSftpFileTransferImport} / {@code javaFtpFileTransferImport}.
 */
public final class SftpFtpConnectorsExample {

  private static final String BUNDLE = "file_transfer";
  private static final String PIPELINE = "pipelines/ftp_sources_only.pipeline.json";

  private SftpFtpConnectorsExample() {}

  public static JSONObject runPipeline(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      Path fixturesDir,
      String ftpUri,
      Path sinkParquet)
      throws Throwable {
    Path bundleRoot =
        PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
            .orElseThrow(
                () -> new IllegalStateException("tests/fixtures/" + BUNDLE + " not found"));
    String pipeline =
        PipelineJsonFixtures.resolvePipelineJson(
            bundleRoot,
            PIPELINE,
            Map.of(
                "FTP_URI", ftpUri,
                "SINK_PATH", sinkParquet.toAbsolutePath().normalize().toString()));
    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root;
  }

  public static void assertFileTransferSourcesOk(JSONObject interchange) {
    JSONArray ft = interchange.getJSONArray("file_transfer_source_results");
    if (ft.length() < 1) {
      throw new IllegalStateException("expected file_transfer_source_results");
    }
    for (int i = 0; i < ft.length(); i++) {
      if (!"ok".equals(ft.getJSONObject(i).getString("status"))) {
        throw new IllegalStateException("file transfer source not ok: " + ft.getJSONObject(i));
      }
    }
  }

  public static void demonstrate(Path nativeLibrary, String ftpUri) throws Throwable {
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

      Path sink = Files.createTempFile("rdp_file_transfer_demo_", ".parquet");
      try {
        JSONObject root = runPipeline(linker, lookup, arena, fixtures, ftpUri, sink);
        JSONObject inter = root.getJSONObject("interchange");
        assertFileTransferSourcesOk(inter);
        System.out.println(
            "Rust ingested via file_transfer_uris: "
                + inter.getJSONArray("file_transfer_source_results").toString(2));
        System.out.println("ingested_row_count: " + inter.getInt("ingested_row_count"));
        if (!Files.isRegularFile(sink)) {
          throw new IllegalStateException("Rust did not write parquet_file sink: " + sink);
        }
        System.out.println("parquet_file sink: " + sink);
      } finally {
        Files.deleteIfExists(sink);
      }
    }
  }

  public static void main(String[] args) throws Throwable {
    String ftpUri = System.getenv("RDP_DEMO_FTP_URI");
    if (ftpUri == null || ftpUri.isBlank()) {
      System.err.println(
          "Set RDP_DEMO_FTP_URI (e.g. ftp://etl_user:PASS@host:21/rdp/incoming/data.parquet)"
              + " and RDP_JVM_SYS to a built rdp_jvm_sys with cloud_connectors.");
      System.exit(2);
    }
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library");
      System.exit(2);
    }
    demonstrate(lib, ftpUri);
  }
}
