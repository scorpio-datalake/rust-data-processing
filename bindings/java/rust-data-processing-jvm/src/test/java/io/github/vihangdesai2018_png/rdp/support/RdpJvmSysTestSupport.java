package io.github.vihangdesai2018_png.rdp.support;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Shared native library resolution for JVM FFI tests (Panama). */
public final class RdpJvmSysTestSupport {

  private RdpJvmSysTestSupport() {}

  public static String missingNativeLibraryMessage() {
    return "Set absolute path env RDP_JVM_SYS "
        + "(e.g. target/release/librdp_jvm_sys.so or …\\rdp_jvm_sys.dll) "
        + "or -Drdp.jvm.sys.library=… See bindings/java/rust-data-processing-jvm/README.md";
  }

  /** Path to {@code rdp_jvm_sys} native library, when CI or developer sets env / property. */
  public static Optional<Path> resolveNativeLibraryPath() {
    String env = strip(System.getenv("RDP_JVM_SYS"));
    if (!env.isEmpty()) {
      return Optional.of(Path.of(env)).map(Path::toAbsolutePath).filter(Files::exists);
    }
    String prop = strip(System.getProperty("rdp.jvm.sys.library"));
    if (!prop.isEmpty()) {
      return Optional.of(Path.of(prop)).map(Path::toAbsolutePath).filter(Files::exists);
    }
    return Optional.empty();
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
