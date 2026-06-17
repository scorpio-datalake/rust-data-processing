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

@EnabledIfEnvironmentVariable(named = "RUN_CLOUD_INTEGRATION", matches = "1")
final class CloudImportIntegrationTest {

  private static final Path INTEG_ROOT =
      Path.of(System.getenv().getOrDefault("RDP_INTEGRATION_ROOT", "integration_testing"))
          .toAbsolutePath()
          .normalize();

  @Test
  void javaS3ObjectStoreRoundtrip() throws Throwable {
    runObjectStoreRoundtrip("s3", envOrDefault("CLOUD_S3_EXPORT_URI", "s3://rdp-cloud-s3/out.parquet"));
  }

  @Test
  void javaGcsObjectStoreRoundtrip() throws Throwable {
    runObjectStoreRoundtrip(
        "gcs", envOrDefault("CLOUD_GCS_EXPORT_URI", "gs://rdp-cloud-gcs/out.parquet"));
  }

  @Test
  void javaAzureObjectStoreRoundtrip() throws Throwable {
    runObjectStoreRoundtrip(
        "azure",
        envOrDefault(
            "CLOUD_AZURE_EXPORT_URI",
            "azure://rdp-cloud-azure/out.parquet"));
  }

  @Test
  void javaSftpFileTransferImport() throws Throwable {
    runFileTransferImport(
        envOrDefault(
            "CLOUD_SFTP_SOURCE_URI", "sftp://rdp:rdp_sftp_secret@127.0.0.1:2222/upload/incoming.csv"));
  }

  @Test
  void javaFtpFileTransferImport() throws Throwable {
    runFileTransferImport(
        envOrDefault("CLOUD_FTP_SOURCE_URI", "ftp://rdp:rdp_ftp_secret@127.0.0.1:21/incoming.csv"));
  }

  private static void runObjectStoreRoundtrip(String label, String uri) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    Assumptions.assumeTrue(lib != null);
    Path csv = resolveUberCsv();
    int maxRows = Integer.parseInt(System.getenv().getOrDefault("INTEG_MAX_IMPORT_ROWS", "500"));
    JSONObject tableSpec = loadTableSpec();
    String datasetSchema =
        Files.readString(
            INTEG_ROOT.resolve("schema/uber_pickups.schema.json"), StandardCharsets.UTF_8);

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
      JSONObject export =
          new JSONObject()
              .put("pipeline_spec_version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put("paths", new JSONArray().put(csv.toString()))
                      .put("schema", new JSONObject(datasetSchema))
                      .put(
                          "options",
                          new JSONObject().put("format", "csv").put("max_rows", maxRows)))
              .put("transform", new JSONObject().put("sql", transformSql(tableSpec)))
              .put(
                  "sinks",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("kind", "object_store")
                              .put("uri", uri)
                              .put("format", "parquet")))
              .put("orchestration", new JSONObject().put("max_ingested_rows", maxRows));

      JSONObject exportRoot =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, export.toString());
      PytestMirrorAssertions.assertEnvelopeOk(exportRoot);
      int expected = exportRoot.getJSONObject("interchange").getInt("ingested_row_count");
      assertTrue(expected > 0, label + " export ingested no rows");

      String curatedSchema = curatedDatasetSchema(tableSpec, datasetSchema);
      JSONObject importPipeline =
          new JSONObject()
              .put("pipeline_spec_version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put("paths", new JSONArray())
                      .put("schema", new JSONObject(curatedSchema))
                      .put("options", new JSONObject().put("format", "parquet"))
                      .put("object_store_uris", new JSONArray().put(uri)))
              .put(
                  "sinks",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("kind", "parquet_file")
                              .put("path", "/tmp/rdp-cloud-readback.parquet")));
      JSONObject importRoot =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, importPipeline.toString());
      PytestMirrorAssertions.assertEnvelopeOk(importRoot);
      assertEquals(
          expected,
          importRoot.getJSONObject("interchange").getInt("ingested_row_count"),
          label + " read-back row count");
    }
  }

  private static void runFileTransferImport(String uri) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    Assumptions.assumeTrue(lib != null);
    int maxRows = Integer.parseInt(System.getenv().getOrDefault("INTEG_MAX_IMPORT_ROWS", "500"));
    String datasetSchema =
        Files.readString(
            INTEG_ROOT.resolve("schema/uber_pickups.schema.json"), StandardCharsets.UTF_8);

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
      JSONObject importPipeline =
          new JSONObject()
              .put("pipeline_spec_version", 1)
              .put(
                  "sources",
                  new JSONObject()
                      .put("paths", new JSONArray())
                      .put("schema", new JSONObject(datasetSchema))
                      .put(
                          "options",
                          new JSONObject().put("format", "csv").put("max_rows", maxRows))
                      .put("file_transfer_uris", new JSONArray().put(uri)))
              .put(
                  "sinks",
                  new JSONArray()
                      .put(
                          new JSONObject()
                              .put("kind", "parquet_file")
                              .put("path", "/tmp/rdp-cloud-ft-import.parquet")));
      JSONObject importRoot =
          RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, importPipeline.toString());
      PytestMirrorAssertions.assertEnvelopeOk(importRoot);
      assertTrue(
          importRoot.getJSONObject("interchange").getInt("ingested_row_count") > 0,
          "file_transfer import returned no rows for " + uri);
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
    sb.append(" FROM df");
    return sb.toString();
  }

  private static JSONObject loadTableSpec() throws Exception {
    return new JSONObject(
        Files.readString(
            INTEG_ROOT.resolve("schema/uber_pickups.table.json"), StandardCharsets.UTF_8));
  }

  // Post-transform schema (curated column names) for Parquet read-back after export.
  private static String curatedDatasetSchema(JSONObject tableSpec, String datasetSchemaJson)
      throws org.json.JSONException {
    JSONObject datasetSchema = new JSONObject(datasetSchemaJson);
    JSONArray fields = new JSONArray();
    JSONArray columns = tableSpec.getJSONArray("columns");
    for (int i = 0; i < columns.length(); i++) {
      JSONObject col = columns.getJSONObject(i);
      String name = col.getString("name");
      String src = col.getString("source_field");
      String dataType = "Utf8";
      JSONArray srcFields = datasetSchema.getJSONArray("fields");
      for (int j = 0; j < srcFields.length(); j++) {
        JSONObject f = srcFields.getJSONObject(j);
        if (src.equals(f.getString("name"))) {
          dataType = f.getString("data_type");
          break;
        }
      }
      fields.put(new JSONObject().put("name", name).put("data_type", dataType));
    }
    return new JSONObject().put("fields", fields).toString();
  }

  private static Path resolveUberCsv() {
    Path sample = INTEG_ROOT.resolve("data/uber_nyc_pickups_sample.csv");
    Path full = INTEG_ROOT.resolve("data/uber_nyc_pickups_apr2014.csv");
    if (Files.isRegularFile(sample)) return sample;
    if (Files.isRegularFile(full)) return full;
    Assumptions.assumeTrue(false, "Uber CSV missing");
    return sample;
  }

  private static String envOrDefault(String key, String defaultValue) {
    String val = System.getenv(key);
    return val != null && !val.isBlank() ? val : defaultValue;
  }
}
