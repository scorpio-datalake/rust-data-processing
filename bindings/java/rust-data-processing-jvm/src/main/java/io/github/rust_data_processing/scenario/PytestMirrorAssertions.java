package io.github.rust_data_processing.scenario;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Field-level checks aligned with {@code python-wrapper/tests/*.py} scenarios — mirrors Rust
 * {@code parity_mirrors.rs}. Used by JUnit tests and runnable Java examples.
 */
public final class PytestMirrorAssertions {

  private PytestMirrorAssertions() {}

  /** Routes to the detailed mirror assertion for {@code rdp_parity_*_mirror} exports. */
  public static void validateMirrorExport(String exportName, JSONObject root) {
    assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    switch (exportName) {
      case "rdp_parity_bindings_mirror":
        assertBindingsMirror(interchange);
        break;
      case "rdp_parity_mapping_spec_mirror":
        assertMappingSpecMirror(interchange);
        break;
      case "rdp_parity_sql_suite_mirror":
        assertSqlSuiteMirror(interchange);
        break;
      case "rdp_parity_partition_discovery_mirror":
        assertPartitionDiscoveryMirror(interchange);
        break;
      case "rdp_parity_watermark_mirror":
        assertWatermarkMirror(interchange);
        break;
      case "rdp_parity_deep_seattle_mirror":
        assertDeepSeattleMirror(interchange);
        break;
      case "rdp_parity_sft_sample_mirror":
        assertSftSampleMirror(interchange);
        break;
      case "rdp_parity_benchmark_smoke_mirror":
        assertBenchmarkSmokeMirror(interchange);
        break;
      case "rdp_parity_observability_mirror":
        assertObservabilityMirror(interchange);
        break;
      default:
        throw new IllegalArgumentException("not a pytest mirror export: " + exportName);
    }
  }

  public static void assertEnvelopeOk(JSONObject root) {
    if (!root.getBoolean("ok")) {
      throw new AssertionError("expected ok=true: " + root);
    }
  }

  public static void assertKind(JSONObject interchange, String expectedKind) {
    String k = interchange.getString("kind");
    if (!expectedKind.equals(k)) {
      throw new AssertionError("kind: expected " + expectedKind + " got " + k);
    }
  }

  public static void assertBindingsMirror(JSONObject interchange) {
    assertKind(interchange, "bindings_mirror_pytest");
    assertDoubleClose("processing_reduce_sum", 30.0, interchange.opt("processing_reduce_sum"));
    if (interchange.getInt("processing_filter_kept_rows") != 2) {
      throw new AssertionError("processing_filter_kept_rows");
    }
    JSONArray cols = interchange.getJSONArray("transform_columns");
    if (!cols.toList().contains("score_f")) {
      throw new AssertionError("transform_columns should contain score_f");
    }
    if (interchange.getInt("group_by_row_count") != 2) {
      throw new AssertionError("group_by_row_count");
    }
    if (interchange.getInt("profile_row_count") != 2) {
      throw new AssertionError("profile_row_count");
    }
    if (interchange.getInt("validation_failed_checks") < 1) {
      throw new AssertionError("validation_failed_checks");
    }
    if (interchange.getInt("parallel_filter_rows") != 5) {
      throw new AssertionError("parallel_filter_rows");
    }
  }

  public static void assertMappingSpecMirror(JSONObject interchange) {
    assertKind(interchange, "mapping_spec_mirror_pytest");
    JSONObject r = interchange.getJSONObject("rename_cast_fill_select");
    JSONArray names = r.getJSONArray("columns");
    if (names.length() != 2 || !"score_i".equals(names.getString(1))) {
      throw new AssertionError("rename_cast_fill_select columns");
    }
    Object tag = interchange.getJSONObject("drop_with_literal").get("first_row_tag");
    if (!"v1".equals(extractUtf8String(tag))) {
      throw new AssertionError("drop_with_literal tag: " + tag);
    }
  }

  public static void assertSqlSuiteMirror(JSONObject interchange) {
    assertKind(interchange, "sql_suite_mirror_pytest");
    if (interchange.getJSONObject("basic_select").getJSONArray("rows").length() != 2) {
      throw new AssertionError("basic_select rows");
    }
    if (interchange.getJSONObject("group_having").getJSONArray("rows").length() != 1) {
      throw new AssertionError("group_having rows");
    }
    if (interchange.getJSONObject("join").getJSONArray("rows").length() != 2) {
      throw new AssertionError("join rows");
    }
    if (interchange.getString("missing_table_error").isEmpty()) {
      throw new AssertionError("missing_table_error");
    }
    if (!interchange.getBoolean("missing_column_error_lower_has_missing")) {
      throw new AssertionError("missing_column_error_lower_has_missing");
    }
  }

  public static void assertPartitionDiscoveryMirror(JSONObject interchange) {
    assertKind(interchange, "partition_discovery_mirror_pytest");
    if (interchange.getInt("discover_all_len") != 3) {
      throw new AssertionError("discover_all_len");
    }
    if (interchange.getInt("discover_events_glob_len") != 2) {
      throw new AssertionError("discover_events_glob_len");
    }
    if (interchange.getInt("skip_non_hive_len") != 0) {
      throw new AssertionError("skip_non_hive_len");
    }
    if (!interchange.getBoolean("reject_non_directory_ok")) {
      throw new AssertionError("reject_non_directory_ok");
    }
    if (interchange.getInt("glob_csv_count") < 3) {
      throw new AssertionError("glob_csv_count");
    }
    if (interchange.getInt("explicit_list_len") != 2) {
      throw new AssertionError("explicit_list_len");
    }
    if (!interchange.isNull("parse_dt")
        && !"2024-01-01".equals(interchange.getJSONObject("parse_dt").getString("value"))) {
      throw new AssertionError("parse_dt");
    }
    if (!interchange.getBoolean("parse_nodash_is_null")) {
      throw new AssertionError("parse_nodash_is_null");
    }
  }

  public static void assertWatermarkMirror(JSONObject interchange) {
    assertKind(interchange, "watermark_mirror_pytest");
    if (interchange.getInt("csv_row_count") != 2) {
      throw new AssertionError("csv_row_count");
    }
    JSONArray ids = interchange.getJSONArray("csv_ids");
    if (ids.length() != 2 || extractInt64(ids.get(0)) != 2 || extractInt64(ids.get(1)) != 4) {
      throw new AssertionError("csv_ids");
    }
    if (interchange.getInt("empty_row_count") != 0) {
      throw new AssertionError("empty_row_count");
    }
    if (interchange.getInt("json_row_count") != 2) {
      throw new AssertionError("json_row_count");
    }
    if (!interchange.getBoolean("watermark_rejects_incomplete_options")) {
      throw new AssertionError("watermark_rejects_incomplete_options");
    }
  }

  public static void assertDeepSeattleMirror(JSONObject interchange) {
    assertKind(interchange, "deep_seattle_mirror_pytest");
    if (interchange.getInt("row_count") <= 1000) {
      throw new AssertionError("row_count");
    }
    if (interchange.getLong("group_by_total_rows_sum") != interchange.getLong("dataset_row_count")) {
      throw new AssertionError("group_by row sum");
    }
    if (!interchange.getBoolean("feature_wise_len_match")) {
      throw new AssertionError("feature_wise_len_match");
    }
    if (!interchange.getBoolean("arg_max_ge_min")) {
      throw new AssertionError("arg_max_ge_min");
    }
    if (interchange.getJSONArray("top_weather_pairs").length() != 5) {
      throw new AssertionError("top_weather_pairs len");
    }
  }

  public static void assertSftSampleMirror(JSONObject interchange) {
    assertKind(interchange, "sft_sample_mirror_pytest");
    if (interchange.getInt("row_count") != 4) {
      throw new AssertionError("row_count");
    }
    if (interchange.getInt("jsonl_line_count") != 4) {
      throw new AssertionError("jsonl_line_count");
    }
    if (!interchange.getBoolean("first_line_has_instruction")) {
      throw new AssertionError("first_line_has_instruction");
    }
  }

  public static void assertBenchmarkSmokeMirror(JSONObject interchange) {
    assertKind(interchange, "benchmark_smoke_mirror_pytest");
    if (interchange.getInt("wide_row_count") != 8000) {
      throw new AssertionError("wide_row_count");
    }
    if (interchange.getInt("filtered_rows") <= 0) {
      throw new AssertionError("filtered_rows");
    }
    if (interchange.getInt("parallel_filter_rows") <= 0) {
      throw new AssertionError("parallel_filter_rows");
    }
  }

  public static void assertObservabilityMirror(JSONObject interchange) {
    assertKind(interchange, "observability_mirror_pytest");
    if (!interchange.getBoolean("missing_file_is_err")) {
      throw new AssertionError("missing_file_is_err");
    }
  }

  private static void assertDoubleClose(String label, double expected, Object actual) {
    double v;
    try {
      v = extractFloat64(actual);
    } catch (AssertionError e) {
      throw new AssertionError(label + ": " + e.getMessage());
    }
    if (Math.abs(v - expected) > 1e-6) {
      throw new AssertionError(label + ": expected " + expected + " got " + v);
    }
  }

  /**
   * Rust {@code Value} uses serde's externally tagged JSON: {@code {"Int64":2}}, {@code
   * {"Float64":30.0}}, {@code {"Utf8":"x"}}, etc. Unwrap to the inner scalar when present.
   */
  private static Object unwrapValueEnvelope(Object raw) {
    if (!(raw instanceof JSONObject obj) || obj.isEmpty()) {
      return raw;
    }
    if (obj.length() != 1) {
      return raw;
    }
    String k = obj.keys().next();
    return switch (k) {
      case "Int64", "Float64", "Bool", "Utf8" -> obj.get(k);
      case "Null" -> null;
      default -> raw;
    };
  }

  private static double extractFloat64(Object raw) {
    Object v = unwrapValueEnvelope(raw);
    if (v instanceof Number n) {
      return n.doubleValue();
    }
    throw new AssertionError("not a number " + raw);
  }

  private static long extractInt64(Object raw) {
    Object v = unwrapValueEnvelope(raw);
    if (v instanceof Number n) {
      return n.longValue();
    }
    throw new AssertionError("not an int " + raw);
  }

  private static String extractUtf8String(Object raw) {
    Object v = unwrapValueEnvelope(raw);
    if (v == null || JSONObject.NULL.equals(v)) {
      return null;
    }
    if (v instanceof String s) {
      return s;
    }
    throw new AssertionError("not a string " + raw);
  }
}
