# JVM release alignment (Maven + Gradle)

Extend root **`docs/RELEASE_CHECKLIST.md`**. Bump **Rust / Python / JVM** versions together.

| Artefact | File / location |
| --- | --- |
| Rust crates.io | root **`Cargo.toml`** `[package] version` |
| PyPI | **`python-wrapper/pyproject.toml`**, **`python-wrapper/Cargo.toml`** |
| JVM SemVer | **`bindings/java/VERSION`** → mirror **`pom.xml`** + **`gradle.properties`** (**`scripts/check_java_version_consistency.py`**) |
| **`rdp-jvm-sys`** native crate | **`bindings/jvm-sys/Cargo.toml`** (`publish=false` until policy changes) |

## Maven Central checklist

See **[MAVEN_CENTRAL_PUBLISHING.md](MAVEN_CENTRAL_PUBLISHING.md)** (**verified namespace** `io.github.scorpio-datalake`, publish **`groupId`** `io.github.scorpio-datalake.rust-data-processing`, **publisher user token**, **GPG**).

**CI publish:** push a GitHub Release with tag **`v{VERSION}`** after bumping **`bindings/java/VERSION`** off `-SNAPSHOT` — workflow **`jvm_maven_central_release.yml`** deploys automatically when the tag matches VERSION.

**Activate signing / javadoc bundles** locally or in CI staging:

```bash
mvn -DcentralRelease=true verify
```

(Requires local GPG + credentials configured per maintainer.)

## Native artefacts

Shipping classifier JARs for **`rdp_jvm_sys`** (**per OS/CPU**) is **P3-E1-S1e**. Until then CI builds natives per runner and Maven/Gradle consume **`RDP_JVM_SYS`** for integration tests only.
