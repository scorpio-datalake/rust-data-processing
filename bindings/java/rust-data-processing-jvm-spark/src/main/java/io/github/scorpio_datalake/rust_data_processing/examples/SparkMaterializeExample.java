package io.github.scorpio_datalake.rust_data_processing.examples;

import io.github.scorpio_datalake.rust_data_processing.spark.RdpSparkMaterializer;
import java.lang.foreign.Arena;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import org.apache.spark.sql.Dataset;
import org.apache.spark.sql.Row;
import org.apache.spark.sql.SparkSession;

/**
 * Loads {@code rdp_export_parquet_temp} into Spark via {@link RdpSparkMaterializer}; the temp
 * Parquet file is removed after materialization.
 */
public final class SparkMaterializeExample {

  private SparkMaterializeExample() {}

  public static void main(String[] args) throws Throwable {
    Optional<Path> lib = resolveNativeLibraryPath();
    if (lib.isEmpty()) {
      System.err.println(missingLibraryMessage());
      System.exit(2);
    }
    SparkSession spark =
        SparkSession.builder().appName("rdp-spark-materialize").master("local[*]").getOrCreate();
    spark.sparkContext().setLogLevel("WARN");
    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lookup = SymbolLookup.libraryLookup(lib.get(), arena);
      Dataset<Row> df = RdpSparkMaterializer.fromExportParquetTemp(linker, lookup, arena, spark);
      df.printSchema();
      df.show();
    } finally {
      spark.stop();
    }
  }

  private static String missingLibraryMessage() {
    return "Set RDP_JVM_SYS or -Drdp.jvm.sys.library to the built rdp_jvm_sys library.";
  }

  private static Optional<Path> resolveNativeLibraryPath() {
    String env = strip(System.getenv("RDP_JVM_SYS"));
    if (!env.isEmpty()) {
      return Optional.of(Path.of(env)).map(Path::toAbsolutePath).filter(Files::exists);
    }
    String prop = strip(System.getProperty("rdp.jvm.sys.library"));
    if (!prop.isEmpty()) {
      return Optional.of(Path.of(prop)).map(Path::toAbsolutePath).filter(Files::exists);
    }
    return Optional.empty();
  }

  private static String strip(String s) {
    return s == null ? "" : s.strip();
  }
}
