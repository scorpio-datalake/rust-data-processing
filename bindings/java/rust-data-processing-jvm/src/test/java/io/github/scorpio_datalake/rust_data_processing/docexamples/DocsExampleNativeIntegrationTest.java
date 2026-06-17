package io.github.scorpio_datalake.rust_data_processing.docexamples;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.support.RdpJvmSysTestSupport;
import io.github.scorpio_datalake.rust_data_processing.testsupport.JvmNativeContractScenarios;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Integration tests that keep {@code docs/java/examples/*.java} honest in CI.
 *
 * <p><strong>Why these tests exist.</strong> Documentation examples are copy-paste sources; they
 * are not compiled into the JAR. Without JUnit, a Rust FFI or fixture change could break every
 * {@code main} on the docs site while unit tests still pass. Each method here names the doc file it
 * guards (see {@code JvmNativeContractScenarios} for assertions).
 *
 * <p><strong>What they prove.</strong> Given {@code RDP_JVM_SYS}, Panama can load symbols, Rust
 * returns {@code ok: true}, and interchange fields match committed fixtures (row counts, kinds,
 * temp Parquet paths). They do <em>not</em> re-test every symbol — see {@code
 * FfiExportedSymbolsContractTest} for manifest-wide smoke.
 *
 * <p><strong>RDP vs “plain Java”</strong> — tests never parse CSV/JSON/Excel in Java. Java builds
 * UTF-8 JSON payloads; ingestion, Polars SQL, and Excel run in {@code rdp_jvm_sys}.
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
   * Keeps {@code docs/java/examples/OrderedPaths.java} honest: directory scan + watermark payload
   * from {@code tests/fixtures/watermark/} (parity with Rust/Python path-scan fixtures).
   */
  @Test
  void orderedPathsDirectoryScanWatermarkMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runOrderedPathsDirectoryScanWatermarkContract(
          linker, lookup, arena);
    }
  }

  /**
   * Back-compat alias test for {@code docs/java/examples/PathFromDirectoryScan.java} (delegates to
   * {@link OrderedPaths}).
   */
  @Test
  void pathFromDirectoryScanWatermarkMatchesDocsExample() throws Throwable {
    orderedPathsDirectoryScanWatermarkMatchesDocsExample();
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

  /**
   * Guards {@code ExportJsonlTrainTest.java}: JSONL lines and train/test index demo from {@code
   * rdp_parity_export_privacy_reports} (Phase 2 §1).
   */
  @Test
  void exportJsonlTrainTestMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPhase2ExportJsonlTrainTestContract(linker, lookup, arena);
    }
  }

  /**
   * Guards {@code PrivacyDiffReports.java}: {@code privacy_report_json} shape after UTF-8 column
   * diff (Phase 2 §3).
   */
  @Test
  void privacyDiffReportsMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPhase2PrivacyDiffReportsContract(linker, lookup, arena);
    }
  }

  /**
   * Guards {@code ReportsTruncateUtf8.java}: {@code reports_truncated_sample} byte cap (Phase 2
   * §4).
   */
  @Test
  void reportsTruncateUtf8MatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPhase2ReportsTruncateUtf8Contract(linker, lookup, arena);
    }
  }

  /**
   * Guards {@code TransformUtf8Masking.java}: {@code rdp_parity_transform} dataset interchange
   * (Phase 2 §5; Utf8 masking still Python-first).
   */
  @Test
  void transformUtf8MaskingMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPhase2TransformUtf8MaskingContract(linker, lookup, arena);
    }
  }

  /** Guards {@code ValidationUtf8Length.java}: validation summary over FFI (Phase 2 §6). */
  @Test
  void validationUtf8LengthMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPhase2ValidationUtf8LengthContract(linker, lookup, arena);
    }
  }

  /**
   * Guards {@code IngestValidateJsonlEndToEnd.java}: {@code people.csv} ingest + validation + JSONL
   * preview chain (Phase 2 §9).
   */
  @Test
  void ingestValidateJsonlEndToEndMatchesDocsExample() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      JvmNativeContractScenarios.runPhase2IngestValidateJsonlEndToEndContract(
          linker, lookup, arena);
    }
  }

  /**
   * Guards {@code DeltaLakeHandoff.java}: no live lake in CI — verifies handoff docs and Parquet
   * ingest fixtures exist (Phase 2 §8).
   */
  @Test
  void deltaLakeHandoffPrerequisitesMatchDocsExample() throws Exception {
    Assumptions.assumeTrue(
        RdpJvmSysTestSupport.resolveTestsFixturesDir().isPresent(),
        "tests/fixtures not discoverable — run from repository checkout");
    JvmNativeContractScenarios.runPhase2DeltaLakeHandoffPrerequisitesContract();
  }

  @Test
  void quickStartIngestPeopleCsvMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runQuickStartIngestPeopleCsvContract);
  }

  @Test
  void partitionDiscoveryMirrorMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runPartitionDiscoveryMirrorContract);
  }

  @Test
  void ingestObservabilityMirrorMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runIngestObservabilityMirrorContract);
  }

  @Test
  void profilingParityMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runProfilingParityContract);
  }

  @Test
  void outlierDetectionParityMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runOutlierDetectionParityContract);
  }

  @Test
  void cdcBoundaryParityMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runCdcBoundaryParityContract);
  }

  @Test
  void processingReduceParityMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runProcessingReduceParityContract);
  }

  @Test
  void groupByAggregatesSqlSuiteMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runGroupByAggregatesSqlSuiteContract);
  }

  @Test
  void sqlJoinPipelineParityMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runSqlJoinPipelineParityContract);
  }

  @Test
  void cookbookMappingSpecMirrorMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runCookbookMappingSpecMirrorContract);
  }

  @Test
  void warehouseExportHandoffMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runWarehouseExportHandoffContract);
  }

  @Test
  void inferredSchemaExcelIngestMatchesDocsExample() throws Throwable {
    Optional<Path> peopleXlsx = RdpJvmSysTestSupport.resolveFixtureFile(PEOPLE_XLSX_FIXTURE);
    Assumptions.assumeTrue(
        peopleXlsx.isPresent(),
        "Missing tests/fixtures/"
            + PEOPLE_XLSX_FIXTURE
            + " — from repo root: python scripts/write_people_xlsx_stdlib.py");
    runWithNative(JvmNativeContractScenarios::runInferredSchemaExcelIngestContract);
  }

  @Test
  void objectStoreUrlsFilePipelineMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runObjectStoreUrlsFilePipelineContract);
  }

  @Test
  void platformConnectorsFilePipelineMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runPlatformConnectorsFilePipelineContract);
  }

  /** {@code DbReadPipelineExample} — template resolution only (no warehouse connection). */
  @Test
  void dbReadPipelineTemplateMatchesDocsExample() throws Exception {
    Assumptions.assumeTrue(
        RdpJvmSysTestSupport.resolveTestsFixturesDir().isPresent(),
        "tests/fixtures not discoverable — run from repository checkout");
    JvmNativeContractScenarios.runDbReadPipelineTemplateContract();
  }

  /** {@code SftpFtpConnectorsExample} — pipeline JSON only (no loopback FTP in JVM CI). */
  @Test
  void sftpFtpPipelineTemplateMatchesDocsExample() throws Exception {
    Assumptions.assumeTrue(
        RdpJvmSysTestSupport.resolveTestsFixturesDir().isPresent(),
        "tests/fixtures not discoverable — run from repository checkout");
    JvmNativeContractScenarios.runSftpFtpPipelineTemplateContract();
  }

  /** {@code KafkaEltLoadExample} — Load via {@code rdp_kafka_elt_load_records_json} (no broker). */
  @Test
  void kafkaEltLoadMatchesDocsExample() throws Throwable {
    runWithNative(JvmNativeContractScenarios::runKafkaEltLoadRecordsJsonContract);
  }

  @FunctionalInterface
  private interface NativeScenario {
    void run(Linker linker, SymbolLookup lookup, Arena arena) throws Throwable;
  }

  private static void runWithNative(NativeScenario scenario) throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      RdpNativeJson.invokeAbiVersion(linker, lookup);
      scenario.run(linker, lookup, arena);
    }
  }
}
