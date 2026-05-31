# Integration testing

Opt-in connector tests (Oracle, cloud, Kafka, …). **Not** run in default PR CI.

## Layout

| Path | Purpose |
| --- | --- |
| `scripts/rancher/check_rancher_desktop.py` | Step 1: verify Rancher Desktop; `--configure` disables autostart |
| `scripts/rancher/start_rancher_desktop.py` | Start Rancher on demand |
| `python3 integration_testing/scripts/rancher/stop_rancher_desktop.py --stop-docker` | Stop Rancher after tests |
| `scripts/build_libs/build_rust_lib.py` | Step 3a: Rust `db_connectorx` → `libs/rust/` |
| `scripts/build_libs/build_java_lib.py` | Step 3b: `rdp_jvm_sys` + JAR → `libs/java/` |
| `scripts/build_libs/build_python_lib.py` | Step 3c: PyO3 `--features db` → `libs/python/` |
| `scripts/build_libs/build_all_libs.py` | Build all three (incremental; `--force` to rebuild) |
| `scripts/data_download/download_uber_data.py` | Step 4: Uber NYC pickups CSV → `data/` |
| `scripts/common.py` | Shared paths and build helpers (library module) |
| `Oracle/docker-compose.yml` | Step 2: Oracle XE for local `oracle://` tests |
| `Oracle/run_tests.py` | Tri-language import test orchestrator |
| `Oracle/README.md` | Oracle + Rancher setup details |
| `libs/` | Built artifacts + `env.sh` (gitignored binaries) |
| `data/` | Uber CSV (gitignored; see `data/README.md`) |

Start with `Oracle/README.md`.

**Quick prep:**

```bash
python3 integration_testing/scripts/build_libs/build_all_libs.py
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
```
