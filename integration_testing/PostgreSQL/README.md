# PostgreSQL integration testing (local Docker)

Tri-language RDP connector tests against a real PostgreSQL instance via ConnectorX `postgresql://` URLs.

Reuses the same built libraries as Oracle (`integration_testing/libs/`). See `docs/CONNECTORS.md` for URL/auth conventions.

## Prerequisites

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
```

**Build features** (validated by `scripts/check_jvm_full_features.py`):

| Leg | Flag | Purpose |
| --- | --- | --- |
| Rust | `integration_full` | `ingest_from_db` verify + shared Polars artifacts |
| Java | `rdp_jvm_sys --features full` | `kind: postgresql` sink via `rdp_run_pipeline_json` |
| Python | `integration_full` | `ingest_from_db` verify; load via ctypes → `librdp_jvm_sys` |

**Rust compile cost:** `build_rust_lib.py` pre-builds `PostgreSQL/rust/` into `integration_testing/.target/`. `run_tests.py` only runs `cargo test` — no cold Polars rebuild if lib build completed first.

Docker (Rancher Desktop or Docker Engine) — see [Oracle/README.md](../Oracle/README.md) for Rancher setup on desktops.

## Run all tests

```bash
python3 integration_testing/PostgreSQL/run_tests.py
```

`run_tests.py`:

1. Optionally starts Rancher Desktop / ensures Docker
2. Stops other containers, prunes unused Docker resources, tears down Oracle compose if present
3. Starts `postgres:16-alpine` via `docker-compose.yml`
4. Runs Java, Python, and Rust import tests (all **RDP pipeline** — no JDBC / psycopg)

### Flags

| Flag | Effect |
| --- | --- |
| `--no-rancher` | Skip Rancher start/stop (Docker already running) |
| `--keep-postgres` | Leave compose stack up after tests |
| `--no-isolate` | Do not stop other containers / prune before start |

Re-run tests against an already-running database:

```bash
python3 integration_testing/PostgreSQL/run_tests.py --no-rancher --keep-postgres --no-isolate
```

## ConnectorX URL (default `.env.example`)

```text
postgresql://etl_user:rdp_test_etl@localhost:5432/rdp_test?cxprotocol=binary
```

| Variable | Default | Role |
| --- | --- | --- |
| `POSTGRES_APP_USER` | `etl_user` | Database user |
| `POSTGRES_APP_PASSWORD` | `rdp_test_etl` | Password |
| `POSTGRES_DB` | `rdp_test` | Database name |
| `POSTGRES_PORT` | `5432` | Host port |

## Layout

| Path | Role |
| --- | --- |
| `docker-compose.yml` | PostgreSQL 16 (Alpine) |
| `run_tests.py` | Orchestrator |
| `java/` | JUnit — `rdp_run_pipeline_json` → `kind: postgresql` sink |
| `tests/` | pytest — `rdp_pipeline.py` load + `ingest_from_db` verify |
| `rust/` | `cargo test` — `librdp_jvm_sys` pipeline + `ingest_from_db` verify |

Each leg: truncate table → ingest Uber CSV via RDP pipeline → verify row count via RDP read.

Shared schemas: `integration_testing/schema/uber_pickups.{schema,table}.json`.
