# `build_all.sh` — full repo build, test, and docs

Linux/macOS entry point for the CI-style pipeline. It optionally installs missing toolchain pieces on fresh Ubuntu/Debian VMs, runs an upfront clean, then delegates to `scripts/python_scripts/build_all.py`.

Windows: use `pwsh -File scripts/build_all.ps1` (same orchestrator; no shell convenience flags yet).

## Quick start

From the repository root:

```bash
chmod +x scripts/build_all.sh   # once, if needed
./scripts/build_all.sh
```

First run on a minimal cloud image may use `sudo` to install OpenJDK 21, `build-essential` (`cc`), and `uv` (Python). Later runs reuse what is already installed.

## Logs (fresh each run)

Capture logs are **removed at the start** of every pipeline run so you do not read stale output from an earlier attempt.

| Mechanism | Log path | Cleanup |
|-----------|----------|---------|
| Foreground | `./scripts/build_all.sh` (stdout) or `> build_all.log` | Deletes repo-root `build_all.log` before work begins |
| Background (SSH-safe) | `./build_all_run.sh start` | Deletes `build_all.log`, prunes prior `.build_all_runs/run-*` (keeps an in-flight run), writes a new `build.log` per run |

```bash
./build_all_run.sh start              # detached; survives logout
./build_all_run.sh logs -f            # follow .build_all_runs/latest/build.log
./build_all_run.sh status             # running / OK / failed
./build_all_run.sh failures           # grep summary after failure
```

Set `BUILD_ALL_KEEP_LOGS=1` to skip deletion of `build_all.log` and prior `.build_all_runs/` directories (for comparing runs).

## What the default run does

1. **`cargo clean`** — root Rust workspace  
2. **`python_clean.py`** — Python wrapper build artifacts under `python-wrapper/`  
3. **`build_all.py`** — in order, with short pauses between heavy steps:
   - Rust: `cargo fmt --check`, **`cargo audit`** (RustSec; same gate as `rust_ci.yml`), clippy, build, tests + doctests, `ci_expanded` tests, `people.xlsx` fixture
   - Python: Ruff, `uv sync` (no pep517 build yet), debug `maturin develop` into shared repo `target/`, pytest (wheel smoke skipped here — see `python_ci.yml`)
   - Java (same gates as `.github/workflows/jvm_bindings_ci.yml`): FFI manifest + version checks, Spotless (Gradle main + Maven on all three modules), `rdp_jvm_sys` (`--features full`), `people.xlsx`, `mvn verify` + install (main + examples), Spark `mvn package`, Gradle `check` + `jmh` + `publishToMavenLocal`
   - Docs: `cargo doc`, pdoc → `_site/python/`, pandoc → `_site/java/examples.html`

## Convenience flags

These are handled in the shell script (not only in Python):

| Flag | Effect |
|------|--------|
| `--python-only` | Python build + tests; skips Rust, Java, docs; **no** upfront `cargo clean` / python clean |
| `--java-only` | JVM CI parity (`java_build.py` + `java_test.py`); skips Rust, Python, docs; **no** upfront clean |
| `--rust-only` | Rust build + tests only; **no** upfront clean |
| `--docs-only` | Generate Rust, Python, and Java docs only; **no** upfront clean |
| `--docs-rust` | Rust API docs only (`cargo doc` → `target/doc/…`) |
| `--docs-python` | Python API docs only (`pdoc` → `_site/python/`) |
| `--docs-java` | Java examples HTML only (`pandoc` → `_site/java/examples.html`) |
| `--no-clean` | Skip `cargo clean` and `python_clean.py` (keep when iterating) |
| `-h`, `--help` | Short usage on stdout |
| `--readme` | Print this file |

Examples:

```bash
./scripts/build_all.sh --python-only
./scripts/build_all.sh --java-only
./scripts/build_all.sh --rust-only
./scripts/build_all.sh --docs-only
./scripts/build_all.sh --docs-rust
./scripts/build_all.sh --docs-python
./scripts/build_all.sh --docs-java
./scripts/build_all.sh --docs-rust --docs-python   # both (orchestrator accepts combined *-only flags)
./scripts/build_all.sh --offline --rust-only
./scripts/build_all.sh --no-clean --skip-java --skip-docs   # Rust + Python only, keep artifacts
./scripts/build_all.sh --no-clean --java-only               # JVM CI only, reuse prior builds
```

