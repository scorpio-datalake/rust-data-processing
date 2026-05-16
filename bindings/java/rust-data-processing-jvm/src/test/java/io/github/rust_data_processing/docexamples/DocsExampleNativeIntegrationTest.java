package io.github.rust_data_processing.docexamples;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.support.RdpJvmSysTestSupport;
import io.github.rust_data_processing.testsupport.JvmNativeContractScenarios;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests aligned with {@code docs/java/examples/*.java}: same fixtures and assertions as
 * the doc snippets, run under JUnit when {@code rdp_jvm_sys} is discoverable. FFI manifest drift
 * and per-symbol smoke checks live in {@code
 * io.github.rust_data_processing.FfiExportedSymbolsContractTest}.
 *
 * <p><strong>RDP vs “plain Java”</strong> — these tests never parse CSV/JSON/Excel in Java. They
 * load the native library from {@code RDP_JVM_SYS} / {@code rdp.jvm.sys.library}, then call {@link
 * RdpNativeJson} helpers that use Panama {@link Linker#downcallHandle} on symbols exported by Rust
 * (for example {@code rdp_excel_ingest_path_sheet}, {@code rdp_run_pipeline_json}). Java builds
 * UTF-8 path strings and parses the returned JSON envelope; ingestion, Polars SQL, and Excel
 * reading run inside {@code rdp_jvm_sys}.
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
   * Keeps {@code docs/java/examples/ExcelSnippets.java} honest: payload ingest and {@code
   * rdp_excel_ingest_path_sheet} on {@code people.xlsx} (sheet {@value #PEOPLE_SHEET}). Generate
   * workbook if missing: {@code cargo run --features excel_test_writer --bin
   * generate_people_xlsx_fixture}.
   */
  @Test
  void excelSnippetsPeopleMatchesDocsExampleWhenFixturePresent() throws Throwable {
    Optional<Path> peopleXlsx = RdpJvmSysTestSupport.resolveFixtureFile(PEOPLE_XLSX_FIXTURE);
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
      JvmNativeContractScenarios.runExcelSnippetsPeopleContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/SQLQueries.java} single-table sketch honest: Polars SQL on
   * {@code df} after ingest.
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

  /**
   * Keeps {@code docs/java/examples/GhcnJsonXmlParquetPipeline.java} honest: committed GHCN sample
   * JSON → {@code xml_file} → {@code parquet_file} with three bundle schemas under {@code
   * tests/fixtures/ghcn/}.
   */
  @Test
  void ghcnJsonXmlParquetPipelineMatchesDocsExample() throws Throwable {
    Optional<Path> sample =
        RdpJvmSysTestSupport.resolveFixtureFile("ghcn/ghcn_stations_sample.json");
    Assumptions.assumeTrue(
        sample.isPresent(),
        "Missing tests/fixtures/ghcn/ghcn_stations_sample.json — add GHCN bundle (see ghcn/SOURCES.md)");

    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runGhcnJsonXmlParquetPipelineContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/RDPOnlyETLExample.java} legacy three-path pipeline honest:
   * {@code student_etl/data/part-*.json} → {@code rdp_run_pipeline_json}.
   */
  @Test
  void studentEtlLegacyThreePathsMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runStudentEtlLegacyThreePathsContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/RDPOnlyETLExample.java} ordered ingest sketch honest: {@code
   * ordered_ingest_dataset_2paths.payload.json} on two committed parts.
   */
  @Test
  void studentEtlOrderedIngestTwoPartsMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runStudentEtlOrderedIngestTwoPartsContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/PathFromDirectoryScan.java} honest: directory glob + watermark
   * payload from {@code tests/fixtures/watermark/}.
   */
  @Test
  void pathFromDirectoryScanWatermarkMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPathFromDirectoryScanWatermarkContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/JsonParquetExcelSnippets.java} honest: people JSON/CSV
   * payloads, path ingest, and Parquet pipeline round-trip.
   */
  @Test
  void jsonParquetExcelSnippetsPeopleMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runJsonParquetExcelSnippetsPeopleContract(linker, lookup, arena);
    }
  }

  /**
   * Keeps {@code docs/java/examples/ParquetSnippets.java} honest: {@code
   * csv_to_parquet.pipeline.json} + {@code people_flat} path ingest.
   */
  @Test
  void parquetSnippetsCsvToParquetRoundTripMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runParquetSnippetsCsvToParquetRoundTripContract(
          linker, lookup, arena);
    }
  }

  /** {@code ParquetSnippets#exportParquetTempEnvelope} ({@code rdp_export_parquet_temp}). */
  @Test
  void parquetSnippetsExportTempMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runParquetSnippetsExportTempContract(linker, lookup, arena);
    }
  }
}
