# Airflow example — minimal DAG sketch

## Contract

- **Exit code:** `0` on success, non-zero on failure (Airflow `PythonOperator` / `BashOperator` standard).
- **Env:** pass paths and options via environment variables or Airflow Variables — **no secrets** in DAG files.

## Files

| File | Purpose |
|------|---------|
| [`minimal_dag.py`](minimal_dag.py) | Calls a stub shell command; replace with `python -m your_entry` or `maturin`/wheel-installed CLI. |

## Suggested env vars

| Name | Meaning |
|------|---------|
| `RDP_INPUT_CSV` | File to ingest. |
| `RDP_OUT_JSON` | Optional path to write validation/profile JSON reports. |

## Container sketch

Use an image that contains **Rust + Python** or a prebuilt wheel; mount data read-only; write artifacts to a volume.
