import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Cookbook rename / cast / {@code fill_null} — {@code TransformSpec} via {@code
 * rdp_parity_mapping_spec_mirror}.
 *
 * <p>For {@code TransformStep} parity (rename + cast + fill), see also {@link TransformUtf8Masking}
 * ({@code rdp_parity_transform}).
 */
public final class CookbookTransformsExample {

  private static final String EXPORT = "rdp_parity_mapping_spec_mirror";

  private CookbookTransformsExample() {}

  public static JSONObject mappingSpecInterchange(
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
      JSONObject inter = mappingSpecInterchange(linker, lookup, arena);
      System.out.println(
          "rename_cast_fill_select columns: "
              + inter.getJSONObject("rename_cast_fill_select").getJSONArray("columns"));
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
