# rust-data-processing

![Phase 2 scope: Phase 1 baseline plus export, privacy, Arrow, incremental ETL → Python; JVM planned](https://raw.githubusercontent.com/scorpio-datalake/rust-data-processing/main/docs/images/phase-2-scope-overview.png)

**Rust** library: schema-first ingestion (CSV, JSON, Parquet, Excel with Cargo features) into an in-memory [`DataSet`](https://docs.rs/rust-data-processing/latest/rust_data_processing/types/struct.DataSet.html), plus Polars-backed pipelines, optional SQL, profiling, validation, map/reduce-style processing, **Phase 2** export (JSONL, train/test splits), UTF-8 privacy transforms and summaries, median aggregations, Arrow interop, and incremental ingest helpers.

*Infographic: Phase 2 — Phase 1 single-node flow (ingest → `DataSet`, pipelines, SQL, profile, validate, outliers, transforms, parallel execution) plus JSONL export, privacy tooling, median, Arrow batch paths, watermark / ordered-file / Hive-style discovery; JVM bindings planned Phase 3.*

**Limits (masking / “PII”):** UTF-8 transforms and validation checks are **mechanical** helpers only; callers supply policy and must not treat outputs as legal guarantees. See `Planning/P2_E6_PRIVACY_POLICY.md` in the repository.

This file is the **crate README** shown on [crates.io](https://crates.io/crates/rust-data-processing) and at the top of [docs.rs](https://docs.rs/rust-data-processing) (Rust-only). The [repository’s `README.md`](https://github.com/scorpio-datalake/rust-data-processing/blob/main/README.md) is the full monorepo overview (including Python).

## Documentation

| | Link |
| --- | --- |
| **Rust API (module tree)** | Use the **crate** index on this docs.rs page (left sidebar). |
| **Repository** | [github.com/scorpio-datalake/rust-data-processing](https://github.com/scorpio-datalake/rust-data-processing) |
| **Markdown API overview** | [`API.md`](./API.md) (shipped in this crate) |
| **Rust examples & cookbook** | [`docs/rust/README.md`](./docs/rust/README.md) |
| **Python package (PyPI)** | [pypi.org/project/rust-data-processing](https://pypi.org/project/rust-data-processing/) |
| **Python runnable examples (HTML)** | [GitHub Pages — examples](https://scorpio-datalake.github.io/rust-data-processing/python/examples.html) |
| **HTML site (Rust + Python pages)** | [GitHub Pages — home](https://scorpio-datalake.github.io/rust-data-processing/) — **Rust (rustdoc):** [crate index on Pages](https://scorpio-datalake.github.io/rust-data-processing/rust/rust_data_processing/index.html) (or [docs.rs](https://docs.rs/rust-data-processing)); **Python (pdoc):** [module root](https://scorpio-datalake.github.io/rust-data-processing/python/rust_data_processing.html). [Setup](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/DOCUMENTATION.md) if the site is empty. |

## Quick start (Rust)

```rust
use rust_data_processing::ingestion::{ingest_from_path, IngestionOptions};
use rust_data_processing::types::{DataType, Field, Schema};

let schema = Schema::new(vec![
    Field::new("id", DataType::Int64),
    Field::new("name", DataType::Utf8),
]);
let _ds = ingest_from_path("path/to/data.csv", &schema, &IngestionOptions::default())
    .expect("ingest");
```

More patterns: [`docs/rust/README.md`](./docs/rust/README.md).

## Features (Cargo)

- `default`: includes `sql` (Polars-backed SQL via `polars-sql`).
- `excel`: Excel workbook ingestion (`calamine`).
- `sql`: Polars SQL (on by default; use `default-features = false` to drop).
- `db_connectorx`: optional DB → Arrow → `DataSet`.
- `arrow` / `serde_arrow`: Arrow interop helpers.

Full list: [`Cargo.toml`](./Cargo.toml) `[features]`.

## License

`MIT OR Apache-2.0` - see [LICENSE-MIT](https://github.com/scorpio-datalake/rust-data-processing/blob/main/LICENSE-MIT) and [LICENSE-APACHE](https://github.com/scorpio-datalake/rust-data-processing/blob/main/LICENSE-APACHE).
