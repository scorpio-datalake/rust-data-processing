package io.github.rust_data_processing;

import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.rust_data_processing.support.RdpJvmSysTestSupport;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Ensures Phase 3 parity symbols {@code rdp_parity_*} resolve when {@code RDP_JVM_SYS} is set (CI).
 * Full JSON contracts live in {@link FfiExportedSymbolsContractTest}.
 */
final class ParityMatrixDeferredExportTest {

  static Stream<Arguments> parityExports() {
    return Stream.of(
        Arguments.of("rdp_parity_ingestion", "ingestion"),
        Arguments.of("rdp_parity_types_dataset", "types / Schema / DataSet"),
        Arguments.of("rdp_parity_processing", "processing (filter / map / reduce)"),
        Arguments.of("rdp_parity_pipeline_sql", "pipeline / SQL"),
        Arguments.of("rdp_parity_profiling", "profiling"),
        Arguments.of("rdp_parity_validation", "validation"),
        Arguments.of("rdp_parity_outliers", "outliers"),
        Arguments.of("rdp_parity_transform", "transform / TransformSpec"),
        Arguments.of("rdp_parity_cdc", "cdc"),
        Arguments.of("rdp_parity_export_privacy_reports", "export / privacy / reports (Phase 2)"),
        Arguments.of("rdp_parity_kafka", "Kafka (Phase 3 connectivity)"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("parityExports")
  @DisplayName("parity FFI export present")
  void parityExportPresent(String mangledSymbol, String label) throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      assertTrue(
          lookup.find(mangledSymbol).isPresent(),
          "Missing `"
              + mangledSymbol
              + "` in rdp_jvm_sys ("
              + label
              + "). See Planning/PHASE3_EPICS.md + ffi_manifest.json.");
    }
  }
}
