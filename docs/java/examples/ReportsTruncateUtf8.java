import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Phase 2 §4 — Truncate large UTF-8 blobs for logs or LLM context.
 *
 * <p><strong>Why this example exists.</strong> Privacy reports and profile JSON can exceed log or
 * prompt limits. Python calls {@code reports_truncate_utf8_bytes}; JVM operators need the truncated
 * string field returned in the parity envelope and a safe byte cap enforced in Rust.
 *
 * <p><strong>What it demonstrates.</strong> Reuses {@code rdp_parity_export_privacy_reports}, which
 * builds a full privacy JSON string then applies {@code truncate_utf8_by_bytes(..., 120)}. Java reads
 * {@code interchange.reports_truncated_sample} only.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §4):
 *
 * <pre>{@code
 * snippet = rdp.reports_truncate_utf8_bytes(blob, max_bytes=256)
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#reportsTruncateUtf8MatchesDocsExample}):
 * sample is non-empty and length ≤ 120 characters, matching the Rust parity cap.
 */
public final class ReportsTruncateUtf8 {

  private static final String EXPORT = "rdp_parity_export_privacy_reports";

  private ReportsTruncateUtf8() {}

  public static String truncatedSample(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"export_privacy_reports_phase2".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange.getString("reports_truncated_sample");
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      String snippet = truncatedSample(linker, lookup, arena);

      System.out.println("Truncate UTF-8 for logs / LLM context (reports_truncated_sample): ok");
      System.out.println("  Python analogue: reports_truncate_utf8_bytes(blob, max_bytes=256)");
      System.out.println("  truncated length (chars): " + snippet.length());
      System.out.println("  preview: " + snippet.substring(0, Math.min(120, snippet.length())) + "…");
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
