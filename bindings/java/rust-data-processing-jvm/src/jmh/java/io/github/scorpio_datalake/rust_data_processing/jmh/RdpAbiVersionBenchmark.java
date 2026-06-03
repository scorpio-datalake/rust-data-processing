package io.github.scorpio_datalake.rust_data_processing.jmh;

import io.github.scorpio_datalake.rust_data_processing.ffi.RdpNativeJson;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.Setup;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.TearDown;
import org.openjdk.jmh.annotations.Threads;
import org.openjdk.jmh.annotations.Warmup;

/**
 * Microbenchmark for Panama downcalls into {@code rdp_jvm_sys} — CI runs with minimal forks /
 * iterations (see {@code pom.xml} / Gradle {@code jmh} block). Requires {@code RDP_JVM_SYS} or
 * {@code -Drdp.jvm.sys.library}.
 */
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 1, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Measurement(iterations = 1, time = 100, timeUnit = TimeUnit.MILLISECONDS)
@Fork(1)
@Threads(1)
@State(Scope.Benchmark)
public class RdpAbiVersionBenchmark {

  private Arena arena;
  private MethodHandle abiHandle;

  @Setup
  public void setup() throws Throwable {
    Path lib = RdpNativeJson.resolveNativeLibraryFromEnvOrProperty();
    if (lib == null) {
      throw new IllegalStateException(
          "rdp_jvm_sys not found. Run: cargo build --manifest-path bindings/jvm-sys/Cargo.toml"
              + " --features full --release"
              + " (or set RDP_JVM_SYS / -Drdp.jvm.sys.library).");
    }
    arena = Arena.ofConfined();
    Linker linker = Linker.nativeLinker();
    SymbolLookup nativeLib = SymbolLookup.libraryLookup(lib, arena);
    abiHandle =
        linker.downcallHandle(
            nativeLib.find("rdp_ffi_abi_version").orElseThrow(),
            FunctionDescriptor.of(ValueLayout.JAVA_INT));
  }

  @TearDown
  public void tearDown() {
    if (arena != null) {
      arena.close();
      arena = null;
    }
  }

  @Benchmark
  public int abiVersion() throws Throwable {
    return (int) abiHandle.invokeExact();
  }
}
