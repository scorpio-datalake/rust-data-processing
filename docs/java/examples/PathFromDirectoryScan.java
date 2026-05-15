import io.github.rust_data_processing.ffi.RdpNativeJson;
import io.github.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * JVM equivalent of Python {@code paths_from_directory_scan} + {@code ingest_from_ordered_paths}
 * with watermark options (see {@code docs/python/README.md}).
 *
 * <p>Rust exposes ordered multi-file ingest as {@code rdp_ingest_ordered_paths_json}; there is no
 * separate FFI for directory listing today, so this example lists files on the JVM in the same way
 * as {@code rust_data_processing::ingestion::paths_from_directory_scan}: walk under a root,
 * optional glob on paths <strong>relative to the root</strong> (forward slashes in the pattern,
 * e.g. {@code **/*.csv}), sort for stable ordering, then pass absolute paths to Rust.
 *
 * <p>Run with {@code RDP_JVM_SYS} (or {@code -Drdp.jvm.sys.library}) and {@code
 * --enable-native-access=ALL-UNNAMED}. Not built by the main Maven module — copy into {@code
 * rust-data-processing-jvm-examples} if you want {@code mvn} to compile it.
 */
public final class PathFromDirectoryScan {

  private PathFromDirectoryScan() {}

  /**
   * Lists regular files under {@code root} whose path relative to {@code root} matches {@code
   * relativeGlob} (same string you would pass to Python {@code paths_from_directory_scan} as the
   * second argument), sorted lexicographically by full path.
   */
  public static List<Path> pathsFromDirectoryScan(Path root, String relativeGlob) throws Exception {
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("directory scan root must be an existing directory: " + root);
    }
    FileSystem fs = root.getFileSystem();
    PathMatcher matcher = fs.getPathMatcher("glob:" + relativeGlob);
    List<Path> out = new ArrayList<>();
    try (var stream = Files.walk(root)) {
      stream.filter(Files::isRegularFile).forEach(p -> {
        Path rel = root.relativize(p);
        if (matcher.matches(rel)) {
          out.add(p);
        }
      });
    }
    out.sort(Comparator.naturalOrder());
    return out;
  }

  /** Minimal CSV schema with a {@code ts} column for watermark demos. */
  public static JSONObject exampleEventSchema() {
    JSONArray fields =
        new JSONArray()
            .put(new JSONObject().put("name", "id").put("data_type", "Int64"))
            .put(new JSONObject().put("name", "ts").put("data_type", "Int64"));
    return new JSONObject().put("fields", fields);
  }

  /**
   * Max of column {@code ts} in the returned tabular JSON dataset (mirrors Python {@code
   * meta["max_watermark_value"]} for Int64 watermarks when you only have the FFI dataset body).
   */
  public static Long maxInt64InColumn(JSONObject dataset, String column) throws Exception {
    JSONArray fieldDefs = dataset.getJSONObject("schema").getJSONArray("fields");
    int col = -1;
    for (int i = 0; i < fieldDefs.length(); i++) {
      if (column.equals(fieldDefs.getJSONObject(i).getString("name"))) {
        col = i;
        break;
      }
    }
    if (col < 0) {
      throw new IllegalArgumentException("column not in schema: " + column);
    }
    JSONArray rows = dataset.getJSONArray("rows");
    long max = Long.MIN_VALUE;
    for (int r = 0; r < rows.length(); r++) {
      JSONArray row = rows.getJSONArray(r);
      JSONObject cell = row.getJSONObject(col);
      if (cell.has("Int64")) {
        max = Math.max(max, cell.getLong("Int64"));
      }
    }
    return max == Long.MIN_VALUE ? null : max;
  }

  public static void demonstrate(Path nativeLibrary) throws Throwable {
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(nativeLibrary, arena);

      Path incoming = Files.createTempDirectory("rdp_path_from_directory_scan_");
      try {
        Path nested = incoming.resolve("nested");
        Files.createDirectories(nested);
        Path a = incoming.resolve("a.csv");
        Path b = nested.resolve("b.csv");
        Files.writeString(
            a,
            "id,ts\n1,50\n2,99\n",
            StandardCharsets.UTF_8);
        Files.writeString(
            b,
            "id,ts\n3,150\n4,200\n",
            StandardCharsets.UTF_8);

        List<Path> paths = pathsFromDirectoryScan(incoming, "**/*.csv");
        JSONArray pathJson = new JSONArray();
        for (Path p : paths) {
          pathJson.put(p.toAbsolutePath().normalize().toString());
        }

        JSONObject payload =
            new JSONObject()
                .put("paths", pathJson)
                .put("schema", exampleEventSchema())
                .put(
                    "options",
                    new JSONObject()
                        .put("format", "csv")
                        .put("watermark_column", "ts")
                        .put("watermark_exclusive_above", 100))
                .put("response", new JSONObject().put("mode", "dataset").put("max_rows", 10_000));

        JSONObject root =
            RdpNativeJson.invokeIngestOrderedPathsJson(linker, lookup, arena, payload.toString());
        PytestMirrorAssertions.assertEnvelopeOk(root);
        JSONObject interchange = root.getJSONObject("interchange");
        JSONObject batch = interchange.getJSONObject("ordered_batch");

        System.out.println("last_path: " + batch.opt("last_path"));
        JSONObject dataset = interchange.getJSONObject("dataset");
        System.out.println("max_watermark_value (from returned rows): " + maxInt64InColumn(dataset, "ts"));
        System.out.println("returned_row_count: " + interchange.getInt("returned_row_count"));
      } finally {
        try (var walk = Files.walk(incoming)) {
          for (Path p : walk.sorted(Comparator.reverseOrder()).toList()) {
            Files.deleteIfExists(p);
          }
        }
      }
    }
  }

  public static void main(String[] args) throws Throwable {
    String env = System.getenv("RDP_JVM_SYS");
    String prop = System.getProperty("rdp.jvm.sys.library");
    Path lib = null;
    if (env != null && !env.isBlank()) {
      Path p = Path.of(env.strip()).toAbsolutePath();
      if (Files.exists(p)) {
        lib = p;
      }
    }
    if (lib == null && prop != null && !prop.isBlank()) {
      Path p = Path.of(prop.strip()).toAbsolutePath();
      if (Files.exists(p)) {
        lib = p;
      }
    }
    if (lib == null) {
      System.err.println("Set RDP_JVM_SYS or -Drdp.jvm.sys.library to a built rdp_jvm_sys library.");
      System.exit(2);
    }
    demonstrate(lib);
  }
}
