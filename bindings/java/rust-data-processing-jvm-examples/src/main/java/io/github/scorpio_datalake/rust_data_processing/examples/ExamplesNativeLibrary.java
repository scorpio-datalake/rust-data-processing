package io.github.scorpio_datalake.rust_data_processing.examples;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.nio.file.Path;
import java.util.Optional;

/** Delegates to {@link RdpNativeJson} (env, property, Maven classifier JAR, or checkout build). */
public final class ExamplesNativeLibrary {

  private ExamplesNativeLibrary() {}

  public static String missingLibraryMessage() {
    return "Add rdp-jvm-sys classifier dependency for your OS, or set RDP_JVM_SYS / -Drdp.jvm.sys.library."
        + " See docs/java/NATIVE_ARTIFACT_PACKAGING.md";
  }

  public static Optional<Path> resolveNativeLibraryPath() {
    return Optional.ofNullable(RdpNativeJson.resolveNativeLibraryFromEnvOrProperty());
  }
}
