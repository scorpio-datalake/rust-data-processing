# Phase 2 — Python examples

Copy-paste snippets for **export / JSONL**, **privacy summaries**, **reports truncation**, **UTF-8 transforms**, **string-length validation**, **median** reductions, and **Delta/Iceberg handoff**. Requires `pip install rust-data-processing` (or `maturin develop` from `python-wrapper/`).

---

## 1. JSON Lines export and train/test indices

```python
import rust_data_processing as rdp

schema = [{"name": "id", "data_type": "int64"}, {"name": "label", "data_type": "utf8"}]
rows = [[1, "ok"], [2, "ok"], [3, "holdout"]]
ds = rdp.DataSet(schema, rows)

text = rdp.export_dataset_jsonl(ds, ["id", "label"])
print(text)  # one JSON object per line

records = rdp.export_jsonl_records(ds, ["id", "label"])  # list[dict]

train_idx, test_idx = rdp.export_train_test_row_indices(ds.row_count(), test_fraction=0.34)
train_rows = [rows[i] for i in train_idx]
test_rows = [rows[i] for i in test_idx]
```

---

## 2. UTF-8 length filter (export helper)

```python
import rust_data_processing as rdp

ds = rdp.DataSet(
    [{"name": "msg", "data_type": "utf8"}],
    [["hi"], ["this is too long for a tweet style cap"]],
)
short_only = rdp.export_filter_rows_max_utf8_chars(ds, "msg", max_chars=10)
```

---

## 3. Privacy diff reports (after `transform_apply`)

```python
import rust_data_processing as rdp

schema = [{"name": "email", "data_type": "utf8"}]
before = rdp.DataSet(schema, [["user@company.com"]])
spec = {
    "output_schema": {"fields": [{"name": "email", "data_type": "Utf8"}]},
    "steps": [
        {
            "Utf8RedactMiddle": {
                "column": "email",
                "keep_left": 2,
                "keep_right": 0,
                "redaction": "***",
            }
        }
    ],
}
after = rdp.transform_apply(before, spec)

rows = rdp.privacy_summarize_utf8_changes(before, after, ["email"])  # list of dicts
md = rdp.privacy_summarize_utf8_changes(before, after, ["email"], as_markdown=True)
```

---

## 4. Truncate large JSON / text for logs or LLM context

```python
import rust_data_processing as rdp

blob = '{"profile": ' + ("x" * 5000) + "}"
snippet = rdp.reports_truncate_utf8_bytes(blob, max_bytes=256)
```

---

## 5. UTF-8 masking transforms (`TransformSpec`)

Supported step variants: `Utf8Truncate`, `Utf8Sha256Hex`, `Utf8RedactMiddle` (see Rust enum names in JSON). Use `transform_apply(dataset, spec_dict)` so you do not hand-serialize JSON.

```python
import rust_data_processing as rdp

ds = rdp.DataSet([{"name": "s", "data_type": "utf8"}], [["secret-value"]])
spec = {
    "output_schema": {"fields": [{"name": "s", "data_type": "Utf8"}]},
    "steps": [{"Utf8Sha256Hex": {"column": "s"}}],
}
out = rdp.transform_apply(ds, spec)
```

---

## 6. Validation: UTF-8 length (Unicode scalars)

```python
import rust_data_processing as rdp

ds = rdp.DataSet([{"name": "code", "data_type": "utf8"}], [["AB"], ["ABCDE"]])
rep = rdp.validate_dataset(
    ds,
    {
        "checks": [
            {
                "kind": "utf8_len_chars_between",
                "column": "code",
                "min_chars": 3,
                "max_chars": 10,
                "severity": "warn",
            }
        ]
    },
)
```

---

## 7. Median: `processing_reduce` and `DataFrame`

```python
import rust_data_processing as rdp

ds = rdp.DataSet([{"name": "x", "data_type": "int64"}], [[10], [20], [30], [40]])
assert rdp.processing_reduce(ds, "x", "median") == 25.0

ds2 = rdp.DataSet(
    [{"name": "g", "data_type": "utf8"}, {"name": "v", "data_type": "int64"}],
    [["a", 1], ["a", 100]],
)
lf = rdp.DataFrame.from_dataset(ds2)
m = lf.group_by(["g"], [{"type": "median", "column": "v", "alias": "m"}]).collect()
```

---

## 8. Delta Lake / Iceberg (handoff — not in-process in default wheel)

Use **Python** `deltalake` or **PyIceberg** to read a table, write **Parquet** (or build rows), then:

```python
import rust_data_processing as rdp

# After you materialize a Parquet path from deltalake / Spark:
schema = rdp.infer_schema_from_path("exported_slice.parquet")
ds = rdp.ingest_from_path("exported_slice.parquet", schema)
```

Details: [`LAKE_TABLE_READ.md`](../LAKE_TABLE_READ.md), [`ADR_P2_E2_LAKE_TABLE_READ.md`](../../Planning/ADR_P2_E2_LAKE_TABLE_READ.md).

---

## 9. End-to-end: ingest → validate → JSONL (tabular QA)

```python
import rust_data_processing as rdp

# Point at a real CSV path on disk.
path = "tests/fixtures/people.csv"  # from repo root; use absolute path in notebooks
schema = rdp.infer_schema_from_path(path)
ds = rdp.ingest_from_path(path, schema)
_ = rdp.profile_dataset(ds, {"sampling": "full"})
rep = rdp.validate_dataset(ds, {"checks": [{"kind": "not_null", "column": schema[0]["name"], "severity": "warn"}]})
cols = [f["name"] for f in schema]
jl = rdp.export_dataset_jsonl(ds, cols)
```

---

## 10. Incremental ETL helpers (watermark, ordered paths, Hive-style discovery)

These shipped in **Phase 2 / P2-E1** and are exposed on the same Python module.

```python
import rust_data_processing as rdp

# Watermark: after ingest, keep rows strictly above a floor (set both keys together).
opts = {"watermark_column": "ts", "watermark_exclusive_above": 100}
# ds = rdp.ingest_from_path("events.csv", schema, opts)

# Append-only batch: concatenate files then apply watermark once.
# ds, meta = rdp.ingest_from_ordered_paths(["/data/a.csv", "/data/b.csv"], schema, opts)

# Hive-style layout: discover partitioned files under a root.
# files = rdp.discover_hive_partitioned_files("s3://bucket/prefix", file_pattern="*.parquet")
# paths = rdp.paths_from_directory_scan("/data/events", relative_pattern="*.csv")
```

See [`API.md`](../../python-wrapper/API.md) § Ingestion and **Incremental / watermark**.

---

## See also

- [`SFT_PYTHON_EXAMPLES.md`](SFT_PYTHON_EXAMPLES.md) — Alpaca-style NDJSON + optional Hugging Face `datasets` + JSONL export for trainers.
- [`API.md`](../../python-wrapper/API.md) — full function table.
- [`README.md`](README.md) — longer tour including Phase 1 APIs.
- [`notebooks/README.md`](../../notebooks/README.md) — Jupyter entrypoints.
