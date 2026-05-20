# ADR 003 — Phase 3 JVM spike: Panama `cdylib` C ABI (**increment 1 / n**)

**Status:** Accepted — **spike** complete; Phase 3 **overall** mandates **Maven + Gradle + Panama** (this ADR describes the thin **Rust + hand-wired Panama** increment only).  
**Date:** 2026-05-10 (updated **2026-05-10** — requirements clarified).  
**Stories:** `Planning/PHASE3_EPICS.md` — groundwork for **P3-E1-S0\* / S1\***.

---

## Context

Phase 3 **requires** (not optional for the shipped product):

1. **Java** integration via **Project Panama** (**FFM**, **`jextract`** from a versioned **`C` header**).
2. **Maven**: `pom.xml`, **`mvn verify`** in CI, **Maven Central** publication path.
3. **Gradle**: `build.gradle.kts`, **Gradle Wrapper**, **`gradle check`** in CI **on the same OS × JDK matrix** as Maven **(both gates must pass)**.

This repository’s **`spikes/jvm-panama-ffi`** crate proves **Rust → `cdylib` → hand-written Panama invocation** **without Polars**. It deliberately omits Maven/Gradle scaffolding to keep **increment-1 compile time and disk tiny**—**not** because Maven or Gradle are optional for Phase 3.

**Async / threading:** Panama downcalls from Java remain **blocking** unless a later ADR introduces an async reactor; FFI boundary stays synchronous for **P3**.

---

## Decision (increment 1 — spike crate)

| Topic | Outcome |
| --- | --- |
| **`cdylib` + C ABI** | Validated **`rdp_ffi_abi_version`** + **`rdp_ffi_sum_i32`** (**`rdp_ffi.h`**). Stable pattern for **`jextract`** + production header growth. |
| **Panama** | **Mandatory** JVM strategy for Phase 3. This spike uses a **minimal** **`PanamaSmoke.java`** ahead of **`jextract`** codegen; **production replaces hand descriptors** with generated sources checked into **Maven and Gradle** source trees (**P3-E1-S1b**). |
| **Maven + Gradle** | **Mandatory for Phase 3 GA**; **out of scope for this spike directory only** until **`bindings/java-maven`** / **`bindings/java-gradle`** (or unified layout — ADR in **P3-E1-S0a**) lands. |
| **`jextract`** | **Required** in Phase 3 delivery; spike defers codegen to limit increment-1 surface area. |
| **JNI JDK 17** | **Optional *supplementary*** enterprise path **only if** Phase 3 ADR signs matrix; never replaces Panama + dual build tooling on **JDK 21+**. |
| **Arrow / zero-copy** | Spike does **not** exercise Arrow payloads; **`P3-E1-S0b` / `S1d`** mandate the ownership + IPC contract **before Phase 3 closeout**. |

---

## Implementation (artifacts for increment 1)

| Path | Role |
| --- | --- |
| [`spikes/jvm-panama-ffi/Cargo.toml`](../../spikes/jvm-panama-ffi/Cargo.toml) | Isolated **`cdylib` + `rlib`**, **`publish = false`**, **stdlib-only** |
| [`spikes/jvm-panama-ffi/include/rdp_ffi.h`](../../spikes/jvm-panama-ffi/include/rdp_ffi.h) | Header for **`jextract`** in later increments |
| [`spikes/jvm-panama-ffi/java/PanamaSmoke.java`](../../spikes/jvm-panama-ffi/java/PanamaSmoke.java) | **Mandatory technological path** (**Panama**); kept small until codegen replaces literals |

The published **crates.io** tarball [**excludes** `spikes/`](../../Cargo.toml) (`[package.exclude]`).

---

## Verification (**increment 1**)

### Rust (**required on every spike change**)

```bash
cargo test --manifest-path spikes/jvm-panama-ffi/Cargo.toml
```

### Panama smoke (**same binding technology as Phase 3 product** — validates this increment with **`javac`** / **`java`** before **Maven + Gradle** CI subsumes raw compilation)

Instructions: [`spikes/jvm-panama-ffi/README.md`](../../spikes/jvm-panama-ffi/README.md). Expect **`rdp_ffi_abi_version -> 3`** and **`rdp_ffi_sum_i32 rc=0 sum=60`**.

*(CI wiring for **Maven + Gradle** replaces ad-hoc `javac` in **Phase 3 P3-E1-S3b / S4a**.)*

---

## Phase 3 completion criteria (beyond this spike)

**Not deferred:** **Maven Central** staging/production, **`mvn verify`**, **Gradle Wrapper + `./gradlew check`**, **`jextract`** in build graphs, **`docs/java`** quick starts for **both** Gradle and Maven, tri-platform matrices **(Maven ∧ Gradle)** per **`PHASE3_EPICS.md`** **P3-E1**, plus **`PHASE3_EPICS`** **P3-E2 — Kafka on Rust / Python / JVM** (native **`rdkafka`** paths and **bring-your-own-connector** ingestion—**`P3-E2-Conn`** / **`P3-E2-P2`** / **`P3-E2-J2`**).

---

## Changelog snippet

> Phase 3 JVM: **Panama mandatory**; **Maven + Gradle mandatory** for product delivery. Spike directory remains a **narrow `cdylib` proof**; full build-tooling tracks **`PHASE3_EPICS`** **P3-E1**.
