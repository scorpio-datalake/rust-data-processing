import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Map;
import org.json.JSONObject;

/**
 * External DB client → local Parquet → Rust ingest (warehouse export handoff).
 *
 * <p>Step A is your app's JDBC/sqlcmd/Spark export (out of scope on FFI). Step B is the same as
 * {@link ParquetSnippets}: CSV→Parquet pipeline, then {@code rdp_ingest_parquet_path}.
 */
public final class WarehouseExportHandoffExample {

  private static final String BUNDLE = "people";
  private static final String PIPELINE = "pipelines/csv_to_parquet.pipeline.json";
  private static final String SCHEMA_FLAT = "schemas/people_flat.schema.json";

  private WarehouseExportHandoffExample() {}

  public static JSONObject handoffParquetIngest(
      Linker linker, SymbolLookup lookup, Arena arena, Path fixturesDir) throws Exception {
    Path csv = fixturesDir.resolve("people.csv");
    if (!Files.isRegularFile(csv)) {
      throw new IllegalStateException("missing tests/fixtures/people.csv");
    }
    Path bundle =
        PipelineJsonFixtures.resolveBundleRoot(fixturesDir, BUNDLE)
            .orElseThrow(() -> new IllegalStateException("tests/fixtures/" + BUNDLE + " missing"));
    Path work = Files.createTempDirectory("rdp_warehouse_handoff_");
    try {
      Path parquet = work.resolve("warehouse_export.parquet");
      String pipeline =
          PipelineJsonFixtures.resolvePipelineJson(
              bundle,
              PIPELINE,
              Map.of(
                  "SOURCE_PATH", csv.toAbsolutePath().normalize().toString(),
                  "SINK_PATH", parquet.toAbsolutePath().normalize().toString()));
      JSONObject run = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, pipeline);
      PytestMirrorAssertions.assertEnvelopeOk(run);
      String schema = PipelineJsonFixtures.loadSchemaJson(bundle, SCHEMA_FLAT);
      JSONObject ingest =
          RdpNativeJson.invokeIngestParquetPath(
              linker,
              lookup,
              arena,
              parquet.toString(),
              schema,
              PipelineJsonFixtures.defaultPathIngestOptionsJson());
      PytestMirrorAssertions.assertEnvelopeOk(ingest);
      return ingest.getJSONObject("interchange");
    } finally {
      try (var walk = Files.walk(work)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      Path fixtures =
          PipelineJsonFixtures.resolveTestsFixturesDir()
              .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
      JSONObject inter = handoffParquetIngest(linker, lookup, arena, fixtures);
      int rows = inter.getJSONObject("dataset").getJSONArray("rows").length();
      System.out.println("Warehouse handoff ingest rows: " + rows);
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library");
      System.exit(2);
    }
    demonstrate(lib);
  }
}
