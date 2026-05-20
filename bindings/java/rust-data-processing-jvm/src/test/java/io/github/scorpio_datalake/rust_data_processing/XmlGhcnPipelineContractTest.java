package io.github.scorpio_datalake.rust_data_processing;

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
 * Committed GHCN JSON → Rust XML sink → Rust Parquet sink, with three distinct schemas. Input lives
 * under {@code tests/fixtures/ghcn/}; nothing is downloaded at test time. Doc-aligned walkthrough:
 * {@code docs/java/examples/GhcnJsonXmlParquetPipeline.java} (also exercised by {@code
 * DocsExampleNativeIntegrationTest#ghcnJsonXmlParquetPipelineMatchesDocsExample}).
 */
final class XmlGhcnPipelineContractTest {

  @Test
  void ghcnJsonToXmlToParquetUsesDistinctSchemas() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      JvmNativeContractScenarios.runGhcnJsonXmlParquetPipelineContract(linker, lookup, arena);
    }
  }
}
