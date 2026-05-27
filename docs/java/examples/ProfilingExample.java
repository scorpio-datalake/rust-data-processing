import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/** Profiling — {@code profile_dataset} JSON report over FFI ({@code rdp_parity_profiling}). */
public final class ProfilingExample {

  private static final String EXPORT = "rdp_parity_profiling";

  private ProfilingExample() {}

  public static JSONObject profilingInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"profiling_polars".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    if (!interchange.has("report")) {
      throw new IllegalStateException("expected profiling report object");
    }
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject inter = profilingInterchange(linker, lookup, arena);
      System.out.println("Profiling engine: " + inter.getString("engine"));
      System.out.println("Report keys: " + inter.getJSONObject("report").keySet());
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
