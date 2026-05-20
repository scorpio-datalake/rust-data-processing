package io.github.scorpio_datalake.rust_data_processing.examples;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import org.json.JSONObject;

/**
 * Runs several {@code rdp_parity_*} exports in one process so you can see JSON envelopes end to end
 * (bindings, mapping spec, transform, processing, SQL, validation, benchmark smoke). With
 * arguments, runs only the symbols you name (must appear in {@code ffi_manifest.json}).
 *
 * <p>For production, prefer running large ETL in Rust and writing Parquet/CSV/DB instead of pulling
 * huge {@code interchange.dataset} JSON into the JVM; this walkthrough is for contract visibility
 * and small payloads (see {@code docs/java/EXAMPLES.md} § Rust-first ETL vs JVM consumption).
 *
 * <pre>
 *   java ... ParityScenariosWalkthrough
 *   java ... ParityScenariosWalkthrough rdp_parity_transform rdp_parity_processing
 * </pre>
 */
public final class ParityScenariosWalkthrough {

  /**
   * Order mirrors common docs: types → bindings → mapping → transform → processing → SQL →
   * validation → smoke.
   */
  private static final List<String> DEFAULT_EXPORTS =
      List.of(
          "rdp_parity_types_dataset",
          "rdp_parity_bindings_mirror",
          "rdp_parity_mapping_spec_mirror",
          "rdp_parity_transform",
          "rdp_parity_processing",
          "rdp_parity_pipeline_sql",
          "rdp_parity_validation",
          "rdp_parity_benchmark_smoke_mirror");

  private ParityScenariosWalkthrough() {}

  public static void main(String[] args) throws Throwable {
    Optional<Path> lib = ExamplesNativeLibrary.resolveNativeLibraryPath();
    if (lib.isEmpty()) {
      System.err.println(ExamplesNativeLibrary.missingLibraryMessage());
      System.exit(2);
    }
    List<String> exports = args.length == 0 ? DEFAULT_EXPORTS : Arrays.asList(args);

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      System.out.println("Native library: " + lib.get());
      System.out.println(
          "ABI (rdp_ffi_abi_version): " + RdpNativeJson.invokeAbiVersion(linker, lookup));
      System.out.println("---");
      for (String name : exports) {
        runOne(linker, lookup, arena, name);
      }
      System.out.println("---");
      System.out.println("Done (" + exports.size() + " export(s)).");
    }
  }

  private static void runOne(Linker linker, SymbolLookup lookup, Arena arena, String exportName)
      throws Throwable {
    System.out.println(exportName);
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, exportName);
    if (exportName.endsWith("_mirror")) {
      PytestMirrorAssertions.validateMirrorExport(exportName, root);
    } else {
      PytestMirrorAssertions.assertEnvelopeOk(root);
    }
    printInterchangeSummary(root);
    System.out.println();
  }

  private static void printInterchangeSummary(JSONObject root) {
    JSONObject ic = root.getJSONObject("interchange");
    String kind = ic.optString("kind", "");
    System.out.println("  ok=true  kind=" + (kind.isEmpty() ? "—" : kind));
    if (ic.has("dataset")) {
      JSONObject ds = ic.getJSONObject("dataset");
      var rows = ds.optJSONArray("rows");
      var schema = ds.optJSONArray("schema");
      int n = rows != null ? rows.length() : -1;
      int cols = schema != null ? schema.length() : -1;
      System.out.println("  dataset: schema.fields=" + cols + " rows=" + n);
    }
    if (ic.has("filtered_row_count")) {
      System.out.println(
          "  processing: filtered_row_count="
              + ic.getInt("filtered_row_count")
              + " mapped_row_count="
              + ic.optInt("mapped_row_count", -1));
    }
    if (ic.has("summary") && ic.get("summary") instanceof JSONObject) {
      JSONObject s = ic.getJSONObject("summary");
      System.out.println("  validation summary keys: " + s.keySet());
    }
    if (ic.has("wide_row_count")) {
      System.out.println(
          "  benchmark smoke: wide_row_count="
              + ic.getInt("wide_row_count")
              + " filtered_rows="
              + ic.optInt("filtered_rows", -1)
              + " parallel_filter_rows="
              + ic.optInt("parallel_filter_rows", -1));
    }
  }
}
