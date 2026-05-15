package io.github.rust_data_processing.docexamples;

import static org.junit.jupiter.api.Assertions.assertEquals;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import io.github.rust_data_processing.support.RdpJvmSysTestSupport;
import io.github.rust_data_processing.testsupport.JvmNativeContractScenarios;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests aligned with {@code docs/java/examples/*.java}: same fixtures and assertions as
 * the doc snippets, run under JUnit when {@code rdp_jvm_sys} is discoverable. FFI manifest drift and
 * per-symbol smoke checks live in {@code io.github.rust_data_processing.FfiExportedSymbolsContractTest}.
 *
 * <p><strong>RDP vs “plain Java”</strong> — these tests never parse CSV/JSON/Excel in Java. They load
 * the native library from {@code RDP_JVM_SYS} / {@code rdp.jvm.sys.library}, then call {@link
 * RdpNativeJson} helpers that use Panama {@link Linker#downcallHandle} on symbols exported by Rust
 * (for example {@code rdp_excel_ingest_path_sheet}, {@code rdp_run_pipeline_json}). Java builds UTF-8
 * path strings and parses the returned JSON envelope; ingestion, Polars SQL, and Excel reading run
 * inside {@code rdp_jvm_sys}.
 */
final class DocsExampleNativeIntegrationTest {

  private static final String PEOPLE_XLSX_FIXTURE = "people.xlsx";
  private static final String PEOPLE_SHEET = "Sheet1";

  /**
   * Keeps {@code docs/java/examples/DataFrameCentricPipeline.java} honest in CI when {@code
   * rdp_jvm_sys} is on the test classpath: Polars SQL transform + {@code parquet_file} sink row
   * count matches the Python {@code out.row_count() == 2} sketch.
   */
  @Test
  void runPipelineJsonPolarsSqlFilterAndMultiplyMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPipelineJsonDataFrameCentricSqlContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/ExcelSnippets.java} honest: {@code rdp_excel_ingest_path_sheet}
   * on {@code tests/fixtures/people.xlsx} (sheet {@value #PEOPLE_SHEET}), aligned with
   * {@code people.csv}. Generate if missing: {@code python scripts/write_people_xlsx_stdlib.py} or
   * {@code cargo run --features excel_test_writer --bin generate_people_xlsx_fixture}.
   */
  @Test
  void excelIngestPathSheetMatchesDocsExampleWhenFixturePresent() throws Throwable {
    Optional<Path> peopleXlsx =
        RdpJvmSysTestSupport.resolveFixtureFile(PEOPLE_XLSX_FIXTURE);
    Assumptions.assumeTrue(
        peopleXlsx.isPresent(),
        "Missing tests/fixtures/"
            + PEOPLE_XLSX_FIXTURE
            + " — from repo root: python scripts/write_people_xlsx_stdlib.py"
            + " or: cargo run --features excel_test_writer --bin generate_people_xlsx_fixture");

    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JSONObject root =
          RdpNativeJson.excelIngestPathSheet(
              linker, lookup, arena, peopleXlsx.get().toString(), PEOPLE_SHEET);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      JSONObject interchange = root.getJSONObject("interchange");
      assertEquals("excel_ingest_sheet", interchange.getString("kind"));
      assertEquals(2, interchange.getJSONObject("dataset").getJSONArray("rows").length());
    }
  }

  /**
   * Keeps {@code docs/java/examples/SQLQueries.java} single-table sketch honest: Polars SQL on {@code df}
   * after ingest.
   */
  @Test
  void runPipelineJsonSingleTableSqlMatchesDocsSqlQueriesExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPipelineJsonSqlQueryDatasetContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/SQLQueries.java} JOIN sketch honest via {@code
   * rdp_parity_sql_suite_mirror} (Rust {@code SqlContext}-equivalent workload for parity).
   */
  @Test
  void rdpParitySqlSuiteMirrorJoinMatchesDocsSqlQueriesExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.sqlSuiteMirrorJoinContract(linker, lookup, arena);
    }
  }
}
