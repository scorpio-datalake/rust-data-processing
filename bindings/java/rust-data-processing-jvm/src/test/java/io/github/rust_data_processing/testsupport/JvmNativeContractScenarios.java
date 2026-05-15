package io.github.rust_data_processing.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.json.SerdeDatasetRows;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import io.github.rust_data_processing.support.RdpJvmSysTestSupport;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared native-call scenarios for {@code FfiExportedSymbolsContractTest} (manifest-driven symbol
 * checks) and {@code io.github.rust_data_processing.docexamples.DocsExampleNativeIntegrationTest}
 * (doc-aligned integration tests).
 *
 * <p>Pipeline specs and schemas load from {@code tests/fixtures/<bundle>/} (see {@code
 * PipelineFixtureSupport}).
 */
public final class JvmNativeContractScenarios {

  private JvmNativeContractScenarios() {}

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

  private static Path requireBundle(String name) {
    return PipelineFixtureSupport.resolveBundleRoot(name)
        .orElseThrow(() -> new IllegalStateException("tests/fixtures/" + name + " not found"));
  }

  public static void runPipelineJsonDataFrameCentricSqlContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = requireBundle("jvm_contract");
    Path inputJson = requireJvmContractFixture("jvm_contract_three_rows.json");
    Path work = Files.createTempDirectory("rdp_contract_dataframe_centric_");
    try {
      Path parquetPath = work.resolve("out.parquet");
      String payload =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/dataframe_centric_sql.pipeline.json",
              Map.of(
                  "SOURCE_PATH", inputJson.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", parquetPath.toAbsolutePath().normalize().toString()));

      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      assertEquals("run_pipeline_json", inter.getString("kind"));
      assertEquals(3, inter.getInt("ingested_row_count"));
      JSONObject sink = inter.getJSONArray("sink_results").getJSONObject(0);
      assertEquals("ok", sink.getString("status"));
      assertEquals(2, sink.getInt("row_count"));
      assertTrue(Files.exists(parquetPath));
    } finally {
      deleteTree(work);
    }
  }

  public static void runPipelineJsonSqlQueryDatasetContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = requireBundle("jvm_contract");
    Path inputJson = requireJvmContractFixture("jvm_contract_three_rows.json");
    Path work = Files.createTempDirectory("rdp_contract_sql_query_dataset_");
    try {
      Path parquetPath = work.resolve("out.parquet");
      String payload =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/sql_query_dataset.pipeline.json",
              Map.of(
                  "SOURCE_PATH", inputJson.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", parquetPath.toAbsolutePath().normalize().toString()));

      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      assertEquals("run_pipeline_json", inter.getString("kind"));
      assertEquals(3, inter.getInt("ingested_row_count"));
      JSONObject sink = inter.getJSONArray("sink_results").getJSONObject(0);
      assertEquals("ok", sink.getString("status"));
      assertEquals(2, sink.getInt("row_count"));
      assertTrue(Files.exists(parquetPath));
    } finally {
      deleteTree(work);
    }
  }

  public static void sqlSuiteMirrorJoinContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_sql_suite_mirror");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertSqlSuiteMirror(root.getJSONObject("interchange"));
  }

  public static void excelIngestPathSheetContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Optional<Path> excelPath = RdpJvmSysTestSupport.resolveFixtureFile("people.xlsx");
    Assumptions.assumeTrue(
        excelPath.isPresent(),
        "Missing tests/fixtures/people.xlsx — from repo root: python scripts/write_people_xlsx_stdlib.py"
            + " or: cargo run --features excel_test_writer --bin generate_people_xlsx_fixture");
    JSONObject root =
        RdpNativeJson.excelIngestPathSheet(
            linker, lookup, arena, excelPath.get().toString(), "Sheet1");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("excel_ingest_sheet", interchange.getString("kind"));
    assertEquals(2, interchange.getJSONObject("dataset").getJSONArray("rows").length());
  }

  public static void runPipelineJsonContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Path bundle = requireBundle("jvm_contract");
    Path part1 = requireJvmContractFixture("jvm_contract_pipeline_part_01.json");
    Path part2 = requireJvmContractFixture("jvm_contract_pipeline_part_02.json");
    Path work = Files.createTempDirectory("rdp_contract_run_pipeline_");
    try {
      Path outParquet = work.resolve("out.parquet");
      String payload =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/ordered_json_to_parquet.pipeline.json",
              Map.of(
                  "SOURCE_PATH_A", part1.toAbsolutePath().normalize().toString(),
                  "SOURCE_PATH_B", part2.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", outParquet.toAbsolutePath().normalize().toString()));

      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
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
    } finally {
      deleteTree(work);
    }
  }

  public static void ingestOrderedPathsContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Path bundle = requireBundle("jvm_contract");
    Path p1 = requireJvmContractFixture("jvm_contract_ordered_part_a.csv");
    Path p2 = requireJvmContractFixture("jvm_contract_ordered_part_b.csv");
    String abspath1 = p1.toAbsolutePath().normalize().toString();
    String abspath2 = p2.toAbsolutePath().normalize().toString();
    Path parquetOut = null;
    Path arrowOut = null;
    try {
      String payloadDataset =
          PipelineFixtureSupport.resolvePayloadJson(
              bundle,
              "payloads/ordered_paths_dataset.payload.json",
              Map.of("PATH_A", abspath1, "PATH_B", abspath2));
      JSONObject rootDataset =
          RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payloadDataset);
      PytestMirrorAssertions.assertEnvelopeOk(rootDataset);
      JSONObject interD = rootDataset.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_dataset", interD.getString("kind"));
      assertEquals(2, interD.getInt("total_row_count"));
      assertEquals(2, interD.getInt("returned_row_count"));
      assertFalse(interD.getBoolean("truncated"));

      String payloadTruncate =
          PipelineFixtureSupport.resolvePayloadJson(
              bundle,
              "payloads/ordered_paths_truncate.payload.json",
              Map.of("PATH_A", abspath1, "PATH_B", abspath2));
      JSONObject rootTruncate =
          RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payloadTruncate);
      PytestMirrorAssertions.assertEnvelopeOk(rootTruncate);
      JSONObject interT = rootTruncate.getJSONObject("interchange");
      assertTrue(interT.getBoolean("truncated"));
      assertEquals(1, interT.getInt("returned_row_count"));
      assertEquals(2, interT.getInt("total_row_count"));

      String payloadParquet =
          PipelineFixtureSupport.resolvePayloadJson(
              bundle,
              "payloads/ordered_paths_parquet_temp.payload.json",
              Map.of("PATH_A", abspath1, "PATH_B", abspath2));
      JSONObject rootParquet =
          RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payloadParquet);
      PytestMirrorAssertions.assertEnvelopeOk(rootParquet);
      JSONObject interP = rootParquet.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_parquet_temp", interP.getString("kind"));
      parquetOut = Path.of(interP.getString("path"));
      assertTrue(Files.exists(parquetOut));

      String payloadArrow =
          PipelineFixtureSupport.resolvePayloadJson(
              bundle,
              "payloads/ordered_paths_arrow_ipc_temp.payload.json",
              Map.of("PATH_A", abspath1, "PATH_B", abspath2));
      JSONObject rootArrow =
          RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payloadArrow);
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

  public static void runGhcnJsonXmlParquetPipelineContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = PipelineFixtureSupport.resolveBundleRoot("ghcn")
        .orElseThrow(() -> new IllegalStateException("tests/fixtures/ghcn not found"));
    Path jsonInput = requireJvmContractFixture("ghcn/ghcn_stations_sample.json");
    Path work = Files.createTempDirectory("rdp_contract_ghcn_json_xml_parquet_");
    Path xmlPath = work.resolve("stations.xml");
    Path parquetPath = work.resolve("stations.parquet");
    try {
      String jsonToXmlPayload =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/json_to_xml.pipeline.json",
              Map.of(
                  "SOURCE_PATH", jsonInput.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", xmlPath.toAbsolutePath().normalize().toString()));

      JSONObject xmlStage =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, jsonToXmlPayload);
      PytestMirrorAssertions.assertEnvelopeOk(xmlStage);
      JSONObject xmlSink = xmlStage.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
      assertEquals("xml_file", xmlSink.getString("kind"));
      assertEquals("ok", xmlSink.getString("status"));
      assertEquals(5, xmlSink.getInt("row_count"));
      assertTrue(Files.exists(xmlPath), "XML sink file missing: " + xmlPath);

      String xmlSchemaJson =
          PipelineFixtureSupport.loadSchemaJson(bundle, "schemas/xml_intermediate.schema.json");
      String pathIngestOpts = PipelineFixtureSupport.defaultPathIngestOptionsJson();
      JSONObject xmlVerify =
          RdpNativeJson.invokeIngestXmlPath(
              linker, lookup, arena, xmlPath.toString(), xmlSchemaJson, pathIngestOpts);
      PytestMirrorAssertions.assertEnvelopeOk(xmlVerify);
      assertEquals(
          "ingest_path_xml", xmlVerify.getJSONObject("interchange").getString("kind"));
      assertEquals(
          5, xmlVerify.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length());

      String xmlToParquetPayload =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/xml_to_parquet.pipeline.json",
              Map.of(
                  "SOURCE_PATH", xmlPath.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", parquetPath.toAbsolutePath().normalize().toString()));

      JSONObject parquetStage =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, xmlToParquetPayload);
      PytestMirrorAssertions.assertEnvelopeOk(parquetStage);
      JSONObject parquetSink =
          parquetStage.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
      assertEquals("parquet_file", parquetSink.getString("kind"));
      assertEquals("ok", parquetSink.getString("status"));
      assertEquals(5, parquetSink.getInt("row_count"));
      assertTrue(Files.exists(parquetPath));

      String parquetSchemaJson =
          PipelineFixtureSupport.loadSchemaJson(bundle, "schemas/parquet_lake.schema.json");
      JSONObject parquetVerify =
          RdpNativeJson.invokeIngestParquetPath(
              linker, lookup, arena, parquetPath.toString(), parquetSchemaJson, pathIngestOpts);
      PytestMirrorAssertions.assertEnvelopeOk(parquetVerify);
      JSONArray rows =
          parquetVerify.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows");
      assertEquals(5, rows.length());
      JSONArray firstRow = rows.getJSONArray(0);
      assertEquals("ACW00011604", SerdeDatasetRows.utf8(firstRow, 0));
      assertEquals(17.1167, SerdeDatasetRows.float64(firstRow, 1), 0.0001);
    } finally {
      deleteTree(work);
    }
  }

  private static void deleteTree(Path root) throws Exception {
    try (var walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
