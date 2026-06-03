# Integration / build_all — session notes

Personal checklist (not run by CI). Repo root: `cd /home/ubuntu/rust-data-processing`.

**Tracker:** `Planning/PHASE3_EPICS.md` → **P3-E4** (local-first). **Tomorrow = `cargo generate-lockfile`, then build/test (no `--force`).**

## Done

- [x] `build_all_run.sh start --java-only` (wait for finish)
- [x] `build_all_run.sh start --docs-only`
- [x] Integration libs: **Rust** (`libs/rust/env.sh`) and **Java** (`libs/java/librdp_jvm_sys.so`, JAR, `env.sh`) via `build_all_libs.py` (**Python** lib not finished)
- [x] **Stopped** `build_all_libs.py --force` (2026-06-03 — server cost; cold `integration_full` + connector prebuild not worth running overnight)

## Tomorrow — verify P3-E4 integration stories (implemented in-tree)

Stories **I3, I4, I7, I8, I1+I2** are coded; **you** run builds/tests tomorrow.

| Done | Story | What landed |
| ---- | ----- | ----------- |
| [x] | **P3-E4-I4** | `libs/rust/.oracle_test_built_at`, `.postgresql_test_built_at`; skip connector prebuild when fresh |
| [x] | **P3-E4-I8** | `--skip-prebuild`, `INTEG_SKIP_PREBUILD=1`; keep repo `target/` when `libs/rust/.built_at` exists (unless `--force`) |
| [x] | **P3-E4-I3** | `[profile.integration]`; prebuild uses it; `run_*_tests.py` still uses `release` |
| [x] | **P3-E4-I1 + I2** | Root workspace members; connector `integration_full`; per-connector `Cargo.lock` removed |
| [x] | **P3-E4-I7** | `integration_testing/README.md` build-script section |

**First command tomorrow (refresh root lockfile after workspace):**

```bash
cd /home/ubuntu/rust-data-processing
cargo generate-lockfile
```

**Later (repo-wide):** P3-E4-M3 (target-dir doc in ADR); P3-E4-P3 (`build_all.sh` default `--no-clean`); P3-E4-G1 (`sccache` on Linux dev VM).

## After stories — build libs (no `--force`)

```bash
# All three legs (incremental; respects new stamps / skip-prebuild)
python3 integration_testing/scripts/build_libs/build_all_libs.py

# Python only if Rust + Java already OK:
python3 integration_testing/scripts/build_libs/build_python_lib.py
```

Background (optional):

```bash
nohup python3 integration_testing/scripts/build_libs/build_all_libs.py \
  > /tmp/build_all_libs.log 2>&1 &
tail -f /tmp/build_all_libs.log
```

**Repo builds** (separate from integration):

```bash
./scripts/build_all.sh --no-clean --rust-only   # or --java-only / --python-only
```

Wait for `build_all` / `build_all_run.sh` to finish before `build_all_libs.py` (integration may remove repo `target/`).

## After all three libs exist — Oracle integration

```bash
python3 integration_testing/scripts/data_download/download_uber_data.py --sample
python3 integration_testing/Oracle/run_oracle_tests.py
```

Requires `libs/rust/`, `libs/java/`, and `libs/python/` (`.so` + `env.sh` each).

```bash
nohup python3 integration_testing/Oracle/run_oracle_tests.py \
  > /tmp/run_oracle_tests.log 2>&1 &
tail -f /tmp/run_oracle_tests.log
```

```bash
docker compose -f integration_testing/Oracle/docker-compose.yml down
python3 integration_testing/Oracle/run_oracle_tests.py --no-rancher --keep-oracle
```

## Reference — scripts

| Script | Purpose |
| ------ | ------- |
| `scripts/build_libs/build_all_libs.py` | Rust → Java → Python |
| `scripts/build_libs/build_rust_lib.py` | `integration_full` + connector prebuild (**expensive today**) |
| `scripts/build_libs/build_python_lib.py` | Python only |
| `Oracle/run_oracle_tests.py` | Docker Oracle + tri-language tests |
| `scripts/build_all.sh` | Full repo pipeline (prefer `--no-clean`, `*-only`) |

## Stop background jobs

```bash
pkill -f "build_all_libs.py"
pkill -f "build_rust_lib.py"
pkill -f "build_python_lib.py"
pkill -f "run_oracle_tests.py"
pkill -f "cargo build.*integration_full"
pkill -f "cargo test.*oracle_import"
# Orphan rustc on integration target:
pkill -9 -f "integration_testing/.target"
```