## Lighter runs (limited CPU / disk)

Use the existing skip flags instead of a separate “fast” mode. Full `./scripts/build_all.sh` matches CI; trim what you are not touching:

| Goal | Example |
|------|---------|
| Java only (JVM CI parity) | `./scripts/build_all.sh --java-only` |
| Skip JVM entirely | `./scripts/build_all.sh --skip-java` or `--skip-java --skip-docs` |
| Skip formatting only | add `--skip-fmt` (Rust, Python, and Java) |
| Fix Java formatting (Spotless) | `./scripts/build_all.sh --java-only --fix-fmt` or `python3 scripts/python_scripts/java_build.py --fix-fmt` |
| Keep compile artifacts | add `--no-clean` |
| Shorter pauses between steps | `--wait-seconds 0 --rust-build-test-wait-seconds 0` |

`--java-only` still runs native `cargo build` for `rdp_jvm_sys`, Maven verify on main + examples, Spark compile, and Gradle check/JMH — the same steps as JVM bindings CI (except the GitHub OS matrix).

## Orchestrator flags (passed to `build_all.py`)

Any unrecognized argument is forwarded to `scripts/python_scripts/build_all.py`:

| Flag | Meaning |
|------|---------|
| `--offline` | `CARGO_NET_OFFLINE` / `RUSTUP_OFFLINE`; may run **one** online `cargo fetch` if the cache is empty (disable with `BUILD_ALL_NO_CARGO_PREFETCH=1`) |
| `--skip-rust` | Skip Rust build and tests |
| `--skip-python` | Skip Python build and tests |
| `--skip-java` | Skip JVM build and tests |
| `--skip-docs` | Skip doc generation |
| `--skip-fmt` | Skip format checks (Rust, Python, Java) |
| `--skip-audit` | Skip RustSec `cargo audit` (Rust step only) |
| `--fix-fmt` | Java: `spotless:apply` then `spotless:check` on main, examples, and spark Maven modules + Gradle (not Rust/Python) |
| `--clean` | Also run Gradle `clean` during Java steps (shell already ran `cargo clean` unless `--no-clean`) |
| `--rust-expanded-only` | Rust clippy/build/test with `ci_expanded` only |
| `--wait-seconds N` | Pause between major steps (default: 10) |
| `--rust-build-test-wait-seconds N` | Extra pause after Rust build before tests (default: 30) |
| `--docs-rust-only` | Rust `cargo doc` only (shell alias: `--docs-rust`) |
| `--docs-python-only` | Python pdoc only (shell alias: `--docs-python`) |
| `--docs-java-only` | Java pandoc HTML only (shell alias: `--docs-java`) |

Combine convenience and orchestrator flags:

```bash
./scripts/build_all.sh --python-only --skip-fmt
./scripts/build_all.sh --java-only --clean
```

## Documentation flags

Per-language doc generation skips build, test, and upstream clean (same as `--docs-only`):

| Shell flag | Orchestrator flag | Output |
|------------|-------------------|--------|
| `--docs-only` | `--docs-only` | All three below |
| `--docs-rust` | `--docs-rust-only` | `target/doc/rust_data_processing/index.html` |
| `--docs-python` | `--docs-python-only` | `_site/python/index.html` |
| `--docs-java` | `--docs-java-only` | `_site/java/examples.html` |

```bash
./scripts/build_all.sh --docs-rust
./scripts/build_all.sh --docs-python
./scripts/build_all.sh --docs-java
./scripts/build_all.sh --docs-rust --offline          # rustdoc with offline Cargo
./scripts/build_all.sh --docs-rust --docs-python        # two languages in one invocation
```

**Requirements:** Rust docs need `cargo`. Python docs need **`uv`** (runs `maturin develop` before pdoc). Java docs need **pandoc** on `PATH` (`sudo apt install pandoc`); the pipeline skips Java HTML if pandoc is missing (`--skip-if-no-pandoc`).

## Doc output locations (standalone scripts)

