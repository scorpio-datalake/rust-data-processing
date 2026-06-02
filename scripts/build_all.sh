#!/usr/bin/env bash
# Full CI-style pipeline: see scripts/build_all.md

# Re-exec under bash when invoked as `sh build_all.sh` (dash lacks pipefail).
if [ -z "${BASH_VERSION:-}" ]; then
  exec /usr/bin/env bash "$0" "$@"
fi

set -euo pipefail

script_dir="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
repo_root="$(cd "${script_dir}/.." && pwd)"
readme="${script_dir}/build_all.md"

# Fresh capture logs each invocation (see scripts/build_all.md). Set BUILD_ALL_KEEP_LOGS=1 to retain.
if [[ -z "${BUILD_ALL_KEEP_LOGS:-}" ]]; then
  rm -f "${repo_root}/build_all.log"
fi

usage() {
  cat <<'EOF'
Usage: ./scripts/build_all.sh [OPTIONS] [ORCHESTRATOR_ARGS...]

Convenience flags (expanded before calling build_all.py):
  --python-only     Python wrapper build + tests only
  --java-only       JVM CI parity: native lib + Maven verify (all modules) + Gradle check/JMH
  --rust-only       Rust build + tests only (no upfront cargo clean)
  --docs-only       Generate Rust, Python, and Java HTML docs
  --docs-rust       Rust API docs only (cargo doc)
  --docs-python     Python API docs only (pdoc → _site/python/)
  --docs-java       Java examples HTML only (pandoc → _site/java/)
  --no-clean        Skip cargo clean and python_clean.py (also set by *-only flags)

Orchestrator flags (passed through to build_all.py):
  --offline         Cargo/rustup offline; may fetch once if cache is empty
  --skip-rust | --skip-python | --skip-java | --skip-docs
  --docs-rust-only | --docs-python-only | --docs-java-only  (also via --docs-rust, etc.)
  --skip-fmt        Skip format checks (Rust, Python, Java)
  --fix-fmt         Java: Spotless apply then check (all Maven modules + Gradle)
  --clean           Gradle clean during Java steps (shell already ran cargo clean)
  --rust-expanded-only
  --wait-seconds N  --rust-build-test-wait-seconds N

Environment (optional auto-install on Debian/Ubuntu):
  BUILD_ALL_NO_AUTO_JAVA=1
  BUILD_ALL_NO_AUTO_BUILD_ESSENTIAL=1
  BUILD_ALL_NO_AUTO_UV=1
  BUILD_ALL_NO_CARGO_PREFETCH=1

Full guide: scripts/build_all.md
EOF
}

# --- parse args ---
skip_upstream_clean=false
orch_args=()

while [[ $# -gt 0 ]]; do
  case "$1" in
    -h|--help)
      usage
      exit 0
      ;;
    --readme)
      if [[ -f "${readme}" ]]; then
        cat "${readme}"
      else
        echo "missing ${readme}" >&2
        exit 1
      fi
      exit 0
      ;;
    --no-clean)
      skip_upstream_clean=true
      shift
      ;;
    --python-only)
      skip_upstream_clean=true
      orch_args+=(--skip-rust --skip-java --skip-docs)
      shift
      ;;
    --java-only)
      skip_upstream_clean=true
      orch_args+=(--skip-rust --skip-python --skip-docs)
      shift
      ;;
    --rust-only)
      skip_upstream_clean=true
      orch_args+=(--skip-python --skip-java --skip-docs)
      shift
      ;;
    --docs-only)
      skip_upstream_clean=true
      orch_args+=(--docs-only)
      shift
      ;;
    --docs-rust)
      skip_upstream_clean=true
      orch_args+=(--docs-rust-only)
      shift
      ;;
    --docs-python)
      skip_upstream_clean=true
      orch_args+=(--docs-python-only)
      shift
      ;;
    --docs-java)
      skip_upstream_clean=true
      orch_args+=(--docs-java-only)
      shift
      ;;
    *)
      orch_args+=("$1")
      shift
      ;;
  esac
done

# Non-login shells often omit rustup's PATH; ensure ~/.cargo/bin is visible.
ensure_cargo_on_path() {
  if [[ -z "${HOME:-}" ]]; then
    HOME="$(getent passwd "$(id -un)" 2>/dev/null | cut -d: -f6 || true)"
    export HOME
  fi
  if [[ -z "${HOME:-}" ]]; then
    HOME="/home/$(id -un)"
    export HOME
  fi
  if [[ -f "${HOME}/.cargo/env" ]]; then
    # shellcheck source=/dev/null
    source "${HOME}/.cargo/env"
  fi
  local cargo_bin="${HOME}/.cargo/bin"
  if [[ -x "${cargo_bin}/cargo" ]]; then
    case ":${PATH}:" in
      *":${cargo_bin}:"*) ;;
      *) export PATH="${cargo_bin}:${PATH}" ;;
    esac
  fi
}

ensure_cargo_on_path

cd "${repo_root}"

if ! command -v cargo >/dev/null 2>&1; then
  echo "error: cargo not found. Install Rust (https://rustup.rs/) or ensure ~/.cargo/bin is on PATH." >&2
  exit 127
