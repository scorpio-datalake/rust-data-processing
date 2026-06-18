package io.github.scorpio_datalake.rust_data_processing;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

/** Verifies {@code rdp-jvm-sys} classifier JAR loads without {@code RDP_JVM_SYS}. */
class NativeClassifierClasspathTest {

  @Test
  void resolvesAndCallsAbiFromClassifierJarOnClasspath() throws Throwable {
    assumeTrue(
        System.getenv("RDP_JVM_SYS") == null || System.getenv("RDP_JVM_SYS").isBlank(),
        "Unset RDP_JVM_SYS to exercise classpath extraction");
    assumeTrue(
        System.getProperty("rdp.jvm.sys.library") == null
            || System.getProperty("rdp.jvm.sys.library").isBlank(),
        "Unset -Drdp.jvm.sys.library to exercise classpath extraction");

    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    assertNotNull(lib, "native library path (classifier META-INF/native or checkout build)");
    assertTrue(Files.isRegularFile(lib), lib::toString);

    try (Arena arena = Arena.ofConfined()) {
      Linker linker = Linker.nativeLinker();
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib, arena);
      long abi = RdpNativeJson.invokeAbiVersion(linker, lookup);
      assertTrue(abi > 0L, "rdp_ffi_abi_version");
    }
  }
}
