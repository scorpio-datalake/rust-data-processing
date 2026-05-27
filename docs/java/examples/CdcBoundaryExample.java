import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * CDC boundary types — {@code CdcEvent}, {@code CdcOp}, {@code RowImage} shapes (no live connector).
 *
 * <p>Uses {@code rdp_parity_cdc} for a single insert event JSON sketch.
 */
public final class CdcBoundaryExample {

  private static final String EXPORT = "rdp_parity_cdc";

  private CdcBoundaryExample() {}

  public static JSONObject cdcInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"cdc_boundary_types".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject inter = cdcInterchange(linker, lookup, arena);
      System.out.println("CDC event: " + inter.getJSONObject("event").toString(2));
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
