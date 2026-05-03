# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- **Phase 2 (batch):** `ReduceOp::Median` / `Agg::Median`; UTF-8 privacy `TransformStep`s (`Utf8Truncate`, `Utf8Sha256Hex`, `Utf8RedactMiddle`); validation `Check::Utf8LenCharsBetween`; modules **`export`** (JSONL + deterministic train/test indices), **`privacy`** (UTF-8 diff summaries), **`reports`** (byte-safe truncation); Arrow **`record_batches_to_dataset`** + `LargeUtf8` support; Python bindings `export_dataset_jsonl`, `privacy_summarize_utf8_changes_json`, `reports_truncate_utf8_bytes`.
- **Docs & examples:** lake read ADR + user guide, SFT format guide, outreach shortlist, ML reduce gap audit, P2-E6 policy + P2-E7 deferral notes, vector export recipe, `examples/{dbt,airflow,tabular_nn,llm_prep}/`, `notebooks/` index + starter notebooks.

## [0.1.7] - 2026-04-14

### Fixed

- **docs.rs**: Fix rustdoc intra-doc links that become hard errors under `RUSTDOCFLAGS=-D warnings` (broken `Some(None)`-style links, link to a private helper, redundant explicit targets, and ambiguous `glob` / `reduce` links). Version **0.1.6** documentation failed to build for this reason; republish after this change.

### Planned

- **JVM / Java / Maven:** first-class bindings (native library + **Maven Central** artifact, cross-platform CI) are **Phase 3** scope, not Phase 2, so Phase 2 can focus on Rust + Python with **smaller releases** and faster cycles. Follow releases and notes here for when Java support lands.

### Fixed

- **docs.rs**: add `[package.metadata.docs.rs]` with `cargo-args = ["-j", "1"]` so the documentation build is less likely to run out of memory while compiling Polars and the rest of the dependency graph (see `Cargo.toml` comments).

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

[0.1.7]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.7
[0.1.5]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.5
[0.1.4]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.4
[0.1.3]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.3
[0.1.2]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.2
[0.1.1]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.1
[0.1.0]: https://github.com/vihangdesai2018-png/rust-data-processing/releases/tag/v0.1.0
