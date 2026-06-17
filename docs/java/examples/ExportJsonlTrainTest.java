import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Phase 2 §1 — JSON Lines export and train/test row indices (Python tour).
 *
 * <p><strong>Why this example exists.</strong> Python users call {@code export_dataset_jsonl} and
 * {@code export_train_test_row_indices} on an in-memory {@code DataSet}. JVM integrators need to see
 * that the <em>same Rust helpers</em> are reachable over FFI, even though Java does not construct
 * {@code DataSet} objects yet.
 *
 * <p><strong>What it demonstrates.</strong> A single parity downcall, {@code
 * rdp_parity_export_privacy_reports}, runs a built-in two-row email table in Rust, serializes JSONL
 * lines, and computes train/test index lengths for {@code row_count=100}, {@code test_fraction=0.2}
 * (80 train / 20 test). Java only parses {@code interchange.jsonl_preview_lines} and {@code
 * train_test_indices_demo}.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §1):
 *
 * <pre>{@code
 * text = rdp.export_dataset_jsonl(ds, ["id", "label"])
 * train_idx, test_idx = rdp.export_train_test_row_indices(ds.row_count(), test_fraction=0.34)
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#exportJsonlTrainTestMatchesDocsExample}):
 * asserts envelope {@code ok}, at least one JSONL line containing {@code email}, and {@code
 * train_len=80} / {@code test_len=20} so the index helper does not regress silently.
 */
public final class ExportJsonlTrainTest {

  private static final String EXPORT = "rdp_parity_export_privacy_reports";

  private ExportJsonlTrainTest() {}

  /** Invokes the parity export and validates {@code interchange.kind}. */
  public static JSONObject exportPrivacyReportsInterchange(
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

      JSONObject interchange = exportPrivacyReportsInterchange(linker, lookup, arena);

      JSONArray jsonlLines = interchange.getJSONArray("jsonl_preview_lines");
      JSONObject trainTest = interchange.getJSONObject("train_test_indices_demo");

      System.out.println("JSONL export + train/test indices (rdp_parity_export_privacy_reports): ok");
      System.out.println("  Python analogue: export_dataset_jsonl, export_train_test_row_indices");
      System.out.println("  jsonl_preview_lines (" + jsonlLines.length() + "):");
      for (int i = 0; i < jsonlLines.length(); i++) {
        System.out.println("    " + jsonlLines.getString(i));
      }
      System.out.println(
          "  train_test_indices_demo: train_len="
              + trainTest.getInt("train_len")
              + " test_len="
              + trainTest.getInt("test_len"));
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
