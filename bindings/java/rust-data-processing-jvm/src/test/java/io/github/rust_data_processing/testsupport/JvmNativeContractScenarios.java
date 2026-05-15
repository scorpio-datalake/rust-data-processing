package io.github.rust_data_processing.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import io.github.rust_data_processing.support.RdpJvmSysTestSupport;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared native-call scenarios for {@code FfiExportedSymbolsContractTest} (manifest-driven symbol
 * checks) and {@code io.github.rust_data_processing.docexamples.DocsExampleNativeIntegrationTest}
 * (doc-aligned integration tests).
 */
public final class JvmNativeContractScenarios {

  private JvmNativeContractScenarios() {}

  /**
   * Committed inputs under {@code tests/fixtures/} (e.g. {@code jvm_contract_three_rows.json}) so
   * developers can open the same files RDP ingests in contract tests.
   */
  public static Path requireJvmContractFixture(String filename) {
    Assumptions.assumeTrue(
        RdpJvmSysTestSupport.resolveTestsFixturesDir().isPresent(),
        "tests/fixtures not discoverable — set GITHUB_WORKSPACE or run from a checkout where"
            + " tests/fixtures/people.csv exists relative to an ancestor of the JVM cwd");
    Optional<Path> p = RdpJvmSysTestSupport.resolveFixtureFile(filename);
    Assumptions.assumeTrue(
        p.isPresent(),
        "Missing tests/fixtures/" + filename + " — add JVM contract fixtures with the repository");
    return p.get();
  }

