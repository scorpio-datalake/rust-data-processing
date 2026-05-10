# JVM examples (pytest mirrors)

Runnable programs under `io.github.rust_data_processing.examples` exercise the same Panama +
JSON contracts as `rust-data-processing-jvm` unit tests (`PytestMirrorAssertions`), aligned with
`python-wrapper/tests/*.py`.

| Class | Purpose |
| --- | --- |
| **`LoadFfiManifestExample`** | Read bundled **`ffi_manifest.json`** from the JVM JAR, list **`exported_symbols`**, probe **`rdp_ffi_abi_version`** when **`RDP_JVM_SYS`** is set |
| **`RunPytestMirrorExample`** | Invoke one **`rdp_parity_*`** export by name and print / validate JSON |
| **`ParityScenariosWalkthrough`** | Run a **curated set** of parity exports in sequence (types, bindings, mapping spec, transform, processing, SQL, validation, benchmark smoke) so you can see JSON **`interchange`** shapes; optional CLI args = subset of export names |
| **`ExamplesNativeLibrary`** | Resolve **`RDP_JVM_SYS`** / **`rdp.jvm.sys.library`** (same rules as tests) |

Full walk-through (Maven, **`java -cp`**, manifest resource path): **`docs/java/FFI_MANIFEST_JAVA_USAGE.md`**.

## Prerequisites

- JDK **21+**
- Built `rdp_jvm_sys` native library (same artifact CI builds with `cargo build --release -p rdp-jvm-sys --features full`)
- Environment: **`RDP_JVM_SYS`** = absolute path to `librdp_jvm_sys.so` / `rdp_jvm_sys.dll` / `.dylib`

## Build against the local bindings JAR

From repository root (after `mvn install` in `bindings/java/rust-data-processing-jvm`):

```bash
cd bindings/java/rust-data-processing-jvm-examples
mvn -q -DskipTests package
java --enable-native-access=ALL-UNNAMED -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/classes:$(dependency paths)" ...
```

CI installs the main module first, then runs `mvn package` here so examples stay in sync with the JAR under test.

Quick walkthrough (after `mvn -q -DskipTests package` in this module, with `rust-data-processing-jvm` already installed):

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so   # or .dll / .dylib
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.rust_data_processing.examples.ParityScenariosWalkthrough
```

These examples are **not** the Python `examples/` tree nor Rust book examples — they live only under this Maven module.
