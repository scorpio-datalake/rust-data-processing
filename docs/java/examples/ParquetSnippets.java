import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.integration.RdpParquetTemp;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Path;
import org.json.JSONObject;

/**
 * Parquet handoff from RDP to the JVM without shipping huge row JSON over FFI.
 *
 * <p><strong>{@code rdp_export_parquet_temp}</strong> — Rust writes a small sample {@code DataSet} to a
 * temp Parquet file under the OS temp directory and returns a compact JSON envelope:
 * {@code interchange.kind} = {@code parquet_export_temp}, plus {@code path}, {@code row_count},
 * {@code schema}. Read the file with Spark {@code local[*]} or any Parquet reader, then delete the
 * path (see {@link RdpParquetTemp}).
 *
 * <p>For arbitrary paths like Python {@code rdp.ingest_from_path(..., {"format": "parquet"})}, use
 * the Python extension or Rust crate directly until a parameterized Parquet-ingest FFI exists. The
 * Rust crate exposes {@code rust_data_processing::ingestion::export_dataset_to_parquet} for writing
 * any in-memory {@code DataSet} to a path from Rust.
 */
public final class ParquetSnippets {

  private ParquetSnippets() {}

  /** Full JSON envelope from {@code rdp_export_parquet_temp} (asserts {@code ok}). */
  public static JSONObject exportParquetTempEnvelope(
      Linker linker, SymbolLookup lookup, Arena arena) throws Throwable {
    JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
    PytestMirrorAssertions.assertEnvelopeOk(root);
    JSONObject interchange = root.getJSONObject("interchange");
    if (!"parquet_export_temp".equals(interchange.getString("kind"))) {
      throw new IllegalStateException("unexpected kind: " + interchange.getString("kind"));
    }
    return root;
  }

  /** Absolute Parquet path for Spark / Polars / etc., then caller should {@link RdpParquetTemp#deleteQuietly}. */
  public static String parquetPathFromEnvelope(JSONObject root) {
    return RdpParquetTemp.parquetPath(root);
  }

  /** Convenience: {@link #exportParquetTempEnvelope} with {@code SymbolLookup.libraryLookup}. */
  public static JSONObject exportParquetTempEnvelope(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);
      return exportParquetTempEnvelope(linker, lookup, arena);
    }
  }

  /*
   * Example with Spark (add spark-sql to your app classpath):
   *
   *   JSONObject root = ParquetSnippets.exportParquetTempEnvelope(linker, lookup, arena);
   *   String path = ParquetSnippets.parquetPathFromEnvelope(root);
   *   Dataset<Row> df = spark.read().parquet(path);
   *   RdpParquetTemp.deleteQuietly(path);
   */
}
