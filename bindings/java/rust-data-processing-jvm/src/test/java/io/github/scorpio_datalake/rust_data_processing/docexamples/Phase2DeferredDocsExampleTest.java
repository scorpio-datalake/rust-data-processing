package io.github.scorpio_datalake.rust_data_processing.docexamples;

import io.github.scorpio_datalake.rust_data_processing.support.RdpJvmSysTestSupport;
import java.lang.foreign.Arena;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

/**
 * Placeholder tests for Phase 2 doc examples that are <strong>documentation-only</strong> until new
 * symbols appear in {@code ffi_manifest.json}.
 *
 * <p><strong>Why this class exists.</strong> {@code ExportFilterRowsMaxUtf8Chars.java} and {@code
 * MedianReduceAndDataFrame.java} describe Rust APIs that Python already exposes but the JVM does not
 * call yet. We still want a JUnit hook so that when a maintainer adds {@code
 * rdp_parity_export_filter_rows_max_utf8_chars} (or similar), CI fails until the real contract is
 * implemented — instead of leaving silent doc drift.
 *
 * <p><strong>Behavior today.</strong> Each test assumes the native library is present, then {@code
 * assumeTrue(symbol exists)}. The symbol is missing, so tests <em>skip</em> with an explicit message.
 * That skip is intentional, not a pass.
 */
final class Phase2DeferredDocsExampleTest {

  private static final String EXPORT_FILTER_UTF8 = "rdp_parity_export_filter_rows_max_utf8_chars";
  private static final String MEDIAN_REDUCE = "rdp_parity_median_reduce_and_groupby";

  /**
   * Future guard for {@code ExportFilterRowsMaxUtf8Chars.java} (Phase 2 §2). When the symbol is added,
   * replace this skip with {@code JvmNativeContractScenarios.runPhase2ExportFilterRows…} and assert
   * only the short UTF-8 row survives {@code max_chars=10}.
   */
  @Test
  void exportFilterRowsMaxUtf8CharsWhenFfiPresent() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      Assumptions.assumeTrue(
          lookup.find(EXPORT_FILTER_UTF8).isPresent(),
          "Deferred: "
              + EXPORT_FILTER_UTF8
              + " not in rdp_jvm_sys — see docs/java/examples/ExportFilterRowsMaxUtf8Chars.java");
    }
  }

  /**
   * Future guard for {@code MedianReduceAndDataFrame.java} (Phase 2 §7). When median parity lands,
   * assert {@code processing_reduce} median value and/or grouped median row counts match Python §7.
   */
  @Test
  void medianReduceAndDataFrameWhenFfiPresent() throws Throwable {
    Optional<Path> lib = RdpJvmSysTestSupport.resolveNativeLibraryPath();
    Assumptions.assumeTrue(lib.isPresent(), RdpJvmSysTestSupport.missingNativeLibraryMessage());

    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      Assumptions.assumeTrue(
          lookup.find(MEDIAN_REDUCE).isPresent(),
          "Deferred: "
              + MEDIAN_REDUCE
              + " not in rdp_jvm_sys — see docs/java/examples/MedianReduceAndDataFrame.java");
    }
  }
}
