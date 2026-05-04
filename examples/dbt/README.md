# dbt example — calling `rust-data-processing` from a Python model

This folder shows a **minimal pattern**: a **dbt Python model** that imports the published wheel and returns a pandas DataFrame (dbt’s generic Python model contract).

## Prerequisites

- `pip install rust-data-processing` (or your fork’s wheel).
- dbt Core with Python model support enabled for your adapter.

## Layout

| File | Purpose |
|------|---------|
| [`models/example_rdp.py`](models/example_rdp.py) | Imports `rust_data_processing`, ingests a CSV path, returns `pandas.DataFrame`. |

## Paths

Copy-paste: adjust `CSV_PATH` to an absolute path on the machine running dbt (no secrets in-repo).

## dbt vars

Use `vars:` in `dbt_project.yml` for file paths and thresholds rather than hard-coding secrets.
