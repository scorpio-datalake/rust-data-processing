package io.github.rust_data_processing.json;

import org.json.JSONArray;
import org.json.JSONObject;

/** Decode cells in {@code interchange.dataset.rows} from Rust {@code DataSet} serde JSON. */
public final class SerdeDatasetRows {

  private SerdeDatasetRows() {}

  public static String utf8(JSONArray row, int column) {
    return decodeString(row.get(column));
  }

  public static double float64(JSONArray row, int column) {
    Object cell = row.get(column);
    if (cell instanceof Number n) {
      return n.doubleValue();
    }
    if (cell instanceof JSONObject jo && jo.has("Float64")) {
      return jo.getDouble("Float64");
    }
    throw new IllegalArgumentException("expected Float64 cell, got " + cell);
  }

  private static String decodeString(Object cell) {
    if (cell instanceof String s) {
      return s;
    }
    if (cell instanceof JSONObject jo && jo.has("Utf8")) {
      return jo.getString("Utf8");
    }
    throw new IllegalArgumentException("expected Utf8 cell, got " + cell);
  }
}
