package io.github.rust_data_processing.integration;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Helpers for {@code rdp_export_parquet_temp}: read the absolute Parquet path from the JSON
 * envelope, then delete the file after Spark (or another reader) has consumed it.
 *
 * <p>Add Spark on your own classpath; this module does not depend on Spark. Typical flow:
 *
 * <pre>{@code
 * JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
 * String path = RdpParquetTemp.parquetPath(root);
 * Dataset<Row> df = spark.read().parquet(path);
 * RdpParquetTemp.deleteQuietly(path);
 * }</pre>
 */
public final class RdpParquetTemp {

  private RdpParquetTemp() {}

  /** Absolute filesystem path from a successful {@code rdp_export_parquet_temp} response. */
  public static String parquetPath(JSONObject root) {
    return root.getJSONObject("interchange").getString("path");
  }

  public static void deleteQuietly(String absolutePath) {
    try {
      Files.deleteIfExists(Path.of(absolutePath));
    } catch (IOException ignored) {
      // best-effort cleanup
    }
  }
}
