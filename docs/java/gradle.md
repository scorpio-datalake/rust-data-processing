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

**JDK notes:** Gradle **9.2.1** wrapper runs on JDK **21+**. The build emits **`--release 21`** on JDK **21–23**; from **JDK 24** onward Gradle matches the bootstrapping JDK major and adds **`--enable-preview`** for lingering `java.lang.foreign` preview builds (**Linux CI fixes Temurin 21**, so no preview).

Tests always append **`--enable-native-access=ALL-UNNAMED`**. JDK **≥24** Maven callers may still need **`mvn verify -Drdp.foreignPreview=true`** (see **`bindings/java/rust-data-processing-jvm/README.md`**).

## Consume from Maven Local (**P3-E1-S3c**)

After **`./gradlew publishToMavenLocal`**:

**Gradle (Kotlin DSL)**

```kotlin
repositories { mavenLocal() }
dependencies {
    implementation("io.github.vihangdesai2018_png:rust-data-processing-jvm:0.1.0-SNAPSHOT")
}
```

Use the exact version from **`bindings/java/VERSION`**.

**Maven**

```xml
<dependency>
  <groupId>io.github.vihangdesai2018_png</groupId>
  <artifactId>rust-data-processing-jvm</artifactId>
  <version>0.1.0-SNAPSHOT</version>
</dependency>
```
