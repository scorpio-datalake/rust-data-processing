import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

/**
 * ConnectorX {@code sources.db_reads} pipeline sketch — Rust executes SQL; Java passes JSON only.
 *
 * <p><strong>CI note:</strong> JUnit only resolves the committed pipeline template (no live Oracle/MSSQL).
 * To run end-to-end, build {@code rdp_jvm_sys} with {@code db_connectorx}, set a real {@code oracle://}
 * or {@code mssql://} URL, and call {@code RdpNativeJson.invokeRunPipelineJson}.
 *
 * <p>Fixture: {@code tests/fixtures/cloud_connectors/pipelines/oracle_db_read.pipeline.json}.
 */
public final class DbReadPipelineExample {

  private static final String BUNDLE = "cloud_connectors";
  private static final String PIPELINE = "pipelines/oracle_db_read.pipeline.json";

  private DbReadPipelineExample() {}

  public static String resolveOracleDbReadPipeline(Path fixturesDir, Path curatedParquet)
      throws Exception {
    Path bundle =
        PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
            .orElseThrow(() -> new IllegalStateException("tests/fixtures/" + BUNDLE + " missing"));
    return PipelineJsonFixtures.resolvePipelineJson(
        bundle,
        PIPELINE,
        Map.of(
            "CURATED_PARQUET",
            curatedParquet.toAbsolutePath().normalize().toString()));
  }

  public static void demonstrate() throws Exception {
    Path fixtures =
        PipelineJsonFixtures.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path sink = Files.createTempFile("rdp_db_read_demo_", ".parquet");
    try {
      String pipeline = resolveOracleDbReadPipeline(fixtures, sink);
      System.out.println("=== db_reads pipeline (substituted paths only) ===");
      System.out.println(pipeline);
      System.out.println();
      System.out.println(
          "Run with RDP_JVM_SYS + db_connectorx when you have a reachable warehouse URL.");
    } finally {
      Files.deleteIfExists(sink);
    }
  }

  public static void main(String[] args) throws Exception {
    demonstrate();
  }
}
