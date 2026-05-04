# Supervised fine-tuning (SFT) — common file shapes and `DataSet` mapping

**Runnable Python (not “high level only”):** **[`docs/python/SFT_PYTHON_EXAMPLES.md`](python/SFT_PYTHON_EXAMPLES.md)** — full scripts for: in-repo **[`examples/sft/sample_alpaca.ndjson`](../examples/sft/sample_alpaca.ndjson)** (Alpaca columns), optional **`datasets.load_dataset("tatsu-lab/alpaca", …)`**, **`databricks/databricks-dolly-15k`** (§4 in that doc), a **chat `messages`** column as JSON text, then profile / validate / **`export_dataset_jsonl`**. Those examples are also indexed from **[`examples/sft/README.md`](../examples/sft/README.md)** and included in the **pdoc** HTML site via `rust_data_processing.examples` (see `python-wrapper/rust_data_processing/examples.py`).

This library stays **format-agnostic**: you model columns with [`Schema`](https://docs.rs/rust-data-processing/latest/rust_data_processing/types/struct.Schema.html) and export with [`export::dataset_to_jsonl`](https://docs.rs/rust-data-processing/latest/rust_data_processing/export/fn.dataset_to_jsonl.html) or Parquet/CSV ingest paths.

## Chat / messages (ShareGPT-style)

- **Columns:** `messages` (JSON string or separate `role` / `content` columns if flattened).
- **Export:** one JSON object per line; `messages` as a JSON array string or nested object per your trainer (TRL / HF Datasets accept dict columns).

## Alpaca (`instruction`, `input`, `output`)

- **Schema:** three UTF-8 columns; optional fourth `system`.
- **Transform:** `TransformSpec` rename/cast/select to match trainer template.

## JSONL export

- Use **`dataset_to_jsonl`** with an explicit **`column_order`** for stable diffs.
- **Train/val split:** [`export::train_test_row_indices`](https://docs.rs/rust-data-processing/latest/rust_data_processing/export/fn.train_test_row_indices.html) (deterministic tail holdout) or your own shuffle policy.

## **Bold warning (trainers)**

**Chat templates and tokenizers are the trainer’s responsibility.** This repo exports **raw UTF-8** fields only; align columns with your target model’s template **before** calling `Trainer` / `SFTTrainer`.
