/**
 * Back-compat alias for {@link OrderedPaths} (directory scan + ordered batch watermark ingest).
 *
 * @deprecated Prefer {@link OrderedPaths} — matches the Python examples tour heading.
 */
@Deprecated
public final class PathFromDirectoryScan {

  private PathFromDirectoryScan() {}

  public static java.nio.file.Path watermarkBundle(java.nio.file.Path fixturesDir) {
    return OrderedPaths.watermarkBundle(fixturesDir);
  }

  public static java.util.List<java.nio.file.Path> pathsFromDirectoryScan(
      java.nio.file.Path root, String relativeGlob) throws Exception {
    return OrderedPaths.pathsFromDirectoryScan(root, relativeGlob);
  }

  public static org.json.JSONObject exampleEventSchema(java.nio.file.Path fixturesDir)
      throws Exception {
    return OrderedPaths.exampleEventSchema(fixturesDir);
  }

  public static String resolveWatermarkIngestPayload(
      java.nio.file.Path fixturesDir, org.json.JSONArray absolutePaths) throws Exception {
    return OrderedPaths.resolveWatermarkIngestPayload(fixturesDir, absolutePaths);
  }

  public static String resolveTwoCsvWatermarkPayload(
      java.nio.file.Path fixturesDir, java.nio.file.Path pathA, java.nio.file.Path pathB)
      throws Exception {
    return OrderedPaths.resolveTwoCsvWatermarkPayload(fixturesDir, pathA, pathB);
  }

  public static Long maxInt64InColumn(org.json.JSONObject dataset, String column)
      throws Exception {
    return OrderedPaths.maxInt64InColumn(dataset, column);
  }

  public static org.json.JSONObject ingestScannedCsvWithWatermark(
      java.lang.foreign.Linker linker,
      java.lang.foreign.SymbolLookup lookup,
      java.lang.foreign.Arena arena,
      java.nio.file.Path fixturesDir,
      org.json.JSONArray absolutePaths)
      throws Throwable {
    return OrderedPaths.ingestScannedCsvWithWatermark(
        linker, lookup, arena, fixturesDir, absolutePaths);
  }

  public static void demonstrate(java.nio.file.Path nativeLibrary) throws Throwable {
    OrderedPaths.demonstrate(nativeLibrary);
  }

  public static void main(String[] args) throws Throwable {
    OrderedPaths.main(args);
  }
}
