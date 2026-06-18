# Gradle — `rust-data-processing-jvm`

Module: **`bindings/java/rust-data-processing-jvm/`**.

## Consume from Maven Central (recommended — no Rust)

Add **`rust-data-processing-jvm`** and **one** **`rdp-jvm-sys`** classifier for the machine running the JVM. Use the same version for both (see **`bindings/java/VERSION`**). Full classifier table: **[NATIVE_ARTIFACT_PACKAGING.md](NATIVE_ARTIFACT_PACKAGING.md)**.

**Gradle (Kotlin DSL)**

```kotlin
val rdpVersion = "0.3.4"
val nativeClassifier = "linux-x86_64" // osx-aarch64, windows-x86_64, …

dependencies {
    implementation("io.github.scorpio-datalake.rust-data-processing:rust-data-processing-jvm:$rdpVersion")
    implementation("io.github.scorpio-datalake.rust-data-processing:rdp-jvm-sys:$rdpVersion:$nativeClassifier")
}
```

**JVM flags** when running your app:

```text
--enable-native-access=ALL-UNNAMED
```

`RdpNativeJson` loads the native library from **`META-INF/native/`** on the classpath. Override with **`RDP_JVM_SYS`** or **`-Drdp.jvm.sys.library`** only for custom builds or debugging.

## Maintainer: run tests from a checkout

Integration tests on CI build **`rdp_jvm_sys`** per runner and point **`RDP_JVM_SYS`** at the `cdylib`. Mirror that locally:

```bash
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full
```

**Linux / macOS**

```bash
export RDP_JVM_SYS="$(pwd)/bindings/jvm-sys/target/release/librdp_jvm_sys.so"
cd bindings/java/rust-data-processing-jvm
./gradlew check --no-daemon
```

**Windows (PowerShell)**

```powershell
$cargoDll = Resolve-Path .\bindings\jvm-sys\target\release\rdp_jvm_sys.dll
$env:RDP_JVM_SYS = $cargoDll.Path
cd bindings/java/rust-data-processing-jvm
.\gradlew.bat check --no-daemon
```

**Classifier smoke (Linux x86_64):** `./scripts/test_native_classifier_local.sh` from the repo root.

**JDK notes:** Gradle **9.2.1** wrapper runs on JDK **21+**. The build uses **`--release 21`** on JDK **21–23** and adds **`--enable-preview`** so **`java.lang.foreign`** compiles on **JDK 21** (FFM preview there). From **JDK 24** onward the project matches the runtime JDK major for **`release`** and still passes **`--enable-preview`** (harmless when unused).

Tests always append **`--enable-preview`** and **`--enable-native-access=ALL-UNNAMED`**.

## Consume from Maven Local (**P3-E1-S3c**)

After **`./gradlew publishToMavenLocal`**:

**Gradle (Kotlin DSL)**

```kotlin
repositories { mavenLocal() }
dependencies {
    implementation("io.github.scorpio-datalake.rust-data-processing:rust-data-processing-jvm:0.1.0-SNAPSHOT")
}
```

Use the exact version from **`bindings/java/VERSION`**. Native classifiers are **not** published to `mavenLocal` by default — use **`RDP_JVM_SYS`** or depend on a locally packaged classifier JAR (see **`scripts/package_rdp_jvm_sys_native_jar.py`**).

**Maven**

```xml
<dependency>
  <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
  <artifactId>rust-data-processing-jvm</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```
