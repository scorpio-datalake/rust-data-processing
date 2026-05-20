package io.github.rust_data_processing.support;

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

  /**
   * Resolves the repo's {@code tests/fixtures} directory when {@code GITHUB_WORKSPACE} is set (CI)
   * or by walking parents of the JVM working directory until {@code tests/fixtures/people.csv}
   * exists (local {@code mvn} / Gradle from {@code bindings/java/rust-data-processing-jvm}).
   */
  public static Optional<Path> resolveTestsFixturesDir() {
    String gw = strip(System.getenv("GITHUB_WORKSPACE"));
    if (!gw.isEmpty()) {
      Path p = Path.of(gw).toAbsolutePath().normalize().resolve("tests").resolve("fixtures");
      if (Files.isRegularFile(p.resolve("people.csv"))) {
        return Optional.of(p);
      }
    }
    Path cwd = Path.of("").toAbsolutePath();
    for (Path cur = cwd; cur != null; cur = cur.getParent()) {
      Path p = cur.resolve("tests").resolve("fixtures");
      if (Files.isRegularFile(p.resolve("people.csv"))) {
        return Optional.of(p);
      }
    }
    return Optional.empty();
  }

  /**
   * Absolute path to a file under repo {@code tests/fixtures} when {@link
   * #resolveTestsFixturesDir()} succeeds and {@code name} exists (e.g. {@code
   * jvm_contract_three_rows.json} for JVM contract tests — inspect these files to see the inputs
   * RDP ingests).
   */
  public static Optional<Path> resolveFixtureFile(String name) {
    return resolveTestsFixturesDir()
        .map(dir -> dir.resolve(name))
        .filter(Files::isRegularFile)
        .map(p -> p.toAbsolutePath().normalize());
  }
}
