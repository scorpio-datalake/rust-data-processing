package io.github.scorpio_datalake.rust_data_processing.spark;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.io.BufferedWriter;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Turns RDP JSON envelopes into a cached Spark {@code Dataset<Row>} while hiding temp files.
 *
 * <ul>
 *   <li>{@code parquet_export_temp} or {@code polars_parquet_export_temp} — {@code
 *       spark.read().parquet}, then delete after {@code cache + count}.
 *   <li>{@code arrow_ipc_export_temp} — Apache Arrow Java reads the IPC file, {@code
 *       SparkSession#createDataFrame}, then delete after {@code cache + count}.
 *   <li>Otherwise, if {@code interchange.dataset} is present (serde {@code DataSet} JSON), rows are
 *       written to a temp CSV, Spark reads it, then the CSV is deleted.
 * </ul>
 */
public final class RdpSparkMaterializer {

  private static final Set<String> PARQUET_TEMP_KINDS =
      Set.of("parquet_export_temp", "polars_parquet_export_temp");

  private RdpSparkMaterializer() {}

  /**
   * Materializes from a full RDP root JSON object ({@code ok}, {@code interchange}, …).
   *
   * @throws IllegalStateException if {@code ok} is false
   */
  public static Dataset<Row> fromRdpJsonRoot(JSONObject root, SparkSession spark) throws Exception {
    if (!root.optBoolean("ok", false)) {
      throw new IllegalStateException("RDP envelope ok=false: " + root);
    }
    JSONObject interchange = root.getJSONObject("interchange");
    String kind = interchange.optString("kind", "");

    if (PARQUET_TEMP_KINDS.contains(kind)) {
      String pathStr = interchange.getString("path");
      Path path = Path.of(pathStr);
      String uri = path.toUri().toString();
      Dataset<Row> df = spark.read().parquet(uri);
      return cacheCountAndDelete(df, path);
    }

    if ("arrow_ipc_export_temp".equals(kind)) {
      String pathStr = interchange.getString("path");
      Path path = Path.of(pathStr);
      Dataset<Row> df = ArrowIpcFileToSpark.load(path, spark);
      return cacheCountAndDelete(df, path);
    }

    if (!interchange.has("dataset")) {
      throw new IllegalArgumentException(
          "interchange has no dataset and kind is not a known temp-file handoff (kind="
              + kind
              + ")");
    }
    JSONObject dataset = interchange.getJSONObject("dataset");
    Path csv = Files.createTempFile("rdp_spark_", ".csv");
    try {
      writeTabularDatasetAsCsv(dataset, csv);
      String csvUri = csv.toAbsolutePath().toUri().toString();
      Dataset<Row> df = spark.read().option("header", true).option("inferSchema", true).csv(csvUri);
      return cacheCountAndDelete(df, csv);
    } catch (Throwable t) {
      Files.deleteIfExists(csv);
      throw t;
    }
  }

  /** Invokes {@link RdpNativeJson#invokeParityExport} then materializes. */
  public static Dataset<Row> fromParityExport(
      Linker linker, SymbolLookup lookup, Arena arena, String exportedSymbol, SparkSession spark)
      throws Throwable {
    JSONObject root = RdpNativeJson.invokeParityExport(linker, lookup, arena, exportedSymbol);
    return fromRdpJsonRoot(root, spark);
  }

  /** Invokes {@link RdpNativeJson#invokeExportParquetTemp} then materializes. */
  public static Dataset<Row> fromExportParquetTemp(
      Linker linker, SymbolLookup lookup, Arena arena, SparkSession spark) throws Throwable {
    JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
    return fromRdpJsonRoot(root, spark);
  }

  /** Invokes {@link RdpNativeJson#invokeExportArrowIpcTemp} then materializes. */
  public static Dataset<Row> fromExportArrowIpcTemp(
      Linker linker, SymbolLookup lookup, Arena arena, SparkSession spark) throws Throwable {
    JSONObject root = RdpNativeJson.invokeExportArrowIpcTemp(linker, lookup, arena);
    return fromRdpJsonRoot(root, spark);
  }

  /** Invokes {@link RdpNativeJson#invokeExportPolarsParquetTemp} then materializes. */
  public static Dataset<Row> fromExportPolarsParquetTemp(
      Linker linker, SymbolLookup lookup, Arena arena, SparkSession spark) throws Throwable {
    JSONObject root = RdpNativeJson.invokeExportPolarsParquetTemp(linker, lookup, arena);
    return fromRdpJsonRoot(root, spark);
  }

  private static Dataset<Row> cacheCountAndDelete(Dataset<Row> df, Path path) throws IOException {
    Dataset<Row> cached = df.cache();
    cached.count();
    Files.deleteIfExists(path);
    return cached;
  }

  static void writeTabularDatasetAsCsv(JSONObject dataset, Path out) throws IOException {
    JSONObject schema = dataset.getJSONObject("schema");
    JSONArray fields = schema.getJSONArray("fields");
    int n = fields.length();
    String[] names = new String[n];
    for (int i = 0; i < n; i++) {
      names[i] = fields.getJSONObject(i).getString("name");
    }
    JSONArray rows = dataset.getJSONArray("rows");
    try (BufferedWriter w = Files.newBufferedWriter(out, StandardCharsets.UTF_8)) {
      w.write(csvJoin(names));
      w.newLine();
      for (int r = 0; r < rows.length(); r++) {
        JSONArray row = rows.getJSONArray(r);
        if (row.length() != n) {
          throw new IllegalArgumentException(
              "row " + r + " length " + row.length() + " != schema " + n);
        }
        String[] cells = new String[n];
        for (int c = 0; c < n; c++) {
          cells[c] = valueToCsvCell(row.get(c));
        }
        w.write(csvJoin(cells));
        w.newLine();
      }
    }
  }

  private static String csvJoin(String[] cells) {
    StringBuilder sb = new StringBuilder();
    for (int i = 0; i < cells.length; i++) {
      if (i > 0) {
        sb.append(',');
      }
      sb.append(csvEscapeField(cells[i]));
    }
    return sb.toString();
  }

  private static String csvEscapeField(String s) {
    if (s.indexOf('"') >= 0
        || s.indexOf(',') >= 0
        || s.indexOf('\n') >= 0
        || s.indexOf('\r') >= 0) {
      return '"' + s.replace("\"", "\"\"") + '"';
    }
    return s;
  }

  /**
   * Decodes serde-JSON {@code Value} cells: unit {@code Null} as the string {@code "Null"}, newtype
   * variants as single-key objects (e.g. {@code {"Int64":1}}).
   */
  static String valueToCsvCell(Object o) {
    if (o == null || o == JSONObject.NULL) {
      return "";
    }
    if (o instanceof Number n) {
      return n.toString();
    }
    if (o instanceof Boolean b) {
      return Boolean.toString(b);
    }
    if (o instanceof String s) {
      if ("Null".equals(s)) {
        return "";
      }
      return s;
    }
    if (o instanceof JSONObject jo) {
      String[] tags = {"Int64", "Float64", "Bool", "Utf8", "Null"};
      for (String t : tags) {
        if (!jo.has(t)) {
          continue;
        }
        if ("Null".equals(t)) {
          return "";
        }
        Object inner = jo.get(t);
        if (inner == null || inner == JSONObject.NULL) {
          return "";
        }
        return String.valueOf(inner);
      }
      return jo.toString();
    }
    return String.valueOf(o);
  }
}
