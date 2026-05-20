package io.github.scorpio_datalake.rust_data_processing.examples;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import io.github.scorpio_datalake.rust_data_processing.integration.RdpParquetTemp;
import io.github.scorpio_datalake.rust_data_processing.scenario.PytestMirrorAssertions;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.json.JSONObject;

/**
 * Low-level demo: calls {@code rdp_export_parquet_temp}, prints JSON, checks the Parquet path,
 * deletes it. For Spark without manual temp handling, use {@code RdpSparkMaterializer} in {@code
 * rust-data-processing-jvm-spark} ({@code SparkMaterializeExample}).
 */
public final class ParquetTempExportExample {

  private ParquetTempExportExample() {}

  public static void main(String[] args) throws Throwable {
    Optional<Path> lib = ExamplesNativeLibrary.resolveNativeLibraryPath();
    if (lib.isEmpty()) {
      System.err.println(ExamplesNativeLibrary.missingLibraryMessage());
      System.exit(2);
    }
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      JSONObject root = RdpNativeJson.invokeExportParquetTemp(linker, lookup, arena);
      PytestMirrorAssertions.assertEnvelopeOk(root);
      String pathStr = RdpParquetTemp.parquetPath(root);
      Path p = Path.of(pathStr);
      System.out.println(root.toString(2));
      if (!Files.exists(p)) {
        System.err.println("Expected Parquet at: " + p);
        System.exit(1);
      }
      System.out.println("Parquet bytes (exists): " + Files.size(p));
      RdpParquetTemp.deleteQuietly(pathStr);
      System.out.println("Deleted temp file.");
    }
  }
}
