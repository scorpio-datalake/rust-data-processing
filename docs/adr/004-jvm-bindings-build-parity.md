# ADR 004 — JVM bindings build: Panama parity + Maven∧Gradle (Phase 3)

**Status:** Proposed (**scaffold landed**)  
**Date:** 2026-05-10  
**Supercedes:** narrative-only coverage in **ADR 003** (spike); this ADR covers **product** layout.

---

## Context

Phase 3 demands:

1. **Panama + `jextract`** from **`bindings/jvm-sys/include/rdp_jvm_sys.h`**.
2. **`mvn verify`** **and** **`./gradlew check`** as **blocking** gates.
3. **Rust API parity** enshrined in **`Planning/PHASE3_EPICS.md`** (parity rows).

This ADR records the **repository layout** and **ABI version** policy for the first integrated increment.

---

## Decision

| Topic | Outcome |
| --- | --- |
| **Rust FFI crate path** | **`bindings/jvm-sys`** (`cargo package` **`publish=false`**) produces **`rdp_jvm_sys`**. **`rdp_ffi_abi_version`** starts at **`400`** (≠ spike **`3`**). Bump with every native breaking change / semantic shift. |
| **Optional linkage to main crate** | Feature **`link-main`** (and **`full`**) attaches **`rust-data-processing`** when wiring APIs; **`default`** build stays slim for iterative JVM toolchain work (**CI default** builds without **`link-main`** until FFI surface stabilises). |
| **Maven coordinates** | **`io.github.scorpio-datalake.rust-data-processing`** : **`rust-data-processing-jvm`** (**`bindings/java/rust-data-processing-jvm/pom.xml`**) · **Gradle** mirrored in **`build.gradle.kts`**. |
| **Codegen** | **`jextract`** output expected under **`src/main/java/...`** (policy: generated vs checked-in finalized in **`P3-E1-S1b`** CI task). |

---

## Consequences

- **Maven Central** publishes require **sources + javadoc + signed** artefacts — scaffolding lives in Maven profile **`central-release`** (**`-DcentralRelease=true`**).
- **Gradle Central Publishing Plugin** aligns with Maven coordinates (**`P3-E1-S3c`** rollout).