  /**
   * Same Polars SQL + Parquet sink shape as {@code docs/java/examples/DataFrameCentricPipeline.java}
   * (Python {@code DataFrame.filter_eq(...).multiply_f64(...).collect()} expressed as SQL on {@code df}).
   */
  public static void runPipelineJsonDataFrameCentricSqlContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path inputJson = requireJvmContractFixture("jvm_contract_three_rows.json");
    Path work = Files.createTempDirectory("rdp_contract_dataframe_centric_");
    try {
      Path parquetPath = work.resolve("out.parquet");

      JSONObject schema =
          new JSONObject()
              .put(
                  "fields",
                  new JSONArray()
                      .put(new JSONObject().put("name", "id").put("data_type", "Int64"))
                      .put(new JSONObject().put("name", "active").put("data_type", "Bool"))
                      .put(new JSONObject().put("name", "score").put("data_type", "Float64")));

      JSONObject payload =
          new JSONObject()
              .put("pipeline_spec_version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put(
                          "paths",
                          new JSONArray().put(inputJson.toString()))
                      .put("schema", schema)
                      .put("options", new JSONObject().put("format", "json")))
              .put(
                  "transform",
                  new JSONObject()
                      .put(
                          "sql",
                          "SELECT id, active, (score * 2.0) AS score FROM df WHERE active = TRUE ORDER BY id"))
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
      JSONObject inter = root.getJSONObject("interchange");
      assertEquals("run_pipeline_json", inter.getString("kind"));
      assertEquals(3, inter.getInt("ingested_row_count"));
      JSONObject sink = inter.getJSONArray("sink_results").getJSONObject(0);
      assertEquals("ok", sink.getString("status"));
      assertEquals(2, sink.getInt("row_count"));
      assertTrue(Files.exists(parquetPath));
    } finally {
      try (var walk = Files.walk(work)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  /**
   * Same as {@code docs/java/examples/SQLQueries.java}: Python {@code sql_query_dataset} on {@code df}
   * → {@code rdp_run_pipeline_json} {@code transform.sql} + Parquet sink.
   */
  public static void runPipelineJsonSqlQueryDatasetContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path inputJson = requireJvmContractFixture("jvm_contract_three_rows.json");
    Path work = Files.createTempDirectory("rdp_contract_sql_query_dataset_");
    try {
      Path parquetPath = work.resolve("out.parquet");

      JSONObject schema =
          new JSONObject()
              .put(
                  "fields",
                  new JSONArray()
                      .put(new JSONObject().put("name", "id").put("data_type", "Int64"))
                      .put(new JSONObject().put("name", "active").put("data_type", "Bool"))
                      .put(new JSONObject().put("name", "score").put("data_type", "Float64")));

      String sql = "SELECT id, score FROM df WHERE active = TRUE ORDER BY id DESC LIMIT 10";
      JSONObject payload =
          new JSONObject()
              .put("pipeline_spec_version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put(
                          "paths",
                          new JSONArray().put(inputJson.toString()))
                      .put("schema", schema)
                      .put("options", new JSONObject().put("format", "json")))
              .put("transform", new JSONObject().put("sql", sql))
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
      JSONObject inter = root.getJSONObject("interchange");
      assertEquals("run_pipeline_json", inter.getString("kind"));
      assertEquals(3, inter.getInt("ingested_row_count"));
      JSONObject sink = inter.getJSONArray("sink_results").getJSONObject(0);
      assertEquals("ok", sink.getString("status"));
      assertEquals(2, sink.getInt("row_count"));
      assertTrue(Files.exists(parquetPath));
    } finally {
      try (var walk = Files.walk(work)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  /** {@code rdp_parity_sql_suite_mirror} — JOIN payload aligned with Python {@code SqlContext} parity. */
  public static void sqlSuiteMirrorJoinContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_sql_suite_mirror");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertSqlSuiteMirror(root.getJSONObject("interchange"));
  }

  /**
   * Same path + assertions as {@code docs/java/examples/ExcelSnippets#excelIngestSheet} against
   * {@code tests/fixtures/people.xlsx} (sheet {@code Sheet1}).
   */
  public static void excelIngestPathSheetContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Optional<Path> fixtures = RdpJvmSysTestSupport.resolveTestsFixturesDir();
    Assumptions.assumeTrue(
        fixtures.isPresent(),
        "Skip Excel FFI when repo tests/fixtures is not discoverable (set GITHUB_WORKSPACE or run from repo).");
    Path excelPath = fixtures.get().resolve("people.xlsx");
    Assumptions.assumeTrue(
        Files.isRegularFile(excelPath),
        "Skip when tests/fixtures/people.xlsx is missing; generate: cargo run --features"
            + " excel_test_writer --bin generate_people_xlsx_fixture");
    JSONObject root =
        RdpNativeJson.excelIngestPathSheet(
            linker, lookup, arena, excelPath.toString(), "Sheet1");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("excel_ingest_sheet", interchange.getString("kind"));
    assertEquals(2, interchange.getJSONObject("dataset").getJSONArray("rows").length());
  }

  public static void runPipelineJsonContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Path part1 = requireJvmContractFixture("jvm_contract_pipeline_part_01.json");
    Path part2 = requireJvmContractFixture("jvm_contract_pipeline_part_02.json");
    Path work = Files.createTempDirectory("rdp_contract_run_pipeline_");
    try {
      Path outParquet = work.resolve("out.parquet");
      String abspath1 = part1.toString();
      String abspath2 = part2.toString();
      String schemaJson =
          "{\"fields\":["
              + "{\"name\":\"id\",\"data_type\":\"Int64\"},"
              + "{\"name\":\"name\",\"data_type\":\"Utf8\"}"
              + "]}";
      JSONObject payload =
          new JSONObject()
              .put("version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put("paths", new JSONArray().put(abspath1).put(abspath2))
                      .put("schema", new JSONObject(schemaJson))
                      .put("options", new JSONObject().put("format", "json")))
              .put("transform", new JSONObject().put("sql", "SELECT * FROM df ORDER BY id"))
              .put(
                  "sinks",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("kind", "parquet_file")
                              .put("path", outParquet.toAbsolutePath().toString())));
      JSONObject root =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload.toString());
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      assertEquals("run_pipeline_json", inter.getString("kind"));
      assertEquals(2, inter.getInt("ingested_row_count"));
      assertTrue(Files.exists(outParquet), "pipeline parquet sink missing: " + outParquet);
      assertEquals(
          "ok",
          inter.getJSONArray("sink_results").getJSONObject(0).getString("status"));
      assertTrue(inter.has("orchestration"));
      assertEquals(1, inter.getJSONObject("orchestration").getInt("pipeline_spec_version"));
      Files.deleteIfExists(outParquet);
    } finally {
      try (var walk = Files.walk(work)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  public static void ingestOrderedPathsContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Path p1 = requireJvmContractFixture("jvm_contract_ordered_part_a.csv");
    Path p2 = requireJvmContractFixture("jvm_contract_ordered_part_b.csv");
    String abspath1 = p1.toAbsolutePath().normalize().toString();
    String abspath2 = p2.toAbsolutePath().normalize().toString();
    Path parquetOut = null;
    Path arrowOut = null;
    try {
      String schemaJson =
          "{\"fields\":["
              + "{\"name\":\"id\",\"data_type\":\"Int64\"},"
              + "{\"name\":\"name\",\"data_type\":\"Utf8\"}"
              + "]}";

      JSONObject payloadDataset =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "dataset").put("max_rows", 50));
      JSONObject rootDataset =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadDataset.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootDataset);
      JSONObject interD = rootDataset.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_dataset", interD.getString("kind"));
      assertEquals(2, interD.getInt("total_row_count"));
      assertEquals(2, interD.getInt("returned_row_count"));
      assertFalse(interD.getBoolean("truncated"));

      JSONObject payloadTruncate =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "dataset").put("max_rows", 1));
      JSONObject rootTruncate =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadTruncate.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootTruncate);
      JSONObject interT = rootTruncate.getJSONObject("interchange");
      assertTrue(interT.getBoolean("truncated"));
      assertEquals(1, interT.getInt("returned_row_count"));
      assertEquals(2, interT.getInt("total_row_count"));

      JSONObject payloadParquet =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "parquet_temp"));
      JSONObject rootParquet =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadParquet.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootParquet);
      JSONObject interP = rootParquet.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_parquet_temp", interP.getString("kind"));
      parquetOut = Path.of(interP.getString("path"));
      assertTrue(Files.exists(parquetOut));

      JSONObject payloadArrow =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "arrow_ipc_temp"));
      JSONObject rootArrow =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadArrow.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootArrow);
      JSONObject interA = rootArrow.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_arrow_ipc_temp", interA.getString("kind"));
      arrowOut = Path.of(interA.getString("path"));
      assertTrue(Files.exists(arrowOut));
    } finally {
      if (parquetOut != null) {
        Files.deleteIfExists(parquetOut);
      }
      if (arrowOut != null) {
        Files.deleteIfExists(arrowOut);
      }
    }
  }
}
