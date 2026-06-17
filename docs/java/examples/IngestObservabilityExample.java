import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Ingest observability — missing-file errors and alert hooks (Python {@code observer} / {@code
 * alert_at_or_above} in ingest options).
 *
 * <p>JVM parity: {@code rdp_parity_observability_mirror} (no live observer callback across FFI yet).
 */
public final class IngestObservabilityExample {

  private static final String EXPORT = "rdp_parity_observability_mirror";

  private IngestObservabilityExample() {}

  public static JSONObject observabilityInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.validateMirrorExport(EXPORT, root);
    return root.getJSONObject("interchange");
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject inter = observabilityInterchange(linker, lookup, arena);
      System.out.println(
          "Observability mirror: missing_file_is_err=" + inter.getBoolean("missing_file_is_err"));
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library");
      System.exit(2);
    }
    demonstrate(lib);
  }
}
