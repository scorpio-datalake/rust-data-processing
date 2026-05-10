# rust-data-processing-jvm — Maven bindings (Phase 3)

Build the native **`rdp_jvm_sys`** first:

```bash
cargo build --release --manifest-path ../../../jvm-sys/Cargo.toml
```

Run tests (Unix):

```bash
export RDP_JVM_SYS="$(pwd)/../../../jvm-sys/target/release/librdp_jvm_sys.so"
export JAVA_TOOL_OPTIONS='--enable-native-access=ALL-UNNAMED'
mvn -q verify
```

Windows PowerShell:

```powershell
$m = Resolve-Path ../../../jvm-sys/target/release/rdp_jvm_sys.dll
$env:RDP_JVM_SYS = $m.Path
$env:JAVA_TOOL_OPTIONS = "--enable-native-access=ALL-UNNAMED"
mvn -q verify
```

**JMH:** `mvn verify` runs microbenchmarks from `src/jmh/java` during the **`integration-test`** phase (after unit tests). Gradle: `./gradlew jmh`. Skip Maven JMH only: `mvn verify -Drdp.jmh.skip=true`.

**Examples:** pytest-style mirrors are exercised by **`PytestMirrorAssertions`** (same checks as `python-wrapper/tests`). Runnable entrypoints live in **`bindings/java/rust-data-processing-jvm-examples/`** (`RunPytestMirrorExample`, `LoadFfiManifestExample`). After `mvn install` in this directory, build and test that module with `mvn verify` (requires `RDP_JVM_SYS`).

**`ffi_manifest.json`:** bundled in the JAR at **`RdpNativeJson.FFI_MANIFEST_RESOURCE`**; usage (read manifest, call exports, classpath) is documented in **`docs/java/FFI_MANIFEST_JAVA_USAGE.md`**.

**JDK:**

- Prefer **JDK 21** (**FFM** final). JDK **24+ / 25**: if **`java.lang.foreign`** triggers “preview”, run:

```bash
mvn -q verify -Drdp.foreignPreview=true
```

## jextract

Add generated sources locally (committed **after** codegen policy in **P3-E1-S1b**):

```bash
export RDP_HEADERS="$PWD/../../../jvm-sys/include"
jextract --output src/main/java/generated \
  --target-package io.github.rust_data_processing.internal.foreign \
  "$RDP_HEADERS/rdp_jvm_sys.h"
```
