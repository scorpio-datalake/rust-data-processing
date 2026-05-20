package io.github.scorpio_datalake.rust_data_processing.examples;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

/** Same resolution rules as {@code RdpJvmSysTestSupport} (main tests). */
public final class ExamplesNativeLibrary {

  private ExamplesNativeLibrary() {}

  public static String missingLibraryMessage() {
    return "Set RDP_JVM_SYS or -Drdp.jvm.sys.library to the built rdp_jvm_sys library.";
  }

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
