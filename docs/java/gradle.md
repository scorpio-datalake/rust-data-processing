# Gradle — `rust-data-processing-jvm`

Module: **`bindings/java/rust-data-processing-jvm/`**.

## Prerequisite

Release **`rdp_jvm_sys`**:

```bash
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml
```

## Run tests

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

Use the exact version from **`bindings/java/VERSION`**.

**Maven**

```xml
<dependency>
  <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
  <artifactId>rust-data-processing-jvm</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```
