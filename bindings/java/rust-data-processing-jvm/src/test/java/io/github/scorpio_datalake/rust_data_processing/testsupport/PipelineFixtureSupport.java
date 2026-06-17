package io.github.scorpio_datalake.rust_data_processing.testsupport;

import io.github.scorpio_datalake.rust_data_processing.fixture.PipelineJsonFixtures;
import io.github.scorpio_datalake.rust_data_processing.support.RdpJvmSysTestSupport;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;

/** Test-scoped helpers over {@link PipelineJsonFixtures} and {@code tests/fixtures/}. */
public final class PipelineFixtureSupport {

  private PipelineFixtureSupport() {}

  public static Optional<Path> resolveBundleRoot(String bundleName) {
    return RdpJvmSysTestSupport.resolveTestsFixturesDir()
        .flatMap(dir -> PipelineJsonFixtures.resolveBundleRoot(dir, bundleName));
  }

  public static String loadSchemaJson(Path bundleRoot, String schemaRel) throws IOException {
    return PipelineJsonFixtures.loadSchemaJson(bundleRoot, schemaRel);
  }

  public static String resolvePipelineJson(
      Path bundleRoot, String pipelineRel, Map<String, String> bindings) throws IOException {
    return PipelineJsonFixtures.resolvePipelineJson(bundleRoot, pipelineRel, bindings);
  }

  public static String resolvePayloadJson(
      Path bundleRoot, String payloadRel, Map<String, String> bindings) throws IOException {
    return PipelineJsonFixtures.resolvePayloadJson(bundleRoot, payloadRel, bindings);
  }

  public static String readBundleUtf8(Path bundleRoot, String relativePath) throws IOException {
    return PipelineJsonFixtures.readUtf8(bundleRoot, relativePath);
  }

  /** {@link PipelineJsonFixtures#defaultPathIngestOptionsJson()}. */
  public static String defaultPathIngestOptionsJson() {
    return PipelineJsonFixtures.defaultPathIngestOptionsJson();
  }

  /** {@link PipelineJsonFixtures#pipelinePathBinding(Path)}. */
  public static String pipelinePathBinding(java.nio.file.Path path) {
    return PipelineJsonFixtures.pipelinePathBinding(path);
  }

  public static String loadPeopleSchemaJson(String schemaRel) throws IOException {
    Path people =
        RdpJvmSysTestSupport.resolveTestsFixturesDir()
            .map(d -> d.resolve("people"))
            .orElseThrow(() -> new IOException("tests/fixtures not found"));
    return PipelineJsonFixtures.loadSchemaJson(people, schemaRel);
  }
}
