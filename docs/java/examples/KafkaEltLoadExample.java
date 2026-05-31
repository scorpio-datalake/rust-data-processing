import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Kafka streaming ELT **Load** step — map a JSON poll window into a landing table (no broker).
 *
 * <p>Python analogue: {@code elt_load_kafka_records_json(records_json, landing_schema)}. JVM uses
 * {@code rdp_kafka_elt_load_records_json} with fixture {@code tests/fixtures/kafka/stream_records.json}.
 */
public final class KafkaEltLoadExample {

  private static final String FIXTURE = "kafka/stream_records.json";
  private static final String LANDING_SCHEMA =
      """
      {"fields":[
        {"name":"event_id","data_type":"Int64"},
        {"name":"payload","data_type":"Utf8"},
        {"name":"_kafka_offset","data_type":"Int64"}
      ]}
      """;

  private KafkaEltLoadExample() {}

  public static JSONObject loadStreamRecordsFixture(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir) throws Throwable {
    Path jsonPath = fixturesDir.resolve(FIXTURE).toAbsolutePath().normalize();
    String recordsJson = Files.readString(jsonPath, StandardCharsets.UTF_8);
    JSONObject root =
        RdpNativeJson.invokeKafkaEltLoadRecordsJson(
            linker, lookup, arena, recordsJson, LANDING_SCHEMA);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    return root.getJSONObject("interchange");
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      Path fixtures =
          io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures
              .resolveTestsFixturesDir()
              .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
      JSONObject inter = loadStreamRecordsFixture(linker, lookup, arena, fixtures);
      int rows = inter.getJSONObject("dataset").getJSONArray("rows").length();
      System.out.println("Kafka ELT load (stream_records.json): " + rows + " rows");
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library (build with --features kafka)");
      System.exit(2);
    }
    demonstrate(lib);
  }
}
