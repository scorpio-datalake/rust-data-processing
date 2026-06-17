import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.nio.file.Path;

/**
 * Phase 2 §2 — UTF-8 length row filter ({@code export_filter_rows_max_utf8_chars}).
 *
 * <p><strong>Why this example exists.</strong> Product teams often cap string column length (tweets,
 * UI fields) before export. Python exposes that as a first-class API; JVM readers need to know the
 * capability lives in Rust today and <em>which FFI gap</em> blocks Java until a parity export or
 * parameterized ingest payload is added.
 *
 * <p><strong>What it demonstrates.</strong> This class is <strong>documentation-only</strong>: it prints
 * the expected behavior of the Python snippet and points at {@code
 * rust_data_processing::export::filter_rows_max_utf8_chars}. It does not call native code.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §2):
 *
 * <pre>{@code
 * short_only = rdp.export_filter_rows_max_utf8_chars(ds, "msg", max_chars=10)
 * }</pre>
 *
 * <p>Input rows {@code ["hi"]} and {@code ["this is too long…"]} → only the short row remains.
 *
 * <p><strong>JUnit contract</strong> ({@code Phase2DeferredDocsExampleTest#exportFilterRowsMaxUtf8CharsWhenFfiPresent}):
 * skipped in CI until {@code rdp_parity_export_filter_rows_max_utf8_chars} (or equivalent) appears in
 * {@code ffi_manifest.json}; then maintainers add a real contract next to this doc example.
 */
public final class ExportFilterRowsMaxUtf8Chars {

  private ExportFilterRowsMaxUtf8Chars() {}

  public static void demonstrate() {
    System.out.println("UTF-8 length row filter (export_filter_rows_max_utf8_chars):");
    System.out.println("  Python: rust_data_processing.export_filter_rows_max_utf8_chars(ds, column, max_chars)");
    System.out.println("  JVM: not exposed yet — no rdp_parity_* or dataset JSON FFI for this helper");
    System.out.println("  Rust: rust_data_processing::export::filter_rows_max_utf8_chars");
    System.out.println();
    System.out.println("Expected behavior (Python snippet in docs/python/PHASE2_EXAMPLES.md §2):");
    System.out.println("  input rows: [\"hi\"], [\"this is too long for a tweet style cap\"]");
    System.out.println("  max_chars=10 on column \"msg\" → keeps only the short row");
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
