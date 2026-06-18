# Native artifact packaging (**P3-E1-S1e**)

Prebuilt **`rdp_jvm_sys`** shared libraries ship as **separate Maven classifier JARs** alongside **`rust-data-processing-jvm`**. This is **not** a fat JAR — each classifier contains **one** platform binary (~tens of MB), not all platforms bundled together.

## FAQ

| Question | Answer |
| --- | --- |
| **Do end users need Rust installed?** | **No**, when they add the **`rdp-jvm-sys`** classifier for their OS/CPU. Rust is only needed to **build from source** (contributors / custom features). |
| **Is it a fat JAR?** | **No.** You depend on **`rust-data-processing-jvm`** (Java API) **plus one** `rdp-jvm-sys` classifier (native only). |
| **How big is each classifier JAR?** | One `cdylib` with `--features full` (batch connectors). Expect on the order of **~20–80 MB** per platform depending on linked crates — not the multi‑hundred‑MB “all platforms” bundle. |
| **Does the main JVM JAR grow?** | **No.** Native bytes stay in **`rdp-jvm-sys`** classifiers only. |

## Maven coordinates

| Artifact | Classifier | Contents |
| --- | --- | --- |
| `io.github.scorpio-datalake.rust-data-processing:rust-data-processing-jvm` | *(none)* | Java bindings, `ffi_manifest.json` |
| `io.github.scorpio-datalake.rust-data-processing:rdp-jvm-sys` | see below | `META-INF/native/<basename>` only |

**Classifiers (CI builds each independently):**

| Platform | Classifier | Native basename in JAR |
| --- | --- | --- |
| Linux x86_64 | `linux-x86_64` | `librdp_jvm_sys.so` |
| Linux aarch64 | `linux-aarch64` | `librdp_jvm_sys.so` |
| macOS Apple Silicon | `osx-aarch64` | `librdp_jvm_sys.dylib` |
| macOS Intel | `osx-x86_64` | `librdp_jvm_sys.dylib` |
| Windows x86_64 | `windows-x86_64` | `rdp_jvm_sys.dll` |

## Consumer setup (Maven)

Use the **same version** for both artifacts. Pick **one** classifier matching the machine running the JVM.

```xml
<properties>
  <rdp.jvm.version>0.3.4</rdp.jvm.version>
  <!-- Linux x86_64 example — change classifier per table above -->
  <rdp.jvm.native.classifier>linux-x86_64</rdp.jvm.native.classifier>
</properties>

<dependencies>
  <dependency>
    <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
    <artifactId>rust-data-processing-jvm</artifactId>
    <version>${rdp.jvm.version}</version>
  </dependency>
  <dependency>
    <groupId>io.github.scorpio-datalake.rust-data-processing</groupId>
    <artifactId>rdp-jvm-sys</artifactId>
    <version>${rdp.jvm.version}</version>
    <classifier>${rdp.jvm.native.classifier}</classifier>
  </dependency>
</dependencies>
```

**JVM flags** (JDK 21+):

```text
--enable-native-access=ALL-UNNAMED
```

On JDK 21 you may also need `--enable-preview` when compiling **your** code against Panama APIs; the published JAR is built with preview enabled.

**Loading:** `RdpNativeJson.resolveNativeLibraryFromEnvOrProperty()` checks, in order:

1. `RDP_JVM_SYS` environment variable  
2. `-Drdp.jvm.sys.library=…`  
3. **`META-INF/native/`** on the classpath (from the classifier JAR — **no manual path**)  
4. `bindings/jvm-sys/target/` checkout build (developers only)

Overrides (1–2) still work for custom builds or debugging.

## Consumer setup (Gradle Kotlin DSL)

```kotlin
val rdpVersion = "0.3.4"
val nativeClassifier = "linux-x86_64" // osx-aarch64, windows-x86_64, …

dependencies {
    implementation("io.github.scorpio-datalake.rust-data-processing:rust-data-processing-jvm:$rdpVersion")
    implementation("io.github.scorpio-datalake.rust-data-processing:rdp-jvm-sys:$rdpVersion:$nativeClassifier")
}
```

## Build from source (optional)

Contributors and users who need custom Cargo features:

```bash
cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full
export RDP_JVM_SYS=$PWD/bindings/jvm-sys/target/release/librdp_jvm_sys.so
```

## CI / release

Workflow: **`.github/workflows/jvm_native_maven_release.yml`**

- **One workflow**, **independent jobs** per platform (`build-linux-x86_64`, `build-linux-aarch64`, `build-windows-x86_64`, `build-macos-aarch64`, `build-macos-x86_64`) so a single OS failure is visible and re-runnable without blocking others.
- **`deploy-native-classifiers`** runs only when **all** build jobs succeed; publishes to Maven Central next to `rust-data-processing-jvm`.
- Packaging script: `scripts/package_rdp_jvm_sys_native_jar.py` → JAR layout `META-INF/native/…`
- Deploy script: `scripts/deploy_rdp_jvm_sys_native_jars.sh`

Triggered on GitHub **Release published** when tag `v{VERSION}` matches `bindings/java/VERSION` (same gate as the Java JAR release).

## Platform notes

- **Linux:** built on `ubuntu-latest` (x86_64) and `ubuntu-24.04-arm` (aarch64). Targets **glibc** on typical GitHub-hosted runners (document minimum distro in release notes if you tighten this).
- **macOS x86_64:** cross-compiled on `macos-latest` via `x86_64-apple-darwin`.
- **Windows:** **VC++ Redistributable** may be required on target hosts when the `cdylib` links the dynamic MSVC runtime.

## Kafka

Batch **`full`** is the default classifier build. Streaming Kafka (`--features kafka`) is a **separate** native build — not yet published as its own classifier; use source build + `RDP_JVM_SYS` for Kafka examples today.
