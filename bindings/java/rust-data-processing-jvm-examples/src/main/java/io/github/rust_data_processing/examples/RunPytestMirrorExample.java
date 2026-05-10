package io.github.rust_data_processing.examples;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import java.util.Optional;
import org.json.JSONObject;

/**
 * Runs one pytest-mirror FFI export (see {@code parity_mirrors.rs}). Usage:
 *
 * <pre>
 *   java ... RunPytestMirrorExample rdp_parity_bindings_mirror
 * </pre>
 *
 * Maps to {@code python-wrapper/tests/test_bindings.py}, {@code test_mapping_spec.py}, etc.
 */
public final class RunPytestMirrorExample {

  private RunPytestMirrorExample() {}

  public static void main(String[] args) throws Throwable {
    if (args.length != 1) {
      System.err.println(
          "Usage: RunPytestMirrorExample <export>\n"
              + "Example: RunPytestMirrorExample rdp_parity_bindings_mirror");
      System.exit(2);
    }
    Optional<Path> lib = ExamplesNativeLibrary.resolveNativeLibraryPath();
    if (lib.isEmpty()) {
      System.err.println(ExamplesNativeLibrary.missingLibraryMessage());
      System.exit(2);
    }
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, args[0]);
      if (args[0].endsWith("_mirror")) {
        PytestMirrorAssertions.validateMirrorExport(args[0], root);
      } else {
        PytestMirrorAssertions.assertEnvelopeOk(root);
      }
      System.out.println(root.toString(2));
    }
  }
}
