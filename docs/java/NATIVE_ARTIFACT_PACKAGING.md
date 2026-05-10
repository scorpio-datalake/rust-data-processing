# Native artifact packaging (**P3-E1-S1e**)

Release **`rdp_jvm_sys`** per OS/CPU as attachments alongside **`rust-data-processing-jvm`**.

## Coordinates (preview)

| Artifact | classifier examples |
| --- | --- |
| **`rdp_jvm_sys`** | `windows-x86_64`, `linux-x86_64`, `linux-aarch64`, `osx-x86_64`, `osx-aarch64` |

Maven attaches **`${os}-${arch}`** classifier JARs containing **`META-INF/native/`** copies of **`dll`/`so`/`dylib`** (pattern used by **sqlite-jdbc**, **netty-tcnative**, etc.). Gradle **`maven-publish`** mirrors the same.

## MSVC runtime

Windows builds linked against **dynamic** MSVC runtime require **VC++ Redistributable** on target hosts — document min version in release notes once **`rdp_jvm_sys`** ships MSVC-linked binaries.

## CI matrix

**`.github/workflows/jvm_bindings_ci.yml`** builds **Linux / Windows / macOS** runners and publishes artifacts for downstream classifier packaging (**future**: attach to Maven staging).