| Language | Command (standalone) | Open in browser |
|----------|----------------------|-----------------|
| Rust | `python3 scripts/python_scripts/docs_rust.py` | `target/doc/rust_data_processing/index.html` |
| Python | `python3 scripts/python_scripts/docs_python.py` | `_site/python/index.html` |
| Java | `python3 scripts/python_scripts/docs_java.py` | `_site/java/examples.html` |

## Running individual steps

Same modules the orchestrator calls (from repo root):

```bash
python3 scripts/python_scripts/python_build.py
python3 scripts/python_scripts/python_test.py

python3 scripts/python_scripts/java_build.py   # native lib, Spotless (Gradle + Maven), people.xlsx
python3 scripts/python_scripts/java_test.py    # mvn verify (3 modules) + gradlew check/jmh

python3 scripts/python_scripts/docs_rust.py
python3 scripts/python_scripts/docs_python.py
python3 scripts/python_scripts/docs_java.py
```

## Environment variables

| Variable | When set | Behavior |
|----------|----------|----------|
| `BUILD_ALL_NO_AUTO_JAVA=1` | JVM steps need JDK 21+ | Fail instead of `apt install openjdk-21-jdk` |
| `BUILD_ALL_NO_AUTO_BUILD_ESSENTIAL=1` | Rust needs `cc` | Fail instead of `apt install build-essential` |
| `BUILD_ALL_NO_AUTO_LIBCLANG=1` | JVM `rdp_jvm_sys --features full` (bindgen) | Fail instead of `apt install libclang-dev` on Debian/Ubuntu |
| `BUILD_ALL_NO_AUTO_UV=1` | Python steps need `uv` | Fail instead of astral `uv` installer |
| `BUILD_ALL_NO_AUTO_MAVEN=1` | JVM steps need `mvn` | Fail instead of `apt install maven` |
| `BUILD_ALL_NO_AUTO_CARGO_AUDIT=1` | Rust audit step | Fail instead of `cargo install cargo-audit` when missing |
| `BUILD_ALL_NO_CARGO_PREFETCH=1` | `--offline` and empty cache | Fail instead of one online `cargo fetch` |
| `BUILD_ALL_KEEP_LOGS=1` | You want prior `build_all.log` / `.build_all_runs/` kept | Skip log cleanup at start of `build_all.sh` / `build_all_run.sh start` |
| `BUILD_ALL_CARGO_JOBS` | Linux test links OOM or you have more RAM | `2` default via `rust_test.py`; `4`+ on large machines; `0` = Cargo default job count |
| `BUILD_ALL_CLEAN_BETWEEN_RUST_FEATURES` | Skip recompile between default and `ci_expanded` on a large VM | **`1` (default)** — `cargo clean` after default Rust clippy/build; set `=0` to keep artifacts |
| `BUILD_ALL_MIN_DISK_GIB` | Preflight before `build_all` / Rust steps | `8` — fail early if root filesystem has less free space |
| `BUILD_ALL_PYTHON_RELEASE` | Match CI `maturin develop --release` locally | Default **debug** develop (reuses repo `target/` after Rust steps; much less disk) |
| `BUILD_ALL_PYTHON_SEPARATE_TARGET` | Isolate Python artifacts | Use `python-wrapper/target/` instead of shared repo `target/` |
| `BUILD_ALL_SKIP_PYTHON_WHEEL_SMOKE` | Disk tight after develop | `build_all` already skips wheel smoke; set when running `python_test.py` alone |
| `BUILD_ALL_NO_DISK_CLEAN` | Keep caches/target between phases | Skip automatic cleanup in `python_build.py` / `java_build.py` |
| `BUILD_ALL_JVM_CLEAN_M2` | JVM phase still out of disk | Also remove `~/.m2/repository` before JVM (slow; re-downloads Maven deps) |

## Prerequisites (if auto-install is disabled)

