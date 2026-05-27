import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Multi-table JOIN — {@code SqlContext} equivalent via {@code rdp_parity_sql_suite_mirror}.
 *
 * <p>Committed join fixtures live under {@code tests/fixtures/sql_parity/}; this example prints the
 * parity JOIN {@code dataset} (two rows) without crafting pipeline JSON in Java.
 */
public final class SqlJoinPipelineExample {

  private static final String EXPORT = "rdp_parity_sql_suite_mirror";

  private SqlJoinPipelineExample() {}

  public static JSONObject joinDataset(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertSqlSuiteMirror(root.getJSONObject("interchange"));
    return root.getJSONObject("interchange").getJSONObject("join");
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject join = joinDataset(linker, lookup, arena);
      JSONArray rows = join.getJSONArray("rows");
      System.out.println("JOIN result rows: " + rows.length());
      System.out.println(rows.toString(2));
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
