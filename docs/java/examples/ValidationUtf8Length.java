import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Phase 2 §6 — Validation with UTF-8 length checks (Python tour).
 *
 * <p><strong>Why this example exists.</strong> Data quality gates (min/max Unicode scalar length per
 * column) are common before publishing datasets. Python uses {@code utf8_len_chars_between} in {@code
 * validate_dataset}; JVM consumers need to see how validation summaries cross the FFI boundary even
 * when the exact check differs in the parity export.
 *
 * <p><strong>What it demonstrates.</strong> {@code rdp_parity_validation} runs a built-in table with a
 * {@code not_null} check on {@code name} (one failing row). Java reads {@code interchange.summary}
 * ({@code total_checks}, {@code failed_checks}) — the same envelope shape production code would use for
 * any validation spec.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §6):
 *
 * <pre>{@code
 * rep = rdp.validate_dataset(ds, {
 *     "checks": [{"kind": "utf8_len_chars_between", "column": "code", ...}],
 * })
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#validationUtf8LengthMatchesDocsExample}):
 * asserts at least one check ran and at least one failure, proving the validation engine reports
 * failures over FFI (parity uses {@code not_null}, not utf8 length, until a dedicated export exists).
 */
public final class ValidationUtf8Length {

  private static final String VALIDATION = "rdp_parity_validation";

  private ValidationUtf8Length() {}

  public static JSONObject validationInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, VALIDATION);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"validation_polars_dsl".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      JSONObject interchange = validationInterchange(linker, lookup, arena);
      JSONObject summary = interchange.getJSONObject("summary");

      System.out.println("Validation over FFI (rdp_parity_validation): ok");
      System.out.println("  Python §6 analogue: validate_dataset + utf8_len_chars_between");
      System.out.println(
          "  summary: total_checks="
              + summary.getInt("total_checks")
              + " failed_checks="
              + summary.getInt("failed_checks"));
      System.out.println("  Note: this export uses not_null on name, not utf8_len on code.");
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
