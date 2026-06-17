import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.nio.file.Path;

/**
 * Phase 2 §7 — Median via {@code processing_reduce} and grouped {@code DataFrame} (Python tour).
 *
 * <p><strong>Why this example exists.</strong> Analytics pipelines use median aggregations (robust to
 * outliers). Python exposes {@code processing_reduce(..., "median")} and SQL/group_by median; JVM
 * documentation must state clearly that these APIs are not yet exposed as parity exports.
 *
 * <p><strong>What it demonstrates.</strong> Documentation-only: points to Rust {@code ReduceOp::Median}
 * and Polars SQL examples in {@code SQLQueries.java}. {@code rdp_parity_processing} today only
 * demonstrates filter/map/sum.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §7):
 *
 * <pre>{@code
 * rdp.processing_reduce(ds, "x", "median")
 * lf.group_by(["g"], [{"type": "median", "column": "v", ...}]).collect()
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code Phase2DeferredDocsExampleTest#medianReduceAndDataFrameWhenFfiPresent}):
 * skipped until a median parity symbol is added; then implement a native contract alongside this file.
 */
public final class MedianReduceAndDataFrame {

  private MedianReduceAndDataFrame() {}

  public static void demonstrate() {
    System.out.println("Median reduce + grouped median (processing_reduce / DataFrame.group_by):");
    System.out.println("  Python: processing_reduce(ds, column, \"median\"); DataFrame.group_by …");
    System.out.println("  JVM: not exposed yet — rdp_parity_processing uses filter/map/sum only");
    System.out.println("  Rust: processing::reduce with ReduceOp::Median; Polars SQL median in pipelines");
    System.out.println();
    System.out.println("See docs/python/PHASE2_EXAMPLES.md §7 and SQLQueries.java for Polars SQL on the JVM.");
  }

  public static void main(String[] args) {
    demonstrate();
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib != null) {
      System.out.println();
      System.out.println("Note: RDP_JVM_SYS is set but this example does not call native code until FFI exists.");
    }
  }
}
