# rust-data-processing

![Phase 3 scope: Rust core with Python (PyO3) and Java (Panama) bindings, agent-ready JSON FFI, and shared batch/streaming connectors](https://raw.githubusercontent.com/scorpio-datalake/rust-data-processing/main/docs/images/phase-3-scope-overview.png)

Python bindings for the **[rust-data-processing](https://docs.rs/rust-data-processing)** crate: schema-first ingestion from CSV, JSON, Parquet, and Excel into an in-memory **`DataSet`**, with profiling, validation, Polars-backed pipelines, SQL, **Phase 2** JSONL export, privacy transforms and summaries, median, Arrow interop, incremental ingest helpers, and **Phase 3** parity with JVM JSON FFI (agent-ready structured in/out for LangGraph and tool-calling workflows).

*Infographic: Phase 3 — one Rust engine; Python (PyO3 / PyPI) and Java (Panama / Maven + Gradle) bindings; Phase 1–2 capabilities; agent-ready JSON in/out; shared connectors (Postgres, S3, Kafka, Snowflake).*

This page is the **PyPI** project description (Python-only). Clone the [repository](https://github.com/scorpio-datalake/rust-data-processing) for developer setup, Rust sources, and the full monorepo README.

## Install

```bash
pip install rust-data-processing
```

Requires **Python 3.10+**.

## Quick start

```python
import rust_data_processing as rdp

schema = [
    {"name": "id", "data_type": "int64"},
    {"name": "name", "data_type": "utf8"},
]
ds = rdp.ingest_from_path("path/to/data.csv", schema, {"format": "csv"})
print("rows", ds.row_count())

report = rdp.profile_dataset(ds, {"head_rows": 50, "quantiles": [0.5]})
print("profile rows sampled", report["row_count"])

validation = rdp.validate_dataset(
    ds,
    {"checks": [{"kind": "not_null", "column": "id", "severity": "error"}]},
)
print("checks", validation["summary"]["total_checks"])
```

## Phase 2 (export, privacy, JSONL, median, Delta handoff)

Copy-paste snippets: **[Phase 2 Python examples (Markdown in repo)](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/python/PHASE2_EXAMPLES.md)**. These APIs are also summarized in **[API.md](https://github.com/scorpio-datalake/rust-data-processing/blob/main/python-wrapper/API.md)** (section **Export, privacy summaries, truncation (Phase 2)**).

## Documentation

| | Link |
| --- | --- |
| **This package on PyPI** | [pypi.org/project/rust-data-processing](https://pypi.org/project/rust-data-processing/) |
| **Python examples (HTML, pdoc)** | [GitHub Pages — examples](https://scorpio-datalake.github.io/rust-data-processing/python/examples.html) |
| **Python API (HTML, pdoc)** | [GitHub Pages — Python](https://scorpio-datalake.github.io/rust-data-processing/python/) |
| **Python API (markdown)** | [API.md in the repository](https://github.com/scorpio-datalake/rust-data-processing/blob/main/python-wrapper/API.md) |
| **Combined site (landing + Rust rustdoc)** | [GitHub Pages — home](https://scorpio-datalake.github.io/rust-data-processing/) |
| **Rust crate API** | [docs.rs/rust-data-processing](https://docs.rs/rust-data-processing) |
| **JVM bindings (Java)** | [docs/java/README.md](https://github.com/scorpio-datalake/rust-data-processing/blob/main/docs/java/README.md) |
| **Repository** | [github.com/scorpio-datalake/rust-data-processing](https://github.com/scorpio-datalake/rust-data-processing) |

## License

MIT OR Apache-2.0 - see [LICENSE-MIT](https://github.com/scorpio-datalake/rust-data-processing/blob/main/LICENSE-MIT) and [LICENSE-APACHE](https://github.com/scorpio-datalake/rust-data-processing/blob/main/LICENSE-APACHE) in the repository.
