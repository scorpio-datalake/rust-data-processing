import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Phase 2 §3 — Privacy diff reports after masking (Python tour).
 *
 * <p><strong>Why this example exists.</strong> Regulated pipelines require auditable evidence of what
 * changed when columns are masked. Python chains {@code transform_apply} with {@code
 * privacy_summarize_utf8_changes}; JVM apps need the JSON report shape returned over FFI.
 *
 * <p><strong>What it demonstrates.</strong> {@code rdp_parity_export_privacy_reports} applies an
 * internal before/after email mask in Rust (standing in for {@code transform_apply} until Java can
 * pass a {@code TransformSpec}), then returns {@code privacy_report_json} — an array of per-column
 * change summaries ({@code column}, cells changed, etc.).
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §3):
 *
 * <pre>{@code
 * after = rdp.transform_apply(before, spec)  // Utf8RedactMiddle
 * rows = rdp.privacy_summarize_utf8_changes(before, after, ["email"])
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#privacyDiffReportsMatchesDocsExample}):
 * asserts {@code privacy_report_json} is present and the first entry has a {@code column} field (UTF-8
 * diff summary shape).
 */
public final class PrivacyDiffReports {

  private static final String EXPORT = "rdp_parity_export_privacy_reports";

  private PrivacyDiffReports() {}

  public static JSONObject privacyReportInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"export_privacy_reports_phase2".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      JSONObject interchange = privacyReportInterchange(linker, lookup, arena);
      Object report = interchange.get("privacy_report_json");

      System.out.println("Privacy diff reports (rdp_parity_export_privacy_reports): ok");
      System.out.println("  Python analogue: transform_apply + privacy_summarize_utf8_changes");
      System.out.println("  privacy_report_json: " + report);
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
