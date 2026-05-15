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
   * on {@code tests/fixtures/people.xlsx} when the fixture exists (CI generates it before {@code mvn
   * verify}).
   */
  @Test
  void excelIngestPathSheetMatchesDocsExampleWhenFixturePresent() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.excelIngestPathSheetContract(linker, lookup, arena);
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