- **Rust:** [rustup](https://rustup.rs/) (`cargo`, `rustc` on `PATH`; `~/.cargo/env` sourced when present); **`cargo-audit`** (auto-installed on first run unless `BUILD_ALL_NO_AUTO_CARGO_AUDIT=1`)
- **Python:** `python3`, plus **`uv`** for wrapper build/test/docs
- **Java:** **JDK 21+**, **Maven** (`mvn`), Gradle wrapper under `bindings/java/rust-data-processing-jvm`; native lib from `bindings/jvm-sys`
- **C linker:** `build-essential` (or any toolchain providing `cc`) for Rust crate build scripts
- **Docs:** `pandoc` for Java examples HTML (optional in full pipeline)

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `cargo: command not found` | Install Rust / `source ~/.cargo/env` |
| `cargo-audit` not found (with `--offline`) | Install while online: `cargo install cargo-audit --vers 0.22.0 --locked`, or re-run without `--offline` |
| `python: command not found` | `sudo apt install python3` |
| `Required tool not on PATH: uv` | Re-run (auto-install) or `curl -LsSf https://astral.sh/uv/install.sh \| sh` |
| `Permission denied: .../gradlew` | Re-run (script chmods or uses `bash gradlew`); or `chmod +x bindings/java/rust-data-processing-jvm/gradlew` |
| `Native library not found` (Java test) | Run `java_build.py` first or set `RDP_JVM_SYS` to the `.so` / `.dll` path |
| Spotless fails on `jvm-examples` but `--java-only` passed | Run `java_build.py` without `--skip-fmt` (checks Maven Spotless on all modules) |
| Maven Central / JVM CI Spotless on `FfiExportedSymbolsContractTest` | `python3 scripts/python_scripts/java_build.py --fix-fmt` then commit; same as `mvn -f bindings/java/rust-data-processing-jvm spotless:apply` on **all three** JVM Maven modules |
| Windows CI Surefire exit `-1073741819` | JVM CI matrix includes Windows; local `--java-only` on Linux does not — push to CI or run on Windows |
| `Required tool not on PATH: mvn` | Install Maven (`sudo apt install maven` on Debian/Ubuntu) |
| `no matching package named ...` with `--offline` | Run once without `--offline`, or unset `BUILD_ALL_NO_CARGO_PREFETCH` |
| `linker cc not found` | Allow `build-essential` install or `sudo apt install build-essential` |
| `ld terminated with signal 7 [Bus error]` / rust-lld stack dump linking tests | Repo disables `lld` and uses GNU `bfd` (`.cargo/config.toml`); `cargo clean` then re-run |
| `ld terminated with signal 9 [Killed]` linking tests or benches (OOM) | `build_all` skips Criterion bench linking in `rust_build.py` (uses `--lib --bins --tests --examples`); tests run with `cargo test -j 2` on Linux. On a bigger VM set `BUILD_ALL_CARGO_JOBS=4` (or `0` for Cargo default); then `cargo clean` and re-run |
| `No space left on device` / `ld.bfd: final link failed` during Rust tests | Root `target/debug/` can exceed 25 GiB on a full `build_all`. Run `cargo clean`, check `df -h`. `build_all` skips Criterion benches in clippy/build, and **by default** runs `cargo clean` between default and `ci_expanded` Rust phases. Disable with `BUILD_ALL_CLEAN_BETWEEN_RUST_FEATURES=0` on a large VM when iterating. Preflight: `BUILD_ALL_MIN_DISK_GIB=8` (default) |
| `No space left on device` during Python / maturin | Free disk (`df -h`); remove old `python-wrapper/target/` if present; re-run — Python now shares repo `target/` and uses debug `maturin develop` (not a second release tree). Optional: `BUILD_ALL_PYTHON_RELEASE=1` only when you need release parity |
| `Unable to find libclang` / `libgssapi-sys` build failed | Re-run `build_all` (auto-installs `libclang-dev` on Debian/Ubuntu before JVM native build), or `sudo apt install libclang-dev libkrb5-dev pkg-config` |

### Automatic disk cleanup (each Python / JVM phase)

Before **Python** (`python_build.py`), `build_all` runs:

- `df -h` and `du -sh` on `target/`, `python-wrapper/target/`, `~/.cargo/registry`, `~/.cargo/git`, `~/.cache/uv`
- Removes those paths (frees space for maturin; next step refetches crates as needed)

Before **JVM** (`java_build.py`), `build_all` runs:

- `df -h` and `du -sh` on `bindings/jvm-sys/target`, Maven `target/` dirs (main, examples, spark), Gradle `build/` + `.gradle/`
- Removes those paths (optional: `BUILD_ALL_JVM_CLEAN_M2=1` also clears `~/.m2/repository`)

Set `BUILD_ALL_NO_DISK_CLEAN=1` to disable both cleanups.
