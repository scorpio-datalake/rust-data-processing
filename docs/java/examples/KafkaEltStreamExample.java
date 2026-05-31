import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Kafka streaming ELT over Rust {@code rdkafka} — **Extract → Load** via Panama FFI (needs broker).
 *
 * <p>Java does <strong>not</strong> use {@code kafka-clients}. All broker I/O is in {@code
 * rdp_jvm_sys} ({@code rdp_kafka_poll_window_loaded_json}). Transform is a separate stage ({@code
 * rdp_run_pipeline_json} or SQL parity exports).
 *
 * <p>Build native library: {@code cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml
 * --features full,kafka}
 *
 * <p>Run: set {@code RDP_JVM_SYS} to the built {@code librdp_jvm_sys.so} / {@code .dylib} / {@code
 * .dll}, then {@code java KafkaEltStreamExample} (or pass broker, group, topic as args).
 */
public final class KafkaEltStreamExample {

  private static final String DEFAULT_BROKERS = "localhost:9092";
  private static final String DEFAULT_GROUP = "rdp-elt-java";
  private static final String DEFAULT_TOPIC = "events";

  private static final String LANDING_SCHEMA =
      """
      {"fields":[
        {"name":"user_id","data_type":"Int64"},
        {"name":"event","data_type":"Utf8"},
        {"name":"_kafka_offset","data_type":"Int64"},
        {"name":"_kafka_partition","data_type":"Int64"}
      ]}
      """;

  private KafkaEltStreamExample() {}

  /** Consumer config JSON passed to {@code rdp_kafka_poll_window_loaded_json}. */
  public static String consumerConfigJson(String brokers, String groupId, String topic, int maxRecords) {
    return new JSONObject()
        .put("brokers", brokers)
        .put("group_id", groupId)
        .put("topic", topic)
        .put("max_records", maxRecords)
        .put("auto_offset_reset", "earliest")
        .toString();
  }

  /** Producer config JSON passed to {@code rdp_kafka_export_dataset_json}. */
  public static String producerConfigJson(String brokers, String topic) {
    return new JSONObject()
        .put("brokers", brokers)
        .put("topic", topic)
        .put("message_timeout_ms", 5_000)
        .toString();
  }

  /**
   * **Extract + Load:** poll one bounded window from the topic and land rows (offsets preserved).
   */
  public static JSONObject pollWindowLoaded(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String brokers,
      String groupId,
      String topic,
      int maxRecords)
      throws Throwable {
    String config = consumerConfigJson(brokers, groupId, topic, maxRecords);
    JSONObject root =
        RdpNativeJson.invokeKafkaPollWindowLoadedJson(
            linker, lookup, arena, config, LANDING_SCHEMA);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root.getJSONObject("interchange");
  }

  /**
   * **Extract only:** poll records without landing schema mapping.
   */
  public static JSONArray pollWindowRaw(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String brokers,
      String groupId,
      String topic,
      int maxRecords)
      throws Throwable {
    String config = consumerConfigJson(brokers, groupId, topic, maxRecords);
    JSONObject root = RdpNativeJson.invokeKafkaPollWindowJson(linker, lookup, arena, config);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root.getJSONObject("interchange").getJSONArray("records");
  }

  /** **Sink:** publish a landed {@code dataset} JSON envelope to an output topic. */
  public static JSONObject exportDataset(
      Linker linker,
      SymbolLookup lookup,
      Arena arena,
      String brokers,
      String topic,
      JSONObject datasetEnvelope)
      throws Throwable {
    String config = producerConfigJson(brokers, topic);
    JSONObject root =
        RdpNativeJson.invokeKafkaExportDatasetJson(
            linker, lookup, arena, config, datasetEnvelope.toString());
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root.getJSONObject("interchange");
  }

  public static void demonstrate(
      Path nativeLibrary, String brokers, String groupId, String topic, int maxRecords)
      throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);

      JSONObject landed =
          pollWindowLoaded(linker, lookup, arena, brokers, groupId, topic, maxRecords);
      int rowCount = landed.getJSONObject("dataset").getJSONArray("rows").length();
      System.out.printf(
          "Extract+Load: %d rows from topic '%s' (brokers=%s, group=%s)%n",
          rowCount, topic, brokers, groupId);

      if (rowCount == 0) {
        System.out.println("No records in poll window — produce test events to the topic and retry.");
        return;
      }

      // Transform is separate — e.g. rdp_run_pipeline_json on landed Parquet / dataset JSON.
      System.out.println("Transform: run rdp_run_pipeline_json or SQL parity on landed data next.");
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library (build with --features kafka)");
      System.exit(2);
    }
    String brokers = envOrArg(args, 0, "KAFKA_BROKERS", DEFAULT_BROKERS);
    String group = envOrArg(args, 1, "KAFKA_GROUP_ID", DEFAULT_GROUP);
    String topic = envOrArg(args, 2, "KAFKA_TOPIC", DEFAULT_TOPIC);
    int maxRecords = args.length > 3 ? Integer.parseInt(args[3]) : 500;
    demonstrate(lib, brokers, group, topic, maxRecords);
  }

  private static String envOrArg(String[] args, int index, String envKey, String defaultValue) {
    if (args.length > index && !args[index].isBlank()) {
      return args[index].strip();
    }
    String env = System.getenv(envKey);
    if (env != null && !env.isBlank()) {
      return env.strip();
    }
    return defaultValue;
  }
}
