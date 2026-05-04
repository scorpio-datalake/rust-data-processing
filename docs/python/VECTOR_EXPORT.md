# Vector stores — export patterns (reference)

## Recommended bulk load shape

1. Build a `DataSet` with at least:
   - **`id`**: stable string or int primary key.
   - **`embedding`**: as repeated columns (unnormalized) **or** a single UTF-8 column holding JSON/hex — **prefer Parquet** for numeric vectors instead of stuffing floats into `Utf8`.
2. Export:
   - **Parquet** or **CSV** via your pipeline (Polars write, DuckDB `COPY`, etc.), **or**
   - **`rust_data_processing::export::dataset_to_jsonl`** from Rust / Python for metadata + keys when embeddings live elsewhere.

## Column layout convention (doc-only)

| Column | Role |
|--------|------|
| `id` | Stable row key in the vector store. |
| `text` | Optional raw passage for hybrid search. |
| `meta_*` | Optional flat metadata prefixed for clarity. |

## pgvector + `psycopg2` (reference only)

This is **not** a supported SDK matrix — one recipe pattern:

1. Export Parquet from Python or Rust.
2. `COPY` into a staging table, then `INSERT INTO items (id, embedding, …) SELECT … FROM staging` using [`pgvector`](https://github.com/pgvector/pgvector) SQL types.

Tune `lists` / indexes per pgvector docs; this repo does not execute SQL against your database.
