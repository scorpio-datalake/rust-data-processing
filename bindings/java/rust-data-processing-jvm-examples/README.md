# JVM examples (pytest mirrors)

Runnable programs under `io.github.rust_data_processing.examples` exercise the same Panama +
JSON contracts as `rust-data-processing-jvm` unit tests (`PytestMirrorAssertions`), aligned with
`python-wrapper/tests/*.py`.

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

These examples are **not** the Python `examples/` tree nor Rust book examples — they live only under this Maven module.
