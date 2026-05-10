package io.github.rust_data_processing.examples;

import io.github.rust_data_processing.ffi.RdpNativeJson;
import java.io.InputStream;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Walk-through: read the bundled {@code ffi_manifest.json} from the {@code rust-data-processing-jvm}
 * JAR, list exported symbols, and (if {@code RDP_JVM_SYS} is set) compare {@code rdp_ffi_abi_version()}
 * to {@code abi_version_constant}. See {@code docs/java/FFI_MANIFEST_JAVA_USAGE.md}.
 */
public final class LoadFfiManifestExample {

  private LoadFfiManifestExample() {}

  public static void main(String[] args) throws Exception {
    JSONObject manifest = readBundledManifest();
    int abiFromManifest = manifest.getInt("abi_version_constant");
    JSONArray symbols = manifest.getJSONArray("exported_symbols");

    System.out.println("abi_version_constant (from JAR manifest): " + abiFromManifest);
    System.out.println("exported_symbols (" + symbols.length() + "):");
    for (int i = 0; i < symbols.length(); i++) {
      System.out.println("  - " + symbols.getString(i));
    }

    Optional<Path> lib = ExamplesNativeLibrary.resolveNativeLibraryPath();
    if (lib.isEmpty()) {
      System.out.println();
      System.out.println("Skip native probe: " + ExamplesNativeLibrary.missingLibraryMessage());
      return;
    }

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      int abiFromNative = RdpNativeJson.invokeAbiVersion(linker, lookup);
      System.out.println();
      System.out.println("rdp_ffi_abi_version() from native library: " + abiFromNative);
      if (abiFromNative != abiFromManifest) {
        System.err.println(
            "WARNING: native ABI "
                + abiFromNative
                + " != manifest "
                + abiFromManifest
                + " (rebuild rdp_jvm_sys or refresh ffi_manifest.json).");
      }
    }
  }

  private static JSONObject readBundledManifest() throws Exception {
    try (InputStream in = RdpNativeJson.class.getResourceAsStream(RdpNativeJson.FFI_MANIFEST_RESOURCE)) {
      if (in == null) {
        throw new IllegalStateException(
            "Missing classpath resource "
                + RdpNativeJson.FFI_MANIFEST_RESOURCE
                + " — ensure rust-data-processing-jvm is on the classpath.");
      }
      return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
    }
  }
}
