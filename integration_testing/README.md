# Integration testing

Opt-in connector tests (Oracle, cloud, Kafka, …). **Not** run in default PR CI.

## Layout

| Path | Purpose |
| --- | --- |
| `scripts/rancher/check_rancher_desktop.py` | Step 1: verify Rancher Desktop; `--configure` disables autostart |
| `scripts/rancher/start_rancher_desktop.py` | Start Rancher on demand |
| `python3 integration_testing/scripts/rancher/stop_rancher_desktop.py --stop-docker` | Stop Rancher after tests |
| `scripts/build_libs/build_rust_lib.py` | Step 3a: Rust `integration_full` + optional connector prebuild → `libs/rust/` |
| `scripts/build_libs/build_java_lib.py` | Step 3b: `rdp_jvm_sys` `--features full` + JAR → `libs/java/` |
| `scripts/build_libs/build_python_lib.py` | Step 3c: PyO3 `--features integration_full` (`db` + `cloud`) → `libs/python/` |
| `scripts/build_libs/build_all_libs.py` | Build all three (incremental; see flags below) |
| `scripts/data_download/download_uber_data.py` | Step 4: Uber NYC pickups CSV → `data/` |
| `scripts/common.py` | Shared paths, workspace Cargo helpers, build stamps |
| `Oracle/docker-compose.yml` | Step 2: Oracle XE for local `oracle://` tests |
| `Oracle/run_oracle_tests.py` | Tri-language RDP import tests (no JDBC / oracledb) |
| `Oracle/README.md` | Oracle + Rancher setup details |
| `PostgreSQL/run_tests.py` | Same RDP pattern for PostgreSQL (no JDBC / psycopg) |
| `PostgreSQL/README.md` | PostgreSQL Docker + build/run |
| `SQLServer/run_mssql_tests.py` | Uber CSV → **`kind: mssql`** sink (Docker SQL Server 2022) |
| `MinIO/docker-compose.yml` | Shared S3-compatible storage for platform connector tests |
| `Snowflake/run_snowflake_tests.py` | Uber CSV → **`kind: snowflake`** `s3://` stage (MinIO Docker) |
| `Databricks/run_databricks_tests.py` | Uber CSV → **`kind: databricks`** `s3://` warehouse (MinIO Docker) |
| `Spark/run_spark_tests.py` | Uber CSV → **`kind: spark`** `s3://` handoff + Spark standalone Docker |
| `CloudConnectors/run_cloud_tests.py` | Uber CSV → **S3 / GCS / Azure** export + read-back; **SFTP / FTP** import |
| `Kafka/run_kafka_tests.py` | Uber CSV → **Kafka** one row per message (Redpanda Docker) |
| `integration_testing_details.md` | Step-by-step flows (Java / Python / Rust) per connector suite |
| `schema/` | Shared `uber_pickups.{schema,table}.json` for connector tests |
| `scripts/rdp_pipeline.py` | Python/Rust ctypes wrapper → `rdp_run_pipeline_json` |
| `libs/` | Built artifacts + `env.sh` (gitignored binaries) |
| `.target/` | Isolated Cargo target (gitignored; avoids races with repo `build_all`) |
| `data/` | Uber CSV (gitignored; see `data/README.md`) |

## Build scripts — when to use which

| Goal | Command |
| --- | --- |
| Full repo CI-style pipeline | `./scripts/build_all.sh` (prefer `--no-clean`, `--rust-only` / `--java-only` while iterating) |
| All integration libs (Rust + Java + Python) | `python3 integration_testing/scripts/build_libs/build_all_libs.py` |
| Rust lib + connector test prebuild only | `python3 integration_testing/scripts/build_libs/build_rust_lib.py` |
| Java or Python leg only | `build_java_lib.py` / `build_python_lib.py` |
| Libs without recompiling Oracle/Postgres test binaries | `build_all_libs.py --skip-prebuild` or `INTEG_SKIP_PREBUILD=1` |

**Flags (integration):**

| Flag / env | Effect |
| --- | --- |
| `--force` | Rebuild libs; remove `integration_testing/.target/`; remove repo `target/`; force connector prebuild |
| `--no-clean` (N/A on build scripts) | Use `INTEG_NO_DISK_CLEAN=1` to skip all disk cleanup |
| `--skip-prebuild` / `INTEG_SKIP_PREBUILD=1` | Skip `cargo test --no-run` for Oracle/PostgreSQL crates |
| (default) | Keeps repo `target/` if `libs/rust/.built_at` exists; skips connector prebuild when per-connector stamps are fresh |

**Order:** Wait for `./scripts/build_all.sh` or `build_all_run.sh` to finish before `build_all_libs.py` — concurrent runs can delete `target/` mid-build.

**Workspace:** Connector crates and `rust-data-processing` share the root `Cargo.lock` (Cargo workspace). After pulling lockfile changes, from repo root:

```bash
cargo generate-lockfile
```

**Disk:** Integration builds use `integration_testing/.target/` (`CARGO_TARGET_DIR`). `INTEG_MIN_DISK_GIB=6` (default) for preflight.

**Connector tests:** Warehouse DB connectors (PostgreSQL, Oracle, SQL Server) use **`rdp_run_pipeline_json`** for load and **`ingest_from_db`** for verify. Platform connectors (Snowflake, Databricks, Spark) write to **`s3://`** on **MinIO**, then **`platform_sql.py`** runs warehouse SQL (`CREATE TABLE`, load from stage, `SELECT COUNT(*)`): Snowflake **emulator** + **spark-sql** for Databricks/Spark. Requires `build_all_libs.py`, **uv** + `uv pip install` into `python-wrapper/.venv` (see `scripts/platform_deps.py`), and Docker. See `MinIO/README.md` and per-connector READMEs.

Start with `Oracle/README.md`, `PostgreSQL/README.md`, `SQLServer/README.md`, or the platform connector READMEs.

**Quick prep:**

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
```

**Planning:** `Planning/PHASE3_EPICS.md` → **P3-E4-Integration** (I1–I8).
