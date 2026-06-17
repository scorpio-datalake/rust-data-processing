package io.github.scorpio_datalake.rust_data_processing.integration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.io.BufferedReader;
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

@EnabledIfEnvironmentVariable(named = "RUN_KAFKA_INTEGRATION", matches = "1")
final class KafkaStreamIntegrationTest {

  private static final Path INTEG_ROOT =
      Path.of(System.getenv().getOrDefault("RDP_INTEGRATION_ROOT", "integration_testing"))
          .toAbsolutePath()
          .normalize();

  private static final String LANDING_SCHEMA =
      """
      {"fields":[
        {"name":"pickup_time","data_type":"Utf8"},
        {"name":"lat","data_type":"Float64"},
        {"name":"lon","data_type":"Float64"},
        {"name":"base_code","data_type":"Utf8"},
        {"name":"_kafka_offset","data_type":"Int64"},
        {"name":"_kafka_partition","data_type":"Int64"}
      ]}
      """;

  @Test
  void javaKafkaStreamOneRowPerMessage() throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    Assumptions.assumeTrue(lib != null, "RDP_JVM_SYS not set");
    Path csv = resolveUberCsv();
    int maxRows = Integer.parseInt(System.getenv().getOrDefault("INTEG_MAX_IMPORT_ROWS", "500"));
    String brokers = envOrDefault("KAFKA_BROKERS", "127.0.0.1:19092");
    String topic = envOrDefault("KAFKA_TOPIC", "rdp-uber-pickups");
    String group = envOrDefault("KAFKA_GROUP_ID", "rdp-integration-test") + "-" + ProcessHandle.current().pid();

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
      int produced = 0;
      try (BufferedReader reader = Files.newBufferedReader(csv, StandardCharsets.UTF_8)) {
        String header = reader.readLine();
        Assumptions.assumeTrue(header != null);
        String line;
        while ((line = reader.readLine()) != null && produced < maxRows) {
          String[] cols = line.split(",", 4);
          if (cols.length < 4) continue;
          JSONObject dataset =
              new JSONObject()
                  .put(
                      "schema",
                      new JSONObject()
                          .put(
                              "fields",
                              new JSONArray()
                                  .put(field("pickup_time", "Utf8"))
                                  .put(field("lat", "Float64"))
                                  .put(field("lon", "Float64"))
                                  .put(field("base_code", "Utf8"))))
                  .put(
                      "rows",
                      new JSONArray()
                          .put(
                              new JSONArray()
                                  .put(new JSONObject().put("Utf8", cols[0]))
                                  .put(new JSONObject().put("Float64", Double.parseDouble(cols[1])))
                                  .put(new JSONObject().put("Float64", Double.parseDouble(cols[2])))
                                  .put(new JSONObject().put("Utf8", cols[3]))));
          JSONObject exportRoot =
              RdpNativeJson.invokeKafkaExportDatasetJson(
                  linker,
                  lookup,
                  arena,
                  producerConfig(brokers, topic),
                  dataset.toString());
          PytestMirrorAssertions.assertEnvelopeOk(exportRoot);
          produced++;
        }
      }
      assertTrue(produced > 0, "no rows produced to Kafka");

      JSONObject pollRoot =
          RdpNativeJson.invokeKafkaPollWindowLoadedJson(
              linker,
              lookup,
              arena,
              consumerConfig(brokers, group, topic, produced),
              LANDING_SCHEMA);
      PytestMirrorAssertions.assertEnvelopeOk(pollRoot);
      int landed = pollRoot.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length();
      assertEquals(produced, landed, "Kafka poll row count");
    }
  }

  private static JSONObject field(String name, String dataType) throws org.json.JSONException {
    return new JSONObject().put("name", name).put("data_type", dataType);
  }

  private static String producerConfig(String brokers, String topic) {
    return new JSONObject()
        .put("brokers", brokers)
        .put("topic", topic)
        .put("message_timeout_ms", 10_000)
        .toString();
  }

  private static String consumerConfig(String brokers, String group, String topic, int maxRecords) {
    return new JSONObject()
        .put("brokers", brokers)
        .put("group_id", group)
        .put("topic", topic)
        .put("max_records", maxRecords)
        .put("auto_offset_reset", "earliest")
        .toString();
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
