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
 * JVM analogue of Python {@code DataFrame.from_dataset(ds).filter_eq(...).multiply_f64(...).collect()}.
 *
 * <p>The Python {@code DataFrame} API is Polars-backed in Rust; on the JVM there is no lazy
 * wrapper — you express the same work as <strong>Polars SQL</strong> on registered table {@code df}
 * inside {@code rdp_run_pipeline_json} (ingest ordered paths → optional {@code transform.sql} →
 * sinks). This matches {@code rust_data_processing::sql::query} on {@code
 * pipeline::DataFrame::from_dataset} (see {@code docs/python/README.md} and {@code docs/adr/006-*.md}).
 *
 * <p>Prerequisites: a native {@code rdp_jvm_sys} built with {@code link-main} (or {@code jvm_ffi}
 * / {@code full}), {@link RdpNativeJson#resolveNativeLibraryFromEnvOrProperty()} must resolve to
 * that file, and the JVM needs {@code --enable-native-access=ALL-UNNAMED}. {@link
 * RdpNativeJson#invokeAbiVersion} runs first as a load/smoke check; stubbed libraries fail with an
 * explicit rebuild hint via {@link PytestMirrorAssertions#assertEnvelopeOk(JSONObject)}.
 *
 * <p>Not built by the main Maven module — copy into {@code rust-data-processing-jvm-examples} if
 * you want {@code mvn} to compile it. The same Polars SQL + assertions are exercised in CI by
 * {@code io.github.rust_data_processing.docexamples.DocsExampleNativeIntegrationTest#runPipelineJsonPolarsSqlFilterAndMultiplyMatchesDocsExample}
 * (see {@code rust-data-processing-jvm} tests) when {@code rdp_jvm_sys} is present.
 */
public final class DataFrameCentricPipeline {

  private DataFrameCentricPipeline() {}

  /** Same logical schema as the Python snippet; serde uses Pascal-case type names. */
  public static JSONObject exampleSchema() {
    JSONArray fields =
        new JSONArray()
            .put(new JSONObject().put("name", "id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "active").put("data_type", "Bool"))
            .put(new JSONObject().put("name", "score").put("data_type", "Float64"));
    return new JSONObject().put("fields", fields);
  }

  /** JSON array-of-objects matching {@link #exampleSchema()} — same rows as the Python example. */
  public static JSONArray exampleRowsJson() {
    return new JSONArray()
        .put(new JSONObject().put("id", 1).put("active", true).put("score", 10.0))
        .put(new JSONObject().put("id", 2).put("active", true).put("score", 20.0))
        .put(new JSONObject().put("id", 3).put("active", false).put("score", 30.0));
  }

  /**
   * Equivalent to {@code filter_eq("active", True).multiply_f64("score", 2.0).collect()} on the
   * ingested frame (two rows: ids 1 and 2 with doubled scores).
   */
  public static String transformSql() {
    return "SELECT id, active, (score * 2.0) AS score FROM df WHERE active = TRUE ORDER BY id";
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      Path work = Files.createTempDirectory("rdp_dataframe_centric_demo_");
      try {
        Path jsonPath = work.resolve("events.json");
        Path parquetPath = work.resolve("out.parquet");
        Files.writeString(jsonPath, exampleRowsJson().toString(), StandardCharsets.UTF_8);

        JSONObject payload =
            new JSONObject()
                .put("pipeline_spec_version", 1)
                .put(
                    "sources",
                    new JSONObject()
                        .put("paths", new JSONArray().put(jsonPath.toAbsolutePath().normalize().toString()))
                        .put("schema", exampleSchema())
                        .put("options", new JSONObject().put("format", "json")))
                .put("transform", new JSONObject().put("sql", transformSql()))
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
        JSONArray sinkResults = interchange.getJSONArray("sink_results");
        JSONObject parquetSink = sinkResults.getJSONObject(0);
        if (!"ok".equals(parquetSink.getString("status"))) {
          throw new AssertionError("sink not ok: " + parquetSink);
        }
        int rowCount = parquetSink.getInt("row_count");
        if (rowCount != 2) {
          throw new AssertionError("expected row_count == 2 (Python assert out.row_count() == 2), got " + rowCount);
        }
        System.out.println("DataFrame-centric pipeline (Polars SQL via rdp_run_pipeline_json): ok");
        System.out.println("sink parquet row_count=" + rowCount + " path=" + parquetSink.getString("path"));
      } finally {
        try (var walk = Files.walk(work)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      }
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
