import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Hive-style partition discovery — {@code discover_hive_partitioned_files}, globs, explicit lists
 * (Python {@code examples.html} ordered-paths section).
 *
 * <p>Uses {@code rdp_parity_partition_discovery_mirror} (committed {@code tests/fixtures/hive_partitioned/}).
 */
public final class PartitionDiscoveryExample {

  private static final String EXPORT = "rdp_parity_partition_discovery_mirror";

  private PartitionDiscoveryExample() {}

  public static JSONObject partitionDiscoveryInterchange(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    PytestMirrorAssertions.validateMirrorExport(EXPORT, root);
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject inter = partitionDiscoveryInterchange(linker, lookup, arena);
      System.out.println("Partition discovery mirror: discover_all_len=" + inter.getInt("discover_all_len"));
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
