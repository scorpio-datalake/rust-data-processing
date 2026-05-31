package io.github.scorpio_datalake.rust_data_processing.testsupport;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.json.SerdeDatasetRows;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import io.github.scorpio_datalake.rust_data_processing.support.RdpJvmSysTestSupport;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;

/**
 * Shared native-call scenarios for JVM FFI tests.
 *
 * <p><strong>Why this class exists.</strong> Doc examples under {@code docs/java/examples/} are not
 * on the test classpath. This class is the <em>executable contract</em> those examples describe:
 * same fixtures, same {@link RdpNativeJson} downcalls, concrete assertions. {@code
 * DocsExampleNativeIntegrationTest} stays thin so each failure names one doc file; {@code
 * FfiExportedSymbolsContractTest} reuses subsets for manifest-wide smoke.
 *
 * <p><strong>What it tests.</strong> End-to-end behavior (row counts, {@code interchange.kind},
 * file sinks on disk), not Java business logic — Java must not parse CSV/Excel itself. Pipeline
 * specs and schemas load from {@code tests/fixtures/<bundle>/} (see {@code
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

  /**
   * {@code docs/java/examples/ExcelSnippets#excelIngestViaPayload}: {@code
   * payloads/excel_sheet_dataset.payload.json} on {@code people.xlsx}.
   */
  public static void runExcelSnippetsViaPayloadContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = requireBundle("people");
    Path workbook =
        RdpJvmSysTestSupport.resolveFixtureFile("people.xlsx")
            .orElseThrow(() -> new IllegalStateException("tests/fixtures/people.xlsx missing"));
    String payload =
        PipelineFixtureSupport.resolvePayloadJson(
            bundle,
            "payloads/excel_sheet_dataset.payload.json",
            Map.of(
                "SOURCE_PATH",
                workbook.toAbsolutePath().normalize().toString(),
                "SHEET_NAME",
                "Sheet1"));
    JSONObject root = RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertEquals(
        "ingest_ordered_paths_dataset", root.getJSONObject("interchange").getString("kind"));
    assertEquals(
        2,
        root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length());
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

  /**
   * {@code docs/java/examples/ExcelSnippets}: payload ingest + {@code rdp_excel_ingest_path_sheet}.
   */
  public static void runExcelSnippetsPeopleContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    runExcelSnippetsViaPayloadContract(linker, lookup, arena);
    excelIngestPathSheetContract(linker, lookup, arena);
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
      assertEquals("ok", inter.getJSONArray("sink_results").getJSONObject(0).getString("status"));
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
    Path bundle =
        PipelineFixtureSupport.resolveBundleRoot("ghcn")
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
      JSONObject xmlSink =
          xmlStage.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
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
      assertEquals("ingest_path_xml", xmlVerify.getJSONObject("interchange").getString("kind"));
      assertEquals(
          5,
          xmlVerify
              .getJSONObject("interchange")
              .getJSONObject("dataset")
              .getJSONArray("rows")
              .length());

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

  /**
   * {@code docs/java/examples/RDPOnlyETLExample}: legacy three-path pipeline over committed {@code
   * student_etl/data/part-*.json}.
   */
  public static void runStudentEtlLegacyThreePathsContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = requireBundle("student_etl");
    Path p0 = bundle.resolve("data/part-00000.json");
    Path p1 = bundle.resolve("data/part-00001.json");
    Path p2 = bundle.resolve("data/part-00002.json");
    Assumptions.assumeTrue(
        Files.isRegularFile(p0) && Files.isRegularFile(p1) && Files.isRegularFile(p2),
        "Missing tests/fixtures/student_etl/data/part-0000*.json");

    String payload =
        PipelineFixtureSupport.resolvePipelineJson(
            bundle,
            "pipelines/legacy_student_etl_three_paths.pipeline.json",
            Map.of(
                "PATH_A", p0.toAbsolutePath().normalize().toString(),
                "PATH_B", p1.toAbsolutePath().normalize().toString(),
                "PATH_C", p2.toAbsolutePath().normalize().toString()));

    JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject inter = root.getJSONObject("interchange");
    assertEquals(3, inter.getInt("ingested_row_count"));
    assertTrue(inter.has("declared_staging_schemas"));
    JSONArray sinks = inter.getJSONArray("sink_results");
    assertEquals(3, sinks.length());
    assertEquals("delta_lake", sinks.getJSONObject(0).getString("kind"));
    assertEquals("DELTA_LAKE_CONNECTOR_PENDING", sinks.getJSONObject(0).getString("error_code"));
  }

  /**
   * {@code docs/java/examples/RDPOnlyETLExample}: ordered ingest over two committed parts via
   * {@code ordered_ingest_dataset_2paths.payload.json}.
   */
  public static void runStudentEtlOrderedIngestTwoPartsContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = requireBundle("student_etl");
    Path p0 = bundle.resolve("data/part-00000.json");
    Path p1 = bundle.resolve("data/part-00001.json");
    Assumptions.assumeTrue(
        Files.isRegularFile(p0) && Files.isRegularFile(p1),
        "Missing tests/fixtures/student_etl/data/part-0000*.json");

    String payload =
        PipelineFixtureSupport.resolvePayloadJson(
            bundle,
            "payloads/ordered_ingest_dataset_2paths.payload.json",
            Map.of(
                "PATH_A", p0.toAbsolutePath().normalize().toString(),
                "PATH_B", p1.toAbsolutePath().normalize().toString()));

    JSONObject root = RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject inter = root.getJSONObject("interchange");
    assertEquals("ingest_ordered_paths_dataset", inter.getString("kind"));
    assertEquals(2, inter.getJSONObject("dataset").getJSONArray("rows").length());
    assertEquals(2, inter.getInt("total_row_count"));
  }

  /**
   * {@code docs/java/examples/OrderedPaths}: recursive directory scan ({@code
   * pathsFromDirectoryScan} with a relative CSV glob) + {@code csv_watermark_ingest.body.json} →
   * {@code rdp_ingest_ordered_paths_json} (parity with {@code
   * tests/path_from_directory_scan_fixtures.rs}).
   */
  public static void runOrderedPathsDirectoryScanWatermarkContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path bundle = requireBundle("watermark");
    Path incoming = Files.createTempDirectory("rdp_contract_ordered_paths_");
    try {
      Path nested = incoming.resolve("nested");
      Files.createDirectories(nested);
      Path a = incoming.resolve("a.csv");
      Path b = nested.resolve("b.csv");
      Files.writeString(a, "id,ts\n1,50\n2,99\n");
      Files.writeString(b, "id,ts\n3,150\n4,200\n");

      List<Path> scanned = pathsFromDirectoryScanGlob(incoming, "**/*.csv");
      assertEquals(2, scanned.size());
      assertEquals("a.csv", scanned.get(0).getFileName().toString());
      assertTrue(scanned.get(1).toString().replace('\\', '/').contains("nested/b.csv"));

      JSONArray pathJson = new JSONArray();
      for (Path p : scanned) {
        pathJson.put(p.toAbsolutePath().normalize().toString());
      }

      JSONObject body =
          new JSONObject(
              PipelineFixtureSupport.resolvePayloadJson(
                  bundle, "payloads/csv_watermark_ingest.body.json", Map.of()));
      body.put("paths", pathJson);

      JSONObject root =
          RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, body.toString());
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_dataset", inter.getString("kind"));
      assertEquals(2, inter.getInt("returned_row_count"));
      JSONArray rows = inter.getJSONObject("dataset").getJSONArray("rows");
      assertEquals(2, rows.length());
      assertEquals(3L, rows.getJSONArray(0).getJSONObject(0).getLong("Int64"));
      assertEquals(4L, rows.getJSONArray(1).getJSONObject(0).getLong("Int64"));
      JSONObject batch = inter.getJSONObject("ordered_batch");
      assertEquals(2, batch.getJSONArray("paths").length());
      assertTrue(batch.getString("last_path").contains("b.csv"));
      assertTrue(batch.has("max_watermark_value"));
      assertEquals(200L, batch.getJSONObject("max_watermark_value").getLong("Int64"));
    } finally {
      deleteTree(incoming);
    }
  }

  /** Back-compat name for {@link #runOrderedPathsDirectoryScanWatermarkContract}. */
  public static void runPathFromDirectoryScanWatermarkContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    runOrderedPathsDirectoryScanWatermarkContract(linker, lookup, arena);
  }

  /** Same glob semantics as {@code docs/java/examples/OrderedPaths#pathsFromDirectoryScan}. */
  private static List<Path> pathsFromDirectoryScanGlob(Path root, String relativeGlob)
      throws Exception {
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("not a directory: " + root);
    }
    var fs = root.getFileSystem();
    var matcher = fs.getPathMatcher("glob:" + relativeGlob);
    java.nio.file.PathMatcher rootMatcher = null;
    if (relativeGlob.startsWith("**/")) {
      rootMatcher = fs.getPathMatcher("glob:" + relativeGlob.substring(3));
    }
    List<Path> out = new java.util.ArrayList<>();
    java.nio.file.PathMatcher rootFileMatcher = rootMatcher;
    try (var stream = Files.walk(root)) {
      stream
          .filter(Files::isRegularFile)
          .forEach(
              p -> {
                Path rel = root.relativize(p);
                if (matcher.matches(rel)
                    || (rootFileMatcher != null && rootFileMatcher.matches(rel))) {
                  out.add(p);
                }
              });
    }
    out.sort(java.util.Comparator.naturalOrder());
    return out;
  }

  /**
   * {@code docs/java/examples/JsonParquetExcelSnippets}: people JSON/CSV payloads, path ingest, and
   * csv→parquet pipeline round-trip (same fixtures as {@code ParquetSnippets} for Parquet).
   */
  public static void runJsonParquetExcelSnippetsPeopleContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path jsonPath = fixtures.resolve("people.json");
    Path csvPath = fixtures.resolve("people.csv");
    Assumptions.assumeTrue(
        Files.isRegularFile(jsonPath) && Files.isRegularFile(csvPath),
        "Skip when tests/fixtures/people.json or people.csv missing");
    Path bundle = requireBundle("people");

    String jsonPayload =
        PipelineFixtureSupport.resolvePayloadJson(
            bundle,
            "payloads/json_path_dataset.payload.json",
            Map.of("SOURCE_PATH", jsonPath.toAbsolutePath().normalize().toString()));
    JSONObject jsonRoot =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, jsonPayload);
    PytestMirrorAssertions.assertEnvelopeOk(jsonRoot);
    assertEquals(
        2,
        jsonRoot
            .getJSONObject("interchange")
            .getJSONObject("dataset")
            .getJSONArray("rows")
            .length());

    String csvPayload =
        PipelineFixtureSupport.resolvePayloadJson(
            bundle,
            "payloads/csv_path_dataset.payload.json",
            Map.of("SOURCE_PATH", csvPath.toAbsolutePath().normalize().toString()));
    JSONObject csvRoot =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, csvPayload);
    PytestMirrorAssertions.assertEnvelopeOk(csvRoot);
    assertEquals(
        2,
        csvRoot
            .getJSONObject("interchange")
            .getJSONObject("dataset")
            .getJSONArray("rows")
            .length());

    String jsonSchema =
        PipelineFixtureSupport.loadSchemaJson(bundle, "schemas/people_json.schema.json");
    JSONObject jsonPathRoot =
        RdpNativeJson.invokeIngestJsonPath(
            linker,
            lookup,
            arena,
            jsonPath.toString(),
            jsonSchema,
            PipelineFixtureSupport.readBundleUtf8(
                bundle, "payloads/json_path_ingest.options.json"));
    PytestMirrorAssertions.assertEnvelopeOk(jsonPathRoot);
    assertEquals(
        2,
        jsonPathRoot
            .getJSONObject("interchange")
            .getJSONObject("dataset")
            .getJSONArray("rows")
            .length());

    String csvSchema =
        PipelineFixtureSupport.loadSchemaJson(bundle, "schemas/people_csv.schema.json");
    JSONObject csvPathRoot =
        RdpNativeJson.invokeIngestCsvPath(
            linker,
            lookup,
            arena,
            csvPath.toString(),
            csvSchema,
            PipelineFixtureSupport.readBundleUtf8(bundle, "payloads/csv_path_ingest.options.json"));
    PytestMirrorAssertions.assertEnvelopeOk(csvPathRoot);
    assertEquals(
        2,
        csvPathRoot
            .getJSONObject("interchange")
            .getJSONObject("dataset")
            .getJSONArray("rows")
            .length());

    runParquetSnippetsCsvToParquetRoundTripContract(linker, lookup, arena);
  }

  /**
   * {@code docs/java/examples/ParquetSnippets}: {@code
   * people/pipelines/csv_to_parquet.pipeline.json} then {@code rdp_ingest_parquet_path} with {@code
   * people_flat.schema.json}.
   */
  public static void runParquetSnippetsCsvToParquetRoundTripContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path csv = fixtures.resolve("people.csv");
    Assumptions.assumeTrue(Files.isRegularFile(csv), "Skip when tests/fixtures/people.csv missing");
    Path bundle = requireBundle("people");

    Path work = Files.createTempDirectory("rdp_contract_parquet_snippets_");
    try {
      Path parquet = work.resolve("people.parquet");
      String pipeline =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/csv_to_parquet.pipeline.json",
              Map.of(
                  "SOURCE_PATH", csv.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", parquet.toAbsolutePath().normalize().toString()));

      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject sink =
          root.getJSONObject("interchange").getJSONArray("sink_results").getJSONObject(0);
      assertEquals("parquet_file", sink.getString("kind"));
      assertEquals("ok", sink.getString("status"));
      assertEquals(2, sink.getInt("row_count"));
      assertTrue(Files.isRegularFile(parquet), "parquet missing: " + parquet);

      String flatSchema =
          PipelineFixtureSupport.loadSchemaJson(bundle, "schemas/people_flat.schema.json");
      JSONObject ingest =
          RdpNativeJson.invokeIngestParquetPath(
              linker,
              lookup,
              arena,
              parquet.toString(),
              flatSchema,
              PipelineFixtureSupport.defaultPathIngestOptionsJson());
      PytestMirrorAssertions.assertEnvelopeOk(ingest);
      assertEquals("ingest_path_parquet", ingest.getJSONObject("interchange").getString("kind"));
      assertEquals(
          2,
          ingest
              .getJSONObject("interchange")
              .getJSONObject("dataset")
              .getJSONArray("rows")
              .length());
    } finally {
      deleteTree(work);
    }
  }

  /** {@code ParquetSnippets#exportParquetTempEnvelope} — {@code rdp_export_parquet_temp}. */
  public static void runParquetSnippetsExportTempContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("parquet_export_temp", interchange.getString("kind"));
    assertEquals(2, interchange.getInt("row_count"));
    Path parquetPath = Path.of(interchange.getString("path"));
    assertTrue(Files.exists(parquetPath), "parquet file missing: " + parquetPath);
    Files.deleteIfExists(parquetPath);
  }

  /**
   * Shared Phase 2 export/privacy/truncate/jsonl parity ({@code
   * rdp_parity_export_privacy_reports}).
   */
  private static JSONObject invokeExportPrivacyReportsPhase2(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(
            linker, lookup, arena, "rdp_parity_export_privacy_reports");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("export_privacy_reports_phase2", interchange.getString("kind"));
    return interchange;
  }

  /**
   * Doc: {@code ExportJsonlTrainTest.java} (Phase 2 §1). Proves {@code dataset_to_jsonl} and {@code
   * train_test_row_indices} results cross FFI as {@code jsonl_preview_lines} and 80/20 index
   * lengths.
   */
  public static void runPhase2ExportJsonlTrainTestContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject interchange = invokeExportPrivacyReportsPhase2(linker, lookup, arena);
    JSONArray jsonlLines = interchange.getJSONArray("jsonl_preview_lines");
    assertTrue(jsonlLines.length() >= 1, "jsonl_preview_lines");
    assertTrue(jsonlLines.getString(0).contains("email"), "JSONL line should include email column");
    JSONObject trainTest = interchange.getJSONObject("train_test_indices_demo");
    assertEquals(80, trainTest.getInt("train_len"));
    assertEquals(20, trainTest.getInt("test_len"));
  }

  /**
   * Doc: {@code PrivacyDiffReports.java} (§3). Proves {@code privacy_report_json} is a non-empty
   * UTF-8 column diff array ({@code column} on first row) after Rust applies an internal mask.
   */
  public static void runPhase2PrivacyDiffReportsContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject interchange = invokeExportPrivacyReportsPhase2(linker, lookup, arena);
    Object report = interchange.get("privacy_report_json");
    assertTrue(report != JSONObject.NULL && report != null, "privacy_report_json");
    if (report instanceof JSONArray arr) {
      assertTrue(arr.length() >= 1, "privacy report rows");
      assertTrue(arr.getJSONObject(0).has("column"), "Utf8ColumnChangeSummary");
    }
  }

  /**
   * Doc: {@code ReportsTruncateUtf8.java} (§4). Proves {@code reports_truncated_sample} respects
   * the 120-byte cap used in Rust parity (log/LLM-safe snippet).
   */
  public static void runPhase2ReportsTruncateUtf8Contract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject interchange = invokeExportPrivacyReportsPhase2(linker, lookup, arena);
    String truncated = interchange.getString("reports_truncated_sample");
    assertFalse(truncated.isEmpty());
    assertTrue(truncated.length() <= 120, "truncate_utf8_by_bytes(max_bytes=120) sample length");
  }

  /**
   * Doc: {@code TransformUtf8Masking.java} (§5). Proves {@code rdp_parity_transform} returns {@code
   * transform_spec_polars} with a two-row {@code dataset} until Utf8Sha256Hex parity is added.
   */
  public static void runPhase2TransformUtf8MaskingContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_transform");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("transform_spec_polars", interchange.getString("kind"));
    assertEquals(2, interchange.getJSONObject("dataset").getJSONArray("rows").length());
  }

  /**
   * Doc: {@code ValidationUtf8Length.java} (§6). Proves validation {@code summary} reports failures
   * over FFI (parity uses {@code not_null}; documents gap vs Python {@code
   * utf8_len_chars_between}).
   */
  public static void runPhase2ValidationUtf8LengthContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_validation");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject summary = root.getJSONObject("interchange").getJSONObject("summary");
    assertTrue(summary.getInt("total_checks") >= 1);
    assertTrue(summary.getInt("failed_checks") >= 1);
  }

  /**
   * Doc: {@code IngestValidateJsonlEndToEnd.java} (§9). Proves real {@code people.csv} ingest (2
   * rows), then validation + JSONL preview parity exports — the production-shaped multi-call tour.
   */
  public static void runPhase2IngestValidateJsonlEndToEndContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path csvPath = fixtures.resolve("people.csv");
    Assumptions.assumeTrue(
        Files.isRegularFile(csvPath), "Skip when tests/fixtures/people.csv missing");
    Path bundle = requireBundle("people");

    String csvPayload =
        PipelineFixtureSupport.resolvePayloadJson(
            bundle,
            "payloads/csv_path_dataset.payload.json",
            Map.of("SOURCE_PATH", csvPath.toAbsolutePath().normalize().toString()));
    JSONObject ingestRoot =
        RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, csvPayload);
    PytestMirrorAssertions.assertEnvelopeOk(ingestRoot);
    assertEquals(
        2,
        ingestRoot
            .getJSONObject("interchange")
            .getJSONObject("dataset")
            .getJSONArray("rows")
            .length());

    runPhase2ValidationUtf8LengthContract(linker, lookup, arena);

    JSONObject exportInterchange = invokeExportPrivacyReportsPhase2(linker, lookup, arena);
    assertTrue(exportInterchange.getJSONArray("jsonl_preview_lines").length() >= 1);
  }

  /** Doc: {@code QuickStartIngestExample.java} — {@code people.csv} path ingest (2 rows). */
  public static void runQuickStartIngestPeopleCsvContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path bundle = requireBundle("people");
    Path csv = fixtures.resolve("people.csv");
    Assumptions.assumeTrue(Files.isRegularFile(csv), "Skip when tests/fixtures/people.csv missing");
    String schema = PipelineFixtureSupport.loadSchemaJson(bundle, "schemas/people_csv.schema.json");
    String options =
        PipelineFixtureSupport.readBundleUtf8(bundle, "payloads/csv_path_ingest.options.json");
    JSONObject root =
        RdpNativeJson.invokeIngestCsvPath(
            linker, lookup, arena, csv.toAbsolutePath().normalize().toString(), schema, options);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    assertEquals(
        2,
        root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length());
  }

  /** Doc: {@code PartitionDiscoveryExample.java}. */
  public static void runPartitionDiscoveryMirrorContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(
            linker, lookup, arena, "rdp_parity_partition_discovery_mirror");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertPartitionDiscoveryMirror(root.getJSONObject("interchange"));
  }

  /** Doc: {@code IngestObservabilityExample.java}. */
  public static void runIngestObservabilityMirrorContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_observability_mirror");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertObservabilityMirror(root.getJSONObject("interchange"));
  }

  /** Doc: {@code ProfilingExample.java}. */
  public static void runProfilingParityContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_profiling");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("profiling_polars", interchange.getString("kind"));
    assertTrue(interchange.has("report"));
  }

  /** Doc: {@code OutlierDetectionExample.java}. */
  public static void runOutlierDetectionParityContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_outliers");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("outliers_polars", interchange.getString("kind"));
    assertTrue(interchange.getInt("outlier_count") >= 1);
  }

  /** Doc: {@code CdcBoundaryExample.java}. */
  public static void runCdcBoundaryParityContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_cdc");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject event = root.getJSONObject("interchange").getJSONObject("event");
    assertEquals("users", event.getString("table"));
    assertTrue(event.getString("op").contains("Insert"));
  }

  /** Doc: {@code ProcessingReduceExample.java}. */
  public static void runProcessingReduceParityContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_processing");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    assertEquals("processing_filter_map_reduce", interchange.getString("kind"));
    assertEquals(1, interchange.getInt("filtered_row_count"));
    assertEquals(1, interchange.getInt("mapped_row_count"));
    assertTrue(interchange.has("reduce_sum_score"));
  }

  /** Doc: {@code GroupByAggregatesExample.java} — {@code group_having} section of SQL suite. */
  public static void runGroupByAggregatesSqlSuiteContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    sqlSuiteMirrorJoinContract(linker, lookup, arena);
  }

  /** Doc: {@code SqlJoinPipelineExample.java} — JOIN section (same export as SQLQueries). */
  public static void runSqlJoinPipelineParityContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    sqlSuiteMirrorJoinContract(linker, lookup, arena);
  }

  /** Doc: {@code CookbookTransformsExample.java}. */
  public static void runCookbookMappingSpecMirrorContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root =
        RdpNativeJson.invokeParityExport(linker, lookup, arena, "rdp_parity_mapping_spec_mirror");
    PytestMirrorAssertions.assertEnvelopeOk(root);
    PytestMirrorAssertions.assertMappingSpecMirror(root.getJSONObject("interchange"));
  }

  /** Doc: {@code DbReadPipelineExample.java} — template only (no live warehouse in CI). */
  public static void runDbReadPipelineTemplateContract() throws Exception {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path bundle = requireBundle("cloud_connectors");
    Path work = Files.createTempDirectory("rdp_contract_db_read_template_");
    try {
      Path sink = work.resolve("curated.parquet");
      String pipeline =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/oracle_db_read.pipeline.json",
              Map.of("CURATED_PARQUET", sink.toAbsolutePath().normalize().toString()));
      assertTrue(pipeline.contains("db_reads"));
      assertTrue(pipeline.contains("oracle://"));
      assertTrue(pipeline.contains(sink.toString().replace('\\', '/')));
    } finally {
      deleteTree(work);
    }
  }

  /** Doc: {@code WarehouseExportHandoffExample.java} — CSV→Parquet→ingest on {@code people.csv}. */
  public static void runWarehouseExportHandoffContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    runParquetSnippetsCsvToParquetRoundTripContract(linker, lookup, arena);
  }

  /** Doc: {@code InferredSchemaIngestExample.java} — Excel infer+ingest on {@code people.xlsx}. */
  public static void runInferredSchemaExcelIngestContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    excelIngestPathSheetContract(linker, lookup, arena);
  }

  /**
   * Doc: {@code ObjectStoreUrlsExample.java} — {@code file://} object_store_uris + parquet sink.
   */
  public static void runObjectStoreUrlsFilePipelineContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fileBase = requireBundle("cloud_connectors");
    Path sink = Files.createTempFile("rdp_contract_object_store_", ".parquet");
    try {
      String pipeline =
          PipelineFixtureSupport.resolvePipelineJson(
              fileBase,
              "pipelines/object_store_sources_only.pipeline.json",
              Map.of(
                  "FILE_BASE", fileBase.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", sink.toAbsolutePath().normalize().toString()));
      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      assertTrue(inter.getInt("ingested_row_count") >= 2);
      JSONArray os = inter.getJSONArray("object_store_source_results");
      for (int i = 0; i < os.length(); i++) {
        assertEquals("ok", os.getJSONObject(i).getString("status"));
      }
      assertTrue(Files.isRegularFile(sink));
    } finally {
      Files.deleteIfExists(sink);
    }
  }

  /**
   * Doc: {@code PlatformConnectorsPipelineExample.java} — {@code file://} URIs in CI (no live
   * cloud).
   */
  public static void runPlatformConnectorsFilePipelineContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path fileBase = requireBundle("cloud_connectors");
    Path stageBase = Files.createTempDirectory("rdp_contract_platform_stage_");
    Path deltaWh = Files.createTempDirectory("rdp_contract_platform_delta_");
    try {
      String pipeline =
          PipelineFixtureSupport.resolvePipelineJson(
              fileBase,
              "pipelines/platform_connectors.pipeline.json",
              Map.of(
                  "FILE_BASE", fileBase.toAbsolutePath().normalize().toString(),
                  "STAGE_BASE", stageBase.toAbsolutePath().normalize().toString(),
                  "DELTA_WH", deltaWh.toAbsolutePath().normalize().toString()));
      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      JSONArray os = inter.getJSONArray("object_store_source_results");
      assertTrue(os.length() >= 1);
      for (int i = 0; i < os.length(); i++) {
        assertEquals("ok", os.getJSONObject(i).getString("status"));
      }
      JSONArray sinks = inter.getJSONArray("sink_results");
      boolean sparkOk = false;
      for (int i = 0; i < sinks.length(); i++) {
        JSONObject s = sinks.getJSONObject(i);
        assertEquals("ok", s.getString("status"));
        if ("spark".equals(s.getString("kind"))) {
          sparkOk = true;
        }
      }
      assertTrue(sparkOk, "expected ok spark sink in platform_connectors fixture");
    } finally {
      deleteTree(stageBase);
      deleteTree(deltaWh);
    }
  }

  /**
   * Doc: {@code SftpFtpConnectorsExample.java} — resolves pipeline template only (no FTP server in
   * CI).
   */
  public static void runSftpFtpPipelineTemplateContract() throws Exception {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path bundle = requireBundle("file_transfer");
    Path sink = Files.createTempFile("rdp_contract_ftp_template_", ".parquet");
    try {
      String pipeline =
          PipelineFixtureSupport.resolvePipelineJson(
              bundle,
              "pipelines/ftp_sources_only.pipeline.json",
              Map.of(
                  "FTP_URI",
                  "ftp://example.invalid/no-connect-in-ci",
                  "SINK_PATH",
                  sink.toAbsolutePath().normalize().toString()));
      assertTrue(pipeline.contains("file_transfer_uris"));
      assertTrue(pipeline.contains("ftp://example.invalid"));
    } finally {
      Files.deleteIfExists(sink);
    }
  }

  /**
   * Doc: {@code DeltaLakeHandoff.java} (§8). No lake connector in CI — asserts repo contains
   * handoff docs ({@code docs/LAKE_TABLE_READ.md}) and Parquet ingest fixtures so the example is
   * actionable.
   */
  public static void runPhase2DeltaLakeHandoffPrerequisitesContract() throws Exception {
    Path fixtures =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path repoRoot = fixtures.getParent().getParent();
    assertTrue(Files.isRegularFile(fixtures.resolve("people.csv")));
    Path bundle = requireBundle("people");
    assertTrue(Files.isRegularFile(bundle.resolve("pipelines/csv_to_parquet.pipeline.json")));
    assertTrue(Files.isRegularFile(bundle.resolve("schemas/people_flat.schema.json")));
    assertTrue(
        Files.isRegularFile(repoRoot.resolve("docs/LAKE_TABLE_READ.md")),
        "docs/LAKE_TABLE_READ.md for lake → Parquet → rdp_ingest_parquet_path handoff");
  }

  /**
   * Doc: {@code KafkaEltLoadExample.java}. **Load** step via {@code
   * rdp_kafka_elt_load_records_json} (fixture JSON — no broker).
   */
  public static void runKafkaEltLoadRecordsJsonContract(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    Path fixture = requireJvmContractFixture("kafka/stream_records.json");
    String recordsJson = Files.readString(fixture, java.nio.charset.StandardCharsets.UTF_8);
    String schemaJson =
        """
        {"fields":[
          {"name":"event_id","data_type":"Int64"},
          {"name":"payload","data_type":"Utf8"},
          {"name":"_kafka_offset","data_type":"Int64"}
        ]}
        """;
    JSONObject root =
        RdpNativeJson.invokeKafkaEltLoadRecordsJson(linker, lookup, arena, recordsJson, schemaJson);
    if (!root.getBoolean("ok")) {
      String err = root.optString("error", root.toString());
      Assumptions.assumeFalse(
          err.contains("kafka support disabled"), "rdp_jvm_sys built without --features kafka");
    }
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject inter = root.getJSONObject("interchange");
    assertEquals("kafka_elt_load", inter.getString("kind"));
    assertEquals(2, inter.getJSONObject("dataset").getJSONArray("rows").length());
  }

  private static void deleteTree(Path root) throws Exception {
    try (var walk = Files.walk(root)) {
      for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
        Files.deleteIfExists(p);
      }
    }
  }
}
