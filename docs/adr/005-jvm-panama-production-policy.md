# ADR 005 — JVM bindings production policy (Panama, semver, parity)

**Status:** Accepted  
**Date:** 2026-05-10  
**Stories:** **P3-E1-S0a**, **S0b** (policy); implementation tracked in **`Planning/PHASE3_EPICS.md`**.

---

## Decision

| Topic | Policy |
| --- | --- |
| **JDK baseline** | **21 LTS** — CI (**Temurin**) pins **21** for reproducible builds. Host JDK **24+** may require **`--enable-preview`** for `java.lang.foreign` on some vendors until stabilized locally (**Gradle `build.gradle.kts`** adapts). |
| **`jextract`** | Generated Java lives under **`bindings/java/rust-data-processing-jvm/src/main/java`** (package **`…internal.foreign`** or **`generated`**) — workflow in **`docs/java/JEXTRACT.md`**. Regeneration is **required** before freezing a Central-bound release when **`rdp_jvm_sys.h`** changes. |
| **`async`** | FFI boundary is **synchronous**. Rust async runtimes / JVM virtual threads **must not** cross the **`#[no_mangle]`** surface without a dedicated ADR (thread offload stays inside Rust). |
| **Rust MSRV** | **`rust-data-processing`** MSRV is authoritative (**see root `Cargo.toml`**). **`rdp-jvm-sys`** may lag (`edition = "2021"`) until aligned. |
| **Semver** | **Maven `artifactId` version**, **`gradle.properties` `version`**, **`bindings/java/VERSION`**, root **`Cargo.toml`** **`rust-data-processing`** — **same SemVer** at GA (**release checklist**). **`rdp_ffi_abi_version`** bumps **only** on native ABI breaks (orthogonal to crate semver). |
| **Maven ∧ Gradle** | **Both** **`mvn verify`** **and** **`./gradlew check`** **block** merges touching **`bindings/`**. **Central** publishing uses Maven **`central-publishing-maven-plugin`** / Gradle **`maven-publish`** (**`P3-E1-S3c`**). |
| **Parity** | **`Planning/PHASE3_EPICS.md`** (parity rows) + **`scripts/check_jvm_ffi_manifest.*`** until full coverage; gaps require **`docs/adr/`** exception with owner + target version. |

---

## Rust-only surface (non-FFI)

Macros (`macro_rules!`), inherent methods not exported across **`extern "C"`**, and unstable experiments stay Rust-only until explicitly exported — list maintained in **`docs/java/FFI_API_SLICE.md`**.

---

## Arrow + Kafka bytes

Bulk tabular data crosses FFI via **Apache Arrow** layouts documented in **`docs/java/ARROW_FFI_JVM.md`** (ownership). Kafka batch injection follows **P3-E2-Conn** once landed.
