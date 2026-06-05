package io.github.scorpio_datalake.rust_data_processing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

/**
 * Oracle integration — RDP {@code rdp_run_pipeline_json} only (CSV ingest, transform, Oracle sink,
 * db_reads verify). No JDBC.
 */
@EnabledIfEnvironmentVariable(named = "RUN_ORACLE_INTEGRATION", matches = "1")
final class OracleImportIntegrationTest {

  private static final Path INTEG_ROOT =
      Path.of(System.getenv().getOrDefault("RDP_INTEGRATION_ROOT", "integration_testing"))
          .toAbsolutePath()
          .normalize();
  private static final Path SCHEMA_DIR = INTEG_ROOT.resolve("schema");

  @Test
  void javaRdpPipelineImportAndVerify() throws Throwable {
    String url = System.getenv("ORACLE_CONNECT_URL");
    Assumptions.assumeTrue(url != null && !url.isBlank(), "ORACLE_CONNECT_URL not set");

    Path csv = resolveUberCsv();
    int maxRows =
        Integer.parseInt(System.getenv().getOrDefault("INTEG_MAX_IMPORT_ROWS", "500"));
    JSONObject tableSpec =
        new JSONObject(
            Files.readString(
                SCHEMA_DIR.resolve("uber_pickups.table.json"), StandardCharsets.UTF_8));
    String table = tableSpec.getJSONObject("connectors").getJSONObject("oracle").getString("table");
    String datasetSchema =
        Files.readString(
            SCHEMA_DIR.resolve("uber_pickups.schema.json"), StandardCharsets.UTF_8);

    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    Assumptions.assumeTrue(lib != null, "RDP_JVM_SYS / rdp.jvm.sys.library not set");

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);

      JSONObject importPipeline =
          buildImportPipeline(csv, datasetSchema, tableSpec, url, maxRows);
      JSONObject importRoot =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, importPipeline.toString());
      PytestMirrorAssertions.assertEnvelopeOk(importRoot);
      JSONObject inter = importRoot.getJSONObject("interchange");
      assertEquals("run_pipeline_json", inter.getString("kind"));
      int expected = inter.getInt("ingested_row_count");
      assertTrue(expected > 0, "pipeline ingested no rows");
      JSONArray sinkResults = inter.getJSONArray("sink_results");
      JSONObject oracleSink = findSink(sinkResults, "oracle");
      assertEquals("ok", oracleSink.getString("status"));
      assertEquals(expected, oracleSink.getInt("row_count"));

      assertEquals(expected, verifyCountRdp(linker, lookup, arena, url, table, tableSpec));
    }
  }

  private static JSONObject buildImportPipeline(
      Path csv, String datasetSchemaJson, JSONObject tableSpec, String url, int maxRows)
      throws org.json.JSONException {
    String table = tableSpec.getJSONObject("connectors").getJSONObject("oracle").getString("table");
    JSONObject schema = new JSONObject(datasetSchemaJson);
    JSONObject payload =
        new JSONObject()
            .put("pipeline_spec_version", 1)
            .put(
                "sources",
                new JSONObject()
                    .put("paths", new JSONArray().put(csv.toString()))
                    .put("schema", schema)
                    .put(
                        "options",
                        new JSONObject().put("format", "csv").put("max_rows", maxRows)))
            .put("transform", new JSONObject().put("sql", transformSql(tableSpec)))
            .put(
                "sinks",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("kind", "oracle")
                            .put("url", url)
                            .put("table", table)
                            .put("create_table_if_missing", true)
                            .put("truncate_before_load", true)))
            .put("orchestration", new JSONObject().put("max_ingested_rows", maxRows));
    return payload;
  }

  private static String transformSql(JSONObject tableSpec) throws org.json.JSONException {
    JSONArray columns = tableSpec.getJSONArray("columns");
    StringBuilder sb = new StringBuilder("SELECT ");
    for (int i = 0; i < columns.length(); i++) {
      if (i > 0) {
        sb.append(", ");
      }
      JSONObject col = columns.getJSONObject(i);
      String src = col.getString("source_field");
      String name = col.getString("name");
      if (src.contains(" ") || src.contains("/")) {
        sb.append('"').append(src).append('"');
      } else {
        sb.append(src);
      }
      sb.append(" AS ").append(name);
    }
    sb.append(" FROM df");
    return sb.toString();
  }

  private static int verifyCountRdp(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String url,
      String table,
      JSONObject tableSpec)
      throws Throwable {
    Path parquet = Files.createTempFile("rdp_oracle_verify_", ".parquet");
    try {
      JSONObject verifySchema =
          new JSONObject().put("fields", new JSONArray().put(new JSONObject().put("name", "CNT").put("data_type", "Int64")));
      String query =
          "SELECT CAST(COUNT(*) AS NUMBER(19)) AS CNT FROM " + table;
      JSONObject payload =
          new JSONObject()
              .put("pipeline_spec_version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put("paths", new JSONArray())
                      .put("schema", verifySchema)
                      .put("options", new JSONObject())
                      .put(
                          "db_reads",
                          new JSONArray()
                              .put(new JSONObject().put("url", url).put("query", query))))
              .put(
                  "sinks",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("kind", "parquet_file")
                              .put("path", parquet.toString())));
      JSONObject root =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload.toString());
      PytestMirrorAssertions.assertEnvelopeOk(root);

      JSONObject readRoot =
          RdpNativeJson.invokeIngestParquetPath(
              linker, lookup, arena, parquet.toString(), verifySchema.toString(), "{}");
      PytestMirrorAssertions.assertEnvelopeOk(readRoot);
      JSONArray rows =
          readRoot
              .getJSONObject("interchange")
              .getJSONObject("dataset")
              .getJSONArray("rows");
      assertTrue(rows.length() > 0, "COUNT(*) returned no rows");
      return jsonCellAsInt(rows.getJSONArray(0), 0);
    } finally {
      Files.deleteIfExists(parquet);
    }
  }

  private static JSONObject findSink(JSONArray sinkResults, String kind)
      throws org.json.JSONException {
    for (int i = 0; i < sinkResults.length(); i++) {
      JSONObject s = sinkResults.getJSONObject(i);
      if (kind.equals(s.getString("kind"))) {
        return s;
      }
    }
    throw new AssertionError("missing sink kind: " + kind);
  }

  private static int jsonCellAsInt(JSONArray row, int idx) throws org.json.JSONException {
    Object cell = row.get(idx);
    if (cell instanceof JSONObject obj) {
      if (obj.has("Int64")) {
        return obj.getInt("Int64");
      }
      if (obj.has("Float64")) {
        return (int) obj.getDouble("Float64");
      }
      if (obj.has("Utf8")) {
        return Integer.parseInt(obj.getString("Utf8"));
      }
    }
    if (cell instanceof Number n) {
      return n.intValue();
    }
    return Integer.parseInt(cell.toString());
  }

  private static Path resolveUberCsv() throws Exception {
    Path sample = INTEG_ROOT.resolve("data/uber_nyc_pickups_sample.csv");
    Path full = INTEG_ROOT.resolve("data/uber_nyc_pickups_apr2014.csv");
    if (Files.isRegularFile(sample)) {
      return sample;
    }
    if (Files.isRegularFile(full)) {
      return full;
    }
    Assumptions.assumeTrue(false, "Uber CSV missing — run download_uber_data.py");
    return sample;
  }
}
