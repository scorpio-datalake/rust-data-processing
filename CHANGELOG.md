# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Changed

- **Planning:** Phase **3** JVM/Kafka tracking consolidated into **`Planning/PHASE3_EPICS.md`** only (parity rows + **P3-E1-S2d/S2e** secret stories). Removed **`docs/java/PARITY_MATRIX.md`** and superseded **`Planning/`** phase-plan markdown (`PHASE1_*`, **`Phase2_plan.md`**, **`PHASE2_EPICS.md`**, and related local notes) per single-tracker policy.
- **`README_CRATE.md`:** Phase 2 hero image uses **`raw.githubusercontent.com`** (same as PyPI) so [crates.io](https://crates.io/crates/rust-data-processing) and [docs.rs](https://docs.rs/rust-data-processing) always load the current PNG; documentation table adds **PyPI**, **Python examples (HTML)**, and explicit **GitHub Pages** URLs for Rust rustdoc vs Python pdoc (root `/rust_data_processing.html` is not rustdoc; see below).
- **GitHub Pages:** Add `docs/landing/rust_data_processing.html` → `site/rust_data_processing.html` so `https://…/rust_data_processing.html` redirects to **`python/rust_data_processing.html`** (pdoc) with a visible link to **`rust/rust_data_processing/index.html`** (rustdoc). **`docs/DOCUMENTATION.md`** lists the canonical paths.

### Added

- **JVM Phase 3 bindings:** **`bindings/jvm-sys`** (`rdp_ffi_abi_version` = **400**, **`jvm_ffi`** feature, **`ffi_manifest.json`**); **`bindings/java/rust-data-processing-jvm`** (Maven, Gradle **`maven-publish`**, **`NativeAbiTest`**, **`FeatureSurfacePlaceholderTest`**, **`central-release`** profile); **`scripts/check_jvm_ffi_manifest.py`**, **`scripts/check_java_version_consistency.py`**; **`bindings/java/VERSION`** ↔ **`pom.xml`** ↔ **`gradle.properties`**.
- **Docs:** **`docs/adr/{004,005}-*.md`**, **`docs/java/{README,gradle,RELEASE,FFI_API_SLICE,ARROW_FFI_JVM,NATIVE_ARTIFACT_PACKAGING,SONATYPE_NAMESPACE_CHECKLIST,JIRA_INTEGRATION,JEXTRACT}.md`**, **`docs/java/MAVEN_CENTRAL_PUBLISHING.md`**; JVM parity rows live only in **`Planning/PHASE3_EPICS.md`** (standalone **`PARITY_MATRIX`** removed).
- **CI:** **`.github/workflows/jvm_bindings_ci.yml`** — **Ubuntu / Windows / macOS** × JDK **21**, **`mvn verify`**, **`./gradlew check`**, **`publishToMavenLocal`**, **`actions/cache`** for Maven/Gradle.

- **Phase 3 planning (JVM + Kafka):** **Panama**, **Maven**, **Gradle**, **Rust↔JVM API parity**, tri-surface **Kafka** (+ BYO connectors) in **`Planning/PHASE3_EPICS.md`**; **`docs/DOCUMENTATION.md`**, **`docs/adr/README.md`**, ADR **`docs/adr/003`**. Completed **increment-1** spike crate **[`spikes/jvm-panama-ffi`](spikes/jvm-panama-ffi/README.md)** (**stdlib-only** `cdylib`, [`rdp_ffi.h`](spikes/jvm-panama-ffi/include/rdp_ffi.h), Panama smoke). Root **`Cargo.toml`** excludes **`spikes/`** from the crates.io tarball.
- **Phase 2 (batch):** `ReduceOp::Median` / `Agg::Median`; UTF-8 privacy `TransformStep`s (`Utf8Truncate`, `Utf8Sha256Hex`, `Utf8RedactMiddle`); validation `Check::Utf8LenCharsBetween`; modules **`export`** (JSONL + deterministic train/test indices), **`privacy`** (UTF-8 diff summaries), **`reports`** (byte-safe truncation); Arrow **`record_batches_to_dataset`** + `LargeUtf8` support; Python bindings `export_dataset_jsonl`, `privacy_summarize_utf8_changes_json`, `reports_truncate_utf8_bytes`.
- **Docs & examples:** lake read ADR + user guide, SFT format guide, outreach shortlist, ML reduce gap audit, P2-E6 policy + P2-E7 deferral notes, vector export recipe, `examples/{dbt,airflow,tabular_nn,llm_prep}/`, `notebooks/` index + starter notebooks.

## [0.3.4] - 2026-06-17

### Changed

- (summarize this release)

## [0.3.3] - 2026-06-17

### Changed

- (summarize this release)

## [0.3.2] - 2026-06-17

### Changed

- (summarize this release)

## [0.3.1] - 2026-05-21

### Changed

- (summarize this release)

## [0.3.0] - 2026-05-20

### Changed

- (summarize this release)

## [0.2.2] - 2026-05-04

### Changed

- (summarize this release)

## [0.2.1] - 2026-05-04

### Fixed

- **crates.io / docs.rs / PyPI README:** Hero infographic now uses **`docs/images/phase-2-scope-overview.png`** and Phase 2 copy in **`README_CRATE.md`** and **`python-wrapper/README_PYPI.md`** (0.2.0 still showed the Phase 1 graphic on registries).

## [0.2.0] - 2026-05-04

### Changed

- (summarize this release)

## [0.1.8] - 2026-04-14

### Changed

- (summarize this release)

## [0.1.7] - 2026-04-14

### Fixed

- **docs.rs**: Fix rustdoc intra-doc links that become hard errors under `RUSTDOCFLAGS=-D warnings` (broken `Some(None)`-style links, link to a private helper, redundant `DataSet` targets, ambiguous `glob` / `reduce` links). Republish so docs.rs can rebuild (0.1.6 docs build failed).
- **docs.rs**: add `[package.metadata.docs.rs]` with `cargo-args = ["-j", "1"]` so the documentation build is less likely to run out of memory while compiling Polars and the rest of the dependency graph (see `Cargo.toml` comments).
- **PyPI / maturin**: Remove duplicate `docs/images/phase-1-scope-overview.png` under `python-wrapper/` and drop `[tool.maturin] include` for PNGs. The sdist merged the repo-root image (from `cargo package`) with the wrapper copy and failed with “was already added … can't add it from … python-wrapper/docs/images/”. The PyPI README now uses a **raw.githubusercontent.com** URL for that image; canonical file stays at **`docs/images/`** in the repo.

### Planned

- **JVM / Java / Maven:** first-class bindings (native library + **Maven Central** artifact, cross-platform CI) are **Phase 3** scope, not Phase 2, so Phase 2 can focus on Rust + Python with **smaller releases** and faster cycles. Follow releases and notes here for when Java support lands.

## [0.1.6] - 2026-04-13

### Changed

- (summarize this release)

## [0.1.5] - 2026-04-02

### Changed

- (summarize this release)

## [0.1.4] - 2026-03-31

### Changed

- (summarize this release)

## [0.1.3] - 2026-03-31

### Changed

- (summarize this release)

## [0.1.2] - 2026-03-31

### Fixed

- **docs.rs / crates.io**: The published crate now ships **`README_CRATE.md`** only (Rust-focused). The monorepo **`README.md`** is excluded from the `.crate` tarball so docs.rs no longer shows Python quick starts or mixed Python/Rust landing copy. PyPI continues to use **`python-wrapper/README_PYPI.md`**.

## [0.1.1] - 2026-03-30

### Changed

- `scripts/release_tag.ps1`: optional `-Comment`, interactive release comment, prints last `v*` tag, fetches tags; clearer error text.
- CI: Documentation workflow uses `astral-sh/setup-uv@v8.0.0` and drops redundant `configure-pages` for static Pages deploy.

## [0.1.0] - 2026-03-20

### Added

- Initial crates.io release of `rust-data-processing`.
- Schema-first ingestion: CSV, JSON / NDJSON (nested dot paths), Parquet; Excel via `excel` feature.
- In-memory `DataSet` model (`types`) with `Int64`, `Float64`, `Bool`, `Utf8`, `Null`.
- `processing`: `filter`, `map`, `reduce` with `ReduceOp` (count, sum, min/max, mean, variance, std dev, sum-squares, L2 norm, count-distinct).
- `processing::multi`: `feature_wise_mean_std`, `arg_max_row`, `arg_min_row`, `top_k_by_frequency`.
- Polars-backed `pipeline::DataFrame` (lazy plan, `collect` to `DataSet`), `group_by` with `Agg`, joins, casts, filters.
- SQL over `DataFrame` (`sql` feature, default-on).
- `execution` engine: parallel filter/map, metrics, observers.
- `profiling`, `validation`, `outliers`, `transform` (TransformSpec), `cdc` boundary types.
- Optional `db_connectorx` for DB → Arrow → `DataSet` ingestion.

[0.3.4]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.3.4
[0.3.3]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.3.3
[0.3.2]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.3.2
[0.3.1]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.3.1
[0.3.0]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.3.0
[0.2.2]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.2.2
[0.2.1]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.2.1
[0.2.0]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.2.0
[0.1.8]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.8
[0.1.7]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.7
[0.1.6]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.6
[0.1.5]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.5
[0.1.4]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.4
[0.1.3]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.3
[0.1.2]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.2
[0.1.1]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.1
[0.1.0]: https://github.com/scorpio-datalake/rust-data-processing/releases/tag/v0.1.0
