# Architecture Decision Records (ADR)

Short, versioned notes for cross-cutting choices. Phase 3 GA **requires** **Panama**, **Maven**, **Gradle**, and **Kafka** on **Rust, Python, JVM** (**`Planning/PHASE3_EPICS.md`**). **`spikes/jvm-panama-ffi/`** is **completed increment 1**: `cdylib` / C ABI smoke only (`README.md` there).

| ID | Title |
|----|--------|
| [003](003-jvm-panama-ffi-spike.md) | JVM increment 1: `cdylib` / C ABI; Panama; **Maven + Gradle + Kafka** mandated for Phase 3 GA |
| [004](004-jvm-bindings-build-parity.md) | Maven + Gradle scaffold; **`bindings/jvm-sys`**; **`rdp_ffi_abi_version=400`**; parity matrix |
| [005](005-jvm-panama-production-policy.md) | Panama JDK **21+**, **`jextract`**, semver, Maven∧Gradle, parity (**P3-E1-S0a**) |
| [006](006-jvm-orchestration-pipeline-json.md) | **`rdp_run_pipeline_json`**: versioning, orchestration limits, structured errors; lake/relational sink contract (**P3-E1-S9/S10**) |
