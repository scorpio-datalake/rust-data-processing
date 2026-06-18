# JVM examples (pytest mirrors)

Runnable programs under `io.github.scorpio_datalake.rust_data_processing.examples` exercise the same Panama +
JSON contracts as `rust-data-processing-jvm` unit tests (`PytestMirrorAssertions`), aligned with
`python-wrapper/tests/*.py`.

**Design note:** these examples intentionally show **small JSON `interchange`** payloads. In real
applications, run heavy ETL **in Rust** (or Python calling Rust), **write Parquet / CSV / database**
outputs, and use the JVM mainly for orchestration or bounded reads. When you need rows on the JVM
only occasionally, keep result sizes small or use a **file handoff** (Rust writes a path; Java or
local Spark reads the file). See **`docs/java/EXAMPLES.md`** → *Rust-first ETL vs JVM consumption*.

| Class | Purpose |
| --- | --- |
| **`LoadFfiManifestExample`** | Read bundled **`ffi_manifest.json`** from the JVM JAR, list **`exported_symbols`**, probe **`rdp_ffi_abi_version`** when a native library is loaded |
| **`RunPytestMirrorExample`** | Invoke one **`rdp_parity_*`** export by name and print / validate JSON |
| **`ParityScenariosWalkthrough`** | Run a **curated set** of parity exports in sequence (types, bindings, mapping spec, transform, processing, SQL, validation, benchmark smoke) so you can see JSON **`interchange`** shapes; optional CLI args = subset of export names |
| **`ParquetTempExportExample`** | Calls **`rdp_export_parquet_temp`**, prints the JSON envelope, checks the Parquet file exists, deletes it (add Spark on your classpath to read with `spark.read().parquet(path)`) |
| **`ExamplesNativeLibrary`** | Resolve native library via **`RdpNativeJson`** (classpath classifier, then **`RDP_JVM_SYS`** / **`rdp.jvm.sys.library`**) |

Full walk-through (Maven, **`java -cp`**, manifest resource path): **`docs/java/FFI_MANIFEST_JAVA_USAGE.md`**.

## Prerequisites

- JDK **21+**
- **Native library** — pick one:
  - **Maven Central (recommended):** add **`rust-data-processing-jvm`** + **one** **`rdp-jvm-sys`** classifier for your OS/CPU (same version). See **[`docs/java/NATIVE_ARTIFACT_PACKAGING.md`](../../../../docs/java/NATIVE_ARTIFACT_PACKAGING.md)**. No **`RDP_JVM_SYS`** required.
  - **From source:** `cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full`, then **`RDP_JVM_SYS`** = absolute path to `librdp_jvm_sys.so` / `rdp_jvm_sys.dll` / `.dylib`
- **JVM flag:** `--enable-native-access=ALL-UNNAMED` (set via **`JAVA_TOOL_OPTIONS`** in the snippets below)

## Build against the local bindings JAR

From repository root (after `mvn install` in `bindings/java/rust-data-processing-jvm`):

```bash
cd bindings/java/rust-data-processing-jvm-examples
mvn -q -DskipTests package
```

CI installs the main module first, sets **`RDP_JVM_SYS`** to a freshly built `cdylib`, then runs `mvn package` here so examples stay in sync with the JAR under test.

## Quick walkthrough

**With Maven Central classifiers** (no Rust): add both dependencies to your app POM, then run with **`JAVA_TOOL_OPTIONS`** only. For a minimal `java -cp` demo, put the classifier JAR on the classpath alongside the main JAR:

```bash
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar:/path/to/rdp-jvm-sys-0.3.4-linux-x86_64.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.ParityScenariosWalkthrough
```

**From a checkout** (after `mvn -q -DskipTests package` in this module, with `rust-data-processing-jvm` already installed):

```bash
export RDP_JVM_SYS=/absolute/path/to/librdp_jvm_sys.so   # or .dll / .dylib
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.ParityScenariosWalkthrough
java -cp "target/rust-data-processing-jvm-examples-0.1.0-SNAPSHOT.jar:../rust-data-processing-jvm/target/rust-data-processing-jvm-0.1.0-SNAPSHOT.jar" \
  io.github.scorpio_datalake.rust_data_processing.examples.ParquetTempExportExample
```

These examples are **not** the Python `examples/` tree nor Rust book examples — they live only under this Maven module.
