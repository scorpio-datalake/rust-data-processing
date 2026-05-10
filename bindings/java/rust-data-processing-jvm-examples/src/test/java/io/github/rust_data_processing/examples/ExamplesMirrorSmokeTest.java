package io.github.rust_data_processing.examples;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import java.util.stream.Stream;
import org.json.JSONObject;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * Ensures each pytest mirror export used in CI matches {@code PytestMirrorAssertions} — same checks
 * as {@code FfiExportedSymbolsContractTest} for mirror symbols.
 */
final class ExamplesMirrorSmokeTest {

  static Stream<Arguments> mirrorExports() {
    return Stream.of(
        Arguments.of("rdp_parity_bindings_mirror", "test_bindings.py"),
        Arguments.of("rdp_parity_mapping_spec_mirror", "test_mapping_spec.py"),
        Arguments.of("rdp_parity_sql_suite_mirror", "test_sql_parity.py"),
        Arguments.of("rdp_parity_partition_discovery_mirror", "test_partition_discovery.py"),
        Arguments.of("rdp_parity_watermark_mirror", "test_watermark_ingestion.py"),
        Arguments.of("rdp_parity_deep_seattle_mirror", "test_deep_parity.py (Seattle subset)"),
        Arguments.of("rdp_parity_sft_sample_mirror", "test_sft_sample.py"),
        Arguments.of("rdp_parity_benchmark_smoke_mirror", "test_benchmarks.py (smoke)"),
        Arguments.of("rdp_parity_observability_mirror", "test_observability_parity.py"));
  }

  @ParameterizedTest(name = "{1}")
  @MethodSource("mirrorExports")
  void mirrorExportMatchesPytestScenario(String exportName, @SuppressWarnings("unused") String label)
      throws Throwable {
    Optional<Path> lib = ExamplesNativeLibrary.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), ExamplesNativeLibrary.missingLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, exportName);
      PytestMirrorAssertions.validateMirrorExport(exportName, root);
    }
  }
}
