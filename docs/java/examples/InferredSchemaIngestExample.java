import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Two-pass infer-then-ingest (Python {@code ingest_with_inferred_schema}).
 *
 * <p>On the JVM, {@code rdp_excel_ingest_path_sheet} infers schema inside Rust before ingest (see
 * {@code bindings/jvm-sys/src/parity.rs}). This example runs that path on {@code people.xlsx}.
 * For CSV/JSON with explicit schemas, use {@link QuickStartIngestExample}.
 */
public final class InferredSchemaIngestExample {

  private static final String PEOPLE_XLSX = "people.xlsx";
  private static final String SHEET = "Sheet1";

  private InferredSchemaIngestExample() {}

  public static JSONObject excelInferIngestInterchange(
      Linker linker, SymbolLookup lookup, Arena arena, Path workbook) throws Throwable {
    JSONObject root =
        RdpNativeJson.excelIngestPathSheet(
            linker, lookup, arena, workbook.toAbsolutePath().normalize().toString(), SHEET);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"excel_ingest_sheet".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return interchange;
  }

  public static void demonstrate(Path nativeLibrary, Path workbook) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject inter = excelInferIngestInterchange(linker, lookup, arena, workbook);
      int rows = inter.getJSONObject("dataset").getJSONArray("rows").length();
      System.out.println("Excel infer+ingest rows: " + rows);
    }
  }

  public static void main(String[] args) throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library");
      System.exit(2);
    }
    Path fixtures =
        io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures
            .resolveTestsFixturesDir()
            .orElseThrow(() -> new IllegalStateException("tests/fixtures not found"));
    Path xlsx = fixtures.resolve(PEOPLE_XLSX);
    if (!java.nio.file.Files.isRegularFile(xlsx)) {
      System.err.println(
          "Missing "
              + PEOPLE_XLSX
              + " — run: python scripts/write_people_xlsx_stdlib.py");
      System.exit(2);
    }
    demonstrate(lib, xlsx);
  }
}
