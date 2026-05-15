import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM analogues of Python {@code sql_query_dataset} and {@code SqlContext} JOINs (see {@code
 * docs/python/README.md}).
 *
 * <p><strong>Single-table SQL</strong> — Python registers the in-memory frame as {@code df}. On
 * the JVM, ingest local JSON into Rust, then run Polars SQL on {@code df} via {@code
 * rdp_run_pipeline_json} ({@code transform.sql} → sinks). Same engine as {@code
 * rust_data_processing::sql::query}.
 *
 * <p><strong>Multi-table JOIN</strong> — There is no JNI for {@code SqlContext} yet. The parity
 * export {@code rdp_parity_sql_suite_mirror} runs the same Rust JOIN / group / error checks the
 * Python suite mirrors; call it to validate JOIN-style results land in the JSON envelope.
 *
 * <p>Prerequisites: native {@code rdp_jvm_sys} with {@code link-main} / {@code full}, {@link
 * RdpNativeJson#resolveNativeLibraryFromEnvOrProperty()}, {@code --enable-native-access=ALL-UNNAMED}.
 * CI exercises this file via {@code
 * io.github.rust_data_processing.docexamples.DocsExampleNativeIntegrationTest} (method names
 * mirror the sketches here) and via {@code FfiExportedSymbolsContractTest} for manifest-driven
 * symbol checks. Not built by the main Maven module — copy into {@code rust-data-processing-jvm-examples}
 * to compile with {@code mvn}.
 */
public final class SQLQueries {

  private SQLQueries() {}

  public static JSONObject exampleSchema() {
    JSONArray fields =
        new JSONArray()
            .put(new JSONObject().put("name", "id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "active").put("data_type", "Bool"))
            .put(new JSONObject().put("name", "score").put("data_type", "Float64"));
    return new JSONObject().put("fields", fields);
  }

  public static JSONArray exampleRowsJson() {
    return new JSONArray()
        .put(new JSONObject().put("id", 1).put("active", true).put("score", 10.0))
        .put(new JSONObject().put("id", 2).put("active", true).put("score", 20.0))
        .put(new JSONObject().put("id", 3).put("active", false).put("score", 30.0));
  }

  /** Same SQL string as Python {@code sql_query_dataset(...)} in the README sketch. */
  public static String singleTableSqlOnDf() {
    return "SELECT id, score FROM df WHERE active = TRUE ORDER BY id DESC LIMIT 10";
  }

  /**
   * {@code rdp_run_pipeline_json}: ingest → {@code transform.sql} on {@code df} → Parquet sink
   * (row count matches materialized query).
   */
  public static void demonstrateSingleTableSql(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      Path work = Files.createTempDirectory("rdp_sql_queries_single_");
      try {
        Path jsonPath = work.resolve("rows.json");
        Path parquetPath = work.resolve("out.parquet");
        Files.writeString(jsonPath, exampleRowsJson().toString(), StandardCharsets.UTF_8);

        JSONObject payload =
            new JSONObject()
                .put("pipeline_spec_version", 1)
                .put(
                    "sources",
                    new JSONObject()
                        .put(
                            "paths",
                            new JSONArray().put(jsonPath.toAbsolutePath().normalize().toString()))
                        .put("schema", exampleSchema())
                        .put("options", new JSONObject().put("format", "json")))
                .put("transform", new JSONObject().put("sql", singleTableSqlOnDf()))
                .put(
                    "sinks",
                    new JSONArray()
                        .put(
                            new JSONObject()
                                .put("kind", "parquet_file")
                                .put("path", parquetPath.toAbsolutePath().normalize().toString())));

        JSONObject root =
            RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload.toString());
        PytestMirrorAssertions.assertEnvelopeOk(root);
        JSONObject interchange = root.getJSONObject("interchange");
        if (!"run_pipeline_json".equals(interchange.getString("kind"))) {
          throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
        }
        JSONObject sink = interchange.getJSONArray("sink_results").getJSONObject(0);
        if (!"ok".equals(sink.getString("status"))) {
          throw new AssertionError("sink not ok: " + sink);
        }
        int rows = sink.getInt("row_count");
        if (rows != 2) {
          throw new AssertionError("expected 2 rows (active=TRUE), got " + rows);
        }
        System.out.println("single-table SQL on df: ok, sink row_count=" + rows);
      } finally {
        try (var walk = Files.walk(work)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      }
    }
  }

  /**
   * {@code rdp_parity_sql_suite_mirror}: Rust-built JOIN payload (people × scores) for pytest /
   * JVM parity — closest JVM path to Python {@code SqlContext} until a dedicated multi-table
   * orchestration JSON exists.
   */
  public static JSONObject demonstrateJoinSketchViaParity(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject root =
          RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_sql_suite_mirror");
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject interchange = root.getJSONObject("interchange");
      PytestMirrorAssertions.assertSqlSuiteMirror(interchange);
      JSONArray joinRows = interchange.getJSONObject("join").getJSONArray("rows");
      System.out.println("JOIN sketch (parity sql_suite): " + joinRows.length() + " row(s)");
      return root;
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
      demonstrateSingleTableSql(lib);
      demonstrateJoinSketchViaParity(lib);
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
