# SFT / fine-tuning data — runnable Python examples (`rust_data_processing`)

These examples use **`rust_data_processing`** (rdp) only for the **tabular** steps: ingest, profile, validate, optional `TransformSpec`, JSONL export, and deterministic train/test row indices. They do **not** run training or ship a tokenizer.

**Prerequisite:** `pip install rust-data-processing` (or `maturin develop` from `python-wrapper/`).

**Trainer warning:** chat templates and tokenizers belong in **TRL / HF / Llama-Factory** — see [`SFT_DATA_FORMATS.md`](../SFT_DATA_FORMATS.md).

---

## 1. Alpaca-style NDJSON (committed sample in this repo)

The file **[`examples/sft/sample_alpaca.ndjson`](../../examples/sft/sample_alpaca.ndjson)** has four rows with `instruction`, `input`, and `output` (UTF-8). Run from the **repository root**, or build an absolute path to that file:

```python
from pathlib import Path

import rust_data_processing as rdp

path = Path("examples/sft/sample_alpaca.ndjson").resolve()

schema = [
    {"name": "instruction", "data_type": "utf8"},
    {"name": "input", "data_type": "utf8"},
    {"name": "output", "data_type": "utf8"},
]
# `.ndjson` is detected as newline-delimited JSON (no need to force `format`).
ds = rdp.ingest_from_path(str(path), schema)

assert ds.row_count() == 4
prof = rdp.profile_dataset(ds, {"sampling": "full"})
print("profile row_count", prof["row_count"])

# Example QA: flag rows with very short outputs (policy is yours)
rep = rdp.validate_dataset(
    ds,
    {
        "checks": [
            {
                "kind": "utf8_len_chars_between",
                "column": "output",
                "min_chars": 2,
                "max_chars": 500,
                "severity": "warn",
            }
        ]
    },
)
print("failed_checks", rep["summary"]["failed_checks"])

# Stable JSONL for a trainer that expects instruction/input/output columns
cols = ["instruction", "input", "output"]
text = rdp.export_dataset_jsonl(ds, cols)
print(text.splitlines()[0][:80], "...")

# Deterministic tail holdout (same semantics as Rust `train_test_row_indices`)
train_idx, test_idx = rdp.export_train_test_row_indices(ds.row_count(), test_fraction=0.25)
rows = ds.to_rows()
train_ds = rdp.DataSet(schema, [rows[i] for i in train_idx])
test_ds = rdp.DataSet(schema, [rows[i] for i in test_idx])
```

---

## 2. “Messages” / chat column as a single JSON string

Some pipelines store one UTF-8 column whose cell is JSON: `[{"role":"user","content":"..."}, ...]`. Keep it as **Utf8**; validate length; export as one field per JSONL line.

```python
import json

import rust_data_processing as rdp

messages = [
    {"role": "user", "content": "Summarize: Rust is a systems language."},
    {"role": "assistant", "content": "Rust is a systems programming language focused on safety."},
]
schema = [{"name": "messages", "data_type": "utf8"}]
ds = rdp.DataSet(schema, [[json.dumps(messages)]])
jl = rdp.export_dataset_jsonl(ds, ["messages"])
print(jl.strip())
```

Your trainer may expect a different key (`conversations`, etc.) — rename in a `TransformSpec` or when building rows.

---

## 3. Optional: slice a **well-known** public dataset with Hugging Face `datasets`

This requires **`pip install datasets`** and **network** the first time HF caches the split. It is **not** a dev dependency of this repo.

**Alpaca** (tatsu-lab; check the current license / terms on the Hugging Face dataset card before production use):

```python
# pip install datasets
from datasets import load_dataset

import rust_data_processing as rdp

hf = load_dataset("tatsu-lab/alpaca", split="train[:8]")  # tiny slice for a demo
schema = [
    {"name": "instruction", "data_type": "utf8"},
    {"name": "input", "data_type": "utf8"},
    {"name": "output", "data_type": "utf8"},
]
rows = [
    [row["instruction"], row.get("input") or "", row["output"]]
    for row in hf
]
ds = rdp.DataSet(schema, rows)
rep = rdp.validate_dataset(
    ds,
    {"checks": [{"kind": "not_null", "column": "instruction", "severity": "error"}]},
)
print(rdp.export_dataset_jsonl(ds, ["instruction", "output"]))
```

Other common entrypoints (same pattern: `load_dataset` → list of rows → `DataSet`):

- **Databricks Dolly** — see **§4** below (`instruction` / `context` / `response` on the dataset card).
- **OpenAssistant** — larger; use a tiny `split="train[:32]"` for experiments; map whatever columns you need into a fixed `DataSet` schema before export.

Always read the **dataset card** for **license**, attribution, and allowed use.

---

## 4. Databricks Dolly 15k (tiny slice → same QA + JSONL pattern)

The **`databricks/databricks-dolly-15k`** split exposes (among others) `instruction`, `context`, and `response`. Map them into the same three-column shape as Alpaca-style tooling (`instruction`, `input`, `output`) by using `context` as `input`:

```python
# pip install datasets
from datasets import load_dataset

import rust_data_processing as rdp

hf = load_dataset("databricks/databricks-dolly-15k", split="train[:16]")
schema = [
    {"name": "instruction", "data_type": "utf8"},
    {"name": "input", "data_type": "utf8"},
    {"name": "output", "data_type": "utf8"},
]
rows = [
    [r["instruction"], (r.get("context") or "").strip(), r["output"]]
    for r in hf
]
ds = rdp.DataSet(schema, rows)
print("rows", ds.row_count(), "failed_checks", rdp.validate_dataset(ds, {"checks": [{"kind": "not_null", "column": "output", "severity": "error"}]})["summary"]["failed_checks"])
print(rdp.export_dataset_jsonl(ds, ["instruction", "input", "output"])[:200], "...")
```

---

## See also

- [`SFT_DATA_FORMATS.md`](../SFT_DATA_FORMATS.md) — column conventions and warnings.
- [`PHASE2_EXAMPLES.md`](PHASE2_EXAMPLES.md) — JSONL export, privacy transforms, Delta handoff.
- [`API.md`](../../python-wrapper/API.md) — full Python API tables.
