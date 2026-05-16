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

## What the default run does

1. **`cargo clean`** — root Rust workspace  
2. **`python_clean.py`** — Python wrapper build artifacts under `python-wrapper/`  
3. **`build_all.py`** — in order, with short pauses between heavy steps:
   - Rust: `cargo fmt --check`, clippy, build, tests (including expanded feature set)
   - Python: Ruff, `uv sync`, `maturin develop`, pytest
   - Java: Spotless, `cargo build` for `rdp_jvm_sys`, `./gradlew check`
   - Docs: `cargo doc`, pdoc → `_site/python/`, pandoc → `_site/java/examples.html`

## Convenience flags

These are handled in the shell script (not only in Python):

| Flag | Effect |
|------|--------|
| `--python-only` | Python build + tests; skips Rust, Java, docs; **no** upfront `cargo clean` / python clean |
| `--java-only` | JVM build + Gradle `check`; skips Rust, Python, docs; **no** upfront clean |
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
```

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

python3 scripts/python_scripts/java_build.py
python3 scripts/python_scripts/java_test.py

python3 scripts/python_scripts/docs_rust.py
python3 scripts/python_scripts/docs_python.py
python3 scripts/python_scripts/docs_java.py
```

## Environment variables

| Variable | When set | Behavior |
|----------|----------|----------|
| `BUILD_ALL_NO_AUTO_JAVA=1` | JVM steps need JDK 21+ | Fail instead of `apt install openjdk-21-jdk` |
| `BUILD_ALL_NO_AUTO_BUILD_ESSENTIAL=1` | Rust needs `cc` | Fail instead of `apt install build-essential` |
| `BUILD_ALL_NO_AUTO_UV=1` | Python steps need `uv` | Fail instead of astral `uv` installer |
| `BUILD_ALL_NO_CARGO_PREFETCH=1` | `--offline` and empty cache | Fail instead of one online `cargo fetch` |

## Prerequisites (if auto-install is disabled)

- **Rust:** [rustup](https://rustup.rs/) (`cargo`, `rustc` on `PATH`; `~/.cargo/env` sourced when present)
- **Python:** `python3`, plus **`uv`** for wrapper build/test/docs
- **Java:** **JDK 21+** for Gradle; native lib built from `bindings/jvm-sys`
- **C linker:** `build-essential` (or any toolchain providing `cc`) for Rust crate build scripts
- **Docs:** `pandoc` for Java examples HTML (optional in full pipeline)

## Troubleshooting

| Symptom | Fix |
|---------|-----|
| `cargo: command not found` | Install Rust / `source ~/.cargo/env` |
| `python: command not found` | `sudo apt install python3` |
| `Required tool not on PATH: uv` | Re-run (auto-install) or `curl -LsSf https://astral.sh/uv/install.sh \| sh` |
| `Permission denied: .../gradlew` | Re-run (script chmods or uses `bash gradlew`); or `chmod +x bindings/java/rust-data-processing-jvm/gradlew` |
| `Native library not found` (Java test) | Run `java_build.py` first or set `RDP_JVM_SYS` to the `.so` path |
| `no matching package named ...` with `--offline` | Run once without `--offline`, or unset `BUILD_ALL_NO_CARGO_PREFETCH` |
| `linker cc not found` | Allow `build-essential` install or `sudo apt install build-essential` |
