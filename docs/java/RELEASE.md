# JVM release alignment (Maven + Gradle)

Extend root **`docs/RELEASE_CHECKLIST.md`**. Bump **Rust / Python / JVM** versions together.

| Artefact | File / location |
| --- | --- |
| Rust crates.io | root **`Cargo.toml`** `[package] version` |
| PyPI | **`python-wrapper/pyproject.toml`**, **`python-wrapper/Cargo.toml`** |
| JVM SemVer | **`bindings/java/VERSION`** → mirror **`pom.xml`** + **`gradle.properties`** (**`scripts/check_java_version_consistency.py`**) |
| **`rdp-jvm-sys`** native crate | **`bindings/jvm-sys/Cargo.toml`** (`publish=false` until policy changes) |
| **`rdp-jvm-sys`** classifier JARs | **`bindings/java/rdp-jvm-sys/pom.xml`** (metadata only; binaries built per platform) |

## Maven Central checklist

See **[MAVEN_CENTRAL_PUBLISHING.md](MAVEN_CENTRAL_PUBLISHING.md)** (**verified namespace** `io.github.scorpio-datalake`, publish **`groupId`** `io.github.scorpio-datalake.rust-data-processing`, **publisher user token**, **GPG**).

**CI publish (Java API JAR):** push a GitHub Release with tag **`v{VERSION}`** after bumping **`bindings/java/VERSION`** off `-SNAPSHOT` — workflow **`jvm_maven_central_release.yml`** deploys automatically when the tag matches VERSION.

**CI publish (native classifiers):** same **`v{VERSION}`** gate — workflow **`jvm_native_maven_release.yml`** builds one classifier JAR per OS/CPU (`linux-x86_64`, `linux-aarch64`, `osx-aarch64`, `osx-x86_64`, `windows-x86_64`) and deploys via **`scripts/deploy_rdp_jvm_sys_native_jars.sh`**. See **[NATIVE_ARTIFACT_PACKAGING.md](NATIVE_ARTIFACT_PACKAGING.md)**.

**Activate signing / javadoc bundles** locally or in CI staging:

```bash
mvn -f bindings/java/rust-data-processing-jvm spotless:check   # or spotless:apply if check fails
mvn -DcentralRelease=true verify
```

(Requires local GPG + credentials configured per maintainer. **`spotless:check`** runs automatically in Maven **`validate`** before **`deploy`**; CI also runs it explicitly in **`jvm_maven_central_release.yml`**.)

## Native artefacts (**P3-E1-S1e**)

Prebuilt **`rdp_jvm_sys`** shared libraries ship as **separate Maven classifier JARs** (`rdp-jvm-sys:{version}:{classifier}`). Each JAR contains one binary under **`META-INF/native/`** (~tens of MB per platform, not a fat multi-OS bundle).

| Consumer | Setup |
| --- | --- |
| **Maven / Gradle apps** | Depend on **`rust-data-processing-jvm`** + **one** **`rdp-jvm-sys`** classifier matching the host OS/CPU. **`RdpNativeJson`** loads from the classpath — **no Rust install**, no **`RDP_JVM_SYS`**. |
| **Contributors / custom features** | `cargo build --release --manifest-path bindings/jvm-sys/Cargo.toml --features full` and set **`RDP_JVM_SYS`** or **`-Drdp.jvm.sys.library`**. |
| **CI integration tests** | Still use **`RDP_JVM_SYS`** pointing at a freshly built `cdylib` on each runner (same as before classifiers shipped). |

**Local smoke before tagging:** `./scripts/test_native_classifier_local.sh` (Linux x86_64: package classifier JAR, **`NativeClassifierClasspathTest`** without **`RDP_JVM_SYS`**, full JVM test suite with explicit path).

**Kafka-enabled natives** are not yet published as classifiers; Kafka JVM examples still require a source build with **`--features kafka`** and **`RDP_JVM_SYS`**.