fi

py=
if command -v python3 >/dev/null 2>&1; then
  py=python3
elif command -v python >/dev/null 2>&1; then
  py=python
else
  echo "error: python3 (or python) not found. On Debian/Ubuntu: sudo apt install python3" >&2
  exit 127
fi

# JVM build targets Java 21 (see bindings/java/.../build.gradle.kts).
needs_java_toolchain() {
  local a
  for a in "${orch_args[@]}"; do
    if [[ "$a" == "--skip-java" || "$a" == --docs-* ]]; then
      return 1
    fi
  done
  return 0
}

needs_maven() {
  needs_java_toolchain
}

ensure_maven_for_build() {
  needs_maven || return 0
  if command -v mvn >/dev/null 2>&1; then
    return 0
  fi
  if [[ -n "${BUILD_ALL_NO_AUTO_MAVEN:-}" ]]; then
    echo "error: mvn not found. Install Maven or unset BUILD_ALL_NO_AUTO_MAVEN." >&2
    exit 1
  fi
  if ! is_debian_like; then
    echo "error: mvn not found. Install Apache Maven for your OS." >&2
    exit 1
  fi
  echo "== Java: installing Maven (sudo apt-get) =="
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y maven
  command -v mvn >/dev/null 2>&1 || {
    echo "error: mvn still not on PATH after install" >&2
    exit 1
  }
}

java_major_version() {
  "${py}" -c '
import re, subprocess, sys
try:
    p = subprocess.run(["java", "-version"], capture_output=True, text=True, timeout=30)
    text = (p.stderr or "") + (p.stdout or "")
    m = re.search(r"version \"([^\"]+)\"", text)
    if not m:
        sys.exit(1)
    v = m.group(1).split("-")[0].split("+")[0]
    if v.startswith("1."):
        print(int(v.split(".")[1]))
    else:
        print(int(v.split(".")[0]))
except Exception:
    sys.exit(1)
'
}

is_debian_like() {
  [[ -f /etc/os-release ]] || return 1
  # shellcheck source=/dev/null
  source /etc/os-release
  [[ "${ID:-}" == "debian" || "${ID:-}" == "ubuntu" ]] && return 0
  [[ "${ID_LIKE:-}" == *"debian"* ]] && return 0
  return 1
}

ensure_java_for_build() {
  needs_java_toolchain || return 0

  local major=-1
  if command -v java >/dev/null 2>&1; then
    set +e
    major="$(java_major_version 2>/dev/null)"
    local _jv=$?
    set -e
    if [[ "${_jv}" -ne 0 || ! "${major}" =~ ^[0-9]+$ ]]; then
      major=-1
    fi
  fi

  if [[ "${major}" -ge 21 ]]; then
    return 0
  fi

  if [[ -n "${BUILD_ALL_NO_AUTO_JAVA:-}" ]]; then
    echo "error: JDK 21+ required for JVM steps (java missing or too old). Install OpenJDK 21+ or use --java-only / --skip-java." >&2
    exit 1
  fi

  if ! is_debian_like; then
    echo "error: JDK 21+ required for JVM steps (java missing or too old). Install a JDK 21+ or use --skip-java." >&2
    exit 1
  fi

  echo "== Java: installing OpenJDK 21 (sudo apt-get) =="
  sudo apt-get update -qq
  sudo DEBIAN_FRONTEND=noninteractive apt-get install -y openjdk-21-jdk

  if ! command -v java >/dev/null 2>&1; then
    echo "error: java still not on PATH after installing openjdk-21-jdk" >&2
    exit 1
  fi
  major="$(java_major_version)"
  if [[ ! "${major}" =~ ^[0-9]+$ || "${major}" -lt 21 ]]; then
    echo "error: java after install reports major version ${major:-?}; need 21+" >&2
    exit 1
  fi
}

ensure_java_for_build
ensure_maven_for_build

# Fail fast before a long compile when the root disk is nearly full.
min_gib="${BUILD_ALL_MIN_DISK_GIB:-8}"
avail_kib="$(df -Pk "${repo_root}" | awk 'NR==2 {print $4}')"
if [[ -n "${avail_kib}" && "${avail_kib}" =~ ^[0-9]+$ ]]; then
  avail_gib=$((avail_kib / 1024 / 1024))
  if (( avail_gib < min_gib )); then
    echo "error: only ${avail_gib} GiB free on ${repo_root} (need ~${min_gib} GiB)." >&2
    echo "  Free space: cargo clean; remove old target/ or .build_all_runs/run-*" >&2
    exit 1
  fi
fi

if [[ "${skip_upstream_clean}" != true ]]; then
  echo "== Rust: cargo clean =="
  cargo clean

  echo "== Python wrapper: python_clean.py =="
  "${py}" "${repo_root}/scripts/python_scripts/python_clean.py"
else
  echo "== Skipping upstream clean (--no-clean or *-only) =="
fi

echo "== Full build / test / docs: build_all.py =="
"${py}" "${repo_root}/scripts/python_scripts/build_all.py" "${orch_args[@]}"
