package io.github.vihangdesai2018_png.rdp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import io.github.vihangdesai2018_png.rdp.support.RdpJvmSysTestSupport;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
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
 * {@code rdp_jvm_sys} and match the manifest ABI when applicable. Add a case when extending the
 * Rust {@code #[no_mangle]} surface.
 */
final class FfiExportedSymbolsContractTest {

  private static final String MANIFEST_RESOURCE =
      "/io/github/vihangdesai2018_png/rdp/ffi_manifest.json";

  @Test
  void classpathFfiManifestReadable() throws Exception {
    try (InputStream in =
        FfiExportedSymbolsContractTest.class.getResourceAsStream(MANIFEST_RESOURCE)) {
      assertNotNull(in, "Place ffi_manifest.json under src/test/resources/.../rdp/");
      JSONObject o = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
      assertEquals(400, o.getInt("abi_version_constant"));
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
        invokeExportedSymbol(linker, nativeLib, name, expectedAbi);
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
      Linker linker, SymbolLookup lookup, String name, int expectedAbi) throws Throwable {
    switch (name) {
      case "rdp_ffi_abi_version":
        MethodHandle mh =
            linker.downcallHandle(
                lookup.find("rdp_ffi_abi_version").orElseThrow(),
                FunctionDescriptor.of(ValueLayout.JAVA_INT));
        int v = (int) mh.invokeExact();
        assertEquals(expectedAbi, v, "Bump JVM manifest + Planning/PHASE3_EPICS.md when Rust ABI changes");
        return;
      default:
        fail(
            "ffi_manifest.json lists `"
                + name
                + "` — add a Panama downcall + assertion in FfiExportedSymbolsContractTest");
    }
  }
}
