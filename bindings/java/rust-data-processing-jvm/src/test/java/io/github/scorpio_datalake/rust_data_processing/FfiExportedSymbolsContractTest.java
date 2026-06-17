package io.github.scorpio_datalake.rust_data_processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import io.github.scorpio_datalake.rust_data_processing.support.RdpJvmSysTestSupport;
import io.github.scorpio_datalake.rust_data_processing.testsupport.JvmNativeContractScenarios;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.GroupLayout;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Contract tests: every symbol listed in {@code ffi_manifest.json} (classpath) must resolve in
 * {@code rdp_jvm_sys} and match the manifest ABI when applicable. Doc-aligned scenarios shared with
 * {@code
 * io.github.scorpio_datalake.rust_data_processing.docexamples.DocsExampleNativeIntegrationTest}
 * live in {@code
 * io.github.scorpio_datalake.rust_data_processing.testsupport.JvmNativeContractScenarios}.
 */
final class FfiExportedSymbolsContractTest {

  private static final String MANIFEST_RESOURCE = RdpNativeJson.FFI_MANIFEST_RESOURCE;

  private static final GroupLayout RDP_JSON_SLICE_LAYOUT =
      MemoryLayout.structLayout(
          ValueLayout.ADDRESS.withName("ptr"),
          ValueLayout.JAVA_LONG.withName("len"),
          ValueLayout.JAVA_LONG.withName("cap"));

  @Test
  void classpathFfiManifestReadable() throws Exception {
    try (InputStream in =
        FfiExportedSymbolsContractTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
      assertNotNull(
          in,
          "Bundled ffi_manifest.json missing from classpath (expected under"
              + " src/main/resources/io/github/scorpio_datalake/rust_data_processing/ in rust-data-processing-jvm).");
      JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(406, o.getInt("abi_version_constant"));
      JSONArray syms = o.getJSONArray("exported_symbols");
      boolean hasAbi = false;
      for (int i = 0; i < syms.length(); i++) {
        if ("rdp_ffi_abi_version".equals(syms.getString(i))) {
          hasAbi = true;
          break;
        }
      }
      assertTrue(hasAbi, "exported_symbols must include rdp_ffi_abi_version");
    }
  }

