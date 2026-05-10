package io.github.rust_data_processing;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
import java.nio.file.Path;
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
      assertEquals(402, o.getInt("abi_version_constant"));
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
}
