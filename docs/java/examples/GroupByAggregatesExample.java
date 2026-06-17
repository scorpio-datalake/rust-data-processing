import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Cookbook {@code group_by} aggregates — Polars SQL {@code GROUP BY} / {@code HAVING} via SQL suite parity.
 *
 * <p>Python uses lazy {@code DataFrame.group_by}; JVM consumers can use pipeline {@code transform.sql} or
 * {@code rdp_parity_sql_suite_mirror} for the same semantics in Rust.
 */
public final class GroupByAggregatesExample {

  private static final String EXPORT = "rdp_parity_sql_suite_mirror";

  private GroupByAggregatesExample() {}

  public static JSONObject groupHavingDataset(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, EXPORT);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertSqlSuiteMirror(root.getJSONObject("interchange"));
    return root.getJSONObject("interchange").getJSONObject("group_having");
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject group = groupHavingDataset(linker, lookup, arena);
      JSONArray rows = group.getJSONArray("rows");
      System.out.println("GROUP BY / HAVING result rows: " + rows.length());
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