  @Test
  void everyExportedSymbolIsCallablePerManifest() throws Throwable {
    JSONObject manifest = readManifest();
    int expectedAbi = manifest.getInt("abi_version_constant");
    JSONArray exported = manifest.getJSONArray("exported_symbols");

    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup nativeLib = SymbolLookup.libraryLookup(lib.get(), arena);
      for (int i = 0; i < exported.length(); i++) {
        String name = exported.getString(i);
        invokeExportedSymbol(linker, nativeLib, arena, name, expectedAbi);
      }
    }
  }

  private static JSONObject readManifest() throws Exception {
    try (InputStream in =
        FfiExportedSymbolsContractTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
      assertNotNull(in, "Missing " + MANIFEST_RESOURCE);
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }

  private static void invokeExportedSymbol(
      Linker linker, SymbolLookup lookup, Arena arena, String name, int expectedAbi)
      throws Throwable {
    switch (name) {
      case "rdp_ffi_abi_version":
        assertEquals(expectedAbi, RdpNativeJson.invokeAbiVersion(linker, lookup));
        return;
      case "rdp_json_slice_free":
        MemorySegment empty = arena.allocate(RDP_JSON_SLICE_LAYOUT);
        MethodHandle free =
            linker.downcallHandle(
                lookup.find("rdp_json_slice_free").orElseThrow(),
                FunctionDescriptor.ofVoid(RDP_JSON_SLICE_LAYOUT));
        free.invokeExact(empty);
        return;
      case "rdp_export_parquet_temp":
        {
          JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
          PytestMirrorAssertions.assertEnvelopeOk(root);
          JSONObject interchange = root.getJSONObject("interchange");
          assertEquals("parquet_export_temp", interchange.getString("kind"));
          assertEquals(2, interchange.getInt("row_count"));
          Path parquetPath = Path.of(interchange.getString("path"));
          assertTrue(Files.exists(parquetPath), "parquet file missing: " + parquetPath);
          Files.deleteIfExists(parquetPath);
          return;
        }
      case "rdp_export_arrow_ipc_temp":
        {
          JSONObject root = RdpNativeJson.invokeExportArrowIpcTemp(linker, lookup, arena);
          PytestMirrorAssertions.assertEnvelopeOk(root);
          JSONObject interchange = root.getJSONObject("interchange");
          assertEquals("arrow_ipc_export_temp", interchange.getString("kind"));
          assertEquals(2, interchange.getInt("row_count"));
          Path ipcPath = Path.of(interchange.getString("path"));
          assertTrue(Files.exists(ipcPath), "arrow ipc file missing: " + ipcPath);
          Files.deleteIfExists(ipcPath);
          return;
        }
      case "rdp_export_polars_parquet_temp":
        {
          JSONObject root = RdpNativeJson.invokeExportPolarsParquetTemp(linker, lookup, arena);
          PytestMirrorAssertions.assertEnvelopeOk(root);
          JSONObject interchange = root.getJSONObject("interchange");
          assertEquals("polars_parquet_export_temp", interchange.getString("kind"));
          assertEquals(1, interchange.getInt("row_count"));
          Path polarsPath = Path.of(interchange.getString("path"));
          assertTrue(Files.exists(polarsPath), "polars parquet file missing: " + polarsPath);
          Files.deleteIfExists(polarsPath);
          return;
        }
      case "rdp_excel_ingest_path_sheet":
        JvmNativeContractScenarios.excelIngestPathSheetContract(linker, lookup, arena);
        return;
      case "rdp_ingest_csv_path":
        {
          Optional<Path> fixtures = RdpJvmSysTestSupport.resolveTestsFixturesDir();
          Assumptions.assumeTrue(
              fixtures.isPresent(), "Skip when repo tests/fixtures is not discoverable from CWD");
          Path csv = fixtures.get().resolve("people.csv");
          Assumptions.assumeTrue(Files.exists(csv), "Skip when tests/fixtures/people.csv missing");
          String schema =
              io.github.scorpio_datalake.rust_data_processing.testsupport.PipelineFixtureSupport
                  .loadPeopleSchemaJson("schemas/people_csv.schema.json");
          JSONObject root =
              RdpNativeJson.invokeIngestCsvPath(
                  linker, lookup, arena, csv.toString(), schema, "{}");
          PytestMirrorAssertions.assertEnvelopeOk(root);
          assertEquals("ingest_path_csv", root.getJSONObject("interchange").getString("kind"));
          assertEquals(
              2,
              root.getJSONObject("interchange")
                  .getJSONObject("dataset")
                  .getJSONArray("rows")
                  .length());
          return;
        }
      case "rdp_ingest_json_path":
        {
          Optional<Path> fixtures = RdpJvmSysTestSupport.resolveTestsFixturesDir();
          Assumptions.assumeTrue(
              fixtures.isPresent(), "Skip when repo tests/fixtures is not discoverable from CWD");
          Path json = fixtures.get().resolve("people.json");
          Assumptions.assumeTrue(
              Files.exists(json), "Skip when tests/fixtures/people.json missing");
          String schema =
              io.github.scorpio_datalake.rust_data_processing.testsupport.PipelineFixtureSupport
                  .loadPeopleSchemaJson("schemas/people_json.schema.json");
          JSONObject root =
              RdpNativeJson.invokeIngestJsonPath(
                  linker, lookup, arena, json.toString(), schema, "{}");
          PytestMirrorAssertions.assertEnvelopeOk(root);
          assertEquals("ingest_path_json", root.getJSONObject("interchange").getString("kind"));
          assertEquals(
              2,
              root.getJSONObject("interchange")
                  .getJSONObject("dataset")
                  .getJSONArray("rows")
                  .length());
          return;
        }
      case "rdp_ingest_parquet_path":
        {
          Optional<Path> fixtures = RdpJvmSysTestSupport.resolveTestsFixturesDir();
          Assumptions.assumeTrue(
              fixtures.isPresent(), "Skip when repo tests/fixtures is not discoverable from CWD");
          Path csv = fixtures.get().resolve("people.csv");
          Assumptions.assumeTrue(Files.exists(csv), "Skip when tests/fixtures/people.csv missing");
          String schema = "{\"fields\":[{\"name\":\"id\",\"data_type\":\"Int64\"}]}";
          JSONObject root =
              RdpNativeJson.invokeIngestParquetPath(
                  linker, lookup, arena, csv.toString(), schema, "{}");
          assertFalse(root.getBoolean("ok"), "CSV path with Parquet ingest should fail");
          return;
        }
      case "rdp_ingest_xml_path":
        {
          Path xml =
              JvmNativeContractScenarios.requireJvmContractFixture(
                  "ghcn/ghcn_stations_intermediate.xml");
          Path bundle =
              io.github.scorpio_datalake.rust_data_processing.testsupport.PipelineFixtureSupport
                  .resolveBundleRoot("ghcn")
                  .orElseThrow();
          String schema =
              io.github.scorpio_datalake.rust_data_processing.testsupport.PipelineFixtureSupport
                  .loadSchemaJson(bundle, "schemas/xml_intermediate.schema.json");
          JSONObject root =
              RdpNativeJson.invokeIngestXmlPath(
                  linker, lookup, arena, xml.toString(), schema, "{}");
          PytestMirrorAssertions.assertEnvelopeOk(root);
          assertEquals("ingest_path_xml", root.getJSONObject("interchange").getString("kind"));
          assertEquals(
              5,
              root.getJSONObject("interchange")
                  .getJSONObject("dataset")
                  .getJSONArray("rows")
                  .length());
          return;
        }
      case "rdp_ingest_ordered_paths_json":
        JvmNativeContractScenarios.ingestOrderedPathsContract(linker, lookup, arena);
        return;
      case "rdp_run_pipeline_json":
        JvmNativeContractScenarios.runPipelineJsonContract(linker, lookup, arena);
        return;
      case "rdp_kafka_elt_load_records_json":
        JvmNativeContractScenarios.runKafkaEltLoadRecordsJsonContract(linker, lookup, arena);
        return;
      case "rdp_kafka_poll_window_json":
        {
          JSONObject root =
              RdpNativeJson.invokeKafkaPollWindowJson(
                  linker,
                  lookup,
                  arena,
                  "{\"brokers\":\"\",\"group_id\":\"g\",\"topic\":\"t\",\"max_records\":1}");
          assertFalse(root.getBoolean("ok"), "empty brokers should fail validation");
          return;
        }
      case "rdp_kafka_poll_window_loaded_json":
        {
          String schema = "{\"fields\":[{\"name\":\"id\",\"data_type\":\"Int64\"}]}";
          JSONObject root =
              RdpNativeJson.invokeKafkaPollWindowLoadedJson(
                  linker,
                  lookup,
                  arena,
                  "{\"brokers\":\"\",\"group_id\":\"g\",\"topic\":\"t\",\"max_records\":1}",
                  schema);
          assertFalse(root.getBoolean("ok"), "empty brokers should fail validation");
          return;
        }
      case "rdp_kafka_export_dataset_json":
        {
          JSONObject root =
              RdpNativeJson.invokeKafkaExportDatasetJson(
                  linker,
                  lookup,
                  arena,
                  "{\"brokers\":\"\",\"topic\":\"t\"}",
                  "{\"schema\":{\"fields\":[]},\"rows\":[]}");
          assertFalse(root.getBoolean("ok"), "empty brokers should fail validation");
          return;
        }
      default:
        if (name.startsWith("rdp_parity_")) {
          JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, name);
          assertParityEnvelope(name, root);
          return;
        }
        fail(
            "ffi_manifest.json lists `"
                + name
                + "` — add a Panama downcall + assertion in FfiExportedSymbolsContractTest (or extend"
                + " JvmNativeContractScenarios if the scenario is shared with doc example tests)");
    }
  }

  private static void assertParityEnvelope(String name, JSONObject root) {
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");

    switch (name) {
      case "rdp_parity_bindings_mirror":
        PytestMirrorAssertions.assertBindingsMirror(interchange);
        return;
      case "rdp_parity_mapping_spec_mirror":
        PytestMirrorAssertions.assertMappingSpecMirror(interchange);
        return;
      case "rdp_parity_sql_suite_mirror":
        PytestMirrorAssertions.assertSqlSuiteMirror(interchange);
        return;
      case "rdp_parity_partition_discovery_mirror":
        PytestMirrorAssertions.assertPartitionDiscoveryMirror(interchange);
        return;
      case "rdp_parity_watermark_mirror":
        PytestMirrorAssertions.assertWatermarkMirror(interchange);
        return;
      case "rdp_parity_deep_seattle_mirror":
        PytestMirrorAssertions.assertDeepSeattleMirror(interchange);
        return;
      case "rdp_parity_sft_sample_mirror":
        PytestMirrorAssertions.assertSftSampleMirror(interchange);
        return;
      case "rdp_parity_benchmark_smoke_mirror":
        PytestMirrorAssertions.assertBenchmarkSmokeMirror(interchange);
        return;
      case "rdp_parity_observability_mirror":
        PytestMirrorAssertions.assertObservabilityMirror(interchange);
        return;
      default:
        break;
    }

    String kind =
        switch (name) {
          case "rdp_parity_types_dataset" -> "types_dataset";
          case "rdp_parity_ingestion" -> "ingestion_csv_reader_polars";
          case "rdp_parity_processing" -> "processing_filter_map_reduce";
          case "rdp_parity_pipeline_sql" -> "pipeline_sql_polars";
          case "rdp_parity_profiling" -> "profiling_polars";
          case "rdp_parity_validation" -> "validation_polars_dsl";
          case "rdp_parity_outliers" -> "outliers_polars";
          case "rdp_parity_transform" -> "transform_spec_polars";
          case "rdp_parity_cdc" -> "cdc_boundary_types";
          case "rdp_parity_export_privacy_reports" -> "export_privacy_reports_phase2";
          case "rdp_parity_kafka" -> "kafka";
          default -> throw new IllegalArgumentException("unexpected parity symbol: " + name);
        };
    assertEquals(kind, interchange.getString("kind"));

    switch (name) {
      case "rdp_parity_types_dataset":
      case "rdp_parity_ingestion":
      case "rdp_parity_pipeline_sql":
      case "rdp_parity_transform":
        JSONObject ds = interchange.getJSONObject("dataset");
        assertTrue(ds.has("schema"), "tabular JSON schema for Java maps");
        assertTrue(ds.has("rows"), "tabular JSON rows for Java List<Map> projection");
        break;
      case "rdp_parity_processing":
        assertEquals(1, interchange.getInt("filtered_row_count"));
        assertEquals(1, interchange.getInt("mapped_row_count"));
        break;
      case "rdp_parity_validation":
        assertTrue(interchange.getJSONObject("summary").getInt("failed_checks") >= 1);
        break;
      default:
        break;
    }
  }

  @Test
  void runPipelineJsonInvalidPayloadHasStructuredError() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      JSONObject root = RdpNativeJson.invokeRunPipelineJson(linker, lookup, arena, "not json");
      assertFalse(root.getBoolean("ok"));
      JSONObject err = root.getJSONObject("error");
      assertEquals("ORCHESTRATION_JSON_INVALID", err.getString("code"));
      assertEquals("parse", err.getString("stage"));
    }
  }
}
