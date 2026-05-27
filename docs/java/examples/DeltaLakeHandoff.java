import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.nio.file.Path;

/**
 * Phase 2 §8 — Delta Lake / Iceberg handoff (out-of-process).
 *
 * <p><strong>Why this example exists.</strong> Lakehouse tables are read with Delta/Iceberg clients in
 * Python or Spark, not inside {@code rdp_jvm_sys}. The integration pattern is: <em>materialize a
 * Parquet slice on disk</em>, then call Rust ingest FFI — same as batch ETL from any external store.
 *
 * <p><strong>What it demonstrates.</strong> No native calls in {@code main}. Lists the three-step handoff and
 * points to {@link PlatformConnectorsPipelineExample} (Databricks / Delta URLs in pipeline JSON),
 * {@link ObjectStoreUrlsExample} (S3 / GCS / Azure), and {@link SparkParquetHandoffExample} /
 * {@code ParquetSnippets.java} for working Parquet handoff FFI.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §8):
 *
 * <pre>{@code
 * ds = rdp.ingest_from_path("exported_slice.parquet", schema)
 * }</pre>
 *
 * <p><strong>Runnable handoff in-repo:</strong> {@link WarehouseExportHandoffExample} (CSV→Parquet→
 * {@code rdp_ingest_parquet_path}) exercises the same file path without a lake client.
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#deltaLakeHandoffPrerequisitesMatchDocsExample}):
 * does not connect to a lake in CI; verifies {@code people.csv}, people Parquet pipeline fixtures, and
 * {@code docs/LAKE_TABLE_READ.md} exist so the documented handoff path is reproducible in-repo.
 */
public final class DeltaLakeHandoff {

  private DeltaLakeHandoff() {}

  public static void demonstrate() {
    System.out.println("Delta Lake / Iceberg handoff (not in-process on JVM):");
    System.out.println("  1. Read table with deltalake, PyIceberg, or Spark in your orchestrator");
    System.out.println("  2. Write a Parquet slice (or CSV) to a path the JVM can pass to Rust");
    System.out.println("  3. Call rdp_ingest_parquet_path / rdp_ingest_ordered_paths_json with schema JSON");
    System.out.println();
    System.out.println("Repository docs:");
    System.out.println("  docs/LAKE_TABLE_READ.md");
    System.out.println("  Planning/ADR_P2_E2_LAKE_TABLE_READ.md");
    System.out.println();
    System.out.println("JVM examples: PlatformConnectorsPipelineExample, ObjectStoreUrlsExample,");
    System.out.println("  SparkParquetHandoffExample, ParquetSnippets, GhcnJsonXmlParquetPipeline");
  }

  public static void main(String[] args) {
    demonstrate();
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib != null) {
      System.out.println();
      System.out.println("Note: RDP_JVM_SYS is set; use Parquet ingest FFI after you export a lake slice to disk.");
    }
  }
}
