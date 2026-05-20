import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.nio.file.Path;

/** Minimal JDK 21+ FFM call into {@code spikes/jvm-panama-ffi} (release {@code cdylib}). */
public final class PanamaSmoke {

  public static void main(String[] args) throws Throwable {
    String libProp = System.getProperty("rdp.jvm.spike.library");
    if (libProp == null || libProp.isBlank()) {
      System.err.println("Set -Drdp.jvm.spike.library=/abs/path/to/cdylib (see spikes/jvm-panama-ffi/README.md)");
      System.exit(2);
      return;
    }
    Path libPath = Path.of(libProp).toAbsolutePath().normalize();

    Linker linker = Linker.nativeLinker();
    try (Arena arena = Arena.ofConfined()) {
      SymbolLookup lib = SymbolLookup.libraryLookup(libPath, arena);

      MethodHandle abi =
          linker.downcallHandle(lib.find("rdp_ffi_abi_version").orElseThrow(), abiDescriptor());

      MethodHandle sum =
          linker.downcallHandle(lib.find("rdp_ffi_sum_i32").orElseThrow(), sumDescriptor());

      int version = (int) abi.invokeExact();
      System.out.println("rdp_ffi_abi_version -> " + version);

      int[] values = new int[] {10, 20, 30};
      MemorySegment xs = arena.allocate(ValueLayout.JAVA_INT, values.length);
      for (int i = 0; i < values.length; i++) {
        xs.setAtIndex(ValueLayout.JAVA_INT, i, values[i]);
      }
      MemorySegment out = arena.allocate(ValueLayout.JAVA_LONG, 1);
      int rc = (int) sum.invokeExact((MemorySegment) xs, (long) 3L, (MemorySegment) out);
      long total = out.get(ValueLayout.JAVA_LONG, 0L);

      System.out.println("rdp_ffi_sum_i32 rc=" + rc + " sum=" + total);
      if (rc != 0 || total != 60L || version != 3) {
        System.exit(1);
      }
    }
  }

  private PanamaSmoke() {}

  private static FunctionDescriptor abiDescriptor() {
    return FunctionDescriptor.of(ValueLayout.JAVA_INT);
  }

  /** Matches `rdp_ffi_sum_i32` on common 64-bit LP64 targets (Rust `usize` ↔ Java {@code long}). */
  private static FunctionDescriptor sumDescriptor() {
    return FunctionDescriptor.of(
        ValueLayout.JAVA_INT,
        ValueLayout.ADDRESS,
        ValueLayout.JAVA_LONG,
        ValueLayout.ADDRESS);
  }
}
