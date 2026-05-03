# Tabular / NN-style example (in-repo fixture)

This example uses the small CSV at **`tests/fixtures/people.csv`** (synthetic; no external download).

## Steps

1. **Ingest** with schema `id` (int64), `name` (utf8), `age` (int64).
2. **Profile** with `profile_dataset` / `profile_dataset_json`.
3. **Validate** (e.g. `age` range, `name` not null).
4. **Train/test split:** use `rust_data_processing::export::train_test_row_indices` from Rust, or split rows in Python after `to_rows()`.
5. **Export** Parquet or JSONL for downstream training (PyTorch `TensorDataset`, etc.).

## License / citation

Fixture is **synthetic** and MIT-licensed with the repo — cite this repository if you publish results.
