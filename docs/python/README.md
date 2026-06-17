# Python quick start and examples

![Phase 2 scope: Phase 1 baseline plus export, privacy, Arrow, incremental ETL → Python; JVM planned](../images/phase-2-scope-overview.png)

The same Markdown is **included in the HTML docs** (pdoc) as [`rust_data_processing.examples`](https://scorpio-datalake.github.io/rust-data-processing/python/examples.html) — see `python-wrapper/rust_data_processing/examples.py`.

This page collects **Python** snippets for the `rust-data-processing` package (PyO3 extension). The [repository README](../../README.md) leads with a short Python quick start; the canonical API reference is [`python-wrapper/API.md`](../../python-wrapper/API.md). Rust snippets live in [`docs/rust/README.md`](../rust/README.md).

Install (from PyPI after release, or from a checkout with `maturin develop` — see [`docs/RELEASE_CHECKLIST.md`](../RELEASE_CHECKLIST.md)):

```bash
pip install rust-data-processing
```

**Notebooks:** see **[`notebooks/README.md`](../../notebooks/README.md)** in the repository for Jupyter examples.

```python
import rust_data_processing as rdp
```

### Delta Lake / Apache Iceberg (read path)

The Rust crate’s **default build** does not embed a Delta/Iceberg table engine (see [`Planning/ADR_P2_E2_LAKE_TABLE_READ.md`](../../Planning/ADR_P2_E2_LAKE_TABLE_READ.md)). In Python, use **`deltalake`** or **`pyiceberg`** to scan a table, write **Parquet**, then call **`rdp.ingest_from_path`** on that file — or stay in Python/Polars for the heavy scan. Details: [`docs/LAKE_TABLE_READ.md`](../LAKE_TABLE_READ.md).

### Phase 2 examples (export, privacy, median, validation, …)

Copy-paste snippets live in **[`PHASE2_EXAMPLES.md`](PHASE2_EXAMPLES.md)** (same folder as this README).

### What this page covers

Use this as a **tour of the Python API** (the same page is rendered on [GitHub Pages](https://scorpio-datalake.github.io/rust-data-processing/python/examples.html)). For every function signature and option, see [`python-wrapper/API.md`](../../python-wrapper/API.md).

| Topic | Where below |
| --- | --- |
| **File ETL** (CSV, JSON, Parquet, Excel) | [Quick start](#quick-start-library-usage), [JSON / Parquet / Excel](#json-parquet-and-excel), [Ordered paths & directory scans](#ordered-paths-and-directory-scans-incremental-batches) |
| **Databases** (Python driver vs built-in ConnectorX) | [Database sources: two ways](#database-sources-two-ways), [ConnectorX](#direct-db-ingestion-connectorx-feature-gated) |
| **SQL & lazy `DataFrame`** | [SQL](#sql-queries-polars-backed-phase-1), [DataFrame pipelines](#dataframe-centric-pipelines-polars-backed-phase-1), [Cookbook](#cookbook-phase-1) |
| **Declarative transforms** | [`TransformSpec`](#end-user-transformation-spec-transformspec-phase-1) |
| **ML / QA** (profile, validate, outliers) | [ML-oriented flow](#ml--training-dataset-prep-phase-1), [Profiling](#profiling-phase-1), [Validation](#validation-phase-1), [Outliers](#outlier-detection-phase-1) |
| **Phase 2** (JSONL export, privacy, truncation, UTF-8 transforms, median, Delta handoff) | [Phase 2 examples](PHASE2_EXAMPLES.md), [Delta / Iceberg](#delta-lake--apache-iceberg-read-path) |
| **SFT / fine-tuning prep** (Alpaca JSONL, HF optional, chat column) | [SFT Python examples](SFT_PYTHON_EXAMPLES.md), [`SFT_DATA_FORMATS.md`](../SFT_DATA_FORMATS.md) |
| **Row-wise & parallel processing** | [Processing pipelines](#processing-pipelines-epic-1--story-12), [Execution engine](#execution-engine-parallel-pipelines-story-13) |
| **Ingestion observability** | [Observability](#observability-failurealert-hooks) |
| **Cloud connectors** (S3, GCS, Azure, SFTP, FTP) | [Cloud storage & file transfer](#cloud-storage-and-file-transfer-connectors) |
| **Kafka streaming ELT** | [Kafka streaming](#kafka-streaming-elt) |
| **CDC boundary types** | [CDC](#cdc-interface-boundary-phase-1-spike) |

## Quick start (library usage)

Ingest a file with an explicit schema (format can be set or inferred from the extension):

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "name", "data_type": "utf8"},
]
ds = rdp.ingest_from_path(
    "tests/fixtures/people.csv",
    schema,
    {"format": "csv"},
)
print("rows", ds.row_count())
```

Infer schema from the file, then ingest (two passes — same idea as Rust’s `ingest_with_inferred_schema`):

```python
ds, schema = rdp.ingest_with_inferred_schema("tests/fixtures/people.csv")
print(schema[0]["name"], ds.row_count())
```

### JSON, Parquet, and Excel

Format is usually inferred from the extension; you can set **`"format"`** explicitly (`"csv"`, `"json"`, `"parquet"`, `"excel"`). JSON with **nested objects** may need **dotted column names** in the schema (e.g. `user.name` for `tests/fixtures/people.json`).

```python
import rust_data_processing as rdp

# JSON (nested columns — matches tests/fixtures/people.json)
schema_json = [
    {"name": "id", "data_type": "int64"},
    {"name": "user.name", "data_type": "utf8"},
    {"name": "score", "data_type": "float64"},
    {"name": "active", "data_type": "bool"},
]
ds_json = rdp.ingest_from_path(
    "tests/fixtures/people.json",
    schema_json,
    {"format": "json"},
)

# Parquet — use a real path to your `.parquet` file
schema_flat = [
    {"name": "id", "data_type": "int64"},
    {"name": "name", "data_type": "utf8"},
    {"name": "score", "data_type": "float64"},
    {"name": "active", "data_type": "bool"},
]
ds_parquet = rdp.ingest_from_path("path/to/file.parquet", schema_flat, {"format": "parquet"})

# Excel — optional sheet selection in options (see python-wrapper/README.md)
ds_excel = rdp.ingest_from_path("path/to/file.xlsx", schema_flat, {"format": "excel"})
```

### Ordered paths and directory scans (incremental batches)

Use **`paths_from_directory_scan`** for a recursive listing with an optional glob on paths **relative to the root**; results are **sorted** so ordering is repeatable. Combine with **`ingest_from_ordered_paths`**: files are read in list order, rows are concatenated, then the same **watermark** options as single-file ingest apply **once** to the combined rows (not per file). The function returns `(DataSet, metadata)` where `metadata` includes `paths`, `last_path`, and `max_watermark_value` (when `watermark_column` / `watermark_exclusive_above` are set) for checkpointing the next run.

```python
paths = rdp.paths_from_directory_scan("data/incoming", "**/*.csv")
ds, meta = rdp.ingest_from_ordered_paths(
    paths,
    schema,
    {"watermark_column": "ts", "watermark_exclusive_above": 100},
)
print(meta["last_path"], meta["max_watermark_value"])
```

## DataFrame-centric pipelines (Polars-backed) (Phase 1)

Use `DataFrame.from_dataset` for a lazy plan; chain methods and `collect()` to a `DataSet`:

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "active", "data_type": "bool"},
    {"name": "score", "data_type": "float64"},
]
rows = [
    [1, True, 10.0],
    [2, True, 20.0],
    [3, False, 30.0],
]
ds = rdp.DataSet(schema, rows)

out = (
    rdp.DataFrame.from_dataset(ds)
    .filter_eq("active", True)
    .multiply_f64("score", 2.0)
    .collect()
)
assert out.row_count() == 2
```

## SQL queries (Polars-backed) (Phase 1)

Single-table SQL — the `DataSet` is registered as table `df`; `sql_query_dataset` returns a materialized `DataSet`:

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "active", "data_type": "bool"},
    {"name": "score", "data_type": "float64"},
]
rows = [
    [1, True, 10.0],
    [2, True, 20.0],
    [3, False, 30.0],
]
ds = rdp.DataSet(schema, rows)

out = rdp.sql_query_dataset(
    ds,
    "SELECT id, score FROM df WHERE active = TRUE ORDER BY id DESC LIMIT 10",
)
```

Multi-table JOINs via `SqlContext` (`execute` returns a lazy `DataFrame`; call `collect()` for a `DataSet`):

```python
import rust_data_processing as rdp

people = rdp.DataSet(
    [
        {"name": "id", "data_type": "int64"},
        {"name": "name", "data_type": "utf8"},
    ],
    [[1, "Ada"], [2, "Grace"]],
)
scores = rdp.DataSet(
    [
        {"name": "id", "data_type": "int64"},
        {"name": "score", "data_type": "float64"},
    ],
    [[1, 9.0], [3, 7.0]],
)

ctx = rdp.SqlContext()
ctx.register("people", rdp.DataFrame.from_dataset(people))
ctx.register("scores", rdp.DataFrame.from_dataset(scores))

out = ctx.execute(
    "SELECT p.id, p.name, s.score FROM people p JOIN scores s ON p.id = s.id"
).collect()
```

## Database sources: two ways

1. **Your own Python driver (no `db` feature):** Use **psycopg2**, **SQLAlchemy**, etc., execute SQL, then build a **`DataSet(schema, rows)`** from the result rows (schema/row shape in [`python-wrapper/API.md`](../../python-wrapper/API.md) § Conventions; **`DataSet`** examples appear throughout this page). This works with the default PyPI wheel; you do not need ConnectorX or `maturin … --features db`.

2. **Built-in SQL → `DataSet` (requires `db`):** **`ingest_from_db`** / **`ingest_from_db_infer`** use ConnectorX inside the native extension. The module must be built with the **`db`** Cargo feature (see [`python-wrapper/README_DEV.md`](../../python-wrapper/README_DEV.md)).

**Concrete pattern (any Python DB-API driver — no `db` feature):** map query results to **`DataSet(schema, rows)`** yourself.

```python
import rust_data_processing as rdp

# After cursor.execute(...): build schema from your column names + logical types,
# then rows = list(cursor.fetchall()) (or batch). Example shape:
schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "name", "data_type": "utf8"},
]
rows = [[1, "ada"], [2, "grace"]]
ds = rdp.DataSet(schema, rows)
```

## Direct DB ingestion (ConnectorX) (feature-gated)

The native module must be built with the **`db`** Cargo feature (see [`python-wrapper/README_DEV.md`](../../python-wrapper/README_DEV.md)). Then:

```python
# Infer column types from the query result
ds = rdp.ingest_from_db_infer(
    "postgresql://user:pass@localhost:5432/db?cxprotocol=binary",
    "SELECT id, score, active FROM my_table",
)
print("rows", ds.row_count())

# Or provide an explicit schema (same strings as file ingest)
schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "score", "data_type": "float64"},
    {"name": "active", "data_type": "bool"},
]
ds = rdp.ingest_from_db(
    "postgresql://user:pass@localhost:5432/db?cxprotocol=binary",
    "SELECT id, score, active FROM my_table",
    schema,
)
```

## End-user transformation spec (TransformSpec) (Phase 1)

`transform_apply` accepts a dict (or JSON string) in the same serde shape as Rust’s `TransformSpec`:

```python
import rust_data_processing as rdp

schema_in = [
    {"name": "id", "data_type": "int64"},
    {"name": "score", "data_type": "int64"},
    {"name": "weather", "data_type": "utf8"},
]
rows = [[1, 10, "drizzle"], [2, None, "rain"]]
ds = rdp.DataSet(schema_in, rows)

spec = {
    "output_schema": {
        "fields": [
            {"name": "id", "data_type": "Int64"},
            {"name": "score_f", "data_type": "Float64"},
            {"name": "wx", "data_type": "Utf8"},
        ]
    },
    "steps": [
        {"Rename": {"pairs": [["weather", "wx"]]}},
        {"Rename": {"pairs": [["score", "score_f"]]}},
        {
            "Cast": {
                "column": "score_f",
                "to": "Float64",
                "mode": "lossy",
            }
        },
        {"FillNull": {"column": "score_f", "value": {"Float64": 0.0}}},
        {"Select": {"columns": ["id", "score_f", "wx"]}},
    ],
}
out = rdp.transform_apply(ds, spec)
assert out.column_names() == ["id", "score_f", "wx"]
```

## ML / training-dataset prep (Phase 1)

For **tabular ML**, teams usually combine **data quality** and **understanding** before training or batch inference: **profile** columns (nulls, ranges, quantiles), **validate** business rules (not-null, regex, ranges), and optionally **flag outliers** for review. This library keeps those steps on a single **`DataSet`**; see [Profiling](#profiling-phase-1), [Validation](#validation-phase-1), and [Outlier detection](#outlier-detection-phase-1) below. Raw **JSON** or **Markdown** report strings are available as `profile_dataset_json` / `profile_dataset_markdown` (and the `validate_*` / `detect_outliers_*` variants) if you want files or PR comments instead of dicts.

## Profiling (Phase 1)

```python
import rust_data_processing as rdp

schema = [{"name": "score", "data_type": "float64"}]
rows = [[1.0], [None], [3.0]]
ds = rdp.DataSet(schema, rows)

rep = rdp.profile_dataset(
    ds,
    {"head_rows": 2, "quantiles": [0.5]},
)
assert rep["row_count"] == 2
assert rep["columns"][0]["null_count"] == 1
```

Markdown for humans (e.g. paste into a doc or ticket):

```python
md = rdp.profile_dataset_markdown(ds, {"head_rows": 100, "quantiles": [0.5, 0.95]})
```

## Validation (Phase 1)

```python
import rust_data_processing as rdp

schema = [{"name": "email", "data_type": "utf8"}]
rows = [
    ["ada@example.com"],
    [None],
    ["not-an-email"],
]
ds = rdp.DataSet(schema, rows)

rep = rdp.validate_dataset(
    ds,
    {
        "checks": [
            {"kind": "not_null", "column": "email", "severity": "error"},
            {
                "kind": "regex_match",
                "column": "email",
                "pattern": r"^[^@]+@[^@]+\.[^@]+$",
                "severity": "warn",
                "strict": True,
            },
        ],
    },
)
assert rep["summary"]["total_checks"] >= 2
```

## Outlier detection (Phase 1)

```python
import rust_data_processing as rdp

schema = [{"name": "x", "data_type": "float64"}]
rows = [[1.0], [1.0], [1.0], [1000.0]]
ds = rdp.DataSet(schema, rows)

rep = rdp.detect_outliers(
    ds,
    "x",
    {"kind": "iqr", "k": 1.5},
    {"sampling": "full", "max_examples": 3},
)
assert rep["outlier_count"] >= 1
```

## CDC interface boundary (Phase 1 spike)

The `rust_data_processing.cdc` submodule exposes plain Python types aligned with the Rust `cdc` module (no connector ships yet):

```python
from rust_data_processing.cdc import CdcEvent, CdcOp, RowImage, SourceMeta, TableRef

ev = CdcEvent(
    meta=SourceMeta(source="db", checkpoint=None),
    table=TableRef.with_schema("public", "users"),
    op=CdcOp.INSERT,
    before=None,
    after=RowImage.new([("id", 1), ("name", "Ada")]),
)
assert ev.op == CdcOp.INSERT
```

## Cookbook (Phase 1)

### Stable transformation wrappers (Polars-backed)

Rename + cast + fill nulls:

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "score", "data_type": "int64"},
]
rows = [[1, 10], [2, None]]
ds = rdp.DataSet(schema, rows)

out = (
    rdp.DataFrame.from_dataset(ds)
    .rename([("score", "score_i")])
    .cast("score_i", "float64")
    .fill_null("score_i", 0.0)
    .collect()
)
```

Group-by aggregates:

```python
schema = [
    {"name": "grp", "data_type": "utf8"},
    {"name": "score", "data_type": "float64"},
]
rows = [
    ["A", 1.0],
    ["A", 2.0],
    ["B", None],
]
ds = rdp.DataSet(schema, rows)

out = (
    rdp.DataFrame.from_dataset(ds)
    .group_by(
        ["grp"],
        [
            {"type": "sum", "column": "score", "alias": "sum_score"},
            {"type": "count_rows", "alias": "cnt"},
        ],
    )
    .collect()
)
```

Per-key **mean**, **sample std dev**, and **count-distinct**:

```python
schema = [
    {"name": "grp", "data_type": "utf8"},
    {"name": "score", "data_type": "float64"},
    {"name": "label", "data_type": "utf8"},
]
rows = [
    ["A", 10.0, "x"],
    ["A", 20.0, "y"],
    ["B", None, "z"],
]
ds = rdp.DataSet(schema, rows)

_ = (
    rdp.DataFrame.from_dataset(ds)
    .group_by(
        ["grp"],
        [
            {"type": "mean", "column": "score", "alias": "mu_score"},
            {
                "type": "std_dev",
                "column": "score",
                "alias": "sd_score",
                "kind": "sample",
            },
            {
                "type": "count_distinct_non_null",
                "column": "label",
                "alias": "n_labels",
            },
        ],
    )
    .collect()
)
```

**Semantics** (nulls, all-null groups, `SUM` vs `MEAN`, casting): see [`docs/REDUCE_AGG_SEMANTICS.md`](../REDUCE_AGG_SEMANTICS.md). More detail: [`python-wrapper/API.md`](../../python-wrapper/API.md) § *Processing pipelines*.

Join two DataFrames:

```python
people_schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "name", "data_type": "utf8"},
]
people_rows = [[1, "Ada"], [2, "Grace"]]
people = rdp.DataSet(people_schema, people_rows)

scores_schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "score", "data_type": "float64"},
]
scores_rows = [[1, 9.0], [3, 7.0]]
scores = rdp.DataSet(scores_schema, scores_rows)

out = (
    rdp.DataFrame.from_dataset(people)
    .join(rdp.DataFrame.from_dataset(scores), ["id"], ["id"], "inner")
    .collect()
)
```

## Processing pipelines (Epic 1 / Story 1.2)

In-memory helpers mirror `rust_data_processing::processing`:

- `processing_filter(ds, predicate)` — `predicate` receives one row as `list`
- `processing_map(ds, mapper)`
- `processing_reduce(ds, column, op)` — op names: `count`, `sum`, `min`, `max`, `mean`, `variance_population`, `variance_sample`, `stddev_population`, `stddev_sample`, `sum_squares`, `l2_norm`, `count_distinct_non_null`, …
- `processing_feature_wise_mean_std`, `processing_arg_max_row`, `processing_arg_min_row`, `processing_top_k_by_frequency`

Polars-backed equivalents: `DataFrame.reduce`, `DataFrame.feature_wise_mean_std`. **Semantics**: [`docs/REDUCE_AGG_SEMANTICS.md`](../REDUCE_AGG_SEMANTICS.md).

Example:

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "active", "data_type": "bool"},
    {"name": "score", "data_type": "float64"},
]
rows = [
    [1, True, 10.0],
    [2, False, 20.0],
    [3, True, None],
]
ds = rdp.DataSet(schema, rows)

filtered = rdp.processing_filter(ds, lambda row: row[1] is True)
mapped = rdp.processing_map(
    filtered,
    lambda row: [
        row[0],
        row[1],
        row[2] * 1.1 if row[2] is not None else None,
    ],
)
s = rdp.processing_reduce(mapped, "score", "sum")
assert s == 11.0
```

### Mean, variance, norms, distinct counts

```python
schema = [
    {"name": "x", "data_type": "float64"},
    {"name": "cat", "data_type": "utf8"},
]
rows = [[2.0, "a"], [4.0, "b"]]
ds = rdp.DataSet(schema, rows)

mean = rdp.processing_reduce(ds, "x", "mean")
std_s = rdp.processing_reduce(ds, "x", "stddev_sample")
l2 = rdp.processing_reduce(ds, "x", "l2_norm")
d = rdp.processing_reduce(ds, "cat", "count_distinct_non_null")
assert mean is not None and d == 2
```

### Polars-backed `DataFrame.reduce` (same op names)

```python
mem = rdp.processing_reduce(ds, "x", "mean")
pol = rdp.DataFrame.from_dataset(ds).reduce("x", "mean")
assert pol is not None and mem == pol
```

### Feature-wise mean and std (memory vs Polars)

```python
schema = [
    {"name": "a", "data_type": "int64"},
    {"name": "b", "data_type": "float64"},
]
rows = [[1, 10.0], [3, 20.0]]
ds = rdp.DataSet(schema, rows)

cols = ["a", "b"]
mem = rdp.processing_feature_wise_mean_std(ds, cols, "sample")
pol = rdp.DataFrame.from_dataset(ds).feature_wise_mean_std(cols, "sample")
assert mem[0]["column"] == pol[0]["column"]
```

### Arg max / arg min row and top‑k label frequencies

```python
schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "region", "data_type": "utf8"},
]
rows = [[1, "west"], [2, "east"], [3, "west"]]
ds = rdp.DataSet(schema, rows)

assert rdp.processing_arg_max_row(ds, "id") is not None
assert len(rdp.processing_top_k_by_frequency(ds, "region", 2)) >= 1
```

### Execution engine (parallel pipelines) (Story 1.3)

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "active", "data_type": "bool"},
    {"name": "score", "data_type": "float64"},
]
rows = [
    [1, True, 10.0],
    [2, False, 20.0],
    [3, True, None],
]
ds = rdp.DataSet(schema, rows)

engine = rdp.ExecutionEngine(
    {"num_threads": 4, "chunk_size": 1024, "max_in_flight_chunks": 4}
)
active_idx = 1
filtered = engine.filter_parallel(ds, lambda row: row[active_idx] is True)
mapped = engine.map_parallel(filtered, lambda row: list(row))
s = engine.reduce(mapped, "score", "sum")
metrics = engine.metrics_snapshot()
print("rows_processed", metrics["rows_processed"], "elapsed", metrics.get("elapsed_seconds"))
```

### More examples: counts, missing columns, all-null numeric

```python
schema = [{"name": "score", "data_type": "float64"}]
rows = [[1.0], [None]]
ds = rdp.DataSet(schema, rows)

assert rdp.processing_reduce(ds, "score", "count") == 2
assert rdp.processing_reduce(ds, "score", "sum") == 1.0
assert rdp.processing_reduce(ds, "missing", "sum") is None

all_null = rdp.DataSet(
    [{"name": "x", "data_type": "float64"}],
    [[None], [None]],
)
assert rdp.processing_reduce(all_null, "x", "mean") is None
```

### Benchmarks (Story 1.2.5)

Criterion benchmarks are run on the **Rust** crate (`cargo bench`, `scripts/run_benchmarks.ps1`). The Python package calls the same native code paths; for throughput numbers, use the Rust workflow or see the [repository README](../../README.md) benchmark snapshot.

### Observability (failure/alert hooks)

Pass `observer` / `alert_at_or_above` in the ingestion `options` dict (see [`python-wrapper/API.md`](../../python-wrapper/API.md) § *Ingestion observability*):

```python
import rust_data_processing as rdp

schema = [{"name": "id", "data_type": "int64"}]

def on_alert(ctx, severity, message):
    print(severity, message)

try:
    rdp.ingest_from_path(
        "does_not_exist.csv",
        schema,
        {
            "format": "csv",
            "alert_at_or_above": "critical",
            "observer": {"on_alert": on_alert},
        },
    )
except ValueError:
    pass
```

## Cloud storage and file transfer connectors

Rust performs all cloud I/O (`object_store`, SFTP/FTP). Python is a thin wrapper — build with **`cloud`** / **`integration_full`** (`maturin develop --features cloud`). Cross-language URLs and auth: [`docs/CONNECTORS.md`](../CONNECTORS.md).

**Validated end-to-end** (Docker): `python3 integration_testing/CloudConnectors/run_cloud_tests.py --no-rancher` — same patterns as [`integration_testing/scripts/cloud_pipeline.py`](../../integration_testing/scripts/cloud_pipeline.py).

### Direct API (single object)

```python
import rust_data_processing as rdp

schema = [
    {"name": "pickup_time", "data_type": "utf8"},
    {"name": "lat", "data_type": "float64"},
    {"name": "lon", "data_type": "float64"},
    {"name": "base_code", "data_type": "utf8"},
]

# Read Parquet from S3 / GCS / Azure (set AWS_*, GOOGLE_APPLICATION_CREDENTIALS, or AZURE_* on this process)
ds = rdp.ingest_from_object_store_uri(
    "s3://rdp-cloud-s3/out.parquet",
    schema,
    {"format": "parquet"},
)

# Write Parquet to cloud
rdp.export_dataset_to_object_store_uri("gs://rdp-cloud-gcs/out.parquet", ds)

# SFTP / FTP import (passwords via SFTP_PASSWORD / FTP_PASSWORD env — not in URI)
ds = rdp.ingest_from_file_transfer_uri(
    "sftp://rdp:rdp_sftp_secret@127.0.0.1:2222/upload/incoming.csv",
    schema,
    {"format": "csv", "max_rows": 500},
)
```

### Pipeline JSON roundtrip (integration-tested)

Integration tests export Uber CSV → transform → **`kind: object_store`** sink, then read back via **`object_store_uris`**. Use the shared helper (ctypes → `rdp_run_pipeline_json`):

```python
import sys
from pathlib import Path

sys.path.insert(0, "integration_testing/scripts")
from cloud_pipeline import verify_object_store_roundtrip, verify_file_transfer_import

csv = Path("integration_testing/data/uber_nyc_pickups_sample.csv")
for protocol in ("s3", "gcs", "azure"):
    rows = verify_object_store_roundtrip(protocol=protocol, csv_path=csv, max_rows=500)
    print(protocol, "roundtrip rows:", rows)

for protocol in ("sftp", "ftp"):
    rows = verify_file_transfer_import(protocol=protocol)
    print(protocol, "import rows:", rows)
```

Set emulator env from `integration_testing/CloudConnectors/.env.example` before running (MinIO, fake-gcs, Azurite, SFTP, FTP).

## Kafka streaming ELT

Kafka is **streaming**, not batch file ETL: **Extract** (poll window) → **Load** (landing schema) → **Transform** separately. Build with **`kafka`**: `uv run maturin develop --release --features kafka`. Full guide: [`docs/KAFKA_ELT.md`](../KAFKA_ELT.md).

**Validated:** `python3 integration_testing/Kafka/run_kafka_tests.py --no-rancher` (Redpanda on `127.0.0.1:19092`). Reference: [`integration_testing/scripts/kafka_stream.py`](../../integration_testing/scripts/kafka_stream.py).

```python
import sys
from pathlib import Path

sys.path.insert(0, "integration_testing/scripts")
from kafka_stream import verify_uber_kafka_stream

# One CSV row → one Kafka message; poll and assert row count (FFI to rdp_kafka_* in librdp_jvm_sys)
count = verify_uber_kafka_stream(
    Path("integration_testing/data/uber_nyc_pickups_sample.csv"),
    max_rows=500,
)
print("kafka rows landed:", count)
```

Native API (same Rust code as JVM FFI):

```python
import rust_data_processing as rdp

landing = [
    {"name": "user_id", "data_type": "int64"},
    {"name": "event", "data_type": "utf8"},
    {"name": "_kafka_offset", "data_type": "int64"},
]

records_json = rdp.poll_kafka_window("127.0.0.1:19092", "rdp-elt", "rdp-uber-pickups", max_records=500)
landed = rdp.elt_load_kafka_records_json(records_json, landing)

# Or Extract+Load in one call:
landed = rdp.poll_kafka_window_loaded(
    "127.0.0.1:19092", "rdp-elt", "rdp-uber-pickups", landing, max_records=500
)
```

`poll_kafka_window*` blocks while holding the GIL — run from a dedicated thread for production loops.

## See also

- [`docs/CONNECTORS.md`](../CONNECTORS.md) — same URLs in Rust, Python, and Java  
- [`integration_testing/integration_testing_details.md`](../../integration_testing/integration_testing_details.md) — step-by-step integration flows  
- [`python-wrapper/README.md`](../../python-wrapper/README.md) — install and dev workflow  
- [`python-wrapper/API.md`](../../python-wrapper/API.md) — full Python API  
- [`docs/rust/README.md`](../rust/README.md) — Rust mirror of this page  
- [`docs/REDUCE_AGG_SEMANTICS.md`](../REDUCE_AGG_SEMANTICS.md) — aggregate semantics
