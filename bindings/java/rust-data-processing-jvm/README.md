# rust-data-processing-jvm — Maven bindings (Phase 3)

![Phase 3 scope: Rust core with Python (PyO3) and Java (Panama) bindings, agent-ready JSON FFI, and shared batch/streaming connectors](https://raw.githubusercontent.com/scorpio-datalake/rust-data-processing/main/docs/images/phase-3-scope-overview.png)

*Infographic: Phase 3 — Java thin Panama wrapper (`rdp_jvm_sys`, Maven + Gradle) on the shared Rust engine; JSON parity FFI; same connectors as Rust/Python.*

## Consumers (Maven Central — no Rust)

Depend on **`rust-data-processing-jvm`** and **one** **`rdp-jvm-sys`** classifier for your OS/CPU at the **same version** ([`bindings/java/VERSION`](../VERSION)). See **[`docs/java/NATIVE_ARTIFACT_PACKAGING.md`](../../../docs/java/NATIVE_ARTIFACT_PACKAGING.md)** for the classifier table and Gradle equivalent.

`RdpNativeJson` loads the native library from **`META-INF/native/`** on the classpath. Set **`RDP_JVM_SYS`** or **`-Drdp.jvm.sys.library`** only to override (custom `cargo` build, debugging).

**JVM flags** when running code that calls native exports:

```text
JAVA_TOOL_OPTIONS=--enable-native-access=ALL-UNNAMED
```

On JDK 21 you may also need `--enable-preview` when compiling **your** code against Panama APIs; the published JAR is built with preview enabled.

## Maintainers: build and test from a checkout

Build the native **`rdp_jvm_sys`** crate, then run Maven/Gradle tests with an explicit library path (same as CI):

```bash
cargo build --release --manifest-path ../../../jvm-sys/Cargo.toml --features full
export RDP_JVM_SYS="$(pwd)/../../../jvm-sys/target/release/librdp_jvm_sys.so"
export JAVA_TOOL_OPTIONS='--enable-preview --enable-native-access=ALL-UNNAMED'
mvn -q verify
```

Windows PowerShell:

```powershell
$m = Resolve-Path ../../../jvm-sys/target/release/rdp_jvm_sys.dll
$env:RDP_JVM_SYS = $m.Path
$env:JAVA_TOOL_OPTIONS = "--enable-preview --enable-native-access=ALL-UNNAMED"
mvn -q verify
```

**Classifier smoke (Linux x86_64):** from repo root, `./scripts/test_native_classifier_local.sh` — packages a classifier JAR, runs **`NativeClassifierClasspathTest`** without **`RDP_JVM_SYS`**, then the full test suite with an explicit path.

**JMH:** `mvn verify` runs microbenchmarks from `src/jmh/java` during the **`integration-test`** phase (after unit tests). Gradle: `./gradlew jmh`. Skip Maven JMH only: `mvn verify -Drdp.jmh.skip=true`.

**Examples:** pytest-style mirrors are exercised by **`PytestMirrorAssertions`** (same checks as `python-wrapper/tests`). Runnable entrypoints live in **`bindings/java/rust-data-processing-jvm-examples/`** (`RunPytestMirrorExample`, `LoadFfiManifestExample`). After `mvn install` in this directory, build and test that module with `mvn verify` (CI uses **`RDP_JVM_SYS`**; consumers use classifier JARs on the classpath).

**`ffi_manifest.json`:** bundled in the JAR at **`RdpNativeJson.FFI_MANIFEST_RESOURCE`**; usage (read manifest, call exports, classpath natives) is documented in **`docs/java/FFI_MANIFEST_JAVA_USAGE.md`**.

**JDK:** **21** is the CI baseline. On JDK **21**, **Panama FFM** (`java.lang.foreign`) is still a **preview** language feature, so the POM enables **`--enable-preview`** for `javac` and Surefire. From **JDK 22** onward FFM is final and **`--enable-preview`** is not required for foreign access (the build keeps the flag for a uniform **`--release 21`** compile until the baseline moves).

## jextract

Add generated sources locally (committed **after** codegen policy in **P3-E1-S1b**):

```bash
export RDP_HEADERS="$PWD/../../../jvm-sys/include"
jextract --output src/main/java/generated \
  --target-package io.github.scorpio_datalake.rust_data_processing.internal.foreign \
  "$RDP_HEADERS/rdp_jvm_sys.h"
```
