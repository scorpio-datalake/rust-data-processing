package io.github.scorpio_datalake.rust_data_processing.fixture;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Iterator;
import java.util.Map;
import java.util.Optional;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Loads shared pipeline, payload, and schema JSON from {@code tests/fixtures/<bundle>/} — same
 * files as {@code rust_data_processing::pipeline_spec::PipelineBundle} and Python {@code
 * tests.pipeline_fixture_support}.
 */
public final class PipelineJsonFixtures {

  /**
   * Default third argument for {@code rdp_ingest_*_path} when no format, watermark, or sheet
   * options apply. Prefer this over committing an empty {@code *.options.json} file per bundle.
   */
  public static final String DEFAULT_PATH_INGEST_OPTIONS_JSON = "{}";

  private PipelineJsonFixtures() {}

  /** Same as {@link #DEFAULT_PATH_INGEST_OPTIONS_JSON}. */
  public static String defaultPathIngestOptionsJson() {
    return DEFAULT_PATH_INGEST_OPTIONS_JSON;
  }

  /**
   * Absolute path with forward slashes — stable in pipeline JSON on Windows and Unix ({@code
   * JSONObject#toString()} escapes backslashes).
   */
  public static String pipelinePathBinding(Path path) {
    return path.toAbsolutePath().normalize().toString().replace('\\', '/');
  }

  /** Repo {@code tests/fixtures} when {@code people.csv} exists on an ancestor of the cwd. */
  public static Optional<Path> resolveTestsFixturesDir() {
    Path cwd = Path.of("").toAbsolutePath();
    for (Path cur = cwd; cur != null; cur = cur.getParent()) {
      Path fixtures = cur.resolve("tests").resolve("fixtures");
      if (Files.isRegularFile(fixtures.resolve("people.csv"))) {
        return Optional.of(fixtures);
      }
    }
    return Optional.empty();
  }

  /** {@code tests/fixtures/<bundleName>} when discoverable from the working directory. */
  public static Optional<Path> resolveBundleRoot(Path fixturesDir, String bundleName) {
    Path root = fixturesDir.resolve(bundleName);
    if (Files.isRegularFile(root.resolve("SOURCES.md"))
        || Files.isDirectory(root.resolve("schemas"))
        || Files.isDirectory(root.resolve("pipelines"))) {
      return Optional.of(root);
    }
    return Optional.empty();
  }

  public static String readUtf8(Path bundleRoot, String relativePath) throws IOException {
    return Files.readString(bundleRoot.resolve(relativePath), StandardCharsets.UTF_8);
  }

  public static String loadSchemaJson(Path bundleRoot, String schemaRel) throws IOException {
    return readUtf8(bundleRoot, schemaRel);
  }

  public static JSONArray loadSchemaFieldsArray(Path bundleRoot, String schemaRel)
      throws IOException {
    return new JSONObject(loadSchemaJson(bundleRoot, schemaRel)).getJSONArray("fields");
  }

  public static JSONObject loadSchemaObject(Path bundleRoot, String schemaRel) throws IOException {
    return new JSONObject(loadSchemaJson(bundleRoot, schemaRel));
  }

  public static String resolvePipelineJson(
      Path bundleRoot, String pipelineRel, Map<String, String> bindings) throws IOException {
    JSONObject root = new JSONObject(readUtf8(bundleRoot, pipelineRel));
    expandSchemaRefs(bundleRoot, root);
    bindPlaceholders(root, bindings);
    return root.toString();
  }

  public static String resolvePayloadJson(
      Path bundleRoot, String payloadRel, Map<String, String> bindings) throws IOException {
    JSONObject root = new JSONObject(readUtf8(bundleRoot, payloadRel));
    expandSchemaRefs(bundleRoot, root);
    bindPlaceholders(root, bindings);
    return root.toString();
  }

  /**
   * {@code transform.sql} from a pipeline template (after reading raw JSON, before placeholder
   * bind).
   */
  public static String pipelineTransformSql(Path bundleRoot, String pipelineRel)
      throws IOException {
    JSONObject pipeline = new JSONObject(readUtf8(bundleRoot, pipelineRel));
    return pipeline.getJSONObject("transform").getString("sql");
  }

  private static void expandSchemaRefs(Path bundleRoot, JSONObject node) throws IOException {
    Iterator<String> keys = node.keys();
    java.util.List<String> refKeys = new java.util.ArrayList<>();
    while (keys.hasNext()) {
      String k = keys.next();
      if (k.endsWith("_ref")) {
        refKeys.add(k);
      }
    }
    for (String refKey : refKeys) {
      String rel = node.getString(refKey);
      String targetKey = refKey.substring(0, refKey.length() - "_ref".length());
      node.remove(refKey);
      node.put(targetKey, new JSONObject(readUtf8(bundleRoot, rel)));
    }
    for (String key : node.keySet()) {
      Object child = node.get(key);
      if (child instanceof JSONObject obj) {
        expandSchemaRefs(bundleRoot, obj);
      } else if (child instanceof JSONArray arr) {
        expandSchemaRefsArray(bundleRoot, arr);
      }
    }
  }

  private static void expandSchemaRefsArray(Path bundleRoot, JSONArray arr) throws IOException {
    for (int i = 0; i < arr.length(); i++) {
      Object el = arr.get(i);
      if (el instanceof JSONObject obj) {
        expandSchemaRefs(bundleRoot, obj);
      } else if (el instanceof JSONArray nested) {
        expandSchemaRefsArray(bundleRoot, nested);
      }
    }
  }

  private static void bindPlaceholders(JSONObject node, Map<String, String> bindings) {
    Iterator<String> keys = node.keys();
    while (keys.hasNext()) {
      String key = keys.next();
      Object child = node.get(key);
      if (child instanceof String s) {
        node.put(key, substitute(s, bindings));
      } else if (child instanceof JSONObject obj) {
        bindPlaceholders(obj, bindings);
      } else if (child instanceof JSONArray arr) {
        bindPlaceholdersArray(arr, bindings);
      }
    }
  }

  private static void bindPlaceholdersArray(JSONArray arr, Map<String, String> bindings) {
    for (int i = 0; i < arr.length(); i++) {
      Object el = arr.get(i);
      if (el instanceof String s) {
        arr.put(i, substitute(s, bindings));
      } else if (el instanceof JSONObject obj) {
        bindPlaceholders(obj, bindings);
      } else if (el instanceof JSONArray nested) {
        bindPlaceholdersArray(nested, bindings);
      }
    }
  }

  private static String substitute(String template, Map<String, String> bindings) {
    String out = template;
    for (Map.Entry<String, String> e : bindings.entrySet()) {
      out = out.replace("{{" + e.getKey() + "}}", e.getValue());
    }
    return out;
  }
}
