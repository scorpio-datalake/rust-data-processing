# Delta / Iceberg → `DataSet` (limits and handoff)

## What this library does **not** ship (Phase 2)

- **No embedded Delta commit log reader** or Iceberg manifest reader in the default `rust-data-processing` build (see [`Planning/ADR_P2_E2_LAKE_TABLE_READ.md`](../Planning/ADR_P2_E2_LAKE_TABLE_READ.md)).
- **No catalog** (REST, Glue, Unity), **no time travel** selection beyond what your export tool writes into files.
- **No distributed** scan or shuffle.

## Recommended patterns

### 1. Export to Parquet (simplest)

Use **Spark**, **Databricks**, **Python `deltalake`**, or **Trino** to `COPY` / write a Parquet directory or single file, then:

```rust
use rust_data_processing::ingestion::{ingest_from_path, IngestionOptions};
// ingest_from_path(..., IngestionOptions::default()) for .parquet
```

### 2. Arrow `RecordBatch` handoff (Rust, `--features arrow`)

Read batches with your tool of choice, then:

```rust
use rust_data_processing::transform::arrow::record_batches_to_dataset;
use rust_data_processing::types::Schema;
// schema must match the logical columns you need (Int64, Float64, Bool, Utf8).
let ds = record_batches_to_dataset(&[batch1, batch2], &schema)?;
```

See rustdoc on `record_batches_to_dataset` for schema alignment rules.

### 3. Python

Use **`deltalake`** or **`pyiceberg`** to scan a table to **PyArrow**, write **Parquet**, then `rust_data_processing.ingest_from_path` on that Parquet path; or serialize batches and use project-specific glue if you need in-process Arrow.

## When to use Spark / Databricks

Use a cluster engine for **large** tables, **ACID** maintenance, **ZORDER**, **liquid clustering**, **Iceberg branching**, or **governance** features. Use this library for **local** QA, transforms, validation, and **smaller** extracts you land as files.
