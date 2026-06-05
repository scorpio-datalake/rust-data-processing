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

@EnabledIfEnvironmentVariable(named = "RUN_DATABRICKS_INTEGRATION", matches = "1")
final class DatabricksImportIntegrationTest {

  private static final Path INTEG_ROOT =
      Path.of(System.getenv().getOrDefault("RDP_INTEGRATION_ROOT", "integration_testing"))
          .toAbsolutePath()
          .normalize();

  @Test
  void javaDatabricksStageImport() throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    Assumptions.assumeTrue(lib != null);
    Path csv = resolveUberCsv();
    int maxRows = Integer.parseInt(System.getenv().getOrDefault("INTEG_MAX_IMPORT_ROWS", "500"));
    JSONObject tableSpec =
        new JSONObject(
            Files.readString(
                INTEG_ROOT.resolve("schema/uber_pickups.table.json"), StandardCharsets.UTF_8));
    JSONObject db = tableSpec.getJSONObject("connectors").getJSONObject("databricks");
    String warehouseUri = System.getenv("DATABRICKS_WAREHOUSE_URI");
    Assumptions.assumeTrue(
        warehouseUri != null && warehouseUri.startsWith("s3://"), "DATABRICKS_WAREHOUSE_URI");
    if (!warehouseUri.endsWith("/")) {
      warehouseUri = warehouseUri + "/";
    }

    JSONObject payload =
        new JSONObject()
            .put("pipeline_spec_version", 1)
            .put(
                "sources",
                new JSONObject()
                    .put("paths", new JSONArray().put(csv.toString()))
                    .put(
                        "schema",
                        new JSONObject(
                            Files.readString(
                                INTEG_ROOT.resolve("schema/uber_pickups.schema.json"),
                                StandardCharsets.UTF_8)))
                    .put("options", new JSONObject().put("format", "csv").put("max_rows", maxRows)))
            .put("transform", new JSONObject().put("sql", transformSql(tableSpec)))
            .put(
                "sinks",
                new JSONArray()
                    .put(
                        new JSONObject()
                            .put("kind", "databricks")
                            .put("workspace_url", db.getString("workspace_url"))
                            .put("warehouse", warehouseUri)
                            .put("namespace", db.getString("namespace"))
                            .put("table", db.getString("table"))))
            .put("orchestration", new JSONObject().put("max_ingested_rows", maxRows));

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
      JSONObject root =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, payload.toString());
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject inter = root.getJSONObject("interchange");
      int expected = inter.getInt("ingested_row_count");
      assertTrue(expected > 0);
      JSONObject sink = inter.getJSONArray("sink_results").getJSONObject(0);
      assertEquals("databricks", sink.getString("kind"));
      assertEquals("ok", sink.getString("status"));
      assertEquals(expected, sink.getInt("row_count"));
      assertTrue(sink.getString("table_uri").startsWith("s3://"), "table_uri must be s3://");
      runPlatformSql("databricks", expected);
    }
  }

  private static void runPlatformSql(String connector, int expected) throws Exception {
    String python =
        System.getenv()
            .getOrDefault(
                "RDP_PLATFORM_PYTHON",
                INTEG_ROOT.resolve("python-wrapper/.venv/bin/python").toString());
    ProcessBuilder pb =
        new ProcessBuilder(
            python,
            INTEG_ROOT.resolve("scripts/platform_sql.py").toString(),
            connector,
            "--expected",
            String.valueOf(expected));
    pb.redirectErrorStream(true);
    Process p = pb.start();
    int rc = p.waitFor();
    if (rc != 0) {
      throw new AssertionError("platform_sql " + connector + " exited " + rc);
    }
  }

  private static String transformSql(JSONObject tableSpec) throws org.json.JSONException {
    JSONArray columns = tableSpec.getJSONArray("columns");
    StringBuilder sb = new StringBuilder("SELECT ");
    for (int i = 0; i < columns.length(); i++) {
      if (i > 0) sb.append(", ");
      JSONObject col = columns.getJSONObject(i);
      String src = col.getString("source_field");
      String name = col.getString("name");
      if (src.contains(" ") || src.contains("/")) sb.append('"').append(src).append('"');
      else sb.append(src);
      sb.append(" AS ").append(name);
    }
    return sb.append(" FROM df").toString();
  }

  private static Path resolveUberCsv() throws Exception {
    Path sample = INTEG_ROOT.resolve("data/uber_nyc_pickups_sample.csv");
    if (Files.isRegularFile(sample)) return sample;
    Path full = INTEG_ROOT.resolve("data/uber_nyc_pickups_apr2014.csv");
    Assumptions.assumeTrue(Files.isRegularFile(full));
    return full;
  }
}
