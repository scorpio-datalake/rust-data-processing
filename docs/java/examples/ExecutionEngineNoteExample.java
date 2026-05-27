/**
 * {@code ExecutionEngine} parallel filter/map/reduce (Python tour) — not on the JVM FFI surface.
 *
 * <p>Use pipeline batching in Rust ({@code rdp_run_pipeline_json}) or stay in Python for
 * {@code ExecutionEngine} metrics. {@code rdp_parity_benchmark_smoke_mirror} covers related Rust
 * workloads for regression only.
 */
public final class ExecutionEngineNoteExample {

  private ExecutionEngineNoteExample() {}

  public static void main(String[] args) {
    System.out.println("ExecutionEngine is Python-first today.");
    System.out.println("JVM options:");
    System.out.println("  - rdp_run_pipeline_json for declarative ETL");
    System.out.println("  - rdp_parity_processing / rdp_parity_benchmark_smoke_mirror for smoke tests");
    System.out.println("See docs/python/README.md and Planning/example_work_remaining_for_java.md");
  }
}
