package io.github.rust_data_processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import io.github.rust_data_processing.support.RdpJvmSysTestSupport;
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
import java.util.Comparator;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Contract tests: every symbol listed in {@code ffi_manifest.json} (classpath) must resolve in
 * {@code rdp_jvm_sys} and match the manifest ABI when applicable.
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
              + " src/main/resources/io/github/rust_data_processing/ in rust-data-processing-jvm).");
      JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(404, o.getInt("abi_version_constant"));
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
      Linker linker, SymbolLookup lookup, Arena arena, String name, int expectedAbi) throws Throwable {
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
        {
          Path excelPath =
              Path.of("tests")
                  .resolve("fixtures")
                  .resolve("people.xlsx")
                  .toAbsolutePath();
          Assumptions.assumeTrue(
              Files.exists(excelPath),
              "Skip Excel FFI contract when tests/fixtures/people.xlsx is not present");
          JSONObject root =
              RdpNativeJson.excelIngestPathSheet(
                  linker, lookup, arena, excelPath.toString(), "Sheet1");
          PytestMirrorAssertions.assertEnvelopeOk(root);
          root.getJSONObject("interchange").getJSONObject("dataset");
          return;
        }
      case "rdp_ingest_csv_path":
        {
          Path csv =
              Path.of("tests").resolve("fixtures").resolve("people.csv").toAbsolutePath();
          Assumptions.assumeTrue(
              Files.exists(csv), "Skip when repo tests/fixtures/people.csv is not cwd-relative");
          String schema =
              "{\"fields\":["
                  + "{\"name\":\"id\",\"data_type\":\"Int64\"},"
                  + "{\"name\":\"name\",\"data_type\":\"Utf8\"},"
                  + "{\"name\":\"score\",\"data_type\":\"Float64\"},"
                  + "{\"name\":\"active\",\"data_type\":\"Bool\"}"
                  + "]}";
          JSONObject root =
              RdpNativeJson.invokeIngestCsvPath(linker, lookup, arena, csv.toString(), schema, "{}");
          PytestMirrorAssertions.assertEnvelopeOk(root);
          assertEquals(
              "ingest_path_csv", root.getJSONObject("interchange").getString("kind"));
          assertEquals(2, root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length());
          return;
        }
      case "rdp_ingest_json_path":
        {
          Path json =
              Path.of("tests").resolve("fixtures").resolve("people.json").toAbsolutePath();
          Assumptions.assumeTrue(
              Files.exists(json), "Skip when repo tests/fixtures/people.json is not cwd-relative");
          String schema =
              "{\"fields\":["
                  + "{\"name\":\"id\",\"data_type\":\"Int64\"},"
                  + "{\"name\":\"user.name\",\"data_type\":\"Utf8\"},"
                  + "{\"name\":\"score\",\"data_type\":\"Float64\"},"
                  + "{\"name\":\"active\",\"data_type\":\"Bool\"}"
                  + "]}";
          JSONObject root =
              RdpNativeJson.invokeIngestJsonPath(
                  linker, lookup, arena, json.toString(), schema, "{}");
          PytestMirrorAssertions.assertEnvelopeOk(root);
          assertEquals(
              "ingest_path_json", root.getJSONObject("interchange").getString("kind"));
          assertEquals(2, root.getJSONObject("interchange").getJSONObject("dataset").getJSONArray("rows").length());
          return;
        }
      case "rdp_ingest_parquet_path":
        {
          Path csv =
              Path.of("tests").resolve("fixtures").resolve("people.csv").toAbsolutePath();
          Assumptions.assumeTrue(Files.exists(csv), "Skip when tests/fixtures/people.csv missing");
          String schema = "{\"fields\":[{\"name\":\"id\",\"data_type\":\"Int64\"}]}";
          JSONObject root =
              RdpNativeJson.invokeIngestParquetPath(
                  linker, lookup, arena, csv.toString(), schema, "{}");
          assertFalse(root.getBoolean("ok"), "CSV path with Parquet ingest should fail");
          return;
        }
      case "rdp_ingest_ordered_paths_json":
        ingestOrderedPathsContract(linker, lookup, arena);
        return;
      default:
        if (name.startsWith("rdp_parity_")) {
          JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, name);
          assertParityEnvelope(name, root);
          return;
        }
        fail(
            "ffi_manifest.json lists `"
                + name
                + "` — add a Panama downcall + assertion in FfiExportedSymbolsContractTest");
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

  private static void ingestOrderedPathsContract(Linker linker, SymbolLookup lookup, Arena arena)
      throws Throwable {
    Path dir = Files.createTempDirectory("rdp_contract_ordered_");
    try {
      Path p1 = dir.resolve("a.csv");
      Path p2 = dir.resolve("b.csv");
      Files.writeString(p1, "id,name\n1,A\n");
      Files.writeString(p2, "id,name\n2,B\n");
      String abspath1 = p1.toAbsolutePath().toString();
      String abspath2 = p2.toAbsolutePath().toString();
      String schemaJson =
          "{\"fields\":["
              + "{\"name\":\"id\",\"data_type\":\"Int64\"},"
              + "{\"name\":\"name\",\"data_type\":\"Utf8\"}"
              + "]}";

      JSONObject payloadDataset =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "dataset").put("max_rows", 50));
      JSONObject rootDataset =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadDataset.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootDataset);
      JSONObject interD = rootDataset.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_dataset", interD.getString("kind"));
      assertEquals(2, interD.getInt("total_row_count"));
      assertEquals(2, interD.getInt("returned_row_count"));
      assertFalse(interD.getBoolean("truncated"));

      JSONObject payloadTruncate =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "dataset").put("max_rows", 1));
      JSONObject rootTruncate =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadTruncate.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootTruncate);
      JSONObject interT = rootTruncate.getJSONObject("interchange");
      assertTrue(interT.getBoolean("truncated"));
      assertEquals(1, interT.getInt("returned_row_count"));
      assertEquals(2, interT.getInt("total_row_count"));

      JSONObject payloadParquet =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "parquet_temp"));
      JSONObject rootParquet =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadParquet.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootParquet);
      JSONObject interP = rootParquet.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_parquet_temp", interP.getString("kind"));
      Path parquetOut = Path.of(interP.getString("path"));
      assertTrue(Files.exists(parquetOut));
      Files.deleteIfExists(parquetOut);

      JSONObject payloadArrow =
          new JSONObject()
              .put("paths", new JSONArray().put(abspath1).put(abspath2))
              .put("schema", new JSONObject(schemaJson))
              .put("options", new JSONObject().put("format", "csv"))
              .put("response", new JSONObject().put("mode", "arrow_ipc_temp"));
      JSONObject rootArrow =
          RdpNativeJson.invokeIngestOrderedPathsJson(
              linker, lookup, arena, payloadArrow.toString());
      PytestMirrorAssertions.assertEnvelopeOk(rootArrow);
      JSONObject interA = rootArrow.getJSONObject("interchange");
      assertEquals("ingest_ordered_paths_arrow_ipc_temp", interA.getString("kind"));
      Path arrowOut = Path.of(interA.getString("path"));
      assertTrue(Files.exists(arrowOut));
      Files.deleteIfExists(arrowOut);
    } finally {
      try (var walk = Files.walk(dir)) {
        for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
          Files.deleteIfExists(p);
        }
      }
    }
  }
}
