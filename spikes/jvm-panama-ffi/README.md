# Phase 3 increment 1 — JVM FFI spike (`cdylib` + C header + Panama)

**Phase 3 product delivery** mandates **Project Panama**, **Maven**, **and** **Gradle**, plus **Kafka** on **Rust, Python, and JVM** (including **bring-your-own-connector** ingestion into Rust). See **`Planning/PHASE3_EPICS.md`** and **`docs/adr/003-jvm-panama-ffi-spike.md`**.  

This **`spikes/jvm-panama-ffi`** directory is **increment 1 only** (completed): it validates **Rust → `cdylib` → Panama** and does **not** host the production **`pom.xml` / Gradle** modules—those are **required Phase 3 deliverables elsewhere** per **P3‑E1**, not deferred options.

- **Mandatory for Phase 3:** Panama **FFM** + **`jextract`** (generation lands with production **`pom.xml` / Gradle** modules).  
- **Mandatory for Phase 3:** **`mvn verify`** **and** **`./gradlew check`** on **`ubuntu-latest`**, **`windows-latest`**, **`macos-latest`** (JDK **21+**) once JVM modules exist.  
- **This spike folder:** stdlib-only Rust; **manual `javac`/`java`** reproduces **the same Panama technology** CI will enforce via Maven + Gradle later.

ADR: **[`docs/adr/003-jvm-panama-ffi-spike.md`](../../docs/adr/003-jvm-panama-ffi-spike.md)**

## Build & test (Rust — small footprint)

```bash
cargo test --manifest-path spikes/jvm-panama-ffi/Cargo.toml
```

Release **`cdylib`** (needed for JVM smoke):

```bash
cargo build --release --manifest-path spikes/jvm-panama-ffi/Cargo.toml
```

Artifacts: **`spikes/jvm-panama-ffi/target/release/`**

| OS | Output |
|----|--------|
| Linux / macOS | `lib…_spike.so` / `.dylib` |
| Windows (MSVC) | `rdp_jvm_ffi_spike.dll` |

## Header regen (`cbindgen` — tooling opt-in)

`cbindgen` is intentionally **not** a dev-dependency of this spike (smaller installs). Maintainer opt-in:

```bash
cargo install cbindgen --locked
cbindgen -c spikes/jvm-panama-ffi/cbindgen.toml -o spikes/jvm-panama-ffi/include/rdp_ffi.h spikes/jvm-panama-ffi
```

## Panama smoke (**FFM — same tech as production Phase 3**)

JDK **21+**. Use an absolute native library path via **`-Drdp.jvm.spike.library=…`**.

See platform snippets in **`docs/adr/003-jvm-panama-ffi-spike.md`** and **Historical** appendix below matching earlier repo examples (Linux/macOS/PowerShell).

<details>
<summary>Linux / macOS</summary>

```bash
cd spikes/jvm-panama-ffi/java
LIB="$(cd .. && pwd)/target/release/librdp_jvm_ffi_spike.so"   # .dylib on macOS
javac --release 21 PanamaSmoke.java
java --enable-native-access=ALL-UNNAMED -Drdp.jvm.spike.library="$LIB" PanamaSmoke
```
</details>

<details>
<summary>Windows PowerShell</summary>

```powershell
cd spikes\jvm-panama-ffi\java
javac --release 21 PanamaSmoke.java
# JDK 24+ preview quirk example:
javac --enable-preview --release 25 PanamaSmoke.java
java --enable-preview --enable-native-access=ALL-UNNAMED `
  "-Drdp.jvm.spike.library=$((Resolve-Path ..\target\release\rdp_jvm_ffi_spike.dll).Path)" `
  PanamaSmoke
```
</details>
