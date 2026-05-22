import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Phase 2 §5 — UTF-8 masking via {@code TransformSpec} (Python tour).
 *
 * <p><strong>Why this example exists.</strong> PII workflows use declarative steps ({@code Utf8Sha256Hex},
 * {@code Utf8RedactMiddle}, …). Python passes a spec dict to {@code transform_apply}; JVM teams need
 * to see how transform results surface as {@code interchange.dataset} JSON today and what is still
 * Python-only.
 *
 * <p><strong>What it demonstrates.</strong> Calls {@code rdp_parity_transform}, which currently runs a
 * <em>numeric</em> rename/cast/fill-null plan (two rows) to prove the TransformSpec engine and JSON
 * dataset interchange. UTF-8 masking steps are documented as the Python analogue until a dedicated
 * parity scenario is added.
 *
 * <p><strong>Python analogue</strong> ({@code docs/python/PHASE2_EXAMPLES.md} §5):
 *
 * <pre>{@code
 * spec = { "steps": [{"Utf8Sha256Hex": {"column": "s"}}], ... }
 * out = rdp.transform_apply(ds, spec)
 * }</pre>
 *
 * <p><strong>JUnit contract</strong> ({@code DocsExampleNativeIntegrationTest#transformUtf8MaskingMatchesDocsExample}):
 * asserts {@code kind=transform_spec_polars} and exactly two output rows so the transform export stays
 * wired end-to-end.
 */
public final class TransformUtf8Masking {

  private static final String TRANSFORM = "rdp_parity_transform";

  private TransformUtf8Masking() {}

  public static JSONObject transformInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, TRANSFORM);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"transform_spec_polars".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      JSONObject interchange = transformInterchange(linker, lookup, arena);
      JSONObject dataset = interchange.getJSONObject("dataset");

      System.out.println("TransformSpec (rdp_parity_transform): ok");
      System.out.println("  Python §5 analogue: transform_apply with Utf8Sha256Hex / Utf8RedactMiddle");
      System.out.println(
          "  JVM export today: rename + cast + fill-null demo; row_count="
              + dataset.getJSONArray("rows").length());
      System.out.println("  For Utf8 masking steps, use docs/python/PHASE2_EXAMPLES.md §5 in Python.");
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
